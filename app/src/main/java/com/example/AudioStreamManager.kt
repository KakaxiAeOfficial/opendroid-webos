package com.example.localutility

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
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

    private var audioTrack: AudioTrack? = null

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

    // --- Phase 3 Step 3.3: Two-Way Audio (Walkie-Talkie Speaker Playback) ---
    fun playWalkieTalkieChunk(base64Pcm: String) {
        try {
            if (audioTrack == null || audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
                initAudioTrack()
            }
            val pcmData = Base64.decode(base64Pcm, Base64.NO_WRAP)
            if (pcmData.isNotEmpty()) {
                audioTrack?.write(pcmData, 0, pcmData.size)
            }
        } catch (e: Exception) {
            Log.e("AudioStream", "Walkie-talkie playback error", e)
        }
    }

    private fun initAudioTrack() {
        try {
            val minBufferSize = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AUDIO_FORMAT
            )
            val bufferSize = maxOf(minBufferSize, 6400)
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AUDIO_FORMAT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioTrack?.play()
            Log.d("AudioStream", "Walkie-talkie AudioTrack initialized and playing")
        } catch (e: Exception) {
            Log.e("AudioStream", "Failed to init AudioTrack", e)
        }
    }

    fun stopWalkieTalkie() {
        try {
            audioTrack?.stop()
            audioTrack?.release()
            audioTrack = null
            Log.d("AudioStream", "Walkie-talkie stopped")
        } catch (e: Exception) {
            Log.e("AudioStream", "Error stopping AudioTrack", e)
        }
    }
}