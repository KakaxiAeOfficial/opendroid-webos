package com.example.localutility

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Universal Ambient Intelligence - Dual Call Recording & Live Audio Engine
 * Uses AudioRecord with VOICE_RECOGNITION for guaranteed non-muted audio capture
 * on Xiaomi HyperOS, MIUI, Samsung, and universal Android devices.
 * Dual-function: writes valid WAV file AND streams live PCM audio to WebOS simultaneously.
 */
@SuppressLint("MissingPermission", "NewApi")
class CallRecordingManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "CallRecordingManager"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        @Volatile
        private var instance: CallRecordingManager? = null

        fun getInstance(context: Context): CallRecordingManager {
            return instance ?: synchronized(this) {
                instance ?: CallRecordingManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val isRecording = AtomicBoolean(false)
    private val isLiveListening = AtomicBoolean(false)
    private var recordThread: Thread? = null
    private var currentRecordingFile: File? = null
    private var currentCallNumber: String = "Unknown"
    private var callStartTime: Long = 0L

    @Volatile
    private var currentCallState: String = "IDLE"

    // Callbacks to notify LocalFileServerService & WebOS
    var onCallStateChanged: ((state: String, number: String) -> Unit)? = null
    var onCallRecordingCompleted: ((fileName: String, durationMs: Long, number: String) -> Unit)? = null
    var onLiveAudioChunk: ((base64Pcm: String) -> Unit)? = null

    // Layer 1: BroadcastReceiver for phone state changes
    private val phoneStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            if (intent.action == TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
                val stateStr = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
                val incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
                Log.d(TAG, "BroadcastReceiver ACTION_PHONE_STATE_CHANGED: state=$stateStr, num=$incomingNumber")
                when (stateStr) {
                    TelephonyManager.EXTRA_STATE_RINGING -> handleNormalizedState("RINGING", incomingNumber)
                    TelephonyManager.EXTRA_STATE_OFFHOOK -> handleNormalizedState("OFFHOOK", incomingNumber)
                    TelephonyManager.EXTRA_STATE_IDLE -> handleNormalizedState("IDLE", incomingNumber)
                }
            }
        }
    }

    // Layer 2: TelephonyManager listener for phone state
    @Suppress("DEPRECATION")
    private val legacyPhoneListener = object : PhoneStateListener() {
        @Deprecated("Deprecated in Java")
        override fun onCallStateChanged(state: Int, phoneNumber: String?) {
            when (state) {
                TelephonyManager.CALL_STATE_RINGING -> handleNormalizedState("RINGING", phoneNumber)
                TelephonyManager.CALL_STATE_OFFHOOK -> handleNormalizedState("OFFHOOK", phoneNumber)
                TelephonyManager.CALL_STATE_IDLE -> handleNormalizedState("IDLE", phoneNumber)
            }
        }
    }

    init {
        registerCallDetection()
    }

    private fun registerCallDetection() {
        try {
            val filter = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.registerReceiver(
                    context,
                    phoneStateReceiver,
                    filter,
                    ContextCompat.RECEIVER_EXPORTED
                )
            } else {
                context.registerReceiver(phoneStateReceiver, filter)
            }
            Log.d(TAG, "Registered ACTION_PHONE_STATE_CHANGED receiver")
        } catch (e: Exception) {
            Log.w(TAG, "BroadcastReceiver register error", e)
        }

        try {
            val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            @Suppress("DEPRECATION")
            telephonyManager?.listen(legacyPhoneListener, PhoneStateListener.LISTEN_CALL_STATE)
            Log.d(TAG, "Registered TelephonyManager PhoneStateListener")
        } catch (e: Exception) {
            Log.w(TAG, "TelephonyManager listen error", e)
        }
    }

    @Synchronized
    private fun handleNormalizedState(newState: String, phoneNumber: String?) {
        if (phoneNumber != null && phoneNumber.isNotEmpty() && phoneNumber != "null") {
            currentCallNumber = phoneNumber
        }

        if (currentCallState == newState) return
        currentCallState = newState

        Log.d(TAG, "Call State Transition: $newState ($currentCallNumber)")

        scope.launch {
            try {
                when (newState) {
                    "RINGING" -> onCallStateChanged?.invoke("RINGING", currentCallNumber)
                    "OFFHOOK" -> {
                        onCallStateChanged?.invoke("OFFHOOK", currentCallNumber)
                        startCallRecording()
                    }
                    "IDLE" -> {
                        onCallStateChanged?.invoke("IDLE", currentCallNumber)
                        stopCallRecording()
                        currentCallNumber = "Unknown"
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling call transition $newState", e)
            }
        }
    }

    fun isCallRecording(): Boolean = isRecording.get()
    fun isCallActive(): Boolean = currentCallState == "OFFHOOK" || currentCallState == "RINGING"

    fun setLiveListening(enabled: Boolean) {
        isLiveListening.set(enabled)
        Log.d(TAG, "Live call listening toggled: $enabled")
    }

    @Synchronized
    private fun startCallRecording() {
        if (isRecording.get()) return

        scope.launch {
            try {
                val recordDir = File(context.filesDir, "call_records").apply {
                    if (!exists()) mkdirs()
                }

                val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val sanitizedNumber = currentCallNumber.replace(Regex("[^0-9+]"), "_")
                val fileName = "CALL_${timeStamp}_${sanitizedNumber}.wav"
                val outFile = File(recordDir, fileName)

                currentRecordingFile = outFile
                callStartTime = System.currentTimeMillis()

                val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
                val bufferSize = maxOf(minBufferSize, 3200)

                // Try AudioSources: VOICE_RECOGNITION is immune to in-call mic muting on Xiaomi/MIUI
                var activeAudioRecord: AudioRecord? = null
                val sourcesToTry = listOf(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    MediaRecorder.AudioSource.MIC,
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    MediaRecorder.AudioSource.DEFAULT
                )

                for (source in sourcesToTry) {
                    try {
                        val ar = AudioRecord(source, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize)
                        if (ar.state == AudioRecord.STATE_INITIALIZED) {
                            activeAudioRecord = ar
                            Log.d(TAG, "Initialized AudioRecord with source: $source")
                            break
                        } else {
                            ar.release()
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed source $source: ${e.message}")
                    }
                }

                val audioRecord = activeAudioRecord ?: run {
                    Log.e(TAG, "No suitable AudioRecord source available")
                    return@launch
                }

                audioRecord.startRecording()
                isRecording.set(true)

                recordThread = Thread({
                    val rawBuffer = ByteArray(3200) // 100ms chunks at 16kHz 16-bit
                    var totalPcmBytes = 0L

                    try {
                        FileOutputStream(outFile).use { fos ->
                            // Reserve 44 bytes for WAV header (will be overwritten on completion)
                            fos.write(ByteArray(44))

                            while (isRecording.get()) {
                                val bytesRead = audioRecord.read(rawBuffer, 0, rawBuffer.size)
                                if (bytesRead > 0) {
                                    fos.write(rawBuffer, 0, bytesRead)
                                    totalPcmBytes += bytesRead

                                    // If user is listening live, push chunk to WebOS
                                    if (isLiveListening.get()) {
                                        val base64 = Base64.encodeToString(
                                            if (bytesRead == rawBuffer.size) rawBuffer else rawBuffer.copyOf(bytesRead),
                                            Base64.NO_WRAP
                                        )
                                        onLiveAudioChunk?.invoke(base64)
                                    }
                                }
                            }
                            fos.flush()
                        }

                        // Write final canonical WAV header into the file
                        writeWavHeader(outFile, totalPcmBytes, SAMPLE_RATE, 1, 16)
                        Log.d(TAG, "Call recording saved cleanly: ${outFile.name} (${totalPcmBytes} bytes PCM)")

                    } catch (e: Exception) {
                        Log.e(TAG, "Error in audio recording loop", e)
                    } finally {
                        try {
                            audioRecord.stop()
                            audioRecord.release()
                        } catch (ignored: Exception) {}
                    }
                }, "CallRecordingWorkerThread").also { it.start() }

                Log.d(TAG, "Call audio recording worker started: ${outFile.name}")

            } catch (e: Exception) {
                Log.e(TAG, "Call recording start failed", e)
                isRecording.set(false)
            }
        }
    }

    @Synchronized
    private fun stopCallRecording() {
        if (!isRecording.get()) return

        isRecording.set(false)
        try {
            recordThread?.join(1500)
        } catch (ignored: Exception) {}
        recordThread = null

        val duration = System.currentTimeMillis() - callStartTime
        val file = currentRecordingFile
        if (file != null && file.exists() && file.length() > 44) {
            Log.d(TAG, "Call recording completed: ${file.name} (Duration: ${duration}ms, Size: ${file.length()} bytes)")
            onCallRecordingCompleted?.invoke(file.name, duration, currentCallNumber)
        } else {
            file?.delete()
        }
        currentRecordingFile = null
    }

    private fun writeWavHeader(file: File, pcmDataLength: Long, sampleRate: Int, channels: Int, bitsPerSample: Int) {
        try {
            RandomAccessFile(file, "rw").use { raf ->
                raf.seek(0)
                val totalDataLen = pcmDataLength + 36
                val byteRate = (sampleRate * channels * bitsPerSample / 8).toLong()
                val blockAlign = (channels * bitsPerSample / 8)

                val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                    put("RIFF".toByteArray(Charsets.US_ASCII))
                    putInt((totalDataLen and 0xffffffffL).toInt())
                    put("WAVE".toByteArray(Charsets.US_ASCII))
                    put("fmt ".toByteArray(Charsets.US_ASCII))
                    putInt(16) // Subchunk1Size for PCM
                    putShort(1.toShort()) // AudioFormat 1 = PCM
                    putShort(channels.toShort())
                    putInt(sampleRate)
                    putInt((byteRate and 0xffffffffL).toInt())
                    putShort(blockAlign.toShort())
                    putShort(bitsPerSample.toShort())
                    put("data".toByteArray(Charsets.US_ASCII))
                    putInt((pcmDataLength and 0xffffffffL).toInt())
                }.array()

                raf.write(header)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed writing WAV header", e)
        }
    }

    fun getRecordedCalls(): List<File> {
        val recordDir = File(context.filesDir, "call_records")
        if (!recordDir.exists()) return emptyList()
        return recordDir.listFiles { f -> f.extension == "wav" || f.extension == "m4a" }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    fun getRecordingFile(fileName: String): File? {
        val file = File(File(context.filesDir, "call_records"), fileName)
        return if (file.exists()) file else null
    }
}
