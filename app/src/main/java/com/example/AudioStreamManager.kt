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
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

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
        private const val CHANNEL_IN_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val CHANNEL_OUT_CONFIG = AudioFormat.CHANNEL_OUT_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val MAX_QUEUE_CHUNKS = 8 // ~800ms jitter buffer: drop excess to prevent ANR/memory leak
    }

    // --- Ambient Microphone Recording ---
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    @Volatile
    var isRecording = false

    // --- Push-to-Talk (Walkie-Talkie) Non-Blocking Playback Queue ---
    private var audioTrack: AudioTrack? = null
    private val playbackQueue = LinkedBlockingQueue<ByteArray>(MAX_QUEUE_CHUNKS)
    private var playbackThread: Thread? = null
    private val isPlaybackActive = AtomicBoolean(false)

    @SuppressLint("MissingPermission")
    fun startStreaming(onChunk: (String) -> Unit) {
        if (isRecording) return

        try {
            val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN_CONFIG, AUDIO_FORMAT)
            val bufferSize = maxOf(minBufferSize, 3200)

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_IN_CONFIG,
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

    // --- Two-Way Audio (Walkie-Talkie Speaker Playback) with Dedicated Single Worker Thread ---
    fun playWalkieTalkieChunk(base64Pcm: String) {
        try {
            val pcmData = Base64.decode(base64Pcm, Base64.NO_WRAP)
            if (pcmData.isEmpty()) return

            ensurePlaybackThreadRunning()

            // Non-blocking queue offer: if queue is full, poll oldest chunk and insert newest
            if (!playbackQueue.offer(pcmData)) {
                playbackQueue.poll() // Drop oldest chunk (avoids audio delay & buffer bloat)
                playbackQueue.offer(pcmData)
            }
        } catch (e: Exception) {
            Log.e("AudioStream", "Walkie-talkie enqueue error", e)
        }
    }

    @Synchronized
    private fun ensurePlaybackThreadRunning() {
        if (isPlaybackActive.get() && playbackThread?.isAlive == true) return

        initAudioTrack()
        isPlaybackActive.set(true)

        playbackThread = Thread({
            Log.d("AudioStream", "Walkie-talkie dedicated playback worker started")
            while (isPlaybackActive.get()) {
                try {
                    val chunk = playbackQueue.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS)
                    if (chunk != null && chunk.isNotEmpty()) {
                        audioTrack?.write(chunk, 0, chunk.size)
                    }
                } catch (_: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.e("AudioStream", "Error playing chunk in worker", e)
                }
            }
            Log.d("AudioStream", "Walkie-talkie playback worker exiting cleanly")
        }, "WalkieTalkiePlaybackThread").also { it.start() }
    }

    private fun initAudioTrack() {
        try {
            if (audioTrack != null && audioTrack?.state == AudioTrack.STATE_INITIALIZED) {
                return
            }

            val minBufferSize = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                CHANNEL_OUT_CONFIG,
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
                        .setChannelMask(CHANNEL_OUT_CONFIG)
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
        isPlaybackActive.set(false)
        playbackThread?.interrupt()
        playbackThread = null
        playbackQueue.clear()

        try {
            audioTrack?.stop()
            audioTrack?.release()
            audioTrack = null
            Log.d("AudioStream", "Walkie-talkie AudioTrack stopped & released")
        } catch (e: Exception) {
            Log.e("AudioStream", "Error stopping AudioTrack", e)
        }
    }
}

