package com.example.bokehsynthesis

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

data class CaptureExportResult(
    val captureId: String,
    val originalImageUri: Uri?,
    val depthPreviewUri: Uri?,
    val metadataFile: File,
    val depthDataFile: File,
    val bokehImageUri: Uri? = null
)

object CaptureExporter {

    // The label used for all logs related to capturing and exporting.
    private const val TAG = "BokehSweep"

    // The quality level for saved JPEGs. 92 is high quality with reasonable
    // file size.
    private const val JPEG_QUALITY = 92

    // Where in the phone's gallery the photos will be saved.
    private const val RELATIVE_PATH = "Pictures/BokehSynthesis"

    fun export(
        context: Context,
        captureId: String,
        original: OriginalFrame,
        depthMap: DepthMap,
        physicalCamera: PhysicalCameraSensor,
        sweepFrames: List<SweepFrame>,
        bokehResult: Bitmap? = null,
        blurIntensityUsed: Float? = null,
        rawUnresolvedPixelCount: Int? = null
    ): CaptureExportResult {
        val originalJpeg = encodeOriginalToJpeg(original)
        val previewBitmap = depthMap.toPreviewBitmap()
        val previewJpeg = ByteArrayOutputStream().use { out ->
            previewBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            out.toByteArray()
        }

        val originalUri = saveJpegToMediaStore(context, "${captureId}_original.jpg", originalJpeg)
        val previewUri = saveJpegToMediaStore(context, "${captureId}_depth_preview.jpg", previewJpeg)

        originalUri?.let { embedCaptureId(context, it, captureId) }
        previewUri?.let { embedCaptureId(context, it, captureId) }

        var bokehUri: Uri? = null
        if (bokehResult != null) {
            val bokehJpeg = ByteArrayOutputStream().use { out ->
                bokehResult.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                out.toByteArray()
            }
            bokehUri = saveJpegToMediaStore(context, "${captureId}_bokeh.jpg", bokehJpeg)
            bokehUri?.let { embedCaptureId(context, it, captureId) }
        }

        val captureDir = File(File(context.filesDir, "captures"), captureId)
        if (!captureDir.exists()) captureDir.mkdirs()

        val depthDataFile = File(captureDir, "depth_data.json")
        depthDataFile.writeText(buildDepthDataJson(depthMap).toString())

        val metadataFile = File(captureDir, "metadata.json")
        metadataFile.writeText(
            buildMetadataJson(
                captureId = captureId,
                physicalCamera = physicalCamera,
                sweepFrames = sweepFrames,
                depthMap = depthMap,
                originalWidth = original.width,
                originalHeight = original.height,
                originalFocusDiopters = original.focusDistanceDiopters,
                bokehRendered = bokehResult != null,
                blurIntensityUsed = blurIntensityUsed,
                rawUnresolvedPixelCount = rawUnresolvedPixelCount
            ).toString()
        )

        Log.i(TAG, "Capture $captureId saved: original=$originalUri preview=$previewUri " +
                "bokeh=$bokehUri metadata=${metadataFile.absolutePath} depthData=${depthDataFile.absolutePath}")

        return CaptureExportResult(captureId, originalUri, previewUri, metadataFile, depthDataFile, bokehUri)
    }

