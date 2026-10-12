package io.github.revocx35.rwr.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream

/** Camera frame <-> JPEG helpers. */
object FrameCodec {
    /**
     * JPEG in sensor orientation, without going through a Bitmap: YUV_420_888 is repacked to NV21 and
     * compressed natively by YuvImage, which is far cheaper than toBitmap() + compress().
     */
    fun toJpeg(image: ImageProxy, quality: Int = 80): ByteArray {
        if (image.format != ImageFormat.YUV_420_888) {
            val out = ByteArrayOutputStream(64 * 1024)
            image.toBitmap().compress(Bitmap.CompressFormat.JPEG, quality, out)
            return out.toByteArray()
        }
        val w = image.width
        val h = image.height
        val nv21 = ByteArray(w * h * 3 / 2)
        val (yp, up, vp) = image.planes
        // Y
        var pos = 0
        val yBuf = yp.buffer
        for (row in 0 until h) {
            yBuf.position(row * yp.rowStride)
            if (yp.pixelStride == 1) {
                yBuf.get(nv21, pos, w); pos += w
            } else {
                for (col in 0 until w) nv21[pos++] = yBuf.get(row * yp.rowStride + col * yp.pixelStride)
            }
        }
        // Interleaved V/U
        val uBuf = up.buffer
        val vBuf = vp.buffer
        for (row in 0 until h / 2) {
            for (col in 0 until w / 2) {
                nv21[pos++] = vBuf.get(row * vp.rowStride + col * vp.pixelStride)
                nv21[pos++] = uBuf.get(row * up.rowStride + col * up.pixelStride)
            }
        }
        val out = ByteArrayOutputStream(64 * 1024)
        YuvImage(nv21, ImageFormat.NV21, w, h, null).compressToJpeg(Rect(0, 0, w, h), quality, out)
        return out.toByteArray()
    }

    /** Decodes a frame and makes it upright and selfie-mirrored, like the live analysis frames. */
    fun uprightMirrored(f: EncodedFrame): Bitmap? {
        val raw = BitmapFactory.decodeByteArray(f.jpeg, 0, f.jpeg.size) ?: return null
        return uprightMirrored(raw, f.rotation)
    }

    fun uprightMirrored(raw: Bitmap, rotation: Int): Bitmap {
        val m = Matrix().apply {
            postRotate(rotation.toFloat())
            postScale(-1f, 1f)
        }
        return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
    }
}
