package io.github.revocx35.rwr.detection

import kotlin.math.asin
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Decoding for the YuNet face detector (OpenCV Zoo, MIT license), the same detector the RWR server uses.
 * Pure Kotlin so it is unit-tested on the JVM; [YuNetFinder] runs the model with LiteRT.
 *
 * The model outputs, for each stride 8/16/32 of the [INPUT_SIZE] square input, one row per grid cell:
 * class and objectness scores, a box (cell offsets plus log size) and five landmarks (eyes, nose tip,
 * mouth corners). This mirrors OpenCV's FaceDetectorYN post-processing; tools/convert_yunet.sh checks the
 * converted model against OpenCV on test images.
 */
object YuNet {
    const val INPUT_SIZE = 640
    val STRIDES = intArrayOf(8, 16, 32)
    const val SCORE_THRESHOLD = 0.6f
    const val NMS_IOU = 0.3f
    const val TOP_K = 50

    /** A detection in input pixels: box plus landmarks (x, y for right eye, left eye, nose, mouth right, mouth left). */
    class Detection(val score: Float, val x: Float, val y: Float, val w: Float, val h: Float, val points: FloatArray)

    /** Outputs of one stride: [cls] and [obj] one value per cell, [bbox] 4 and [kps] 10 values per cell. */
    class StrideOutput(val stride: Int, val cls: FloatArray, val obj: FloatArray, val bbox: FloatArray, val kps: FloatArray)

    fun decode(outputs: List<StrideOutput>, threshold: Float = SCORE_THRESHOLD): List<Detection> {
        val found = ArrayList<Detection>()
        for (o in outputs) {
            val cols = INPUT_SIZE / o.stride
            val s = o.stride.toFloat()
            for (i in o.cls.indices) {
                val score = sqrt(o.cls[i].coerceIn(0f, 1f) * o.obj[i].coerceIn(0f, 1f))
                if (score < threshold) continue
                val r = i / cols
                val c = i % cols
                val cx = (c + o.bbox[i * 4]) * s
                val cy = (r + o.bbox[i * 4 + 1]) * s
                val w = exp(o.bbox[i * 4 + 2]) * s
                val h = exp(o.bbox[i * 4 + 3]) * s
                val pts = FloatArray(10) { k -> ((if (k % 2 == 0) c else r) + o.kps[i * 10 + k]) * s }
                found += Detection(score, cx - w / 2, cy - h / 2, w, h, pts)
            }
        }
        return nms(found)
    }

    private fun nms(dets: List<Detection>): List<Detection> {
        val keep = ArrayList<Detection>()
        for (d in dets.sortedByDescending { it.score }) {
            if (keep.size >= TOP_K) break
            if (keep.all { iou(it, d) < NMS_IOU }) keep += d
        }
        return keep
    }

    private fun iou(a: Detection, b: Detection): Float {
        val iw = min(a.x + a.w, b.x + b.w) - max(a.x, b.x)
        val ih = min(a.y + a.h, b.y + b.h) - max(a.y, b.y)
        if (iw <= 0 || ih <= 0) return 0f
        val inter = iw * ih
        return inter / (a.w * a.h + b.w * b.h - inter)
    }

    /**
     * Rough head pose (yaw, pitch in degrees) from the five landmarks, same formula as the server's
     * `_pose_from_five_points`. Only a prefilter: faces that might count are re-measured with Face Landmarker.
     */
    fun roughPose(p: FloatArray): Pair<Float, Float> {
        val eyeMidX = (p[0] + p[2]) / 2
        val eyeMidY = (p[1] + p[3]) / 2
        val mouthMidY = (p[7] + p[9]) / 2
        val eyeDist = hypot(p[2] - p[0], p[3] - p[1]).takeIf { it > 0f } ?: 1f
        val yaw = Math.toDegrees(asin((2 * (p[4] - eyeMidX) / eyeDist).coerceIn(-1f, 1f).toDouble())).toFloat()
        val span = (mouthMidY - eyeMidY).takeIf { it != 0f } ?: 1f
        // The nose tip sits ~55% of the way from the eyes to the mouth on a level face.
        val pitch = (0.55f - (p[5] - eyeMidY) / span) * 150f
        return yaw to pitch
    }
}
