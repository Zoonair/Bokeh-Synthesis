package com.example.bokehsynthesis

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import kotlin.math.abs
import kotlin.math.exp
import androidx.core.graphics.createBitmap

class DepthMap(
    val width: Int,
    val height: Int,
    val diopters: FloatArray,
    val unresolved: BooleanArray
)

object DepthMapBuilder {

    // --- Sum-Modified Laplacian sharpness scoring ---
    // Pixel offset used by the algorithm. 1 is standard. larger values look
    // further afield per comparison, which can help on very high-resolution
    // frames but isn't needed at analysis resolution.
    const val ML_STEP = 1

    // Radius of the window summed around each pixel (patch size).
    // Small = precise but noisy near edges. Large = smoother but blurs
    // separate depths together.
    const val SML_WINDOW_RADIUS = 7

    // Noise floor of the image when comparing to the brightness value.
    // Anything less than or equal to this value will be considered noise
    // and will have a value of 0.
    const val NOISE_FLOOR = 15f


    // --- Depth-map construction ---

    // How much sharper a later (nearer) frame needs to be than the pixel's
    // current winner before it's allowed to take over. This is a percentage,
    // and ensures the depth map stops at the strongest point.
    const val RELATIVE_MARGIN = 0.08f

    // How far above the WHOLE IMAGE's typical sharpness a frame's reading
    // has to be before it's even eligible to compete for a pixel at all.
    // After some testing, it seemed that 1 was suitable for most scenarios.
    const val ELIGIBILITY_MULTIPLIER = 1f


    // --- Post-construction despeckle: anomaly removal via region growing ---

    // A threshold used to decided if two neighboring pixels belong to the
    // same object. This is essentially the value used to see if pixels are
    // "close enough". The higher the number, the more aggressively it merges
    // (makes flatter).
    const val REGION_MERGE_TOLERANCE = 0.15f

    // Constant used to decided if a group of pixels is a real object, or just
    // noise. The algorithm uses this constant to peel away layers of pixels.
    // If a region is so thin that it completely disappears after being eroded
    // by this constant, the algorithm decides it is untrustworthy.
    const val THICKNESS_THRESHOLD_PIXELS = SML_WINDOW_RADIUS + 1


    // --- Frame alignment ---

    // Nudges each frame in a sweep be these many pixels in every direction
    // (up, down, left, right).
    private const val ALIGNMENT_MAX_SHIFT = 12

    // Instead of looking at every single pixel in the app, this constant
    // tells how many pixels the algorithm can skip by. This is because
    // checking each pixel one at a time would take far too long to process.
    private const val ALIGNMENT_SUBSAMPLE = 4


    // --- Infill ---

    // Number of passes (or rounds) to fill missing data.
    const val INFILL_ITERATIONS = 40

    // The sensitivity for the infill. It determines how strictly the
    // algorithm should respect color boundaries when filling in the missing
    // depth data.
    const val INFILL_COLOR_SIGMA = 30f

    private fun estimateShift(
        reference: ByteArray,
        referenceStride: Int,
        target: ByteArray,
        targetStride: Int,
        width: Int,
        height: Int
    ): Pair<Int, Int> {
        val margin = ALIGNMENT_MAX_SHIFT + ALIGNMENT_SUBSAMPLE
        val roiLeft = (width * 0.25f).toInt().coerceAtLeast(margin)
        val roiRight = (width * 0.75f).toInt().coerceAtMost(width - margin)
        val roiTop = (height * 0.25f).toInt().coerceAtLeast(margin)
        val roiBottom = (height * 0.75f).toInt().coerceAtMost(height - margin)
        if (roiRight <= roiLeft || roiBottom <= roiTop) return 0 to 0

        var bestDx = 0
        var bestDy = 0
        var bestScore = Long.MAX_VALUE
        for (dy in -ALIGNMENT_MAX_SHIFT..ALIGNMENT_MAX_SHIFT) {
            for (dx in -ALIGNMENT_MAX_SHIFT..ALIGNMENT_MAX_SHIFT) {
                var sum = 0L
                var y = roiTop
                while (y < roiBottom) {
                    val refRow = y * referenceStride
                    val targetRow = (y + dy) * targetStride
                    var x = roiLeft
                    while (x < roiRight) {
                        val refVal = reference[refRow + x].toInt() and 0xFF
                        val tVal = target[targetRow + x + dx].toInt() and 0xFF
                        val diff = refVal - tVal
                        sum += (diff * diff).toLong()
                        x += ALIGNMENT_SUBSAMPLE
                    }
                    y += ALIGNMENT_SUBSAMPLE
                }
                if (sum < bestScore) {
                    bestScore = sum
                    bestDx = dx
                    bestDy = dy
                }
            }
        }
        return bestDx to bestDy
    }

