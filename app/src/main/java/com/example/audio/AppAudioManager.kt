package com.example.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AppAudioManager(private val context: Context) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _isSpeakerOn = MutableStateFlow(true)
    val isSpeakerOn: StateFlow<Boolean> = _isSpeakerOn.asStateFlow()

    private var previousAudioMode: Int = AudioManager.MODE_NORMAL
    private var previousIsSpeakerphoneOn: Boolean = false
    private var previousIsMicrophoneMute: Boolean = false

    fun start() {
        previousAudioMode = audioManager.mode
        previousIsSpeakerphoneOn = audioManager.isSpeakerphoneOn
        previousIsMicrophoneMute = audioManager.isMicrophoneMute

        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        setSpeakerphoneOn(true)
    }

    fun setSpeakerphoneOn(on: Boolean) {
        _isSpeakerOn.value = on
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (on) {
                    val speakerDevice = audioManager.availableCommunicationDevices.firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                    }
                    if (speakerDevice != null) {
                        audioManager.setCommunicationDevice(speakerDevice)
                    } else {
                        @Suppress("DEPRECATION")
                        audioManager.isSpeakerphoneOn = true
                    }
                } else {
                    val earpieceDevice = audioManager.availableCommunicationDevices.firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                    }
                    if (earpieceDevice != null) {
                        audioManager.setCommunicationDevice(earpieceDevice)
                    } else {
                        audioManager.clearCommunicationDevice()
                        @Suppress("DEPRECATION")
                        audioManager.isSpeakerphoneOn = false
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = on
            }
        } catch (e: Exception) {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = on
        }
    }

    fun toggleSpeaker() {
        setSpeakerphoneOn(!_isSpeakerOn.value)
    }

    fun stop() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            }
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = previousIsSpeakerphoneOn
            audioManager.isMicrophoneMute = previousIsMicrophoneMute
            audioManager.mode = previousAudioMode
        } catch (e: Exception) {
            audioManager.mode = AudioManager.MODE_NORMAL
        }
    }
}
