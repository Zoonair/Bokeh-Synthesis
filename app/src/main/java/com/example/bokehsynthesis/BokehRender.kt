package com.example.bokehsynthesis

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

object BokehRenderer {

    data class OpticalParams(
        val focalLengthMeters: Float,
        val aperture: Float,
        val pixelPitchMeters: Float
    )

    // The largest allowed blur radius. This prevents crazy values if the
    // focus or depth map is wrong.
    const val MAX_COC_RADIUS_PIXELS = 60f

    // How much to exaggerate the real blur so it's actually visible.
    // 1.0 is physically real, but usually too subtle.
    const val DEFAULT_INTENSITY_MULTIPLIER = 3f

    // The brightness level where we start boosting highlights to create
    // "bokeh balls."
    const val HIGHLIGHT_LINEAR_THRESHOLD = 1f

    // How much extra brightness to give to out-of-focus highlights.
    const val HIGHLIGHT_BOOST_MULTIPLIER = 2.5f

    fun opticalParamsFor(camera: PhysicalCameraSensor, imageWidthPixels: Int): OpticalParams? {
        val focalLengthMm = camera.focalLength ?: return null
        val aperture = camera.availableApertures?.firstOrNull() ?: return null
        val sensorWidthMm = camera.sensorPhysicalWidthMm ?: return null
        if (imageWidthPixels <= 0 || sensorWidthMm <= 0f || focalLengthMm <= 0f || aperture <= 0f) return null

        val pixelPitchMm = sensorWidthMm / imageWidthPixels
        return OpticalParams(
            focalLengthMeters = focalLengthMm / 1000f,
            aperture = aperture,
            pixelPitchMeters = pixelPitchMm / 1000f
        )
    }

