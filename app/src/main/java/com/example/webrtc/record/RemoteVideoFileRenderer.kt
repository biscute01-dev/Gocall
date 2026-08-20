package com.example.webrtc.record

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import org.webrtc.EglBase
import org.webrtc.GlRectDrawer
import org.webrtc.VideoFrame
import org.webrtc.VideoFrameDrawer
import org.webrtc.VideoSink
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.roundToInt

/**
 * High-performance Video & Audio Muxing Recorder that captures BOTH the remote peer's
 * video stream and remote audio PCM samples, encoding them to a unified MP4 file.
 *
 * Handles dynamic resolution transitions (e.g. 360p -> 720p/1080p) via OpenGL scaling
 * onto an HD target canvas, preventing pixelation or low-res lock.
 */
class RemoteVideoFileRenderer(
    private val outputFile: File,
    private val sharedContext: EglBase.Context?
) : VideoSink {

    companion object {
        private const val TAG = "RemoteMediaRenderer"

        // Video configuration (H.264 AVC)
        private const val VIDEO_MIME_TYPE = "video/avc"
        private const val VIDEO_FRAME_RATE = 30
        private const val VIDEO_IFRAME_INTERVAL = 1 // 1 second for smooth seeking
        private const val VIDEO_BITRATE = 3_000_000 // 3.0 Mbps high quality

        // Standard HD target bounds to adaptively host 360p -> 720p -> 1080p without low-res lock
        private const val TARGET_PORTRAIT_WIDTH = 720
        private const val TARGET_PORTRAIT_HEIGHT = 1280
        private const val TARGET_LANDSCAPE_WIDTH = 1280
        private const val TARGET_LANDSCAPE_HEIGHT = 720

        // Audio configuration (AAC-LC)
        private const val AUDIO_MIME_TYPE = "audio/mp4a-latm"
        private const val AUDIO_BITRATE = 128_000 // 128 kbps
        private const val AUDIO_SAMPLE_RATE_DEFAULT = 48000
    }

    private val renderThread = HandlerThread("${TAG}VideoThread").apply { start() }
    private val renderThreadHandler = Handler(renderThread.looper)

    private val audioThread = HandlerThread("${TAG}AudioThread").apply { start() }
    private val audioThreadHandler = Handler(audioThread.looper)

    private val muxerLock = ReentrantLock()
    private var mediaMuxer: MediaMuxer? = null
    private var muxerStarted = false
    private var isRecordingRunning = true

    // Video encoder state
    private var videoEncoder: MediaCodec? = null
    private var videoSurface: Surface? = null
    private var eglBase: EglBase? = null
    private var drawer: GlRectDrawer? = null
    private var frameDrawer: VideoFrameDrawer? = null
    private val videoBufferInfo = MediaCodec.BufferInfo()

    private var canvasWidth = -1
    private var canvasHeight = -1
    private var videoTrackIndex = -1
    private var videoEncoderStarted = false
    private var videoEncoderInitializing = false
    private var videoEncoderInitFailed = false
    private var videoFirstPtsUs = -1L

    // Audio encoder state
    private var audioEncoder: MediaCodec? = null
    private val audioBufferInfo = MediaCodec.BufferInfo()
    private var audioTrackIndex = -1
    private var audioEncoderStarted = false
    private var audioEncoderInitializing = false
    private var audioSampleRate = AUDIO_SAMPLE_RATE_DEFAULT
    private var audioChannelCount = 1
    private var audioBytesProcessed = 0L

    init {
        try {
            outputFile.parentFile?.mkdirs()
            mediaMuxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize MediaMuxer for: ${outputFile.absolutePath}", e)
        }
    }

    // =========================================================================
    // Video Processing & Adaptive Resolution
    // =========================================================================

    override fun onFrame(frame: VideoFrame) {
        if (!isRecordingRunning || videoEncoderInitFailed || mediaMuxer == null) {
            return
        }
        frame.retain()

        // Adaptively initialize HD video encoder on first frame
        if (canvasWidth == -1 && !videoEncoderInitializing) {
            videoEncoderInitializing = true
            val rawW = frame.rotatedWidth
            val rawH = frame.rotatedHeight
            initAdaptiveVideoEncoder(rawW, rawH)
        }

        if (!videoEncoderStarted || canvasWidth == -1 || canvasHeight == -1) {
            frame.release()
            return
        }

        renderThreadHandler.post {
            renderFrameOnRenderThread(frame)
        }
    }

    /**
     * Initializes a crystal-clear HD canvas that adaptively handles resolution scaling.
     * If the remote stream starts at 360p and later ramps up to 720p or 1080p, the frames
     * will be rendered directly onto this HD canvas with bilinear filtering, preserving full clarity.
     */
    private fun initAdaptiveVideoEncoder(incomingWidth: Int, incomingHeight: Int) {
        val isPortrait = incomingHeight >= incomingWidth
        val targetWidth: Int
        val targetHeight: Int

        if (isPortrait) {
            // Target 720x1280 or matched aspect ratio
            val aspect = if (incomingWidth > 0) incomingHeight.toFloat() / incomingWidth.toFloat() else 16f / 9f
            targetWidth = TARGET_PORTRAIT_WIDTH
            var calcH = (targetWidth * aspect).roundToInt()
            if (calcH % 2 != 0) calcH += 1
            targetHeight = maxOf(TARGET_PORTRAIT_HEIGHT, calcH)
        } else {
            // Target 1280x720 or matched aspect ratio
            val aspect = if (incomingHeight > 0) incomingWidth.toFloat() / incomingHeight.toFloat() else 16f / 9f
            targetHeight = TARGET_LANDSCAPE_HEIGHT
            var calcW = (targetHeight * aspect).roundToInt()
            if (calcW % 2 != 0) calcW += 1
            targetWidth = maxOf(TARGET_LANDSCAPE_WIDTH, calcW)
        }

        val finalW = if (targetWidth % 2 != 0) targetWidth - 1 else targetWidth
        val finalH = if (targetHeight % 2 != 0) targetHeight - 1 else targetHeight

        try {
            val format = MediaFormat.createVideoFormat(VIDEO_MIME_TYPE, finalW, finalH).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BITRATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, VIDEO_FRAME_RATE)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, VIDEO_IFRAME_INTERVAL)
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            }

            val enc = MediaCodec.createEncoderByType(VIDEO_MIME_TYPE)
            enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val encSurface = enc.createInputSurface()
            enc.start()

            videoEncoder = enc
            videoSurface = encSurface
            canvasWidth = finalW
            canvasHeight = finalH

            val latch = CountDownLatch(1)
            renderThreadHandler.post {
                try {
                    val base = if (sharedContext != null) {
                        try {
                            EglBase.create(sharedContext, EglBase.CONFIG_RECORDABLE)
                        } catch (e: Exception) {
                            EglBase.create(null, EglBase.CONFIG_RECORDABLE)
                        }
                    } else {
                        EglBase.create(null, EglBase.CONFIG_RECORDABLE)
                    }

                    base.createSurface(encSurface)
                    base.makeCurrent()
                    eglBase = base
                    drawer = GlRectDrawer()
                    frameDrawer = VideoFrameDrawer()
                    videoEncoderStarted = true
                    Log.i(TAG, "Adaptive Video Encoder ready: ${finalW}x${finalH} (Incoming: ${incomingWidth}x${incomingHeight})")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to initialize EglBase for video recording", e)
                    videoEncoderInitFailed = true
                } finally {
                    videoEncoderInitializing = false
                    latch.countDown()
                }
            }
            latch.await(2, TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to configure video encoder ${finalW}x${finalH}", e)
            videoEncoderInitFailed = true
            videoEncoderInitializing = false
        }
    }

    private fun renderFrameOnRenderThread(frame: VideoFrame) {
        val base = eglBase
        val drw = drawer
        val fDrw = frameDrawer
        if (!videoEncoderStarted || base == null || drw == null || fDrw == null) {
            frame.release()
            return
        }

        try {
            // VideoFrameDrawer automatically handles rotation, YUV/RGB conversions,
            // and adapts incoming 360p/720p/1080p frames cleanly into the canvasWidth x canvasHeight surface.
            fDrw.drawFrame(frame, drw, null, 0, 0, canvasWidth, canvasHeight)
            base.swapBuffers()
        } catch (e: Exception) {
            Log.e(TAG, "Error drawing frame to video encoder surface", e)
        } finally {
            frame.release()
        }

        drainVideoEncoder()
    }

    private fun drainVideoEncoder() {
        val enc = videoEncoder ?: return
        val muxer = mediaMuxer ?: return

        while (true) {
            val status = try {
                enc.dequeueOutputBuffer(videoBufferInfo, 5_000)
            } catch (e: Exception) {
                Log.e(TAG, "Error dequeuing video output buffer", e)
                break
            }

            if (status == MediaCodec.INFO_TRY_AGAIN_LATER) {
                break
            } else if (status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val newFormat = enc.outputFormat
                Log.i(TAG, "Video encoder output format changed: $newFormat")
                muxerLock.withLock {
                    if (videoTrackIndex == -1) {
                        videoTrackIndex = muxer.addTrack(newFormat)
                        checkAndStartMuxerLocked(muxer)
                    }
                }
            } else if (status >= 0) {
                val encodedData = enc.getOutputBuffer(status)
                if (encodedData != null) {
                    if ((videoBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        videoBufferInfo.size = 0
                    }

                    if (videoBufferInfo.size > 0 && muxerStarted && videoTrackIndex != -1) {
                        encodedData.position(videoBufferInfo.offset)
                        encodedData.limit(videoBufferInfo.offset + videoBufferInfo.size)

                        if (videoFirstPtsUs == -1L && videoBufferInfo.presentationTimeUs != 0L) {
                            videoFirstPtsUs = videoBufferInfo.presentationTimeUs
                        }
                        if (videoFirstPtsUs != -1L) {
                            videoBufferInfo.presentationTimeUs = maxOf(0L, videoBufferInfo.presentationTimeUs - videoFirstPtsUs)
                        }

                        muxerLock.withLock {
                            try {
                                if (muxerStarted) {
                                    muxer.writeSampleData(videoTrackIndex, encodedData, videoBufferInfo)
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Error writing video sample to muxer", e)
                            }
                        }
                    }

                    try {
                        enc.releaseOutputBuffer(status, false)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error releasing video output buffer", e)
                    }

                    if ((videoBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        Log.i(TAG, "Video EOS reached")
                        break
                    }
                }
            }
        }
    }

    // =========================================================================
    // Audio Processing & AAC Encoding (Remote Peer Audio Track)
    // =========================================================================

    /**
     * Receives remote audio PCM samples from WebRTC playback callback.
     */
    fun onRemoteAudioSamples(audioFormat: Int, channelCount: Int, sampleRate: Int, data: ByteArray) {
        if (!isRecordingRunning || mediaMuxer == null || data.isEmpty()) {
            return
        }

        // Initialize AAC audio encoder if needed
        if (!audioEncoderStarted && !audioEncoderInitializing) {
            audioEncoderInitializing = true
            audioSampleRate = if (sampleRate > 0) sampleRate else AUDIO_SAMPLE_RATE_DEFAULT
            audioChannelCount = if (channelCount > 0) channelCount else 1
            audioThreadHandler.post {
                initAudioEncoder(audioSampleRate, audioChannelCount)
            }
        }

        if (!audioEncoderStarted) {
            return
        }

        // Clone data byte array and post to audio encoding thread
        val pcmCopy = data.clone()
        audioThreadHandler.post {
            feedPcmToAudioEncoder(pcmCopy)
        }
    }

    private fun initAudioEncoder(sampleRate: Int, channelCount: Int) {
        try {
            val format = MediaFormat.createAudioFormat(AUDIO_MIME_TYPE, sampleRate, channelCount).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }

            val enc = MediaCodec.createEncoderByType(AUDIO_MIME_TYPE)
            enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            enc.start()

            audioEncoder = enc
            audioEncoderStarted = true
            audioEncoderInitializing = false
            Log.i(TAG, "Remote Audio AAC Encoder initialized: ${sampleRate}Hz, ${channelCount}ch, ${AUDIO_BITRATE / 1000}kbps")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Audio AAC Encoder", e)
            audioEncoderInitializing = false
        }
    }

    private fun feedPcmToAudioEncoder(pcmBytes: ByteArray) {
        val enc = audioEncoder ?: return
        if (!audioEncoderStarted) return

        try {
            val inIndex = enc.dequeueInputBuffer(10_000)
            if (inIndex >= 0) {
                val inputBuf = enc.getInputBuffer(inIndex)
                if (inputBuf != null) {
                    inputBuf.clear()
                    inputBuf.put(pcmBytes)

                    // Calculate presentation time based on 16-bit PCM samples processed
                    // 2 bytes per 16-bit sample * channels
                    val bytesPerFrame = 2 * audioChannelCount
                    val samples = pcmBytes.size / bytesPerFrame
                    val ptsUs = (audioBytesProcessed / bytesPerFrame) * 1_000_000L / audioSampleRate
                    audioBytesProcessed += pcmBytes.size

                    enc.queueInputBuffer(inIndex, 0, pcmBytes.size, ptsUs, 0)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error feeding PCM data to audio encoder", e)
        }

        drainAudioEncoder()
    }

    private fun drainAudioEncoder() {
        val enc = audioEncoder ?: return
        val muxer = mediaMuxer ?: return

        while (true) {
            val status = try {
                enc.dequeueOutputBuffer(audioBufferInfo, 5_000)
            } catch (e: Exception) {
                Log.e(TAG, "Error dequeuing audio output buffer", e)
                break
            }

            if (status == MediaCodec.INFO_TRY_AGAIN_LATER) {
                break
            } else if (status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val newFormat = enc.outputFormat
                Log.i(TAG, "Audio encoder output format changed: $newFormat")
                muxerLock.withLock {
                    if (audioTrackIndex == -1) {
                        audioTrackIndex = muxer.addTrack(newFormat)
                        checkAndStartMuxerLocked(muxer)
                    }
                }
            } else if (status >= 0) {
                val encodedData = enc.getOutputBuffer(status)
                if (encodedData != null) {
                    if ((audioBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        audioBufferInfo.size = 0
                    }

                    if (audioBufferInfo.size > 0 && muxerStarted && audioTrackIndex != -1) {
                        encodedData.position(audioBufferInfo.offset)
                        encodedData.limit(audioBufferInfo.offset + audioBufferInfo.size)

                        muxerLock.withLock {
                            try {
                                if (muxerStarted) {
                                    muxer.writeSampleData(audioTrackIndex, encodedData, audioBufferInfo)
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Error writing audio sample to muxer", e)
                            }
                        }
                    }

                    try {
                        enc.releaseOutputBuffer(status, false)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error releasing audio output buffer", e)
                    }

                    if ((audioBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        Log.i(TAG, "Audio EOS reached")
                        break
                    }
                }
            }
        }
    }

    private fun checkAndStartMuxerLocked(muxer: MediaMuxer) {
        if (!muxerStarted && videoTrackIndex != -1) {
            try {
                muxer.start()
                muxerStarted = true
                Log.i(TAG, "MediaMuxer started (videoTrack=$videoTrackIndex, audioTrack=$audioTrackIndex)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start MediaMuxer", e)
            }
        }
    }

    // =========================================================================
    // Lifecycle & Clean Finalization
    // =========================================================================

    fun release(onCompleted: ((File?) -> Unit)? = null) {
        isRecordingRunning = false
        val latch = CountDownLatch(2)

        // 1. Drain & Release Audio Encoder on Audio Thread
        audioThreadHandler.post {
            try {
                audioEncoder?.let { enc ->
                    try {
                        // Signal audio EOS
                        val inIndex = enc.dequeueInputBuffer(10_000)
                        if (inIndex >= 0) {
                            enc.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        }
                        drainAudioEncoder()
                        enc.stop()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error stopping audio encoder", e)
                    }
                    try {
                        enc.release()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error releasing audio encoder", e)
                    }
                }
            } finally {
                audioThread.quitSafely()
                latch.countDown()
            }
        }

        // 2. Drain & Release Video Encoder & OpenGL on Render Thread
        renderThreadHandler.post {
            try {
                videoEncoder?.let { enc ->
                    try {
                        drainVideoEncoder()
                        enc.stop()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error stopping video encoder", e)
                    }
                    try {
                        enc.release()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error releasing video encoder", e)
                    }
                }

                eglBase?.let { base ->
                    try {
                        base.release()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error releasing EglBase", e)
                    }
                }

                videoSurface?.let { s ->
                    try {
                        s.release()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error releasing video surface", e)
                    }
                }
            } finally {
                renderThread.quitSafely()
                latch.countDown()
            }
        }

        // Finalize Muxer once threads complete
        Thread {
            try {
                latch.await(3, TimeUnit.SECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }

            muxerLock.withLock {
                mediaMuxer?.let { muxer ->
                    try {
                        if (muxerStarted) {
                            muxer.stop()
                            Log.i(TAG, "MediaMuxer stopped successfully")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error stopping MediaMuxer", e)
                    }
                    try {
                        muxer.release()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error releasing MediaMuxer", e)
                    }
                }
            }

            val validFile = if (outputFile.exists() && outputFile.length() > 0) outputFile else null
            onCompleted?.invoke(validFile)
        }.start()
    }
}