    private fun sampleClamped(
        bytes: ByteArray,
        stride: Int,
        x: Int,
        y: Int,
        shiftX: Int,
        shiftY: Int,
        width: Int,
        height: Int
    ): Int {
        val sx = (x + shiftX).coerceIn(0, width - 1)
        val sy = (y + shiftY).coerceIn(0, height - 1)
        return bytes[sy * stride + sx].toInt() and 0xFF
    }

    fun build(sweepFrames: List<SweepFrame>): DepthMap? {
        if (sweepFrames.isEmpty()) return null
        val width = sweepFrames[0].width
        val height = sweepFrames[0].height
        val pixelCount = width * height

        val framesFarToNear = sweepFrames.sortedBy { it.focusDistanceDiopters }

        val referenceFrame = framesFarToNear[0]
        val referenceBytes = referenceFrame.readYPlane()
        val referenceStride = referenceFrame.rowStride
        val shifts: List<Pair<Int, Int>> = framesFarToNear.map { frame ->
            if (frame === referenceFrame) {
                0 to 0
            } else {
                estimateShift(
                    referenceBytes, referenceStride,
                    frame.readYPlane(), frame.rowStride,
                    width, height
                )
            }
        }

        var sum = 0.0
        var count = 0L
        for ((index, frame) in framesFarToNear.withIndex()) {
            val (shiftX, shiftY) = shifts[index]
            val sharpness = computeSharpnessMap(frame, width, height, shiftX, shiftY)
            for (value in sharpness) {
                sum += value
                count++
            }
        }
        val globalAverageSharpness = if (count > 0) (sum / count).toFloat() else 0f
        val eligibilityFloor = globalAverageSharpness * ELIGIBILITY_MULTIPLIER

        val trueMaxSharpness = FloatArray(pixelCount) { -1f }
        val diopters = FloatArray(pixelCount)

        fun considerFrame(frame: SweepFrame, sharpness: FloatArray, prev: FloatArray?, next: FloatArray?) {
            for (i in 0 until pixelCount) {
                val s = sharpness[i]
                if (s < eligibilityFloor) continue
                if (prev != null && s < prev[i]) continue
                if (next != null && s < next[i]) continue
                val currentMax = trueMaxSharpness[i]
                if (s > currentMax) {
                    trueMaxSharpness[i] = s
                    diopters[i] = frame.focusDistanceDiopters
                } else if (currentMax > 0f && s >= currentMax * (1f - RELATIVE_MARGIN)) {
                    diopters[i] = frame.focusDistanceDiopters
                }
            }
        }

        var prevSharpness: FloatArray? = null
        var currFrame = framesFarToNear[0]
        var currSharpness = computeSharpnessMap(currFrame, width, height, shifts[0].first, shifts[0].second)
        for (frameIndex in 1 until framesFarToNear.size) {
            val frame = framesFarToNear[frameIndex]
            val (shiftX, shiftY) = shifts[frameIndex]
            val nextSharpness = computeSharpnessMap(frame, width, height, shiftX, shiftY)
            considerFrame(currFrame, currSharpness, prevSharpness, nextSharpness)
            prevSharpness = currSharpness
            currSharpness = nextSharpness
            currFrame = frame
        }
        considerFrame(currFrame, currSharpness, prevSharpness, null)

        val nearestDiopter = framesFarToNear.last().focusDistanceDiopters
        val unresolved = BooleanArray(pixelCount)
        for (i in 0 until pixelCount) {
            if (trueMaxSharpness[i] < eligibilityFloor) {
                unresolved[i] = true
                diopters[i] = nearestDiopter
            }
        }

        val despeckled = despeckle(diopters, unresolved, width, height)

        return DepthMap(width, height, despeckled, unresolved)
    }

