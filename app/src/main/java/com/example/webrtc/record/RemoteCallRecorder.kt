package com.example.webrtc.record

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import com.example.webrtc.WebRtcClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.webrtc.EglBase
import org.webrtc.VideoTrack
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed class RecordingStatus {
    object Idle : RecordingStatus()
    data class Recording(
        val durationSeconds: Long,
        val filePath: String
    ) : RecordingStatus()
    object Processing : RecordingStatus()
    data class Saved(
        val file: File,
        val mediaStoreUri: Uri?,
        val durationSeconds: Long,
        val fileSizeBytes: Long
    ) : RecordingStatus()
    data class Error(val message: String) : RecordingStatus()
}

class RemoteCallRecorder(
    private val context: Context,
    private val sharedEglContext: EglBase.Context?
) {
    companion object {
        private const val TAG = "RemoteCallRecorder"
    }

    private val scope = CoroutineScope(Dispatchers.Main)
    private var videoRenderer: RemoteVideoFileRenderer? = null
    private var currentRecordedVideoTrack: VideoTrack? = null
    private var currentRtcClient: WebRtcClient? = null
    private var activeOutputFile: File? = null
    private var timerJob: Job? = null
    private var recordedDuration: Long = 0L

    private val _recordingStatus = MutableStateFlow<RecordingStatus>(RecordingStatus.Idle)
    val recordingStatus: StateFlow<RecordingStatus> = _recordingStatus.asStateFlow()

    val isRecording: Boolean
        get() = _recordingStatus.value is RecordingStatus.Recording

    /**
     * Starts recording only the remote person's video track and remote audio stream.
     */
    fun startRecording(remoteTrack: VideoTrack?, roomId: String?, rtcClient: WebRtcClient? = null): Boolean {
        if (isRecording) {
            Log.w(TAG, "Recording is already active")
            return false
        }

        if (remoteTrack == null) {
            _recordingStatus.value = RecordingStatus.Error("Cannot record: Remote video track is not available")
            return false
        }

        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val roomTag = if (!roomId.isNullOrBlank()) "Room_${roomId}_" else ""
            val fileName = "RemoteCall_${roomTag}${timestamp}.mp4"

            // Temp recording file in cache/external files directory
            val moviesDir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
            val outputFile = File(moviesDir, fileName)
            activeOutputFile = outputFile
            currentRecordedVideoTrack = remoteTrack
            currentRtcClient = rtcClient

            val renderer = RemoteVideoFileRenderer(outputFile, sharedEglContext)
            videoRenderer = renderer
            remoteTrack.addSink(renderer)

            // Hook up remote audio listener to capture remote person's audio
            rtcClient?.remoteAudioListener = { audioFormat, channelCount, sampleRate, data ->
                renderer.onRemoteAudioSamples(audioFormat, channelCount, sampleRate, data)
            }

            recordedDuration = 0L
            _recordingStatus.value = RecordingStatus.Recording(0L, outputFile.absolutePath)

            timerJob?.cancel()
            timerJob = scope.launch {
                while (isActive) {
                    delay(1000)
                    recordedDuration += 1
                    if (_recordingStatus.value is RecordingStatus.Recording) {
                        _recordingStatus.value = RecordingStatus.Recording(
                            durationSeconds = recordedDuration,
                            filePath = outputFile.absolutePath
                        )
                    }
                }
            }

            Log.i(TAG, "Started recording remote video + audio to: ${outputFile.absolutePath}")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start remote video/audio recording", e)
            _recordingStatus.value = RecordingStatus.Error("Failed to start recording: ${e.localizedMessage}")
            return false
        }
    }

    /**
     * Stops recording, releases hardware encoders and saves the MP4 into device storage.
     */
    fun stopRecording(onFinished: ((RecordingStatus.Saved?) -> Unit)? = null) {
        if (!isRecording && _recordingStatus.value !is RecordingStatus.Recording) {
            return
        }

        timerJob?.cancel()
        timerJob = null

        val finalDuration = recordedDuration
        val track = currentRecordedVideoTrack
        val rtc = currentRtcClient
        val renderer = videoRenderer
        val file = activeOutputFile

        _recordingStatus.value = RecordingStatus.Processing

        // Detach audio listener
        rtc?.remoteAudioListener = null

        // Detach video sink
        track?.let {
            try {
                if (renderer != null) {
                    it.removeSink(renderer)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error removing video sink", e)
            }
        }

        scope.launch(Dispatchers.IO) {
            renderer?.release { savedFile ->
                scope.launch {
                    if (savedFile != null && savedFile.exists() && savedFile.length() > 0) {
                        val mediaUri = exportToMediaStore(savedFile)
                        val result = RecordingStatus.Saved(
                            file = savedFile,
                            mediaStoreUri = mediaUri,
                            durationSeconds = finalDuration,
                            fileSizeBytes = savedFile.length()
                        )
                        _recordingStatus.value = result
                        onFinished?.invoke(result)
                        Log.i(TAG, "Recording saved successfully: ${savedFile.absolutePath}, size: ${savedFile.length()} bytes")
                    } else {
                        val errMsg = "Recording file is empty or could not be saved"
                        _recordingStatus.value = RecordingStatus.Error(errMsg)
                        onFinished?.invoke(null)
                    }
                    videoRenderer = null
                    currentRecordedVideoTrack = null
                    currentRtcClient = null
                    activeOutputFile = null
                }
            }
        }
    }

    /**
     * Dismisses the current saved/error state and returns to Idle.
     */
    fun resetStatus() {
        _recordingStatus.value = RecordingStatus.Idle
    }

    /**
     * Exports the recorded MP4 file to public MediaStore (Movies/GoCall) so it's readily accessible
     * in the device Gallery, Files, and Video Player apps.
     */
    private suspend fun exportToMediaStore(file: File): Uri? = withContext(Dispatchers.IO) {
        try {
            val contentValues = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
                put(MediaStore.Video.Media.DATE_MODIFIED, System.currentTimeMillis() / 1000)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/GoCall")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
            }

            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)

            if (uri != null) {
                resolver.openOutputStream(uri)?.use { out ->
                    FileInputStream(file).use { input ->
                        input.copyTo(out)
                    }
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentValues.clear()
                    contentValues.put(MediaStore.Video.Media.IS_PENDING, 0)
                    resolver.update(uri, contentValues, null, null)
                }

                Log.i(TAG, "Exported recording to MediaStore: $uri")
                return@withContext uri
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving recording to MediaStore", e)
        }
        return@withContext null
    }

    /**
     * Creates a share intent for the recorded MP4 video file.
     */
    fun createShareIntent(file: File, mediaStoreUri: Uri?): Intent {
        val uri = mediaStoreUri ?: try {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
        } catch (e: Exception) {
            Uri.fromFile(file)
        }

        return Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * Creates an intent to open and view/play the recorded MP4 video in the user's video player.
     */
    fun createViewIntent(file: File, mediaStoreUri: Uri?): Intent {
        val uri = mediaStoreUri ?: try {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
        } catch (e: Exception) {
            Uri.fromFile(file)
        }

        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/mp4")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
