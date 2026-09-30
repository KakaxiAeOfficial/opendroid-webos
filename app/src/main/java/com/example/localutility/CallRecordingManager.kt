package com.example.localutility

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaRecorder
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Ambient Intelligence - Track 1: Xiaomi/HyperOS & Universal Android Dual-Layer Call Engine
 * High-reliability active call detection, live ambient bridge, and automatic call recording.
 */
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

    private val phoneStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == TelephonyManager.ACTION_PHONE_STATE) {
                val stateStr = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
                val incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
                Log.d(TAG, "📡 BroadcastReceiver ACTION_PHONE_STATE: state=$stateStr, num=$incomingNumber")
                when (stateStr) {
                    TelephonyManager.EXTRA_STATE_RINGING -> {
                        handleNormalizedState("RINGING", incomingNumber)
                    }
                    TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                        handleNormalizedState("OFFHOOK", incomingNumber)
                    }
                    TelephonyManager.EXTRA_STATE_IDLE -> {
                        handleNormalizedState("IDLE", incomingNumber)
                    }
                }
            }
        }
    }

    init {
        registerDualLayerCallDetection()
    }

    private fun registerDualLayerCallDetection() {
        // Layer 1: Dynamic BroadcastReceiver (Crucial for Xiaomi / HyperOS background delivery)
        try {
            val filter = IntentFilter(TelephonyManager.ACTION_PHONE_STATE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(phoneStateReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(phoneStateReceiver, filter)
            }
            Log.d(TAG, "✅ Layer 1: Registered ACTION_PHONE_STATE BroadcastReceiver")
        } catch (e: Exception) {
            Log.w(TAG, "Layer 1 Receiver registration warning (Non-fatal)", e)
        }

        // Layer 2: TelephonyManager Listener (TelephonyCallback for Android 12+ / PhoneStateListener for older)
        try {
            val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            if (telephonyManager != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    telephonyManager.registerTelephonyCallback(
                        context.mainExecutor,
                        object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                            override fun onCallStateChanged(state: Int) {
                                when (state) {
                                    TelephonyManager.CALL_STATE_RINGING -> handleNormalizedState("RINGING", null)
                                    TelephonyManager.CALL_STATE_OFFHOOK -> handleNormalizedState("OFFHOOK", null)
                                    TelephonyManager.CALL_STATE_IDLE -> handleNormalizedState("IDLE", null)
                                }
                            }
                        }
                    )
                    Log.d(TAG, "✅ Layer 2: Registered TelephonyCallback (Android 12+)")
                } else {
                    @Suppress("DEPRECATION")
                    telephonyManager.listen(object : PhoneStateListener() {
                        @Deprecated("Deprecated in Java")
                        override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                            when (state) {
                                TelephonyManager.CALL_STATE_RINGING -> handleNormalizedState("RINGING", phoneNumber)
                                TelephonyManager.CALL_STATE_OFFHOOK -> handleNormalizedState("OFFHOOK", phoneNumber)
                                TelephonyManager.CALL_STATE_IDLE -> handleNormalizedState("IDLE", phoneNumber)
                            }
                        }
                    }, PhoneStateListener.LISTEN_CALL_STATE)
                    Log.d(TAG, "✅ Layer 2: Registered PhoneStateListener (Legacy)")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Layer 2 TelephonyManager registration warning (Non-fatal)", e)
        }
    }

    @Synchronized
    private fun handleNormalizedState(newState: String, phoneNumber: String?) {
        if (phoneNumber != null && phoneNumber.isNotEmpty() && phoneNumber != "null") {
            currentCallNumber = phoneNumber
        }

        // Deduplication: prevent multiple triggers from dual listeners
        if (currentCallState == newState) return
        currentCallState = newState

        Log.d(TAG, "📞 Call State Transition -> $newState ($currentCallNumber)")

        scope.launch {
            try {
                when (newState) {
                    "RINGING" -> {
                        onCallStateChanged?.invoke("RINGING", currentCallNumber)
                    }
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

                // AudioSource fallback hierarchy: VOICE_COMMUNICATION -> MIC -> DEFAULT
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
                        Log.d(TAG, "Configured audio source: $source")
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
                Log.d(TAG, "✅ Call recording started: ${outFile.name}")
            } catch (e: Exception) {
                Log.e(TAG, "Call recording initialization exception (Non-fatal, safe fallback)", e)
                try {
                    mediaRecorder?.release()
                } catch (_: Exception) {}
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
                    Log.d(TAG, "✅ Call recording saved successfully: ${file.name} (Duration: ${duration}ms, Size: ${file.length()} bytes)")
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