    private fun despeckle(
        diopters: FloatArray,
        unresolved: BooleanArray,
        width: Int,
        height: Int
    ): FloatArray {
        val pixelCount = width * height

        val parent = IntArray(pixelCount) { it }
        fun find(start: Int): Int {
            var r = start
            while (parent[r] != r) r = parent[r]
            var c = start
            while (parent[c] != c) {
                val next = parent[c]
                parent[c] = r
                c = next
            }
            return r
        }
        fun union(a: Int, b: Int) {
            val ra = find(a)
            val rb = find(b)
            if (ra != rb) parent[ra] = rb
        }

        for (y in 0 until height) {
            for (x in 0 until width) {
                val idx = y * width + x
                if (unresolved[idx]) continue
                if (x + 1 < width) {
                    val rightIdx = idx + 1
                    if (!unresolved[rightIdx] &&
                        abs(diopters[idx] - diopters[rightIdx]) <= REGION_MERGE_TOLERANCE) {
                        union(idx, rightIdx)
                    }
                }
                if (y + 1 < height) {
                    val downIdx = idx + width
                    if (!unresolved[downIdx] &&
                        abs(diopters[idx] - diopters[downIdx]) <= REGION_MERGE_TOLERANCE) {
                        union(idx, downIdx)
                    }
                }
            }
        }

        val touchesBorder = HashSet<Int>()
        val touchesUnresolved = HashSet<Int>()
        val isBoundaryPixel = BooleanArray(pixelCount)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val idx = y * width + x
                if (unresolved[idx]) continue
                val root = find(idx)
                var boundary = false
                if (x == 0 || x == width - 1 || y == 0 || y == height - 1) {
                    touchesBorder.add(root)
                    boundary = true
                }
                if (x + 1 < width) {
                    if (unresolved[idx + 1]) { touchesUnresolved.add(root); boundary = true }
                    else if (find(idx + 1) != root) boundary = true
                }
                if (x > 0) {
                    if (unresolved[idx - 1]) { touchesUnresolved.add(root); boundary = true }
                    else if (find(idx - 1) != root) boundary = true
                }
                if (y + 1 < height) {
                    if (unresolved[idx + width]) { touchesUnresolved.add(root); boundary = true }
                    else if (find(idx + width) != root) boundary = true
                }
                if (y > 0) {
                    if (unresolved[idx - width]) { touchesUnresolved.add(root); boundary = true }
                    else if (find(idx - width) != root) boundary = true
                }
                isBoundaryPixel[idx] = boundary
            }
        }

        val erosionDistance = IntArray(pixelCount) { -1 }
        val erosionQueue = ArrayDeque<Int>()
        for (i in 0 until pixelCount) {
            if (unresolved[i]) continue
            if (isBoundaryPixel[i]) {
                erosionDistance[i] = 0
                erosionQueue.addLast(i)
            }
        }
        while (erosionQueue.isNotEmpty()) {
            val cur = erosionQueue.removeFirst()
            val cx = cur % width
            val cy = cur / width
            val curRoot = find(cur)
            val curDist = erosionDistance[cur]
            val neighbors = intArrayOf(
                if (cx > 0) cur - 1 else -1,
                if (cx < width - 1) cur + 1 else -1,
                if (cy > 0) cur - width else -1,
                if (cy < height - 1) cur + width else -1
            )
            for (nIdx in neighbors) {
                if (nIdx < 0) continue
                if (unresolved[nIdx]) continue
                if (erosionDistance[nIdx] != -1) continue
                if (find(nIdx) != curRoot) continue
                erosionDistance[nIdx] = curDist + 1
                erosionQueue.addLast(nIdx)
            }
        }

        val maxErosionByRoot = HashMap<Int, Int>()
        for (i in 0 until pixelCount) {
            if (unresolved[i]) continue
            val root = find(i)
            val d = erosionDistance[i]
            if (d > (maxErosionByRoot[root] ?: -1)) maxErosionByRoot[root] = d
        }
        val isTrustworthy = HashSet<Int>()
        val isFrozen = HashSet<Int>()
        for ((root, maxDist) in maxErosionByRoot) {
            if (maxDist >= THICKNESS_THRESHOLD_PIXELS) {
                isTrustworthy.add(root)
            } else if (touchesBorder.contains(root) || touchesUnresolved.contains(root)) {
                isFrozen.add(root)
            }
        }

        val visited = BooleanArray(pixelCount)
        val assigned = FloatArray(pixelCount)
        val queue = ArrayDeque<Int>()

        fun isGrowable(idx: Int): Boolean {
            if (unresolved[idx]) return false
            val root = find(idx)
            return !isTrustworthy.contains(root) && !isFrozen.contains(root)
        }

        fun neighborsOf(idx: Int, x: Int, y: Int): IntArray = intArrayOf(
            if (x > 0) idx - 1 else -1,
            if (x < width - 1) idx + 1 else -1,
            if (y > 0) idx - width else -1,
            if (y < height - 1) idx + width else -1
        )

        for (y in 0 until height) {
            for (x in 0 until width) {
                val idx = y * width + x
                if (unresolved[idx]) continue
                if (!isTrustworthy.contains(find(idx))) continue
                for (nIdx in neighborsOf(idx, x, y)) {
                    if (nIdx < 0) continue
                    if (!isGrowable(nIdx) || visited[nIdx]) continue
                    visited[nIdx] = true
                    assigned[nIdx] = diopters[idx]
                    queue.addLast(nIdx)
                }
            }
        }

        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            val cx = cur % width
            val cy = cur / width
            for (nIdx in neighborsOf(cur, cx, cy)) {
                if (nIdx < 0) continue
                if (!isGrowable(nIdx) || visited[nIdx]) continue
                visited[nIdx] = true
                assigned[nIdx] = assigned[cur]
                queue.addLast(nIdx)
            }
        }

        val out = diopters.copyOf()
        for (i in 0 until pixelCount) {
            if (visited[i]) out[i] = assigned[i]
        }
        return out
    }

    private fun computeSharpnessMap(
        frame: SweepFrame,
        width: Int,
        height: Int,
        shiftX: Int,
        shiftY: Int
    ): FloatArray {
        val bytes = frame.readYPlane()
        val stride = frame.rowStride
        val s = ML_STEP

        val ml = FloatArray(width * height)
        for (y in s until height - s) {
            for (x in s until width - s) {
                val center = sampleClamped(bytes, stride, x, y, shiftX, shiftY, width, height)
                val left = sampleClamped(bytes, stride, x - s, y, shiftX, shiftY, width, height)
                val right = sampleClamped(bytes, stride, x + s, y, shiftX, shiftY, width, height)
                val up = sampleClamped(bytes, stride, x, y - s, shiftX, shiftY, width, height)
                val down = sampleClamped(bytes, stride, x, y + s, shiftX, shiftY, width, height)

                val value = abs(2 * center - left - right) + abs(2 * center - up - down)
                ml[y * width + x] = if (value > NOISE_FLOOR) value.toFloat() else 0f
            }
        }

        val r = SML_WINDOW_RADIUS
        val horizontalSum = FloatArray(width * height)
        for (y in 0 until height) {
            val rowOffset = y * width
            for (x in 0 until width) {
                val xStart = (x - r).coerceAtLeast(0)
                val xEnd = (x + r).coerceAtMost(width - 1)
                var acc = 0f
                for (xi in xStart..xEnd) acc += ml[rowOffset + xi]
                horizontalSum[rowOffset + x] = acc
            }
        }

        val sml = FloatArray(width * height)
        for (x in 0 until width) {
            for (y in 0 until height) {
                val yStart = (y - r).coerceAtLeast(0)
                val yEnd = (y + r).coerceAtMost(height - 1)
                var acc = 0f
                for (yi in yStart..yEnd) acc += horizontalSum[yi * width + x]
                sml[y * width + x] = acc
            }
        }

        return sml
    }



    fun fillUnresolvedGuided(depthMap: DepthMap, colorPixels: IntArray): DepthMap {
        val width = depthMap.width
        val height = depthMap.height
        val pixelCount = width * height
        if (depthMap.unresolved.none { it }) return depthMap

        val known = BooleanArray(pixelCount) { !depthMap.unresolved[it] }
        val working = depthMap.diopters.copyOf()
        val hasValue = known.copyOf()

        val colorR = IntArray(pixelCount)
        val colorG = IntArray(pixelCount)
        val colorB = IntArray(pixelCount)
        for (i in 0 until pixelCount) {
            val p = colorPixels[i]
            colorR[i] = (p shr 16) and 0xFF
            colorG[i] = (p shr 8) and 0xFF
            colorB[i] = p and 0xFF
        }

        fun colorWeight(a: Int, b: Int): Float {
            val dr = (colorR[a] - colorR[b]).toFloat()
            val dg = (colorG[a] - colorG[b]).toFloat()
            val db = (colorB[a] - colorB[b]).toFloat()
            val dist2 = dr * dr + dg * dg + db * db
            return exp(-dist2 / (2f * INFILL_COLOR_SIGMA * INFILL_COLOR_SIGMA))
        }

        for (iteration in 0 until INFILL_ITERATIONS) {
            val forward = iteration % 2 == 0
            val xs = if (forward) (0 until width) else (width - 1 downTo 0)
            val ys = if (forward) (0 until height) else (height - 1 downTo 0)
            for (y in ys) {
                for (x in xs) {
                    val idx = y * width + x

                    var weightedSum = 0f
                    var totalWeight = 0f
                    for (dy in -1..1) {
                        for (dx in -1..1) {
                            if (dx == 0 && dy == 0) continue
                            val nx = x + dx
                            val ny = y + dy
                            if (nx < 0 || nx >= width || ny < 0 || ny >= height) continue
                            val nIdx = ny * width + nx
                            if (!hasValue[nIdx]) continue
                            val w = colorWeight(idx, nIdx)
                            weightedSum += w * working[nIdx]
                            totalWeight += w
                        }
                    }
                    if (totalWeight > 0f) {
                        working[idx] = weightedSum / totalWeight
                        hasValue[idx] = true
                    }
                }
            }
        }

        val stillUnresolved = BooleanArray(pixelCount) { !hasValue[it] }
        return DepthMap(width, height, working, stillUnresolved)
    }
}

