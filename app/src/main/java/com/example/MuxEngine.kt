package com.example

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

object MuxEngine {
    private const val TAG = "MuxEngine"

    data class AudioTrackInput(
        val tempFile: File,
        val languageCode: String,
        val title: String
    )

    interface ProgressListener {
        fun onProgress(stage: String, progress: Float)
        fun onLog(message: String)
    }

    fun multiplex(
        videoFile: File,
        audioTracks: List<AudioTrackInput>,
        outputFile: File,
        includeOriginalAudio: Boolean,
        origLanguageCode: String,
        listener: ProgressListener
    ): Boolean {
        var muxer: MediaMuxer? = null
        val extractorsToRelease = mutableListOf<MediaExtractor>()

        try {
            listener.onLog("Analyzing input video source...")
            val videoExtractor = MediaExtractor().apply { setDataSource(videoFile.absolutePath) }
            extractorsToRelease.add(videoExtractor)

            // Dynamic folder check
            val parentDir = outputFile.parentFile
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs()
            }

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            // Find video track
            var videoTrackSourceIndex = -1
            var videoFormat: MediaFormat? = null
            for (i in 0 until videoExtractor.trackCount) {
                val format = videoExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/")) {
                    videoTrackSourceIndex = i
                    videoFormat = format
                    break
                }
            }

            if (videoTrackSourceIndex == -1 || videoFormat == null) {
                listener.onLog("Error: No video track found in source file!")
                return false
            }

            listener.onLog("Detected video codec: ${videoFormat.getString(MediaFormat.KEY_MIME)}")
            val videoTrackMuxIndex = muxer.addTrack(videoFormat)

            // Identify default audio track from the original video
            var origAudioTrackSourceIndex = -1
            var origAudioFormat: MediaFormat? = null
            if (includeOriginalAudio) {
                for (i in 0 until videoExtractor.trackCount) {
                    val format = videoExtractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                    if (mime.startsWith("audio/")) {
                        origAudioTrackSourceIndex = i
                        origAudioFormat = format
                        break
                    }
                }
                if (origAudioTrackSourceIndex != -1 && origAudioFormat != null) {
                    origAudioFormat.setString(MediaFormat.KEY_LANGUAGE, origLanguageCode)
                    listener.onLog("Mapped original stream: '${origLanguageCode}' language code.")
                } else {
                    listener.onLog("Note: Original audio requested but none found in source video.")
                }
            }

            val origAudioTrackMuxIndex = if (origAudioTrackSourceIndex != -1 && origAudioFormat != null) {
                muxer.addTrack(origAudioFormat)
            } else {
                -1
            }

            // Configure secondary audio lanes
            data class ConfiguredAudioTrack(
                val extractor: MediaExtractor,
                val sourceTrackIdx: Int,
                val destTrackIdx: Int,
                val label: String
            )

            val configuredAudioTracks = mutableListOf<ConfiguredAudioTrack>()

            for (inputTrack in audioTracks) {
                listener.onLog("Analyzing track file: ${inputTrack.title}...")
                val extractor = MediaExtractor().apply { setDataSource(inputTrack.tempFile.absolutePath) }
                extractorsToRelease.add(extractor)

                var audioTrackIdx = -1
                var audioFormat: MediaFormat? = null
                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                    if (mime.startsWith("audio/")) {
                        audioTrackIdx = i
                        audioFormat = format
                        break
                    }
                }

                if (audioTrackIdx == -1 || audioFormat == null) {
                    listener.onLog("Skipped '${inputTrack.title}': No valid audio stream found inside the file.")
                    continue
                }