    private fun srgbToLinear(c: Float): Float =
        if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)

    private fun linearToSrgb(c: Float): Float =
        if (c <= 0.0031308f) c * 12.92f else 1.055f * c.pow(1f / 2.4f) - 0.055f

    private val SRGB_TO_LINEAR_LUT = FloatArray(256) { srgbToLinear(it / 255f) }

    fun computeCoCPixels(
        depthMap: DepthMap,
        focusDiopters: Float,
        params: OpticalParams,
        intensityMultiplier: Float = DEFAULT_INTENSITY_MULTIPLIER
    ): FloatArray {
        val cocRadii = FloatArray(depthMap.diopters.size)
        val coefficient = (params.focalLengthMeters * params.focalLengthMeters) / params.aperture
        for (i in depthMap.diopters.indices) {
            if (depthMap.unresolved[i]) {
                cocRadii[i] = 0f
                continue
            }
            val cocDiameterMeters = coefficient * abs(focusDiopters - depthMap.diopters[i]) * intensityMultiplier
            val cocDiameterPixels = cocDiameterMeters / params.pixelPitchMeters
            val radiusPixels = cocDiameterPixels / 2f
            cocRadii[i] = radiusPixels.coerceIn(0f, MAX_COC_RADIUS_PIXELS)
        }
        return cocRadii
    }

    fun render(
        original: Bitmap,
        cocRadiiPixels: FloatArray,
        depthMapWidth: Int,
        depthMapHeight: Int,
        levelCount: Int = 8
    ): Bitmap {
        require(original.width == depthMapWidth && original.height == depthMapHeight) {
            "BokehRenderer expects original and depth map to be pixel-aligned " +
                    "(got original ${original.width}x${original.height} vs " +
                    "depth map ${depthMapWidth}x${depthMapHeight})"
        }
        require(levelCount >= 2) { "levelCount must be at least 2 (sharp + at least one blur level)" }

        val pixelCount = depthMapWidth * depthMapHeight

        val maxRadiusPresent = cocRadiiPixels.maxOrNull() ?: 0f
        if (maxRadiusPresent <= 0.01f) {
            return original.copy(original.config ?: Bitmap.Config.ARGB_8888, false)
        }

        val levelRadii = FloatArray(levelCount) { i -> maxRadiusPresent * i / (levelCount - 1) }

        val sourcePixels = IntArray(pixelCount)
        original.getPixels(sourcePixels, 0, depthMapWidth, 0, 0, depthMapWidth, depthMapHeight)

        val sourceLinR = FloatArray(pixelCount)
        val sourceLinG = FloatArray(pixelCount)
        val sourceLinB = FloatArray(pixelCount)
        val boostedLinR = FloatArray(pixelCount)
        val boostedLinG = FloatArray(pixelCount)
        val boostedLinB = FloatArray(pixelCount)
        for (i in 0 until pixelCount) {
            val p = sourcePixels[i]
            val r = SRGB_TO_LINEAR_LUT[(p shr 16) and 0xFF]
            val g = SRGB_TO_LINEAR_LUT[(p shr 8) and 0xFF]
            val b = SRGB_TO_LINEAR_LUT[p and 0xFF]
            sourceLinR[i] = r
            sourceLinG[i] = g
            sourceLinB[i] = b
            val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
            val boost = if (luma > HIGHLIGHT_LINEAR_THRESHOLD) HIGHLIGHT_BOOST_MULTIPLIER else 1f
            boostedLinR[i] = r * boost
            boostedLinG[i] = g * boost
            boostedLinB[i] = b * boost
        }

        val levelLinR = arrayOfNulls<FloatArray>(levelCount)
        val levelLinG = arrayOfNulls<FloatArray>(levelCount)
        val levelLinB = arrayOfNulls<FloatArray>(levelCount)
        levelLinR[0] = sourceLinR
        levelLinG[0] = sourceLinG
        levelLinB[0] = sourceLinB
        for (level in 1 until levelCount) {
            val (br, bg, bb) = discBlurLinear(boostedLinR, boostedLinG, boostedLinB,
                depthMapWidth, depthMapHeight, levelRadii[level])
            levelLinR[level] = br
            levelLinG[level] = bg
            levelLinB[level] = bb
        }

        val outPixels = IntArray(pixelCount)
        for (i in 0 until pixelCount) {
            val radius = cocRadiiPixels[i]
            var hi = 1
            while (hi < levelCount - 1 && levelRadii[hi] < radius) hi++
            val lo = hi - 1
            val loRadius = levelRadii[lo]
            val hiRadius = levelRadii[hi]
            val t = if (hiRadius > loRadius) {
                ((radius - loRadius) / (hiRadius - loRadius)).coerceIn(0f, 1f)
            } else {
                0f
            }

            val loR = levelLinR[lo]!![i]; val hiR = levelLinR[hi]!![i]
            val loG = levelLinG[lo]!![i]; val hiG = levelLinG[hi]!![i]
            val loB = levelLinB[lo]!![i]; val hiB = levelLinB[hi]!![i]
            val r = loR + (hiR - loR) * t
            val g = loG + (hiG - loG) * t
            val b = loB + (hiB - loB) * t

            val rByte = (linearToSrgb(r.coerceIn(0f, 1f)) * 255f).roundToInt().coerceIn(0, 255)
            val gByte = (linearToSrgb(g.coerceIn(0f, 1f)) * 255f).roundToInt().coerceIn(0, 255)
            val bByte = (linearToSrgb(b.coerceIn(0f, 1f)) * 255f).roundToInt().coerceIn(0, 255)
            outPixels[i] = -0x1000000 or (rByte shl 16) or (gByte shl 8) or bByte
        }

        val result = Bitmap.createBitmap(depthMapWidth, depthMapHeight, Bitmap.Config.ARGB_8888)
        result.setPixels(outPixels, 0, depthMapWidth, 0, 0, depthMapWidth, depthMapHeight)
        return result
    }

    private fun discBlurLinear(
        r: FloatArray,
        g: FloatArray,
        b: FloatArray,
        width: Int,
        height: Int,
        radius: Float
    ): Triple<FloatArray, FloatArray, FloatArray> {
        if (radius < 0.5f) return Triple(r, g, b)
        val passRadius = max(1, (radius / 3f).roundToInt())

        var rr = r
        var gg = g
        var bb = b
        repeat(3) {
            rr = boxBlurChannelLinear(rr, width, height, passRadius)
            gg = boxBlurChannelLinear(gg, width, height, passRadius)
            bb = boxBlurChannelLinear(bb, width, height, passRadius)
        }
        return Triple(rr, gg, bb)
    }

    private fun boxBlurChannelLinear(channel: FloatArray, width: Int, height: Int, radius: Int): FloatArray {
        if (radius <= 0) return channel
        val stride = width + 1
        val integral = DoubleArray(stride * (height + 1))
        for (y in 0 until height) {
            var rowSum = 0.0
            val rowBase = y * width
            val curRow = (y + 1) * stride
            val prevRow = y * stride
            for (x in 0 until width) {
                rowSum += channel[rowBase + x]
                integral[curRow + x + 1] = integral[prevRow + x + 1] + rowSum
            }
        }

        val out = FloatArray(width * height)
        for (y in 0 until height) {
            val y0 = max(0, y - radius)
            val y1 = min(height - 1, y + radius)
            val rowLo = y0 * stride
            val rowHi = (y1 + 1) * stride
            for (x in 0 until width) {
                val x0 = max(0, x - radius)
                val x1 = min(width - 1, x + radius)
                val area = (x1 - x0 + 1).toLong() * (y1 - y0 + 1).toLong()
                val sum = integral[rowHi + x1 + 1] - integral[rowLo + x1 + 1] -
                        integral[rowHi + x0] + integral[rowLo + x0]
                out[y * width + x] = (sum / area).toFloat()
            }
        }
        return out
    }
}