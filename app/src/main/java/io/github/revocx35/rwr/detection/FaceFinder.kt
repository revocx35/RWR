package io.github.revocx35.rwr.detection

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions

/** Thin wrapper over the bundled (offline) ML Kit face detector. Call [find] off the main thread. */
class FaceFinder : AutoCloseable {
    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            // People behind the user are small in frame; 5% of the image width is ~0.5 m behind at arm's length.
            .setMinFaceSize(0.05f)
            .build()
    )

    /** Blocks until detection finishes. [upright] must already be rotated to display orientation. */
    fun find(upright: Bitmap): List<FaceBox> =
        Tasks.await(detector.process(InputImage.fromBitmap(upright, 0))).map {
            val b = it.boundingBox
            FaceBox(b.left, b.top, b.right, b.bottom, it.headEulerAngleY, it.headEulerAngleX)
        }

    override fun close() = detector.close()
}
