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
                            handleIncomingJson(JSONObject(text))
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
            broadcastMessage(JSONObject().put("type", "SMS_LIST").put("data", teleManager.getRecentSms()).toString())
            broadcastMessage(JSONObject().put("type", "CONTACTS_LIST").put("data", teleManager.getContacts()).toString())
            broadcastMessage(JSONObject().put("type", "STORAGE_STATS").put("data", teleManager.getStorageStats()).toString())
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
        } catch (e: Exception) { e.printStackTrace() }
    }

    fun broadcastMessage(msg: String) {
        wsMessageChannel.trySend(msg)
        mqttSendChannel.trySend(msg)
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
                broadcastMessage("{\"type\":\"pong\",\"timestamp\":${json.optLong("timestamp")}}")
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
                                val success = teleManager.requestUninstallApp(pkg)
                broadcastMessage(JSONObject().apply {
                    put("type", "UNINSTALL_APP_ACK")
                    put("package", pkg)
                    put("success", success)
                }.toString())
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
                        Log.d("OpenDroidAdmin", "Device screen locked successfully via remote command")
                    } else {
                        broadcastMessage(JSONObject().apply {
                            put("type", "LOCK_DEVICE_ACK")
                            put("success", false)
                            put("error", "Device Administrator is not activated on phone")
                        }.toString())
                        Log.w("OpenDroidAdmin", "Remote lock failed: Admin receiver is inactive")
                    }
                } catch (e: Exception) {
                    Log.e("OpenDroidAdmin", "Remote lock exception", e)
                    broadcastMessage(JSONObject().apply {
                        put("type", "LOCK_DEVICE_ACK")
                        put("success", false)
                        put("error", e.message)
                    }.toString())
                }
            }

            // --- Music & Video Queries ---
            "FETCH_AUDIO" -> {
                val audioList = teleManager.getAudioTracks()
                broadcastMessage(JSONObject().apply {
                    put("type", "AUDIO_TRACKS_LIST")
                    put("data", audioList)
                }.toString())
            }

            "FETCH_VIDEOS" -> {
                val videoList = teleManager.getVideoTracks()
                broadcastMessage(JSONObject().apply {
                    put("type", "VIDEO_TRACKS_LIST")
                    put("data", videoList)
                }.toString())
            }

            // --- File Transfer ---
            "DOWNLOAD_FILE_CHUNK" -> {
                val path = json.optString("path", "")
                val offset = json.optLong("offset", 0L)
                val chunkResult = teleManager.readFileChunk(path, offset)
                broadcastMessage(JSONObject().apply {
                    put("type", "FILE_DOWNLOAD_CHUNK")
                    put("data", chunkResult)
                }.toString())
            }

            "UPLOAD_FILE_CHUNK" -> {
                val fileName = json.optString("fileName", "uploaded_file")
                val targetDirPath = json.optString("targetPath", "")
                val base64Data = json.optString("data", "")
                val isFirst = json.optBoolean("isFirst", true)
                val isLast = json.optBoolean("isLast", false)

                val success = teleManager.saveUploadedChunk(targetDirPath, fileName, base64Data, isFirst, isLast)
                if (isLast) {
                    broadcastMessage(JSONObject().apply {
                        put("type", "FILE_UPLOAD_COMPLETE")
                        put("fileName", fileName)
                        put("targetPath", targetDirPath)
                        put("success", success)
                    }.toString())
                } else {
                    broadcastMessage(JSONObject().apply {
                        put("type", "FILE_UPLOAD_CHUNK_ACK")
                        put("fileName", fileName)
                        put("success", success)
                    }.toString())
                }
            }

            // --- Touch Injection ---
            "INPUT_TAP" -> {
                val service = RemoteInputService.instance
                if (service != null) {
                    service.dispatchTap(
                        json.getDouble("x").toFloat(),
                        json.getDouble("y").toFloat()
                    )
                } else {
                    broadcastMessage(JSONObject().apply {
                        put("type", "ACCESSIBILITY_REQUIRED")
                    }.toString())
                }
            }
            "INPUT_SWIPE" -> {
                val service = RemoteInputService.instance
                if (service != null) {
                    service.dispatchSwipe(
                        json.getDouble("startX").toFloat(),
                        json.getDouble("startY").toFloat(),
                        json.getDouble("endX").toFloat(),
                        json.getDouble("endY").toFloat()
                    )
                } else {
                    broadcastMessage(JSONObject().apply {
                        put("type", "ACCESSIBILITY_REQUIRED")
                    }.toString())
                }
            }
            "GLOBAL_ACTION" -> {
                val service = RemoteInputService.instance
                if (service != null) {
                    service.executeGlobalAction(json.getString("actionType"))
                } else {
                    broadcastMessage(JSONObject().apply {
                        put("type", "ACCESSIBILITY_REQUIRED")
                    }.toString())
                }
            }

            // --- Location Request ---
            "FETCH_LOCATION" -> {
                broadcastMessage(teleManager.getLocation().toString())
            }

            // --- Other Utilities ---
            "RING_SIREN" -> {
                val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                audioManager.setStreamVolume(AudioManager.STREAM_ALARM, audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
                val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                currentRingtone = RingtoneManager.getRingtone(applicationContext, uri).apply {
                    audioAttributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                    play()
                }
            }
            "STOP_SIREN" -> currentRingtone?.stop()
            "MAKE_CALL" -> teleManager.makeCall(json.getString("number"))
            "FETCH_SMS" -> broadcastMessage(JSONObject().put("type", "SMS_LIST").put("data", teleManager.getRecentSms()).toString())
            "FETCH_CONTACTS" -> broadcastMessage(JSONObject().put("type", "CONTACTS_LIST").put("data", teleManager.getContacts()).toString())
            "FETCH_STORAGE" -> broadcastMessage(JSONObject().put("type", "STORAGE_STATS").put("data", teleManager.getStorageStats()).toString())
            "FETCH_CALL_LOGS" -> broadcastMessage(JSONObject().put("type", "CALL_LOGS_LIST").put("data", teleManager.getCallLogs()).toString())
            "SEND_SMS" -> {
                val success = teleManager.sendSms(json.getString("to"), json.getString("message"))
                broadcastMessage("{\"type\":\"SMS_SENT\",\"success\":$success}")
            }
            "FETCH_APPS" -> {
                broadcastMessage(JSONObject().put("type", "APPS_LIST").put("data", teleManager.getInstalledApps()).toString())
            }
            "SET_CLIPBOARD" -> {
                val text = json.optString("text", "")
                teleManager.setClipboardText(text)
                broadcastMessage(JSONObject().put("type", "CLIPBOARD_SET_ACK").put("status", "success").toString())
            }
            "FETCH_CLIPBOARD" -> {
                broadcastMessage(JSONObject().put("type", "CLIPBOARD_DATA").put("text", teleManager.getClipboardText()).toString())
            }
            "FETCH_DIR" -> {
                val path = json.optString("path", "")
                val dirData = teleManager.getDirectoryContents(path)
                broadcastMessage(JSONObject().put("type", "DIR_CONTENTS").put("data", dirData).toString())
            }
            "FETCH_PHOTOS" -> {
                val photosData = teleManager.getRecentPhotos()
                broadcastMessage(JSONObject().put("type", "PHOTOS_LIST").put("data", photosData).toString())
            }
        }
    }

    private fun startServer() {
        synchronized(this) {
            if (isServerStartingOrRunning || server != null) return
            isServerStartingOrRunning = true
        }

        serviceScope.launch {
            try {
                server = embeddedServer(CIO, port = PORT) {
                    fileServerModule(baseDir)
                }.start(wait = false)
            } catch (e: Exception) {
                Log.e("LocalServer", "Server startup caught", e)
                synchronized(this@LocalFileServerService) {
                    isServerStartingOrRunning = false
                }
            }
        }
    }

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
                        val fileName = part.originalFileName ?: "file_${System.currentTimeMillis()}"
                        val file = File(directory, fileName)
                        part.streamProvider().use { input ->
                            file.outputStream().buffered().use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                    part.dispose()
                }
                call.respondText("{\"status\":\"success\"}", ContentType.Application.Json)
            }

            webSocket("/ws") {
                val senderJob = launch {
                    for (msg in wsMessageChannel) {
                        send(Frame.Text(msg))
                    }
                }

                for (frame in incoming) {
                    if (frame is Frame.Text) {
                        handleIncomingJson(JSONObject(frame.readText()))
                    }
                }
                senderJob.cancel()
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "File Server Service", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        unregisterReceiver(batteryReceiver)
        currentRingtone?.stop()
        DirectCameraStreamer.getInstance(applicationContext).stopStreaming()
        DirectScreenStreamer.getInstance(applicationContext).stopStreaming()
        AudioStreamManager.getInstance(applicationContext).stopStreaming()

        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.d("KeepAlive", "Partial WakeLock released")
            }
        } catch (e: Exception) {
            Log.e("KeepAlive", "Error releasing WakeLock", e)
        }
        wakeLock = null

        try { server?.stop(500, 1000) } catch (e: Exception) {}
        server = null
        isServerStartingOrRunning = false
        try { mqttClient?.disconnect() } catch (e: Exception) {}
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}