package com.example.webrtc.record

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import org.webrtc.EglBase
import org.webrtc.GlRectDrawer
import org.webrtc.VideoFrame
import org.webrtc.VideoFrameDrawer
import org.webrtc.VideoSink
import org.webrtc.VideoTrack
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * High-performance VideoSink that encodes incoming frames from the remote VideoTrack
 * to an MP4 video file using Hardware MediaCodec and MediaMuxer.
 */
class RemoteVideoFileRenderer(
    private val outputFile: File,
    private val sharedContext: EglBase.Context?
) : VideoSink {

    companion object {
        private const val TAG = "RemoteVideoRenderer"
        private const val MIME_TYPE = "video/avc" // H.264 AVC
        private const val FRAME_RATE = 30
        private const val IFRAME_INTERVAL = 2 // 2 seconds between I-Frames for smooth seeking
        private const val DEFAULT_BITRATE = 2_500_000 // 2.5 Mbps
    }

    private val renderThread = HandlerThread("${TAG}Thread").apply { start() }
    private val renderThreadHandler = Handler(renderThread.looper)

    private val bufferInfo = MediaCodec.BufferInfo()
    private var mediaMuxer: MediaMuxer? = null
    private var encoder: MediaCodec? = null
    private var surface: Surface? = null
    private var eglBase: EglBase? = null
    private var drawer: GlRectDrawer? = null
    private var frameDrawer: VideoFrameDrawer? = null

    private var outputFileWidth = -1
    private var outputFileHeight = -1
    private var trackIndex = -1
    private var muxerStarted = false
    private var isRunning = true
    private var encoderStarted = false
    private var encoderInitializing = false
    private var encoderInitFailed = false
    private var videoFrameStart = 0L

    init {
        try {
            outputFile.parentFile?.mkdirs()
            mediaMuxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize MediaMuxer", e)
        }
    }

    override fun onFrame(frame: VideoFrame) {
        if (!isRunning || encoderInitFailed || mediaMuxer == null) {
            return
        }
        frame.retain()

        if (outputFileWidth == -1 && !encoderInitializing) {
            encoderInitializing = true
            // Ensure width and height are even numbers (required by AVC encoders)
            val rawW = frame.rotatedWidth
            val rawH = frame.rotatedHeight
            val frameWidth = if (rawW % 2 != 0) rawW - 1 else rawW
            val frameHeight = if (rawH % 2 != 0) rawH - 1 else rawH
            initVideoEncoder(frameWidth, frameHeight)
        }

        if (!encoderStarted || outputFileWidth == -1 || outputFileHeight == -1) {
            frame.release()
            return
        }

        renderThreadHandler.post {
            renderFrameOnRenderThread(frame)
        }
    }

    private fun initVideoEncoder(frameWidth: Int, frameHeight: Int) {
        val width = if (frameWidth <= 0) 1280 else frameWidth
        val height = if (frameHeight <= 0) 720 else frameHeight

        try {
            val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, DEFAULT_BITRATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL)
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            }

            val enc = MediaCodec.createEncoderByType(MIME_TYPE)
            enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val encSurface = enc.createInputSurface()
            enc.start()

            encoder = enc
            surface = encSurface
            outputFileWidth = width
            outputFileHeight = height

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
                    encoderStarted = true
                    Log.i(TAG, "Video encoder surface initialized: ${width}x${height}")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to initialize EglBase on render thread", e)
                    encoderInitFailed = true
                } finally {
                    encoderInitializing = false
                    latch.countDown()
                }
            }
            latch.await(2, TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to configure video encoder: ${width}x${height}", e)
            encoderInitFailed = true
            encoderInitializing = false
        }
    }

    private fun renderFrameOnRenderThread(frame: VideoFrame) {
        val base = eglBase
        val drw = drawer
        val fDrw = frameDrawer
        if (!encoderStarted || base == null || drw == null || fDrw == null) {
            frame.release()
            return
        }

        try {
            fDrw.drawFrame(frame, drw, null, 0, 0, outputFileWidth, outputFileHeight)
            base.swapBuffers()
        } catch (e: Exception) {
            Log.e(TAG, "Error drawing frame to encoder surface", e)
        } finally {
            frame.release()
        }

        drainEncoder()
    }

    private fun drainEncoder() {
        val enc = encoder ?: return
        val muxer = mediaMuxer ?: return

        while (true) {
            val status = try {
                enc.dequeueOutputBuffer(bufferInfo, 10_000)
            } catch (e: Exception) {
                Log.e(TAG, "Error dequeuing output buffer", e)
                break
            }

            if (status == MediaCodec.INFO_TRY_AGAIN_LATER) {
                break
            } else if (status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val newFormat = enc.outputFormat
                Log.i(TAG, "Encoder output format changed: $newFormat")
                if (trackIndex == -1) {
                    trackIndex = muxer.addTrack(newFormat)
                    if (trackIndex != -1 && !muxerStarted) {
                        try {
                            muxer.start()
                            muxerStarted = true
                            Log.i(TAG, "MediaMuxer started successfully")
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to start MediaMuxer", e)
                        }
                    }
                }
            } else if (status >= 0) {
                val encodedData = enc.getOutputBuffer(status)
                if (encodedData != null) {
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        bufferInfo.size = 0
                    }

                    if (bufferInfo.size > 0 && muxerStarted && trackIndex != -1) {
                        encodedData.position(bufferInfo.offset)
                        encodedData.limit(bufferInfo.offset + bufferInfo.size)

                        if (videoFrameStart == 0L && bufferInfo.presentationTimeUs != 0L) {
                            videoFrameStart = bufferInfo.presentationTimeUs
                        }
                        bufferInfo.presentationTimeUs = maxOf(0L, bufferInfo.presentationTimeUs - videoFrameStart)

                        try {
                            muxer.writeSampleData(trackIndex, encodedData, bufferInfo)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error writing sample data to muxer", e)
                        }
                    }

                    try {
                        enc.releaseOutputBuffer(status, false)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error releasing output buffer", e)
                    }

                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        Log.i(TAG, "End of stream reached")
                        break
                    }
                }
            }
        }
    }

    fun release(onCompleted: ((File?) -> Unit)? = null) {
        isRunning = false
        val latch = CountDownLatch(1)

        renderThreadHandler.post {
            try {
                // Signal end of stream
                encoder?.let { enc ->
                    try {
                        drainEncoder()
                        enc.stop()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error stopping encoder", e)
                    }
                    try {
                        enc.release()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error releasing encoder", e)
                    }
                }

                eglBase?.let { base ->
                    try {
                        base.release()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error releasing EglBase", e)
                    }
                }

                surface?.let { s ->
                    try {
                        s.release()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error releasing surface", e)
                    }
                }

                mediaMuxer?.let { muxer ->
                    try {
                        if (muxerStarted) {
                            muxer.stop()
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
            } finally {
                renderThread.quitSafely()
                latch.countDown()
                onCompleted?.invoke(if (outputFile.exists() && outputFile.length() > 0) outputFile else null)
            }
        }

        try {
            latch.await(3, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
