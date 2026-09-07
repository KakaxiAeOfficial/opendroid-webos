package com.example.localutility

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
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

class LocalFileServerService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var server: ApplicationEngine? = null
    private lateinit var baseDir: File
    private var currentRingtone: Ringtone? = null
    private val wsMessageChannel = Channel<String>(Channel.UNLIMITED)
    private val mqttSendChannel = Channel<String>(Channel.UNLIMITED)
    private var mqttClient: MqttClient? = null

    companion object {
        private const val PORT = 8888
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "FileServerChannel"
        var currentPairingCode: String = "123456"
        var isCloudConnected: Boolean = false
        var onCloudStatusChanged: ((Boolean) -> Unit)? = null
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            broadcastBatteryStatus()
        }
    }

    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences("opendroid_prefs", Context.MODE_PRIVATE)
        currentPairingCode = prefs.getString("pairing_code", currentPairingCode) ?: currentPairingCode

        baseDir = getExternalFilesDir(null) ?: filesDir
        createNotificationChannel()
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        
        // Connect WebRTC answers & ICE candidates directly to 5G Cloud Message Broker
        val webRtcManager = WebRtcManager.getInstance(applicationContext)
        webRtcManager.onSendMessage = { broadcastMessage(it) }

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

    private fun initCloudBridge() {
        serviceScope.launch {
            try {
                val brokerUrl = "tcp://broker.emqx.io:1883"
                val clientId = "OpenDroidPhone_" + System.currentTimeMillis()
                mqttClient = MqttClient(brokerUrl, clientId, MemoryPersistence())

                val options = MqttConnectOptions().apply {
                    isCleanSession = true
                    connectionTimeout = 15
                    keepAliveInterval = 30
                    isAutomaticReconnect = true
                }

                mqttClient?.setCallback(object : MqttCallbackExtended {
                    override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                        Log.d("CloudBridge", "Connected to EMQX with Code: $currentPairingCode")
                        isCloudConnected = true
                        onCloudStatusChanged?.invoke(true)
                        subscribeToCode(currentPairingCode)
                    }

                    override fun connectionLost(cause: Throwable?) {
                        Log.w("CloudBridge", "Connection lost", cause)
                        isCloudConnected = false
                        onCloudStatusChanged?.invoke(false)
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
            } catch (e: Exception) {
                Log.e("CloudBridge", "EMQX Connect Error", e)
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
        val teleManager = TelephonyAndLocationManager(applicationContext)
        broadcastMessage(JSONObject().apply {
            put("type", "HANDSHAKE_ACK")
            put("code", currentPairingCode)
            put("device", Build.MODEL)
        }.toString())
        broadcastBatteryStatus()
        try {
            broadcastMessage(teleManager.getLocation().put("type", "LOCATION").toString())
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

    private fun broadcastMessage(msg: String) {
        wsMessageChannel.trySend(msg)
        mqttSendChannel.trySend(msg)
    }

    private fun handleIncomingJson(json: JSONObject) {
        val webRtcManager = WebRtcManager.getInstance(applicationContext)
        val teleManager = TelephonyAndLocationManager(applicationContext)

        when (json.optString("type")) {
            "offer" -> webRtcManager.handleRemoteOffer(json.getString("sdp"))
            "candidate" -> webRtcManager.handleRemoteIceCandidate(
                json.getString("sdpMid"), json.getInt("sdpMLineIndex"), json.getString("candidate")
            )
            "ping" -> {
                broadcastMessage("{\"type\":\"pong\",\"timestamp\":${json.optLong("timestamp")}}")
                broadcastBatteryStatus()
            }
        }

        when (json.optString("action")) {
            "HANDSHAKE" -> {
                sendFullSyncData()
            }
            "INPUT_TAP" -> {
                RemoteInputService.instance?.dispatchTap(
                    json.getDouble("x").toFloat(),
                    json.getDouble("y").toFloat()
                )
            }
            "INPUT_SWIPE" -> {
                RemoteInputService.instance?.dispatchSwipe(
                    json.getDouble("startX").toFloat(),
                    json.getDouble("startY").toFloat(),
                    json.getDouble("endX").toFloat(),
                    json.getDouble("endY").toFloat()
                )
            }
            "GLOBAL_ACTION" -> {
                RemoteInputService.instance?.executeGlobalAction(json.getString("actionType"))
            }
            "START_SCREEN_STREAM" -> {
                // Prepares WebRTC for screen sharing
            }
            "START_CAMERA" -> {
                val intent = Intent(this@LocalFileServerService, CameraStreamService::class.java).apply {
                    putExtra("facing", json.optString("facing", "back"))
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
                else startService(intent)
            }
            "SWITCH_CAMERA" -> webRtcManager.switchCamera()
            "TOGGLE_FLASHLIGHT" -> CameraStreamService.instance?.toggleFlashlight()
            "QUICK_REPLY" -> {
                NotificationMirrorService.instance?.sendQuickReply(
                    json.getString("key"),
                    json.getString("text")
                )
            }
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
            "FETCH_LOCATION" -> broadcastMessage(teleManager.getLocation().put("type", "LOCATION").toString())
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
        if (server != null) return
        serviceScope.launch {
            server = embeddedServer(CIO, port = PORT) {
                fileServerModule(baseDir)
            }.start(wait = false)
        }
    }

    private fun Application.fileServerModule(directory: File) {
        install(WebSockets)

        val teleManager = TelephonyAndLocationManager(applicationContext)

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
        unregisterReceiver(batteryReceiver)
        currentRingtone?.stop()
        server?.stop(1000, 2000)
        try { mqttClient?.disconnect() } catch (e: Exception) {}
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}