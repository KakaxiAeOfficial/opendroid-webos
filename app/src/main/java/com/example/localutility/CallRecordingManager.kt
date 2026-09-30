package com.example.localutility

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaRecorder
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Universal Call Audio Recording & State Engine (OpenDroid WebOS)
 * 100% compatible with Android 8.0 (API 26) through Android 15 (API 35).
 * Supports Xiaomi HyperOS, MIUI, Samsung OneUI, Pixel, and OnePlus.
 */
@SuppressLint("MissingPermission", "NewApi")
class CallRecordingManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "CallRecordingManager"

        @Volatile
        private var instance: CallRecordingManager? = null

        fun getInstance(context: Context): CallRecordingManager {
            return instance ?: synchronized(this) {
                instance ?: CallRecordingManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var mediaRecorder: MediaRecorder? = null
    private val isRecording = AtomicBoolean(false)
    private var currentRecordingFile: File? = null
    private var currentCallNumber: String = "Unknown"
    private var callStartTime: Long = 0L

    @Volatile
    private var currentCallState: String = "IDLE"

    // Callbacks to notify LocalFileServerService & WebOS
    var onCallStateChanged: ((state: String, number: String) -> Unit)? = null
    var onCallRecordingCompleted: ((fileName: String, durationMs: Long, number: String) -> Unit)? = null

    // Layer 1: Universal BroadcastReceiver for Phone State
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

    // Layer 2: Universal Telephony PhoneStateListener
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
        // 1. Register BroadcastReceiver via ContextCompat (Safe across all Android API levels)
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

        // 2. Register TelephonyManager listener
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

        // Deduplication to prevent duplicate triggers
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
                val fileName = "CALL_${timeStamp}_${sanitizedNumber}.m4a"
                val outFile = File(recordDir, fileName)

                currentRecordingFile = outFile
                callStartTime = System.currentTimeMillis()

                @Suppress("DEPRECATION")
                val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    MediaRecorder(context)
                } else {
                    MediaRecorder()
                }

                // AudioSource hierarchy: VOICE_COMMUNICATION -> MIC -> DEFAULT
                var sourceConfigured = false
                val audioSourcesToTry = listOf(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    MediaRecorder.AudioSource.MIC,
                    MediaRecorder.AudioSource.DEFAULT
                )

                for (source in audioSourcesToTry) {
                    try {
                        recorder.reset()
                        recorder.setAudioSource(source)
                        sourceConfigured = true
                        break
                    } catch (e: Exception) {
                        Log.w(TAG, "AudioSource $source unavailable, trying next...", e)
                    }
                }

                if (!sourceConfigured) {
                    Log.e(TAG, "No suitable AudioSource found for call recording")
                    recorder.release()
                    return@launch
                }

                recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                recorder.setAudioSamplingRate(16000)
                recorder.setAudioEncodingBitRate(32000)
                recorder.setOutputFile(outFile.absolutePath)

                recorder.prepare()
                recorder.start()

                mediaRecorder = recorder
                isRecording.set(true)
                Log.d(TAG, "Call recording started: ${outFile.name}")
            } catch (e: Exception) {
                Log.e(TAG, "Call recording initialization exception (Safe fallback)", e)
                try {
                    mediaRecorder?.release()
                } catch (ignored: Exception) {}
                mediaRecorder = null
                isRecording.set(false)
            }
        }
    }

    @Synchronized
    private fun stopCallRecording() {
        if (!isRecording.get()) return

        scope.launch {
            try {
                mediaRecorder?.apply {
                    try {
                        stop()
                    } catch (e: Exception) {
                        Log.w(TAG, "MediaRecorder stop warning", e)
                    }
                    release()
                }
                mediaRecorder = null
                isRecording.set(false)

                val duration = System.currentTimeMillis() - callStartTime
                val file = currentRecordingFile
                if (file != null && file.exists() && file.length() > 0) {
                    Log.d(TAG, "Call recording saved: ${file.name} (Duration: ${duration}ms, Size: ${file.length()} bytes)")
                    onCallRecordingCompleted?.invoke(file.name, duration, currentCallNumber)
                } else {
                    file?.delete()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping call recorder", e)
            } finally {
                mediaRecorder = null
                isRecording.set(false)
            }
        }
    }

    fun getRecordedCalls(): List<File> {
        val recordDir = File(context.filesDir, "call_records")
        if (!recordDir.exists()) return emptyList()
        return recordDir.listFiles { f -> f.extension == "m4a" }?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    fun getRecordingFile(fileName: String): File? {
        val file = File(File(context.filesDir, "call_records"), fileName)
        return if (file.exists()) file else null
    }
}