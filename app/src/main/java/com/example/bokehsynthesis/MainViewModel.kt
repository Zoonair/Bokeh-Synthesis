package com.example.bokehsynthesis

import android.app.Application
import android.graphics.Bitmap
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.Build
import android.util.Log
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.application
import androidx.lifecycle.viewModelScope
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.collections.emptyList
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.sqrt

@androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _availableSensors = MutableStateFlow<List<LogicalCameraSensor>>(emptyList())
    val availableSensors: StateFlow<List<LogicalCameraSensor>> = _availableSensors

    private val _selectedPhysicalCamera = MutableStateFlow<List<PhysicalCameraSensor>>(emptyList())
    val selectedPhysicalCamera: StateFlow<List<PhysicalCameraSensor>> = _selectedPhysicalCamera

    private val _isCameraActive = MutableStateFlow(false)
    val isCameraActive: StateFlow<Boolean> = _isCameraActive

    private val _surfaceRequest = MutableStateFlow<SurfaceRequest?>(null)
    val surfaceRequest: StateFlow<SurfaceRequest?> = _surfaceRequest

    private val _isSweeping = MutableStateFlow(false)
    val isSweeping: StateFlow<Boolean> = _isSweeping

    private val _captureResult = MutableStateFlow<SweepCaptureResult?>(null)
    val captureResult: StateFlow<SweepCaptureResult?> = _captureResult

    private val _depthMap = MutableStateFlow<DepthMap?>(null)
    val depthMap: StateFlow<DepthMap?> = _depthMap

    private val _bokehResult = MutableStateFlow<Bitmap?>(null)
    val bokehResult: StateFlow<Bitmap?> = _bokehResult

    private val _blurIntensity = MutableStateFlow(BokehRenderer.DEFAULT_INTENSITY_MULTIPLIER)
    val blurIntensity: StateFlow<Float> = _blurIntensity

    private val _lastExport = MutableStateFlow<CaptureExportResult?>(null)
    val lastExport: StateFlow<CaptureExportResult?> = _lastExport

    private var cameraProvider: ProcessCameraProvider? = null
    private var activePreview: Preview? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var cameraControl: CameraControl? = null
    private var camera2CameraControl: Camera2CameraControl? = null
    private var surfaceMeteringPointFactory: SurfaceOrientedMeteringPointFactory? = null
    var currentPhysicalCamera: PhysicalCameraSensor? = null

    private val cameraExecutor = Executors.newSingleThreadExecutor()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    @Volatile
    private var captureMode: CaptureMode = CaptureMode.IDLE
    @Volatile
    private var currentRequestedDiopter: Float = 0f
    private var originalFrameDeferred: CompletableDeferred<OriginalFrame>? = null
    private var sweepFrameDeferred: CompletableDeferred<SweepFrame>? = null
    private val focusByTimestamp = ConcurrentHashMap<Long, Float>()

    private enum class CaptureMode { IDLE, CAPTURE_ORIGINAL, CAPTURE_SWEEP_FRAME }

    companion object {
        // The label used for logging capture events.
        private const val TAG = "BokehSweep"

        // The length of the longest side for the images used in depth
        // analysis as pixels.
        private const val SWEEP_ANALYSIS_LONG_EDGE = 1440

        // How many different frames to capture during the sweep.
        private const val SWEEP_STEP_COUNT = 20

        // How long to wait for the camera lens to physically move and stop
        // before taking a picture. This helps the focus motor settle.
        private const val FOCUS_SETTLE_DELAY_MS = 50L
    }

    private fun computeSweepAnalysisSize(previewSize: Size?): Size {
        val ratio = if (previewSize != null && previewSize.height != 0) {
            previewSize.width.toFloat() / previewSize.height.toFloat()
        } else {
            4f / 3f
        }
        val longEdge = SWEEP_ANALYSIS_LONG_EDGE
        return if (ratio >= 1f) {
            Size(longEdge, (longEdge / ratio).toInt())
        } else {
            Size((longEdge * ratio).toInt(), longEdge)
        }
    }

    fun fetchLogicalCameraSensors() {
        if (cameraProvider == null) {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(application)
            cameraProviderFuture.addListener({
                cameraProvider = cameraProviderFuture.get()
                fetchLogicalCameraSensors()
            }, ContextCompat.getMainExecutor(application))
            return
        }

        val provider = cameraProvider ?: return
        val sensorList = mutableListOf<LogicalCameraSensor>()
        val cameraManager = getApplication<Application>().getSystemService(android.content.Context.CAMERA_SERVICE) as CameraManager

        try {
            for (cameraInfo in provider.availableCameraInfos) {
                val c2CameraInfo = Camera2CameraInfo.from(cameraInfo)
                val cameraId = c2CameraInfo.cameraId
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)

                val facingInt = characteristics.get(CameraCharacteristics.LENS_FACING)
                val facingString = when (facingInt) {
                    CameraCharacteristics.LENS_FACING_BACK -> "Back Camera"
                    CameraCharacteristics.LENS_FACING_FRONT -> "Front Camera"
                    CameraCharacteristics.LENS_FACING_EXTERNAL -> "External Camera"
                    else -> "Unknown"
                }

                val hasFlash = cameraInfo.hasFlashUnit()
                val minimumFocus = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)

                sensorList.add(
                    LogicalCameraSensor(
                        cameraInfo = cameraInfo,
                        characteristic = characteristics,
                        logicalId = cameraId,
                        facing = facingString,
                        isFlashSupported = hasFlash,
                        minimumFocusDistance = minimumFocus
                    )
                )
            }
            _availableSensors.value = sensorList

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun fetchPhysicalCameraSensors(logicalSensor: LogicalCameraSensor) {
        if (selectedPhysicalCamera.value.isEmpty()) {
            getPhysicalCameraInfo(logicalSensor)
        } else if (logicalSensor.logicalId != selectedPhysicalCamera.value[0].logicalId) {
            _selectedPhysicalCamera.value = emptyList()
            getPhysicalCameraInfo(logicalSensor)
        } else {
            _selectedPhysicalCamera.value = emptyList()
        }
    }

    private fun chooseOptimalPreviewSize(choices: Array<Size>, sensorSize: Size?): Size {
        val maxPreviewArea = 1920L * 1080L
        val underAreaCap = choices.filter { it.width.toLong() * it.height.toLong() <= maxPreviewArea }
        val candidates = underAreaCap.ifEmpty { choices.toList() }

        if (sensorSize != null && sensorSize.height != 0) {
            val sensorRatio = sensorSize.width.toFloat() / sensorSize.height.toFloat()
            val matchingRatio = candidates.filter { size ->
                val ratio = size.width.toFloat() / size.height.toFloat()
                kotlin.math.abs(ratio - sensorRatio) < 0.01f
            }
            if (matchingRatio.isNotEmpty()) {
                return matchingRatio.maxByOrNull { it.width.toLong() * it.height.toLong() }!!
            }
        }

        return candidates.maxByOrNull { it.width.toLong() * it.height.toLong() }
            ?: choices.minByOrNull { it.width.toLong() * it.height.toLong() }
            ?: choices.first()
    }

    fun getPhysicalCameraInfo(logicalSensor: LogicalCameraSensor) {
        try {
            val sensorList = mutableListOf<PhysicalCameraSensor>()
            val logicalSensorInfo = logicalSensor.characteristic
            val cameraManager = getApplication<Application>().getSystemService(android.content.Context.CAMERA_SERVICE) as CameraManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && logicalSensorInfo.physicalCameraIds.isNotEmpty()) {
                for (sensor in logicalSensorInfo.physicalCameraIds) {
                    val physicalSensorInfo = cameraManager.getCameraCharacteristics(sensor)

                    val sensorCapabilities = physicalSensorInfo.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                    val colorFilter = physicalSensorInfo.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)
                    val isTrueColorCamera = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        (colorFilter != CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_NIR &&
                                colorFilter != CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_MONO)
                    } else {
                        true
                    }

                    val isDepthSensor = sensorCapabilities?.contains(
                        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT
                    ) ?: false

                    if (isTrueColorCamera && !isDepthSensor) {
                        val facingInt = physicalSensorInfo.get(CameraCharacteristics.LENS_FACING)
                        val facingString = when (facingInt) {
                            CameraCharacteristics.LENS_FACING_BACK -> "Back Camera"
                            CameraCharacteristics.LENS_FACING_FRONT -> "Front Camera"
                            CameraCharacteristics.LENS_FACING_EXTERNAL -> "External Camera"
                            else -> "Unknown"
                        }

                        val hasFlash = physicalSensorInfo.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
                        val minimumFocus = physicalSensorInfo.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
                        val calibrationQuality = physicalSensorInfo.get(CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION)
                        val sensorSize = physicalSensorInfo.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)

                        val sensorDiagonal = if (sensorSize != null) {
                            sqrt((sensorSize.width * sensorSize.width) + (sensorSize.height * sensorSize.height))
                        } else {
                            null
                        }

                        val cropFactor = if (sensorDiagonal != null) {
                            43.27 / sensorDiagonal
                        } else {
                            null
                        }

                        val focalLength = physicalSensorInfo.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
                        val sensorDimensions = physicalSensorInfo.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
                        val megapixels = if (sensorDimensions != null) {
                            sensorDimensions.height * sensorDimensions.width / 1_000_000f
                        } else {
                            null
                        }

                        val sensorOrientation = physicalSensorInfo.get(CameraCharacteristics.SENSOR_ORIENTATION)

                        val previewSize = physicalSensorInfo
                            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                            ?.getOutputSizes(android.graphics.SurfaceTexture::class.java)
                            ?.let { chooseOptimalPreviewSize(it, sensorDimensions) }

                        val stabilizationModes = physicalSensorInfo.get(
                            CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)

                        val isStabilizationCompatible = stabilizationModes
                            ?.contains(CameraCharacteristics.LENS_OPTICAL_STABILIZATION_MODE_ON) ?: false

                        val apertures = physicalSensorInfo.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)

                        sensorList.add(
                            PhysicalCameraSensor(
                                logicalId = logicalSensor.logicalId,
                                physicalId = sensor,
                                facingId = facingInt,
                                facingString = facingString,
                                isFlashSupported = hasFlash,
                                minimumFocusDistance = minimumFocus,
                                focusCalibration = calibrationQuality,
                                sensorDiagonal = sensorDiagonal,
                                cropFactor = cropFactor,
                                focalLength = focalLength,
                                megapixels = megapixels,
                                sensorOrientation = sensorOrientation,
                                previewSize = previewSize,
                                isStabilizationSupported = isStabilizationCompatible,
                                availableApertures = apertures,
                                sensorPhysicalWidthMm = sensorSize?.width,
                                sensorPhysicalHeightMm = sensorSize?.height
                            )
                        )
                    }
                }
            } else {
                val facingInt = logicalSensor.characteristic.get(CameraCharacteristics.LENS_FACING)
                val sensorSize = logicalSensor.characteristic.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                val calibrationQuality = logicalSensor.characteristic.get(CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION)

                val sensorDiagonal = if (sensorSize != null) {
                    sqrt((sensorSize.width * sensorSize.width) + (sensorSize.height * sensorSize.height))
                } else {
                    null
                }

                val cropFactor = if (sensorDiagonal != null) {
                    43.27 / sensorDiagonal
                } else {
                    null
                }

                val focalLength = logicalSensor.characteristic.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
                val sensorDimensions = logicalSensor.characteristic.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
                val megapixels = if (sensorDimensions != null) {
                    sensorDimensions.height * sensorDimensions.width / 1_000_000f
                } else {
                    null
                }

                val sensorOrientation = logicalSensor.characteristic.get(CameraCharacteristics.SENSOR_ORIENTATION)

                val previewSize = logicalSensor.characteristic
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?.getOutputSizes(android.graphics.SurfaceTexture::class.java)
                    ?.let { chooseOptimalPreviewSize(it, sensorDimensions) }

                val stabilizationModes = logicalSensor.characteristic.get(
                    CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)

                val isStabilizationCompatible = stabilizationModes
                    ?.contains(CameraCharacteristics.LENS_OPTICAL_STABILIZATION_MODE_ON) ?: false

                val apertures = logicalSensor.characteristic.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)

                sensorList.add(
                    PhysicalCameraSensor(
                        logicalId = logicalSensor.logicalId,
                        physicalId = logicalSensor.logicalId,
                        facingId = facingInt,
                        facingString = logicalSensor.facing,
                        isFlashSupported = logicalSensor.isFlashSupported,
                        minimumFocusDistance = logicalSensor.minimumFocusDistance,
                        focusCalibration = calibrationQuality,
                        sensorDiagonal = sensorDiagonal,
                        cropFactor = cropFactor,
                        focalLength = focalLength,
                        megapixels = megapixels,
                        sensorOrientation = sensorOrientation,
                        previewSize = previewSize,
                        isStabilizationSupported = isStabilizationCompatible,
                        availableApertures = apertures,
                        sensorPhysicalWidthMm = sensorSize?.width,
                        sensorPhysicalHeightMm = sensorSize?.height
                    )
                )
            }
            _selectedPhysicalCamera.value = sensorList
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun initCamera(
        lifecycleOwner: LifecycleOwner,
        physicalCameraSensor: PhysicalCameraSensor,
        targetRotation: Int
    ) {
        val provider = cameraProvider ?: return

        try {
            val logicalSensor = availableSensors.value.find { it.logicalId == physicalCameraSensor.logicalId } ?: return
            val cameraSelector = logicalSensor.cameraInfo.cameraSelector

            val previewBuilder = Preview.Builder().setTargetRotation(targetRotation)

            val cameraManager = getApplication<Application>().getSystemService(android.content.Context.CAMERA_SERVICE) as CameraManager
            val streamCharacteristics = cameraManager.getCameraCharacteristics(physicalCameraSensor.physicalId)
            val supportsStabilization = streamCharacteristics
                .get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
                ?.contains(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON) == true



            if (supportsStabilization) {
                Camera2Interop.Extender(previewBuilder)
                    .setCaptureRequestOption(
                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                        CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON
                    )
            }

            physicalCameraSensor.previewSize?.let { size ->
                val resolutionSelector = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(size, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER)
                    )
                    .build()
                previewBuilder.setResolutionSelector(resolutionSelector)
            } ?: run {
                previewBuilder.setTargetAspectRatio(AspectRatio.RATIO_DEFAULT)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                Camera2Interop.Extender(previewBuilder).setCaptureRequestOption(
                    CaptureRequest.DISTORTION_CORRECTION_MODE,
                    CaptureRequest.DISTORTION_CORRECTION_MODE_HIGH_QUALITY)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                physicalCameraSensor.physicalId != physicalCameraSensor.logicalId) {
                Camera2Interop.Extender(previewBuilder).setPhysicalCameraId(physicalCameraSensor.physicalId)
            }

            val preview = previewBuilder.build()
            activePreview = preview

            preview.setSurfaceProvider { request ->
                Log.i(TAG, "Preview bound at ${request.resolution.width}x${request.resolution.height} " +
                        "(requested ${physicalCameraSensor.previewSize})")
                surfaceMeteringPointFactory = SurfaceOrientedMeteringPointFactory(
                    request.resolution.width.toFloat(),
                    request.resolution.height.toFloat()
                )
                _surfaceRequest.value = request
            }

            val sweepAnalysisSize = computeSweepAnalysisSize(physicalCameraSensor.previewSize)
            val analysisBuilder = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_BLOCK_PRODUCER)
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(sweepAnalysisSize, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER)
                        )
                        .build()
                )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                physicalCameraSensor.physicalId != physicalCameraSensor.logicalId) {
                Camera2Interop.Extender(analysisBuilder).setPhysicalCameraId(physicalCameraSensor.physicalId)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                Camera2Interop.Extender(analysisBuilder).setCaptureRequestOption(
                    CaptureRequest.DISTORTION_CORRECTION_MODE,
                    CaptureRequest.DISTORTION_CORRECTION_MODE_HIGH_QUALITY)
            }

            Camera2Interop.Extender(analysisBuilder).setSessionCaptureCallback(
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                    ) {
                        if (captureMode == CaptureMode.IDLE) return
                        val focusDistance = result.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: return
                        val timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
                        focusByTimestamp[timestamp] = focusDistance
                    }
                }
            )

            val analysis = analysisBuilder.build()
            analysis.setAnalyzer(cameraExecutor) { imageProxy -> handleAnalysisFrame(imageProxy) }
            imageAnalysis = analysis

            provider.unbindAll()
            val camera = provider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, analysis)
            cameraControl = camera.cameraControl
            camera2CameraControl = Camera2CameraControl.from(camera.cameraControl)
            camera2CameraControl?.clearCaptureRequestOptions()

            Log.i(TAG, "ImageAnalysis bound at ${analysis.resolutionInfo?.resolution} " +
                    "(requested target $sweepAnalysisSize)")

            currentPhysicalCamera = physicalCameraSensor
            _isCameraActive.value = true

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun handleAnalysisFrame(imageProxy: ImageProxy) {
        when (captureMode) {
            CaptureMode.CAPTURE_ORIGINAL -> {
                val deferred = originalFrameDeferred
                if (deferred != null && !deferred.isCompleted) {
                    deferred.complete(imageProxy.toOriginalFrame())
                }
            }
            CaptureMode.CAPTURE_SWEEP_FRAME -> {
                val deferred = sweepFrameDeferred
                if (deferred != null && !deferred.isCompleted) {
                    deferred.complete(imageProxy.toSweepFrame(sweepFrameDir(), currentRequestedDiopter))
                }
            }
            CaptureMode.IDLE -> { }
        }
        imageProxy.close()
    }

    private fun ByteBuffer.toByteArray(): ByteArray {
        rewind()
        val out = ByteArray(remaining())
        get(out)
        return out
    }

    private fun sweepFrameDir(): File {
        val dir = File(getApplication<Application>().cacheDir, "sweep_frames")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun ImageProxy.toSweepFrame(outputDir: File, requestedDiopter: Float): SweepFrame {
        val yPlane = planes[0]
        val bytes = yPlane.buffer.toByteArray()
        val file = File(outputDir, "sweep_${imageInfo.timestamp}.y")
        file.writeBytes(bytes)
        return SweepFrame(
            focusDistanceDiopters = Float.NaN,
            requestedDiopter = requestedDiopter,
            timestampNanos = imageInfo.timestamp,
            yPlaneFile = file,
            width = width,
            height = height,
            rowStride = yPlane.rowStride
        )
    }

    private fun ImageProxy.toOriginalFrame(): OriginalFrame {
        val y = planes[0]
        val u = planes[1]
        val v = planes[2]
        return OriginalFrame(
            timestampNanos = imageInfo.timestamp,
            yPlane = y.buffer.toByteArray(),
            uPlane = u.buffer.toByteArray(),
            vPlane = v.buffer.toByteArray(),
            yRowStride = y.rowStride,
            uvRowStride = u.rowStride,
            uvPixelStride = u.pixelStride,
            width = width,
            height = height
        )
    }

    fun tapToFocus(tapCords: Offset) {
        val c2Control = camera2CameraControl ?: return
        val control = cameraControl ?: return

        c2Control.clearCaptureRequestOptions()

        val point = surfaceMeteringPointFactory?.createPoint(tapCords.x, tapCords.y) ?: return
        val meteringAction = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF).build()
        control.startFocusAndMetering(meteringAction)
    }

    fun onShutterPress(haptics: HapticFeedback) {
        if (_isSweeping.value) return

        val control = camera2CameraControl ?: return
        val minFocusDiopters = currentPhysicalCamera?.minimumFocusDistance
        if (minFocusDiopters == null || minFocusDiopters == 0f) {
            _message.value = "Fixed focus camera. Cannot perform focus sweep."
            return
        }

        viewModelScope.launch {
            _isSweeping.value = true
            focusByTimestamp.clear()

            haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)

            try {
                val deferred = CompletableDeferred<OriginalFrame>()
                originalFrameDeferred = deferred
                captureMode = CaptureMode.CAPTURE_ORIGINAL
                val rawOriginal = withTimeoutOrNull(100) { deferred.await() }
                if (rawOriginal != null) {
                    delay(50)
                }
                captureMode = CaptureMode.IDLE
                originalFrameDeferred = null

                if (rawOriginal == null) {
                    Log.w(TAG, "Sweep aborted: no original frame arrived within 500ms")
                    return@launch
                }
                if (control != camera2CameraControl) return@launch
                Log.i(TAG, "Original frame captured: ${rawOriginal.width}x${rawOriginal.height}")

                val originalFocusDiopter = focusByTimestamp[rawOriginal.timestampNanos]
                if (originalFocusDiopter == null) {
                    Log.w(TAG, "No CaptureResult focus distance matched for the original frame " +
                            "(timestamp=${rawOriginal.timestampNanos}) -- Bokeh render will be skipped for this capture")
                    _message.value = "No CaptureResult focus distance matched for the original frame " +
                            "(timestamp=${rawOriginal.timestampNanos}) -- Bokeh render will be skipped for this capture"
                }
                val original = rawOriginal.copy(focusDistanceDiopters = originalFocusDiopter ?: Float.NaN)

                val steps = buildDiopterSweepSteps(minFocusDiopters, SWEEP_STEP_COUNT)
                val sweepFrames = mutableListOf<SweepFrame>()
                val supportsOis = currentPhysicalCamera?.isStabilizationSupported == true

                for ((index, diopter) in steps.withIndex()) {
                    if (control != camera2CameraControl) break
                    val stepStartNs = System.nanoTime()
                    val requestOptionsBuilder = CaptureRequestOptions.Builder()
                        .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                        .setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, diopter)
                    if (supportsOis) {
                        requestOptionsBuilder.setCaptureRequestOption(
                            CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                            CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON
                        )
                    }
                    control.setCaptureRequestOptions(requestOptionsBuilder.build()).awaitCompletion()
                    val afterSubmitNs = System.nanoTime()

                    delay(FOCUS_SETTLE_DELAY_MS)
                    val afterSettleNs = System.nanoTime()

                    val frame = captureOneSweepFrame(diopter)
                    val afterFrameNs = System.nanoTime()

                    val submitMs = (afterSubmitNs - stepStartNs) / 1_000_000
                    val settleMs = (afterSettleNs - afterSubmitNs) / 1_000_000
                    val frameWaitMs = (afterFrameNs - afterSettleNs) / 1_000_000

                    if (frame != null) {
                        sweepFrames.add(frame)
                        Log.d(TAG, "Step ${index + 1}/${steps.size}: requested=$diopter diopters, " +
                                "frame=${frame.width}x${frame.height}, " +
                                "timing(submit=${submitMs}ms settle=${settleMs}ms frameWait=${frameWaitMs}ms)")
                    } else {
                        Log.w(TAG, "Step ${index + 1}/${steps.size}: no frame arrived (requested=$diopter diopters), " +
                                "timing(submit=${submitMs}ms settle=${settleMs}ms frameWait=${frameWaitMs}ms)")
                    }
                }
                delay(50)

                restoreNormalFocus(control)
                _message.value = "Sweep finished.\nProcessing..."

                val tagged = sweepFrames.mapNotNull { raw ->
                    val diopter = focusByTimestamp[raw.timestampNanos] ?: return@mapNotNull null
                    raw.copy(focusDistanceDiopters = diopter)
                }.sortedBy { it.focusDistanceDiopters }

                Log.i(TAG, "Sweep complete: ${steps.size} steps requested, " +
                        "${sweepFrames.size} frames captured, ${tagged.size} tagged with a real diopter")
                tagged.forEach { f ->
                    Log.d(TAG, "  tagged frame: diopter=${f.focusDistanceDiopters} size=${f.width}x${f.height} " +
                            "file=${f.yPlaneFile.name}")
                }
                if (tagged.size < steps.size / 2) {
                    Log.w(TAG, "Only ${tagged.size}/${steps.size} sweep steps produced a usable, " +
                            "tagged frame -- a depth map built from this few frames will be noisy " +
                            "even before considering alignment; consider holding the phone steadier " +
                            "or checking Logcat for earlier per-step warnings")
                }

                _captureResult.value = SweepCaptureResult(original, tagged)

                val rawDepthMap = withContext(Dispatchers.Default) {
                    DepthMapBuilder.build(tagged)
                }

                if (rawDepthMap == null) {
                    Log.w(TAG, "No depth map built: 0 usable tagged frames out of " +
                            "${steps.size} requested steps -- see the per-step warnings above " +
                            "for why frames were dropped (timeouts are the usual cause, and " +
                            "camera movement can indirectly cause those)")
                    _message.value = "Focus sweep produced no usable frames -- try again, holding steady"
                    _depthMap.value = null
                    return@launch
                }

                val rawUnresolvedCount = rawDepthMap.unresolved.count { it }
                val depthMap = withContext(Dispatchers.Default) {
                    val colorBitmap = original.toBitmap()
                    val colorPixels = IntArray(colorBitmap.width * colorBitmap.height)
                    colorBitmap.getPixels(
                        colorPixels, 0, colorBitmap.width,
                        0, 0, colorBitmap.width, colorBitmap.height
                    )
                    DepthMapBuilder.fillUnresolvedGuided(rawDepthMap, colorPixels)
                }
                _depthMap.value = depthMap

                run {
                    val unresolvedCount = depthMap.unresolved.count { it }
                    val totalPixels = depthMap.width * depthMap.height
                    Log.i(TAG, "Depth map built: ${depthMap.width}x${depthMap.height}, " +
                            "$rawUnresolvedCount/$totalPixels pixels never confidently in focus " +
                            "before guided infill, $unresolvedCount/$totalPixels still unresolved after")

                    val bokehBitmap = withContext(Dispatchers.Default) {
                        renderBokehOrNull(original, depthMap)
                    }
                    _bokehResult.value = bokehBitmap
                    if (bokehBitmap == null) {
                        Log.w(TAG, "Bokeh render skipped for this capture (see preceding log line for why)")
                    }

                    val physicalCamera = currentPhysicalCamera
                    if (physicalCamera != null) {
                        val captureId = "capture_" +
                                SimpleDateFormat("yyyyMMdd_HHmmssSSS", Locale.US).format(Date())
                        val exportResult = withContext(Dispatchers.IO) {
                            try {
                                CaptureExporter.export(
                                    context = getApplication(),
                                    captureId = captureId,
                                    original = original,
                                    depthMap = depthMap,
                                    physicalCamera = physicalCamera,
                                    sweepFrames = tagged,
                                    bokehResult = bokehBitmap,
                                    blurIntensityUsed = _blurIntensity.value,
                                    rawUnresolvedPixelCount = rawUnresolvedCount
                                )
                            } catch (e: Exception) {
                                Log.e(TAG, "Export failed for $captureId", e)
                                null
                            }
                        }
                        _lastExport.value = exportResult
                    } else {
                        Log.e(TAG, "Skipped export: currentPhysicalCamera is null")
                    }
                }

                _message.value = "Finished processing sweep"

            } catch (e: Exception) {
                Log.e(TAG, "Sweep failed", e)
                _message.value = "Focus sweep failed"

            } finally {
                restoreNormalFocus(control)
                captureMode = CaptureMode.IDLE
                _isSweeping.value = false
            }
        }
    }

    private suspend fun captureOneSweepFrame(requestedDiopter: Float): SweepFrame? {
        currentRequestedDiopter = requestedDiopter
        val deferred = CompletableDeferred<SweepFrame>()
        sweepFrameDeferred = deferred
        captureMode = CaptureMode.CAPTURE_SWEEP_FRAME
        val frame = withTimeoutOrNull(300) { deferred.await() }
        captureMode = CaptureMode.IDLE
        sweepFrameDeferred = null
        return frame
    }

    private fun renderBokehOrNull(
        original: OriginalFrame,
        depthMap: DepthMap,
        intensity: Float = _blurIntensity.value
    ): Bitmap? {
        val physicalCamera = currentPhysicalCamera ?: run {
            Log.w(TAG, "Bokeh render skipped: no current physical camera")
            _message.value = "Bokeh render skipped: no current physical camera"
            return null
        }
        if (original.focusDistanceDiopters.isNaN()) {
            Log.w(TAG, "Bokeh render skipped: original frame has no tagged focus diopter")
            _message.value = "Bokeh render skipped: original frame has no tagged focus diopter"
            return null
        }
        if (original.width != depthMap.width || original.height != depthMap.height) {
            Log.w(TAG, "Bokeh render skipped: original (${original.width}x${original.height}) and " +
                    "depth map (${depthMap.width}x${depthMap.height}) resolutions don't match")
            _message.value = "Bokeh render skipped: original (${original.width}x${original.height}) and " +
                    "depth map (${depthMap.width}x${depthMap.height}) resolutions don't match"
            return null
        }
        val opticalParams = BokehRenderer.opticalParamsFor(physicalCamera, depthMap.width) ?: run {
            Log.w(TAG, "Bokeh render skipped: device didn't report the aperture/sensor-size fields " +
                    "BokehRenderer needs (focalLength=${physicalCamera.focalLength}, " +
                    "apertures=${physicalCamera.availableApertures?.toList()}, " +
                    "sensorWidthMm=${physicalCamera.sensorPhysicalWidthMm})")
            _message.value = "Bokeh render skipped: device didn't report the aperture/sensor-size fields."
            return null
        }

        return try {
            val originalBitmap = original.toBitmap()
            val cocRadii = BokehRenderer.computeCoCPixels(depthMap, original.focusDistanceDiopters, opticalParams, intensity)
            BokehRenderer.render(originalBitmap, cocRadii, depthMap.width, depthMap.height)
        } catch (e: Exception) {
            Log.e(TAG, "Bokeh render failed", e)
            null
        }
    }

    private suspend fun restoreNormalFocus(control: Camera2CameraControl) {
        try {
            withTimeoutOrNull(500) {
                control.clearCaptureRequestOptions().awaitCompletion()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun clearMessage () {
        _message.value = null
    }

    private fun buildDiopterSweepSteps(minFocusDiopters: Float, stepCount: Int): List<Float> {
        return (0 until stepCount).map { i ->
            minFocusDiopters * (stepCount - 1 - i) / (stepCount - 1).toFloat()
        }
    }

    private suspend fun <T> ListenableFuture<T>.awaitCompletion(): T = suspendCancellableCoroutine { cont ->
        addListener({
            try {
                cont.resume(get())
            } catch (e: Exception) {
                if (cont.isActive) cont.resumeWithException(e)
            }
        }, ContextCompat.getMainExecutor(getApplication()))
    }

    fun stopCamera() {
        _isSweeping.value = false
        captureMode = CaptureMode.IDLE
        originalFrameDeferred = null
        sweepFrameDeferred = null
        cameraProvider?.unbindAll()
        activePreview = null
        imageAnalysis = null
        cameraControl = null
        camera2CameraControl = null
        surfaceMeteringPointFactory = null
        _isCameraActive.value = false
        _surfaceRequest.value = null
    }

    fun updateTargetRotation(rotation: Int) {
        activePreview?.targetRotation = rotation
    }

    override fun onCleared() {
        stopCamera()
        cameraExecutor.shutdown()
        sweepFrameDir().deleteRecursively()
        super.onCleared()
    }
}