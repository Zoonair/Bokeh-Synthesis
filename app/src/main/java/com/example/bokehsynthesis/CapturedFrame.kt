package com.example.bokehsynthesis

import android.graphics.Bitmap
import java.io.File

data class SweepFrame(
    val focusDistanceDiopters: Float,
    val requestedDiopter: Float,
    val timestampNanos: Long,
    val yPlaneFile: File,
    val width: Int,
    val height: Int,
    val rowStride: Int
) {
    fun readYPlane(): ByteArray = yPlaneFile.readBytes()
}

data class OriginalFrame(
    val timestampNanos: Long,
    val yPlane: ByteArray,
    val uPlane: ByteArray,
    val vPlane: ByteArray,
    val yRowStride: Int,
    val uvRowStride: Int,
    val uvPixelStride: Int,
    val width: Int,
    val height: Int,
    val focusDistanceDiopters: Float = Float.NaN
) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

data class SweepCaptureResult(
    val original: OriginalFrame,
    val sweepFrames: List<SweepFrame>
)

fun OriginalFrame.toNv21(): ByteArray {
    val ySize = width * height
    val chromaWidth = width / 2
    val chromaHeight = height / 2
    val nv21 = ByteArray(ySize + chromaWidth * chromaHeight * 2)

    var pos = 0
    for (row in 0 until height) {
        val rowStart = row * yRowStride
        System.arraycopy(yPlane, rowStart, nv21, pos, width)
        pos += width
    }

    for (row in 0 until chromaHeight) {
        val rowStart = row * uvRowStride
        for (col in 0 until chromaWidth) {
            val index = rowStart + col * uvPixelStride
            nv21[pos++] = vPlane[index]
            nv21[pos++] = uPlane[index]
        }
    }
    return nv21
}

fun OriginalFrame.toBitmap(): Bitmap {
    val nv21 = toNv21()
    val argb = IntArray(width * height)
    val frameSize = width * height
    var yp = 0
    for (j in 0 until height) {
        var uvp = frameSize + (j shr 1) * width
        var u = 0
        var v = 0
        for (i in 0 until width) {
            val y = (0xff and nv21[yp].toInt()) - 16
            val yValue = if (y < 0) 0 else y
            if (i and 1 == 0) {
                v = (0xff and nv21[uvp++].toInt()) - 128
                u = (0xff and nv21[uvp++].toInt()) - 128
            }
            val y1192 = 1192 * yValue
            var r = y1192 + 1634 * v
            var g = y1192 - 833 * v - 400 * u
            var b = y1192 + 2066 * u
            r = (r shr 10).coerceIn(0, 255)
            g = (g shr 10).coerceIn(0, 255)
            b = (b shr 10).coerceIn(0, 255)
            argb[yp] = -0x1000000 or (r shl 16) or (g shl 8) or b
            yp++
        }
    }
    return Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888)
}