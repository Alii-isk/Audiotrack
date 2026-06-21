package com.example

import android.graphics.Color
import android.graphics.Paint
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer

object SyntheticMediaGenerator {

    fun generateWav(file: File, freq: Double, durationSec: Int) {
        val sampleRate = 8000
        val numSamples = durationSec * sampleRate
        val dataSize = numSamples * 2
        val totalSize = 36 + dataSize

        file.outputStream().use { out ->
            out.write("RIFF".toByteArray())
            out.write(ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(totalSize).array())
            out.write("WAVE".toByteArray())

            out.write("fmt ".toByteArray())
            out.write(ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(16).array())
            out.write(ByteBuffer.allocate(2).order(java.nio.ByteOrder.LITTLE_ENDIAN).putShort(1).array()) // PCM
            out.write(ByteBuffer.allocate(2).order(java.nio.ByteOrder.LITTLE_ENDIAN).putShort(1).array()) // Mono
            out.write(ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(sampleRate).array())
            out.write(ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(sampleRate * 2).array())
            out.write(ByteBuffer.allocate(2).order(java.nio.ByteOrder.LITTLE_ENDIAN).putShort(2).array())
            out.write(ByteBuffer.allocate(2).order(java.nio.ByteOrder.LITTLE_ENDIAN).putShort(16).array())

            out.write("data".toByteArray())
            out.write(ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(dataSize).array())

            val buffer = ByteBuffer.allocate(numSamples * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until numSamples) {
                val angle = 2.0 * Math.PI * i * freq / sampleRate
                val value = (Math.sin(angle) * 32767).toInt().toShort()
                buffer.putShort(value)
            }
            out.write(buffer.array())
        }
    }

    fun generateMp4(file: File, durationSec: Int): Boolean {
        val width = 320
        val height = 240
        val bitRate = 125000
        val frameRate = 12
        val totalFrames = durationSec * frameRate
        val mime = MediaFormat.MIMETYPE_VIDEO_AVC

        val format = MediaFormat.createVideoFormat(mime, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var inputSurface: Surface? = null

        try {
            encoder = MediaCodec.createEncoderByType(mime)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = encoder.createInputSurface()
            encoder.start()

            // Ensure parent dir exists
            file.parentFile?.mkdirs()

            muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var videoTrackIndex = -1
            var muxerStarted = false

            val bufferInfo = MediaCodec.BufferInfo()
            val paint = Paint().apply {
                isAntiAlias = true
                textSize = 20f
                strokeWidth = 3f
            }

            for (frameIndex in 0 until totalFrames) {
                // Render frame onto the Surface
                val canvas = inputSurface.lockCanvas(null)
                try {
                    // Changing background colors
                    val cycle = frameIndex % 30
                    val r = if (cycle < 10) 90 else if (cycle < 20) 130 else 50
                    val g = 80
                    val b = if (cycle < 10) 180 else if (cycle < 20) 110 else 164
                    canvas.drawColor(Color.rgb(r, g, b))

                    // Draw box
                    paint.color = Color.WHITE
                    val x = (frameIndex * 8 % (width - 60)).toFloat()
                    canvas.drawRect(x, 100f, x + 50f, 150f, paint)

                    // Draw metadata info
                    paint.color = Color.rgb(218, 165, 32) // Goldenrod
                    canvas.drawText("Track Master Synth Video", 10f, 45f, paint)
                    paint.color = Color.WHITE
                    canvas.drawText("FPS: $frameRate • H.264", 10f, 80f, paint)
                    canvas.drawText("Frame $frameIndex / $totalFrames", 10f, 195f, paint)
                } finally {
                    inputSurface.unlockCanvasAndPost(canvas)
                }

                val timeUs = (frameIndex * 1000000L / frameRate)

                // Drain output buffers
                var done = false
                while (!done) {
                    val status = encoder.dequeueOutputBuffer(bufferInfo, 1000)
                    if (status == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        done = true
                    } else if (status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val newFormat = encoder.outputFormat
                        videoTrackIndex = muxer.addTrack(newFormat)
                        muxer.start()
                        muxerStarted = true
                    } else if (status >= 0) {
                        val encodedData = encoder.getOutputBuffer(status) ?: continue
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            bufferInfo.size = 0
                        }

                        if (bufferInfo.size != 0 && muxerStarted) {
                            encodedData.position(bufferInfo.offset)
                            encodedData.limit(bufferInfo.offset + bufferInfo.size)
                            bufferInfo.presentationTimeUs = timeUs
                            muxer.writeSampleData(videoTrackIndex, encodedData, bufferInfo)
                        }

                        encoder.releaseOutputBuffer(status, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            break
                        }
                        done = true
                    }
                }
            }

            // Signal end of stream and finish encoding
            encoder.signalEndOfInputStream()
            var drainDone = false
            while (!drainDone) {
                val status = encoder.dequeueOutputBuffer(bufferInfo, 10000)
                if (status >= 0) {
                    val encodedData = encoder.getOutputBuffer(status) ?: continue
                    if (bufferInfo.size != 0 && muxerStarted) {
                        encodedData.position(bufferInfo.offset)
                        encodedData.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(videoTrackIndex, encodedData, bufferInfo)
                    }
                    encoder.releaseOutputBuffer(status, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        drainDone = true
                    }
                } else if (status == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    drainDone = true
                }
            }

            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        } finally {
            try {
                encoder?.stop()
                encoder?.release()
            } catch (ignore: Exception) {}
            try {
                inputSurface?.release()
            } catch (ignore: Exception) {}
            try {
                muxer?.stop()
                muxer?.release()
            } catch (ignore: Exception) {}
        }
    }
}
