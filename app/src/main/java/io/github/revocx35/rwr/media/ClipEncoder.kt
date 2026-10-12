package io.github.revocx35.rwr.media

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File

/**
 * Encodes frames to an H.264 MP4 with the platform encoder (hardware on real phones). Runs only when an
 * alert fires, on a background thread. Frames are fed as YUV through MediaCodec's Image API, which works with
 * every encoder colour layout (planar or semi-planar).
 */
object ClipEncoder {
    private const val TAG = "ClipEncoder"
    private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
    private const val TIMEOUT_US = 10_000L

    /**
     * [render] turns input frame [i] into an upright bitmap (decode + rotate + annotate). Returns false on failure,
     * leaving no partial file.
     */
    fun encode(timestamps: List<Long>, render: (Int) -> Bitmap?, out: File): Boolean {
        if (timestamps.isEmpty()) return false
        val first = render(0) ?: return false
        val w = first.width and 1.inv()
        val h = first.height and 1.inv()
        val fps = ClipTiming.fps(timestamps)
        val order = ClipTiming.resample(timestamps, fps)

        val format = MediaFormat.createVideoFormat(MIME, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, (w * h * 4).coerceIn(800_000, 6_000_000))
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = runCatching { MediaCodec.createEncoderByType(MIME) }.getOrElse {
            Log.e(TAG, "no H.264 encoder", it); return false
        }
        var muxer: MediaMuxer? = null
        var ok = false
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val info = MediaCodec.BufferInfo()
            var track = -1
            var muxing = false
            var inputDone = false
            var next = 0
            var cached: Pair<Int, Bitmap>? = 0 to first
            val argb = IntArray(w * h)
            while (true) {
                if (!inputDone) {
                    val idx = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (idx >= 0) {
                        if (next >= order.size) {
                            codec.queueInputBuffer(idx, 0, 0, ptsUs(next, fps), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val src = order[next]
                            val bmp = cached?.takeIf { it.first == src }?.second ?: render(src)?.also { cached = src to it }
                            val image = codec.getInputImage(idx)
                            if (bmp != null && image != null) {
                                val scaled = if (bmp.width == w && bmp.height == h) bmp else Bitmap.createScaledBitmap(bmp, w, h, true)
                                scaled.getPixels(argb, 0, w, 0, 0, w, h)
                                YuvWriter.write(argb, w, h, image)
                            }
                            codec.queueInputBuffer(idx, 0, w * h * 3 / 2, ptsUs(next, fps), 0)
                            next++
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxing = true
                    }
                    outIdx >= 0 -> {
                        val buf = codec.getOutputBuffer(outIdx)
                        if (buf != null && info.size > 0 && muxing && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            muxer.writeSampleData(track, buf, info)
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
            ok = muxing
        } catch (e: Exception) {
            Log.e(TAG, "encoding failed", e)
        } finally {
            runCatching { codec.stop() }
            codec.release()
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            if (!ok) out.delete()
        }
        return ok
    }

    private fun ptsUs(frame: Int, fps: Int) = frame * 1_000_000L / fps
}

/** ARGB -> YUV 4:2:0 (BT.601 limited range) into a MediaCodec input Image, honouring its strides. */
internal object YuvWriter {
    fun write(argb: IntArray, w: Int, h: Int, image: android.media.Image) {
        val (yP, uP, vP) = image.planes
        val yRow = ByteArray(yP.rowStride)
        val yBuf = yP.buffer
        for (y in 0 until h) {
            val base = y * w
            for (x in 0 until w) {
                val c = argb[base + x]
                val r = c shr 16 and 0xff
                val g = c shr 8 and 0xff
                val b = c and 0xff
                yRow[x * yP.pixelStride] = (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).toByte()
            }
            yBuf.position(y * yP.rowStride)
            yBuf.put(yRow, 0, minOf(yRow.size, yBuf.remaining()))
        }
        val uBuf = uP.buffer
        val vBuf = vP.buffer
        for (y in 0 until h / 2) {
            for (x in 0 until w / 2) {
                val c = argb[(y * 2) * w + x * 2]
                val r = c shr 16 and 0xff
                val g = c shr 8 and 0xff
                val b = c and 0xff
                uBuf.put(y * uP.rowStride + x * uP.pixelStride, (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).toByte())
                vBuf.put(y * vP.rowStride + x * vP.pixelStride, (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).toByte())
            }
        }
    }
}
