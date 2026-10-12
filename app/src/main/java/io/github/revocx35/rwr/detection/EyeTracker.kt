package io.github.revocx35.rwr.detection

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker

/**
 * Eye tracking with MediaPipe Face Landmarker (468 face points + 10 iris points, the model behind
 * "laser eyes" style filters). Runs only on faces ML Kit already found: each one is cropped, upscaled to
 * [CROP_SIZE] and passed through the landmarker, so small faces behind you get enough pixels for the irises.
 * Not thread-safe: use from the analysis thread only.
 */
class EyeTracker(context: Context) : AutoCloseable {
    private val landmarker = FaceLandmarker.createFromOptions(
        context,
        FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL).build())
            .setRunningMode(RunningMode.IMAGE)
            .setNumFaces(1)
            .setMinFaceDetectionConfidence(0.3f)
            .setMinFacePresenceConfidence(0.3f)
            .build()
    )
    private val crop = Bitmap.createBitmap(CROP_SIZE, CROP_SIZE, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(crop)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    /** Gaze of the face in [box] of the upright [frame], or null if the landmarker finds no face there. */
    fun gaze(frame: Bitmap, box: FaceBox): Gaze? {
        val side = (maxOf(box.right - box.left, box.bottom - box.top) * CROP_MARGIN).toInt()
        if (side < 8) return null
        val x0 = (box.left + box.right) / 2 - side / 2
        val y0 = (box.top + box.bottom) / 2 - side / 2
        // Square crop centred on the face; parts outside the frame stay black (keeps the face centred).
        canvas.drawColor(Color.BLACK)
        val scale = CROP_SIZE.toFloat() / side
        val src = Rect(maxOf(0, x0), maxOf(0, y0), minOf(frame.width, x0 + side), minOf(frame.height, y0 + side))
        if (src.isEmpty) return null
        val dst = Rect(
            ((src.left - x0) * scale).toInt(), ((src.top - y0) * scale).toInt(),
            ((src.right - x0) * scale).toInt(), ((src.bottom - y0) * scale).toInt(),
        )
        canvas.drawBitmap(frame, src, dst, paint)
        val result = landmarker.detect(BitmapImageBuilder(crop).build())
        val lm = result.faceLandmarks().firstOrNull() ?: return null
        if (lm.size < GazeEstimator.LANDMARKS) return null
        val pts = FloatArray(lm.size * 3)
        lm.forEachIndexed { i, p ->
            pts[i * 3] = x0 + p.x() * side
            pts[i * 3 + 1] = y0 + p.y() * side
            pts[i * 3 + 2] = p.z() * side
        }
        return GazeEstimator.estimate(pts)
    }

    override fun close() = landmarker.close()

    companion object {
        const val MODEL = "face_landmarker.task"
        const val CROP_SIZE = 256
        const val CROP_MARGIN = 1.6f
    }
}