    private fun encodeOriginalToJpeg(original: OriginalFrame): ByteArray {
        val nv21 = original.toNv21()
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, original.width, original.height, null)
        return ByteArrayOutputStream().use { out ->
            yuvImage.compressToJpeg(Rect(0, 0, original.width, original.height), JPEG_QUALITY, out)
            out.toByteArray()
        }
    }

    private fun saveJpegToMediaStore(context: Context, filename: String, jpegBytes: ByteArray): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.e(TAG, "MediaStore insert skipped: requires API 29+, device is ${Build.VERSION.SDK_INT}")
            return null
        }

        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, RELATIVE_PATH)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: run {
            Log.e(TAG, "MediaStore insert returned null for $filename")
            return null
        }

        try {
            resolver.openOutputStream(uri)?.use { it.write(jpegBytes) }
                ?: throw IllegalStateException("openOutputStream returned null")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed writing $filename to MediaStore", e)
            resolver.delete(uri, null, null)
            return null
        }

        return uri
    }

    private fun embedCaptureId(context: Context, uri: Uri, captureId: String) {
        try {
            context.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                val exif = ExifInterface(pfd.fileDescriptor)
                exif.setAttribute(ExifInterface.TAG_USER_COMMENT, "BokehSynthesis captureId=$captureId")
                exif.saveAttributes()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed embedding capture ID into $uri", e)
        }
    }

    private fun buildDepthDataJson(depthMap: DepthMap): JSONObject {
        val diopterArray = JSONArray()
        val unresolvedIndices = JSONArray()
        for (i in depthMap.diopters.indices) {
            diopterArray.put(depthMap.diopters[i].toDouble())
            if (depthMap.unresolved[i]) unresolvedIndices.put(i)
        }
        return JSONObject().apply {
            put("width", depthMap.width)
            put("height", depthMap.height)
            put("diopters", diopterArray)
            put("unresolvedIndices", unresolvedIndices)
        }
    }

    private fun buildMetadataJson(
        captureId: String,
        physicalCamera: PhysicalCameraSensor,
        sweepFrames: List<SweepFrame>,
        depthMap: DepthMap,
        originalWidth: Int,
        originalHeight: Int,
        originalFocusDiopters: Float,
        bokehRendered: Boolean,
        blurIntensityUsed: Float?,
        rawUnresolvedPixelCount: Int?
    ): JSONObject {
        val stepsArray = JSONArray()
        for (frame in sweepFrames.sortedBy { it.focusDistanceDiopters }) {
            stepsArray.put(JSONObject().apply {
                put("requestedDiopters", frame.requestedDiopter)
                put("reportedDiopters", frame.focusDistanceDiopters)
            })
        }

        return JSONObject().apply {
            put("captureId", captureId)
            put("timestampMillis", System.currentTimeMillis())
            put("deviceModel", Build.MODEL)
            put("deviceManufacturer", Build.MANUFACTURER)
            put("androidSdkInt", Build.VERSION.SDK_INT)
            put("physicalCameraId", physicalCamera.physicalId)
            put("focusDistanceCalibration", physicalCamera.focusCalibration)
            put("minimumFocusDistanceDiopters", physicalCamera.minimumFocusDistance)
            put("originalResolutionWidth", originalWidth)
            put("originalResolutionHeight", originalHeight)
            put("analysisResolutionWidth", depthMap.width)
            put("analysisResolutionHeight", depthMap.height)
            put("sweepSteps", stepsArray)
            put("unresolvedPixelCount", depthMap.unresolved.count { it })
            put("rawUnresolvedPixelCount", rawUnresolvedPixelCount ?: JSONObject.NULL)
            put("totalPixelCount", depthMap.width * depthMap.height)
            put("depthAlgorithm", JSONObject().apply {
                put("relativeMargin", DepthMapBuilder.RELATIVE_MARGIN)
                put("eligibilityMultiplier", DepthMapBuilder.ELIGIBILITY_MULTIPLIER)
                put("smlWindowRadius", DepthMapBuilder.SML_WINDOW_RADIUS)
                put("regionMergeTolerance", DepthMapBuilder.REGION_MERGE_TOLERANCE)
                put("thicknessThresholdPixels", DepthMapBuilder.THICKNESS_THRESHOLD_PIXELS)
                put("infillIterations", DepthMapBuilder.INFILL_ITERATIONS)
                put("infillColorSigma", DepthMapBuilder.INFILL_COLOR_SIGMA)
            })
            put("bokeh", JSONObject().apply {
                put("originalFocusDiopters", if (originalFocusDiopters.isNaN()) JSONObject.NULL else originalFocusDiopters)
                put("rendered", bokehRendered)
                put("maxCoCRadiusPixels", BokehRenderer.MAX_COC_RADIUS_PIXELS)
                put("intensityMultiplier", blurIntensityUsed ?: JSONObject.NULL)
                put("highlightLinearThreshold", BokehRenderer.HIGHLIGHT_LINEAR_THRESHOLD)
                put("highlightBoostMultiplier", BokehRenderer.HIGHLIGHT_BOOST_MULTIPLIER)
            })
        }
    }
}