private val UNRESOLVED_COLOR = Color.rgb(0, 0, 0)

private fun colormapColor(normalized: Float): Int {
    return if (normalized < 0.5f) {
        val t = normalized / 0.5f
        Color.rgb(0, (t * 255).toInt(), ((1f - t) * 255).toInt())
    } else {
        val t = (normalized - 0.5f) / 0.5f
        Color.rgb((t * 255).toInt(), ((1f - t) * 255).toInt(), 0)
    }
}

fun DepthMap.toPreviewBitmap(): Bitmap {
    val resolvedDiopters = diopters.filterIndexed { i, _ -> !unresolved[i] }
    val minDiopter = resolvedDiopters.minOrNull() ?: 0f
    val maxDiopter = resolvedDiopters.maxOrNull() ?: 1f
    val range = (maxDiopter - minDiopter).takeIf { it > 0f } ?: 1f

    val pixels = IntArray(width * height)
    for (i in pixels.indices) {
        pixels[i] = if (unresolved[i]) {
            UNRESOLVED_COLOR
        } else {
            val normalized = ((diopters[i] - minDiopter) / range).coerceIn(0f, 1f)
            colormapColor(normalized)
        }
    }

    val legendHeight = (height * 0.12f).toInt().coerceAtLeast(70)
    val full = createBitmap(width, height + legendHeight)
    full.setPixels(pixels, 0, width, 0, 0, width, height)

    val canvas = Canvas(full)
    val barLeft = 16f
    val barRight = width - 16f
    val barTop = height + 12f
    val barBottom = (height + legendHeight - 28).toFloat()

    val barPaint = Paint().apply {
        shader = LinearGradient(
            barLeft, 0f, barRight, 0f,
            intArrayOf(colormapColor(0f), colormapColor(0.5f), colormapColor(1f)),
            null,
            Shader.TileMode.CLAMP
        )
    }
    canvas.drawRect(barLeft, barTop, barRight, barBottom, barPaint)

    val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = legendHeight * 0.28f
        isAntiAlias = true
    }
    canvas.drawText(
        "Far (%.2f D)".format(minDiopter),
        barLeft,
        (height + legendHeight - 6).toFloat(),
        textPaint
    )
    val nearLabel = "Near (%.2f D)".format(maxDiopter)
    canvas.drawText(
        nearLabel,
        barRight - textPaint.measureText(nearLabel),
        (height + legendHeight - 6).toFloat(),
        textPaint
    )

    return full
}