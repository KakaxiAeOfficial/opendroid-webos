package com.example.localutility

import android.annotation.SuppressLint
import android.content.Context
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
 * Ambient Intelligence - Track 1: Call Recording & Audio Logs Engine
 * Safe, isolated background call audio recording with zero crash risk.
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

    // Callbacks to notify LocalFileServerService
    var onCallStateChanged: ((state: String, number: String) -> Unit)? = null
    var onCallRecordingCompleted: ((fileName: String, durationMs: Long, number: String) -> Unit)? = null

    init {
        registerCallStateListener()
    }

    private fun registerCallStateListener() {
        try {
            val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            if (telephonyManager == null) {
                Log.w(TAG, "TelephonyManager unavailable on this device")
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                telephonyManager.registerTelephonyCallback(
                    context.mainExecutor,
                    object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                        override fun onCallStateChanged(state: Int) {
                            handleState(state, null)
                        }
                    }
                )
                Log.d(TAG, "Registered TelephonyCallback for Android 12+")
            } else {
                @Suppress("DEPRECATION")
                telephonyManager.listen(object : PhoneStateListener() {
                    @Deprecated("Deprecated in Java")
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                        handleState(state, phoneNumber)
                    }
                }, PhoneStateListener.LISTEN_CALL_STATE)
                Log.d(TAG, "Registered PhoneStateListener for legacy Android")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register call state listener (Non-fatal)", e)
        }
    }

    private fun handleState(state: Int, incomingNumber: String?) {
        scope.launch {
            try {
                when (state) {
                    TelephonyManager.CALL_STATE_RINGING -> {
                        val num = incomingNumber ?: "Incoming Call"
                        currentCallNumber = num
                        Log.d(TAG, "📞 Call State: RINGING ($num)")
                        onCallStateChanged?.invoke("RINGING", num)
                    }
                    TelephonyManager.CALL_STATE_OFFHOOK -> {
                        // Call connected (active talking)
                        Log.d(TAG, "📞 Call State: OFFHOOK (Call Connected). Starting silent recording...")
                        onCallStateChanged?.invoke("OFFHOOK", currentCallNumber)
                        startCallRecording()
                    }
                    TelephonyManager.CALL_STATE_IDLE -> {
                        // Call ended or hung up
                        Log.d(TAG, "📞 Call State: IDLE (Call Ended). Stopping recording...")
                        onCallStateChanged?.invoke("IDLE", currentCallNumber)
                        stopCallRecording()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling call state (Isolated)", e)
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

                // AudioSource.VOICE_COMMUNICATION or MIC with graceful fallback
                var sourceConfigured = false
                try {
                    recorder.setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                    sourceConfigured = true
                } catch (_: Exception) {
                    try {
                        recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
                        sourceConfigured = true
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to set audio source", e)
                    }
                }

                if (!sourceConfigured) {
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
                Log.d(TAG, "✅ Call recording started silently: ${outFile.name}")
            } catch (e: Exception) {
                Log.e(TAG, "Call recording initialization exception (Non-fatal, app safe)", e)
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
                    } catch (_: Exception) {}
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

    /**
     * Returns a list of all saved call recordings metadata
     */
    fun getRecordedCalls(): List<File> {
        val recordDir = File(context.filesDir, "call_records")
        if (!recordDir.exists()) return emptyList()
        return recordDir.listFiles { f -> f.extension == "m4a" }?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    /**
     * Fetches a specific recorded audio file
     */
    fun getRecordingFile(fileName: String): File? {
        val file = File(File(context.filesDir, "call_records"), fileName)
        return if (file.exists()) file else null
    }
}

