package io.github.revocx35.rwr.detection

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.channels.FileChannel

/**
 * Finds faces with YuNet (the same detector as the RWR server; open source, MIT) running on LiteRT.
 * Small faces are found well, which matters for people standing behind the user. Not thread-safe: use from
 * the analysis thread only. Call [find] off the main thread.
 */
class FaceFinder(context: Context) : AutoCloseable {
    private val interpreter: Interpreter
    private val input: ByteBuffer
    private val outputs = HashMap<Int, FloatBuffer>()
    private val strideIndex = HashMap<String, Int>()
    private val canvasBitmap = Bitmap.createBitmap(YuNet.INPUT_SIZE, YuNet.INPUT_SIZE, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(canvasBitmap)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pixels = IntArray(YuNet.INPUT_SIZE * YuNet.INPUT_SIZE)
    private val floats = FloatArray(3 * YuNet.INPUT_SIZE * YuNet.INPUT_SIZE)

    init {
        val model = context.assets.openFd(MODEL).use { fd ->
            FileInputStream(fd.fileDescriptor).channel.use {
                it.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            }
        }
        interpreter = Interpreter(model, Interpreter.Options().setNumThreads(2))
        input = ByteBuffer.allocateDirect(4 * 3 * YuNet.INPUT_SIZE * YuNet.INPUT_SIZE).order(ByteOrder.nativeOrder())
        for (i in 0 until interpreter.outputTensorCount) {
            val t = interpreter.getOutputTensor(i)
            strideIndex[t.name().substringBefore(':')] = i
            outputs[i] = ByteBuffer.allocateDirect(4 * t.numElements()).order(ByteOrder.nativeOrder()).asFloatBuffer()
        }
        check(YuNet.STRIDES.all { s -> listOf("cls", "obj", "bbox", "kps").all { strideIndex.containsKey("${it}_$s") } }) {
            "unexpected YuNet outputs: ${strideIndex.keys}"
        }
    }

    /** [upright] must already be rotated to display orientation. Boxes come back in its pixel coordinates. */
    fun find(upright: Bitmap): List<FaceBox> {
        // Scale the longer side to the model's square input; the rest stays black (as OpenCV pads).
        val scale = YuNet.INPUT_SIZE.toFloat() / maxOf(upright.width, upright.height)
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(upright, Matrix().apply { setScale(scale, scale) }, paint)
        canvasBitmap.getPixels(pixels, 0, YuNet.INPUT_SIZE, 0, 0, YuNet.INPUT_SIZE, YuNet.INPUT_SIZE)
        var j = 0
        for (p in pixels) {  // BGR, 0..255: what YuNet was trained on
            floats[j++] = (p and 0xFF).toFloat()
            floats[j++] = (p shr 8 and 0xFF).toFloat()
            floats[j++] = (p shr 16 and 0xFF).toFloat()
        }
        input.rewind()
        input.asFloatBuffer().put(floats)
        outputs.values.forEach { it.rewind() }
        interpreter.runForMultipleInputsOutputs(arrayOf<Any>(input), outputs.mapValues { it.value as Any })

        val strides = YuNet.STRIDES.map { s ->
            fun out(name: String) = outputs.getValue(strideIndex.getValue("${name}_$s")).let { b ->
                b.rewind(); FloatArray(b.remaining()).also { b.get(it) }
            }
            YuNet.StrideOutput(s, out("cls"), out("obj"), out("bbox"), out("kps"))
        }
        return YuNet.decode(strides).map { d ->
            val (yaw, pitch) = YuNet.roughPose(d.points)
            FaceBox(
                (d.x / scale).toInt().coerceAtLeast(0), (d.y / scale).toInt().coerceAtLeast(0),
                ((d.x + d.w) / scale).toInt().coerceAtMost(upright.width), ((d.y + d.h) / scale).toInt().coerceAtMost(upright.height),
                yaw, pitch,
            )
        }
    }

    override fun close() = interpreter.close()

    companion object {
        /** Converted from OpenCV Zoo's face_detection_yunet_2023mar.onnx by tools/convert_yunet.sh. */
        const val MODEL = "face_detection_yunet_2023mar_640.tflite"
    }
}
