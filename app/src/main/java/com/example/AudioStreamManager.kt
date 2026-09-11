package com.example.localutility

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Base64
import android.util.Log

class AudioStreamManager private constructor(private val context: Context) {

    companion object {
        @Volatile
        private var instance: AudioStreamManager? = null

        fun getInstance(context: Context): AudioStreamManager {
            return instance ?: synchronized(this) {
                instance ?: AudioStreamManager(context.applicationContext).also { instance = it }
            }
        }

        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    @Volatile
    var isRecording = false

    @SuppressLint("MissingPermission")
    fun startStreaming(onChunk: (String) -> Unit) {
        if (isRecording) return

        try {
            val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            val bufferSize = maxOf(minBufferSize, 3200)

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e("AudioStream", "AudioRecord initialization failed")
                return
            }

            audioRecord?.startRecording()
            isRecording = true

            recordingThread = Thread({
                val audioData = ByteArray(3200) // 100ms of audio at 16kHz 16-bit
                while (isRecording) {
                    val bytesRead = audioRecord?.read(audioData, 0, audioData.size) ?: 0
                    if (bytesRead > 0) {
                        val base64 = Base64.encodeToString(
                            if (bytesRead == audioData.size) audioData else audioData.copyOf(bytesRead),
                            Base64.NO_WRAP
                        )
                        onChunk(base64)
                    }
                }
            }, "DirectAudioRecordThread").also { it.start() }

            Log.d("AudioStream", "Ambient audio streaming active at 16kHz!")

        } catch (e: Exception) {
            Log.e("AudioStream", "Failed to start audio recording", e)
            isRecording = false
        }
    }

    fun stopStreaming() {
        isRecording = false
        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
            recordingThread = null
            Log.d("AudioStream", "Ambient audio streaming stopped")
        } catch (e: Exception) {
            Log.e("AudioStream", "Error stopping audio", e)
        }
    }
}