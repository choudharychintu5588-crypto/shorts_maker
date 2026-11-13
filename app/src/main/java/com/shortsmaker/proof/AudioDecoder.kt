package com.shortsmaker.proof

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DecodedAudio(val pcmFile: File, val samples: Long, val sourceDurationSec: Double) {
    val seconds: Double get() = samples / 16000.0
}

/**
 * Decodes the audio of any video the phone can play into 16 kHz mono signed 16-bit PCM (what Whisper needs),
 * streaming it to a file so long videos never sit in memory.
 */
object AudioDecoder {
    private const val OUT_RATE = 16000

    /** Streaming box-filter resampler (also performs the averaging that prevents aliasing). */
    private class Resampler(inRate: Int, private val emit: (Float) -> Unit) {
        private val step = inRate.toDouble() / OUT_RATE
        private var acc = 0.0
        private var remaining = step
        fun push(x: Float) {
            var w = 1.0
            while (w > 1e-9) {
                val take = if (w < remaining) w else remaining
                acc += x * take
                remaining -= take
                w -= take
                if (remaining <= 1e-9) {
                    emit((acc / step).toFloat())
                    acc = 0.0
                    remaining = step
                }
            }
        }
    }

    fun decodeToPcm16k(
        context: Context, uri: Uri, outFile: File, maxSeconds: Double = Double.MAX_VALUE,
        cancelled: () -> Boolean = { false }, onProgress: (Float) -> Unit = {}
    ): DecodedAudio {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            try {
                extractor.setDataSource(context, uri, null)
            } catch (e: Exception) {
                throw ProcessingException("This file cannot be opened as a video. Pick a normal video (MP4, MOV, MKV, WEBM).", e)
            }
            var track = -1
            var fmt: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { track = i; fmt = f; break }
            }
            if (track < 0 || fmt == null) throw ProcessingException("This video has no sound track, so there is nothing to transcribe.")
            extractor.selectTrack(track)
            val mime = fmt.getString(MediaFormat.KEY_MIME)!!
            val durationUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
            val dec = try {
                MediaCodec.createDecoderByType(mime).also { codec = it }
            } catch (e: Exception) {
                throw ProcessingException("This phone cannot decode the audio format ($mime).", e)
            }
            dec.configure(fmt, null, null, 0)
            dec.start()

            var inRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var pcmEnc = AudioFormat.ENCODING_PCM_16BIT

            var written = 0L
            val chunk = ByteBuffer.allocate(1 shl 16).order(ByteOrder.LITTLE_ENDIAN)
            outFile.parentFile?.mkdirs()
            val os = BufferedOutputStream(FileOutputStream(outFile), 1 shl 16)
            val maxSamples = if (maxSeconds >= 1e9) Long.MAX_VALUE else (maxSeconds * OUT_RATE).toLong()

            fun emitSample(v: Float) {
                if (written >= maxSamples) return
                val c = if (v > 1f) 1f else if (v < -1f) -1f else v
                if (!chunk.hasRemaining()) { os.write(chunk.array(), 0, chunk.position()); chunk.clear() }
                chunk.putShort((c * 32767f).toInt().toShort())
                written++
            }
            var resampler = Resampler(inRate, ::emitSample)

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var lastProgress = 0f
            var idleLoops = 0
            os.use {
                while (!outputDone && written < maxSamples) {
                    if (cancelled()) throw CancelledException()
                    if (!inputDone) {
                        val idx = dec.dequeueInputBuffer(10_000)
                        if (idx >= 0) {
                            val buf = dec.getInputBuffer(idx)!!
                            val n = extractor.readSampleData(buf, 0)
                            if (n < 0) {
                                dec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                dec.queueInputBuffer(idx, 0, n, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val out = dec.dequeueOutputBuffer(info, 10_000)
                    when {
                        out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val of = dec.outputFormat
                            val newRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            pcmEnc = if (of.containsKey(MediaFormat.KEY_PCM_ENCODING))
                                of.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                            if (newRate != inRate) { inRate = newRate; resampler = Resampler(inRate, ::emitSample) }
                        }
                        out >= 0 -> {
                            idleLoops = 0
                            val buf = dec.getOutputBuffer(out)
                            if (buf != null && info.size > 0) {
                                buf.position(info.offset)
                                buf.limit(info.offset + info.size)
                                buf.order(ByteOrder.LITTLE_ENDIAN)
                                val ch = if (channels < 1) 1 else channels
                                if (pcmEnc == AudioFormat.ENCODING_PCM_FLOAT) {
                                    val fb = buf.asFloatBuffer()
                                    while (fb.remaining() >= ch) {
                                        var s = 0f
                                        for (c in 0 until ch) s += fb.get()
                                        resampler.push(s / ch)
                                    }
                                } else {
                                    val sb = buf.asShortBuffer()
                                    while (sb.remaining() >= ch) {
                                        var s = 0
                                        for (c in 0 until ch) s += sb.get().toInt()
                                        resampler.push(s / (32768f * ch))
                                    }
                                }
                            }
                            dec.releaseOutputBuffer(out, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                            if (durationUs > 0) {
                                val p = (info.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f)
                                if (p - lastProgress >= 0.01f) { lastProgress = p; onProgress(p) }
                            }
                        }
                        else -> {
                            // INFO_TRY_AGAIN_LATER: guard against a decoder that never finishes
                            if (inputDone && ++idleLoops > 500) throw ProcessingException("The audio decoder stopped responding.")
                        }
                    }
                }
                if (chunk.position() > 0) os.write(chunk.array(), 0, chunk.position())
            }
            onProgress(1f)
            if (written < OUT_RATE) throw ProcessingException("No usable speech audio was found in this video.")
            return DecodedAudio(outFile, written, durationUs / 1e6)
        } catch (e: ProcessingException) {
            throw e
        } catch (e: CancelledException) {
            throw e
        } catch (e: Exception) {
            throw ProcessingException("Could not read the audio of this video (${e.javaClass.simpleName}).", e)
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            extractor.release()
        }
    }
}
