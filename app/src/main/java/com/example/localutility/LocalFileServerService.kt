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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class LocalFileServerService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var server: ApplicationEngine? = null
    private lateinit var baseDir: File
    private var currentRingtone: Ringtone? = null
    private val wsMessageChannel = Channel<String>(Channel.UNLIMITED)

    companion object {
        private const val PORT = 8888
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "FileServerChannel"
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val isCharging = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1) == BatteryManager.BATTERY_STATUS_CHARGING
            if (level != -1 && scale != -1) {
                val pct = (level * 100 / scale.toFloat()).toInt()
                val json = JSONObject().apply {
                    put("type", "BATTERY")
                    put("percent", pct)
                    put("charging", isCharging)
                }
                wsMessageChannel.trySend(json.toString())
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        baseDir = getExternalFilesDir(null) ?: filesDir
        createNotificationChannel()
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundService()
        startServer()
        return START_STICKY
    }

    private fun startForegroundService() {
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("OpenDroid Local Server")
            .setContentText("Running on port $PORT")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
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

            get("/api/sms") {
                call.respondText(teleManager.getRecentSms().toString(), ContentType.Application.Json)
            }

            post("/api/sms/send") {
                val json = JSONObject(call.receiveText())
                val success = teleManager.sendSms(json.optString("to"), json.optString("message"))
                call.respondText("{\"success\": $success}", ContentType.Application.Json)
            }

            get("/api/contacts") {
                call.respondText(teleManager.getContacts().toString(), ContentType.Application.Json)
            }

            get("/api/location") {
                call.respondText(teleManager.getLocation().toString(), ContentType.Application.Json)
            }

            webSocket("/ws") {
                val webRtcManager = WebRtcManager.getInstance(applicationContext)
                webRtcManager.onSendMessage = { wsMessageChannel.trySend(it) }
                NotificationMirrorService.instance?.onNotificationPosted = { wsMessageChannel.trySend(it.toString()) }

                val senderJob = launch {
                    for (msg in wsMessageChannel) {
                        send(Frame.Text(msg))
                    }
                }

                for (frame in incoming) {
                    if (frame is Frame.Text) {
                        val text = frame.readText()
                        val json = JSONObject(text)

                        when (json.optString("type")) {
                            "offer" -> webRtcManager.handleRemoteOffer(json.getString("sdp"))
                            "candidate" -> webRtcManager.handleRemoteIceCandidate(
                                json.getString("sdpMid"), json.getInt("sdpMLineIndex"), json.getString("candidate")
                            )
                            "ping" -> send(Frame.Text("{\"type\":\"pong\",\"timestamp\":${json.optLong("timestamp")}}"))
                        }

                        when (json.optString("action")) {
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
                        }
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
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}