                // Inject language configuration
                audioFormat.setString(MediaFormat.KEY_LANGUAGE, inputTrack.languageCode)
                val destIdx = muxer.addTrack(audioFormat)
                configuredAudioTracks.add(ConfiguredAudioTrack(extractor, audioTrackIdx, destIdx, inputTrack.title))
                listener.onLog("Configured audio stream: '${inputTrack.title}' mapped with language [${inputTrack.languageCode}].")
            }

            listener.onLog("Ready. Compiling format stream tables...")
            listener.onProgress("Assembling streams...", 0.1f)
            muxer.start()

            val maxBufferSize = 2 * 1024 * 1024 // 2MB stream frame buffer
            val buffer = ByteBuffer.allocateDirect(maxBufferSize)
            val bufferInfo = MediaCodec.BufferInfo()

            // 1. Copy Video Frames
            listener.onLog("Multiplexing video frames...")
            videoExtractor.selectTrack(videoTrackSourceIndex)
            var videoFrames = 0L
            while (true) {
                bufferInfo.size = videoExtractor.readSampleData(buffer, 0)
                if (bufferInfo.size < 0) break
                
                bufferInfo.presentationTimeUs = videoExtractor.sampleTime
                bufferInfo.offset = 0
                bufferInfo.flags = videoExtractor.sampleFlags
                
                muxer.writeSampleData(videoTrackMuxIndex, buffer, bufferInfo)
                videoExtractor.advance()
                videoFrames++
                
                if (videoFrames % 150 == 0L) {
                    listener.onProgress("Processing video stream [$videoFrames frames]...", 0.2f)
                }
            }
            listener.onLog("Completed video track write (Total: $videoFrames frames).")

            // 2. Copy Original Audio if any
            if (origAudioTrackMuxIndex != -1) {
                listener.onLog("Multiplexing original audio lane...")
                listener.onProgress("Processing original audio stream...", 0.45f)
                videoExtractor.unselectTrack(videoTrackSourceIndex)
                videoExtractor.selectTrack(origAudioTrackSourceIndex)
                
                var origAudioFrames = 0L
                while (true) {
                    bufferInfo.size = videoExtractor.readSampleData(buffer, 0)
                    if (bufferInfo.size < 0) break
                    
                    bufferInfo.presentationTimeUs = videoExtractor.sampleTime
                    bufferInfo.offset = 0
                    bufferInfo.flags = videoExtractor.sampleFlags
                    
                    muxer.writeSampleData(origAudioTrackMuxIndex, buffer, bufferInfo)
                    videoExtractor.advance()
                    origAudioFrames++
                }
                listener.onLog("Completed original audio stream write (Total: $origAudioFrames frames).")
            }

            // 3. Copy other audio files
            var audioIndex = 0
            for (track in configuredAudioTracks) {
                audioIndex++
                val stepProgress = 0.5f + (0.45f * (audioIndex.toFloat() / configuredAudioTracks.size.toFloat()))
                listener.onLog("Multiplexing audio lane: '${track.label}'...")
                listener.onProgress("Injecting tracking audio ${audioIndex}/${configuredAudioTracks.size}...", stepProgress)

                track.extractor.selectTrack(track.sourceTrackIdx)
                var trackFrames = 0L
                while (true) {
                    bufferInfo.size = track.extractor.readSampleData(buffer, 0)
                    if (bufferInfo.size < 0) break
                    
                    bufferInfo.presentationTimeUs = track.extractor.sampleTime
                    bufferInfo.offset = 0
                    bufferInfo.flags = track.extractor.sampleFlags
                    
                    muxer.writeSampleData(track.destTrackIdx, buffer, bufferInfo)
                    track.extractor.advance()
                    trackFrames++
                }
                listener.onLog("Completed stream '${track.label}' write (Total: $trackFrames frames).")
            }

            listener.onProgress("Finalizing container indices...", 0.98f)
            muxer.stop()
            listener.onLog("Multiplex operation successfully completed!")
            listener.onProgress("Finished", 1.0f)
            return true

        } catch (e: Exception) {
            val errMsg = e.localizedMessage ?: "Unknown hardware multiplex exception"
            listener.onLog("Failing: $errMsg")
            Log.e(TAG, "Mux exception", e)
            return false
        } finally {
            try {
                muxer?.release()
            } catch (e: Exception) {
                Log.e(TAG, "Muxer release fail", e)
            }
            for (extractor in extractorsToRelease) {
                try {
                    extractor.release()
                } catch (e: Exception) {
                    Log.e(TAG, "Extractor release fail", e)
                }
            }
        }
    }
}
