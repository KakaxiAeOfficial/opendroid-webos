package com.example.localutility
import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.util.Log

import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.os.SystemClock
import java.io.BufferedReader
import java.io.FileReader

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ContentProviderOperation
import java.util.ArrayList
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
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
import android.content.ContentUris
import android.util.Size
import android.provider.MediaStore
import android.telephony.SmsManager
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ThumbnailUtils
import android.media.MediaMetadataRetriever
import android.media.RingtoneManager
import android.app.WallpaperManager
import java.io.ByteArrayOutputStream
import android.provider.Settings
import android.app.NotificationManager
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.app.usage.UsageStats
import android.app.usage.UsageEvents
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone


data class GeofenceZone(
val id: String,
val name: String,
val lat: Double,
val lng: Double,
val radiusMeters: Float
)

class TelephonyAndLocationManager(private val context: Context) {var onLocationUpdated: ((JSONObject) -> Unit)? = null

var onGeofenceEvent: ((JSONObject) -> Unit)? = null

private var locationManager: LocationManager? = null

// Safe-Zones & Location History State

private val prefs: SharedPreferences = context.getSharedPreferences("opendroid_geofences", Context.MODE_PRIVATE)

private val activeGeofences = mutableListOf<GeofenceZone>()

private val insideGeofenceIds = mutableSetOf<String>()

private val locationHistory = mutableListOf<JSONObject>()

private val maxHistoryPoints = 150

init {

    loadSavedGeofences()

    initLocationListener()

}

private fun loadSavedGeofences() {

    try {

        val jsonStr = prefs.getString("geofences_list", "[]") ?: "[]"

        val array = JSONArray(jsonStr)

        activeGeofences.clear()

        for (i in 0 until array.length()) {

            val obj = array.getJSONObject(i)

            activeGeofences.add(

                GeofenceZone(

                    id = obj.optString("id", "geo_$i"),

                    name = obj.optString("name", "Safe Zone"),

                    lat = obj.getDouble("lat"),

                    lng = obj.getDouble("lng"),

                    radiusMeters = obj.optDouble("radius", 500.0).toFloat()

                )

            )

        }

    } catch (e: Exception) {

        e.printStackTrace()

    }

}

private fun saveGeofencesToPrefs() {

    try {

        val array = JSONArray()

        for (zone in activeGeofences) {

            val obj = JSONObject().apply {

                put("id", zone.id)

                put("name", zone.name)

                put("lat", zone.lat)

                put("lng", zone.lng)

                put("radius", zone.radiusMeters)

            }

            array.put(obj)

        }

        prefs.edit().putString("geofences_list", array.toString()).apply()

    } catch (e: Exception) {

        e.printStackTrace()

    }

}

fun setGeofences(jsonArray: JSONArray) {

    activeGeofences.clear()

    for (i in 0 until jsonArray.length()) {

        val obj = jsonArray.getJSONObject(i)

        activeGeofences.add(

            GeofenceZone(

                id = obj.optString("id", "geo_${System.currentTimeMillis()}_$i"),

                name = obj.optString("name", "Zone ${i + 1}"),

                lat = obj.getDouble("lat"),

                lng = obj.getDouble("lng"),

                radiusMeters = obj.optDouble("radius", 500.0).toFloat()

            )

        )

    }

    saveGeofencesToPrefs()

}

fun addGeofence(id: String, name: String, lat: Double, lng: Double, radius: Float): JSONObject {

    val cleanId = if (id.isNotEmpty()) id else "geo_${System.currentTimeMillis()}"

    activeGeofences.removeAll { it.id == cleanId }

    val newZone = GeofenceZone(cleanId, name, lat, lng, radius)

    activeGeofences.add(newZone)

    saveGeofencesToPrefs()

    return JSONObject().apply {

        put("id", newZone.id)

        put("name", newZone.name)

        put("lat", newZone.lat)

        put("lng", newZone.lng)

        put("radius", newZone.radiusMeters)

    }

}

fun removeGeofence(id: String): Boolean {

    val removed = activeGeofences.removeAll { it.id == id }

    insideGeofenceIds.remove(id)

    if (removed) saveGeofencesToPrefs()

    return removed

}

fun getGeofences(): JSONArray {

    val array = JSONArray()

    for (zone in activeGeofences) {

        val obj = JSONObject().apply {

            put("id", zone.id)

            put("name", zone.name)

            put("lat", zone.lat)

            put("lng", zone.lng)

            put("radius", zone.radiusMeters)

            put("isInside", insideGeofenceIds.contains(zone.id))

        }

        array.put(obj)

    }

    return array

}

fun getLocationHistory(): JSONArray {

    val array = JSONArray()

    synchronized(locationHistory) {

        for (item in locationHistory) {

            array.put(item)

        }

    }

    return array

}

private fun checkGeofenceTransitions(location: Location) {

    val currentLoc = Location("temp").apply {

        latitude = location.latitude

        longitude = location.longitude

    }

    for (zone in activeGeofences) {

        val zoneLoc = Location("zone").apply {

            latitude = zone.lat

            longitude = zone.lng

        }

        val distance = currentLoc.distanceTo(zoneLoc)

        val isInsideNow = distance <= zone.radiusMeters

        val wasInside = insideGeofenceIds.contains(zone.id)

        if (isInsideNow && !wasInside) {

            // Entered Zone

            insideGeofenceIds.add(zone.id)

            val alert = JSONObject().apply {

                put("type", "GEOFENCE_ALERT")

                put("event", "ENTER")

                put("geofenceId", zone.id)

                put("geofenceName", zone.name)

                put("lat", location.latitude)

                put("lng", location.longitude)

                put("distance", distance)

                put("timestamp", location.time)

            }

            onGeofenceEvent?.invoke(alert)

        } else if (!isInsideNow && wasInside) {

            // Exited Zone

            insideGeofenceIds.remove(zone.id)

            val alert = JSONObject().apply {

                put("type", "GEOFENCE_ALERT")

                put("event", "EXIT")

                put("geofenceId", zone.id)

                put("geofenceName", zone.name)

                put("lat", location.latitude)

                put("lng", location.longitude)

                put("distance", distance)

                put("timestamp", location.time)

            }

            onGeofenceEvent?.invoke(alert)

        }

    }

}

@SuppressLint("MissingPermission")

private fun initLocationListener() {

    try {

        locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

        val listener = object : LocationListener {

            override fun onLocationChanged(location: Location) {

                val pointJson = JSONObject().apply {

                    put("type", "LOCATION")

                    put("lat", location.latitude)

                    put("lng", location.longitude)

                    put("accuracy", location.accuracy)

                    put("timestamp", location.time)

                }

                // Append to sliding location trail history

                synchronized(locationHistory) {

                    locationHistory.add(pointJson)

                    if (locationHistory.size > maxHistoryPoints) {

                        locationHistory.removeAt(0)

                    }

                }

                // Check enter/exit transitions

                checkGeofenceTransitions(location)

                // Dispatch location update to listeners

                onLocationUpdated?.invoke(pointJson)

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

        val pm = context.packageManager

        val launchIntent = pm.getLaunchIntentForPackage(packageName)

        if (launchIntent != null) {

            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)

            val targetContext = RemoteInputService.instance ?: context

            targetContext.startActivity(launchIntent)

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

            data = Uri.fromParts("package", packageName, null)

            putExtra(Intent.EXTRA_RETURN_RESULT, true)

            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)

        }

        val targetContext = RemoteInputService.instance ?: context

        targetContext.startActivity(intent)

        true

    } catch (e: Exception) {

        e.printStackTrace()

        try {

            val settingsIntent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {

                data = Uri.fromParts("package", packageName, null)

                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            }

            val targetContext = RemoteInputService.instance ?: context

            targetContext.startActivity(settingsIntent)

            true

        } catch (e2: Exception) {

            e2.printStackTrace()

            false

        }

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

                        put("name", it.getString(nameIdx) ?: File(path).name)

                        put("duration", durationFormatted)

                        put("path", path)

                        put("size", it.getLong(sizeIdx))

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

            "${MediaStore.Video.Media.DATE_ADDED} DESC"

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

                    val track = JSONObject().apply {

                        put("title", it.getString(titleIdx) ?: "Unknown Video")

                        put("name", it.getString(nameIdx) ?: File(path).name)

                        put("duration", durationFormatted)

                        put("path", path)

                        put("size", it.getLong(sizeIdx))

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

// --- Target 2: Chunked Download ---

fun readFileChunk(filePath: String, offset: Long, chunkSize: Int = 48 * 1024): JSONObject {

    val json = JSONObject()

    val file = File(filePath)

    if (!file.exists() || !file.canRead()

) {


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

        val mode = "rw"

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

        list.sortBy { !it.isDirectory }

        for (f in list) {

            if (f.name.startsWith(".")) continue

            val fileObj = JSONObject().apply {

                put("name", f.name)

                put("path", f.absolutePath)

                put("isDirectory", f.isDirectory)

                put("size", if (f.isFile) f.length() else 0L)

                put("lastModified", f.lastModified())

            }

            filesArray.put(fileObj)

        }

    }

    result.put("files", filesArray)

    return result

}

// --- Phase 4: Advanced File & Directory Operations ---

fun deleteFileOrFolder(path: String): Boolean {

    return try {

        val target = File(path)

        if (!target.exists()) return false

        if (target.isDirectory) {

            target.deleteRecursively()

        } else {

            target.delete()

        }

    } catch (e: Exception) {

        e.printStackTrace()

        false

    }

}

fun renameFileOrFolder(oldPath: String, newName: String): Boolean {

    return try {

        val target = File(oldPath)

        if (!target.exists()) return false

        val dest = File(target.parentFile, newName)

        target.renameTo(dest)

    } catch (e: Exception) {

        e.printStackTrace()

        false

    }

}

fun createFolder(parentPath: String, folderName: String): Boolean {

    return try {

        val parent = if (parentPath.isEmpty()) Environment.getExternalStorageDirectory() else File(parentPath)

        val newDir = File(parent, folderName)

        if (newDir.exists()) return false

        newDir.mkdirs()

    } catch (e: Exception) {

        e.printStackTrace()

        false

    }

}


    fun copyFileOrFolder(sourcePath: String, destDirPath: String): Boolean {
        return try {
            val src = File(sourcePath)
            if (!src.exists()) return false
            val destDir = if (destDirPath.isEmpty()) Environment.getExternalStorageDirectory() else File(destDirPath)
            if (!destDir.exists()) destDir.mkdirs()
            val target = File(destDir, src.name)
            copyRecursive(src, target)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun copyRecursive(src: File, dst: File) {
        if (src.isDirectory) {
            if (!dst.exists()) dst.mkdirs()
            src.listFiles()?.forEach { child ->
                copyRecursive(child, File(dst, child.name))
            }
        } else {
            FileInputStream(src).use { input ->
                FileOutputStream(dst).use { output ->
                    input.copyTo(output, bufferSize = 64 * 1024)
                }
            }
        }
    }

    fun moveFileOrFolder(sourcePath: String, destDirPath: String): Boolean {
        return try {
            val src = File(sourcePath)
            if (!src.exists()) return false
            val destDir = if (destDirPath.isEmpty()) Environment.getExternalStorageDirectory() else File(destDirPath)
            if (!destDir.exists()) destDir.mkdirs()
            val target = File(destDir, src.name)
            if (src.renameTo(target)) {
                true
            } else {
                copyRecursive(src, target)
                deleteFileOrFolder(src.absolutePath)
                true
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun getFileOrFolderDetails(path: String): JSONObject {
        val json = JSONObject()
        try {
            val file = File(path)
            if (!file.exists()) {
                json.put("error", "File not found")
                return json
            }
            json.put("name", file.name)
            json.put("path", file.absolutePath)
            json.put("isDirectory", file.isDirectory)
            json.put("lastModified", file.lastModified())
            json.put("canRead", file.canRead())
            json.put("canWrite", file.canWrite())
            if (file.isDirectory) {
                var totalBytes = 0L
                var fileCount = 0
                var dirCount = 0
                fun walk(d: File) {
                    d.listFiles()?.forEach { f ->
                        if (f.isDirectory) {
                            dirCount++
                            walk(f)
                        } else {
                            fileCount++
                            totalBytes += f.length()
                        }
                    }
                }
                walk(file)
                json.put("size", totalBytes)
                json.put("fileCount", fileCount)
                json.put("dirCount", dirCount)
            } else {
                json.put("size", file.length())
            }
        } catch (e: Exception) {
            json.put("error", e.message ?: "Unknown error")
        }
        return json
    }

    fun batchDelete(paths: List<String>): JSONObject {
        val result = JSONObject()
        var successCount = 0
        var failCount = 0
        for (p in paths) {
            if (deleteFileOrFolder(p)) successCount++ else failCount++
        }
        result.put("successCount", successCount)
        result.put("failCount", failCount)
        result.put("total", paths.size)
        return result
    }

fun createZipArchive(paths: List<String>): String? {

    return try {

        val cacheDir = context.cacheDir

        val zipFile = File(cacheDir, "OpenDroid_Archive_${System.currentTimeMillis()}.zip")

        java.util.zip.ZipOutputStream(FileOutputStream(zipFile)).use { zos ->

            for (path in paths) {

                val file = File(path)

                if (file.exists()) {

                    addFileToZip(file, file.name, zos)

                }

            }

        }

        zipFile.absolutePath

    } catch (e: Exception) {

        e.printStackTrace()

        null

    }

}

private fun addFileToZip(file: File, baseName: String, zos: java.util.zip.ZipOutputStream) {

    if (file.isDirectory) {

        val children = file.listFiles() ?: return

        for (child in children) {

            addFileToZip(child, "$baseName/${child.name}", zos)

        }

    } else {

        val entry = java.util.zip.ZipEntry(baseName)

        zos.putNextEntry(entry)

        FileInputStream(file).use { fis ->

            val buffer = ByteArray(8192)

            var read: Int

            while (fis.read(buffer).also { read = it } != -1) {

                zos.write(buffer, 0, read)

            }

        }

        zos.closeEntry()

    }

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

                    val obj = JSONObject().apply {

                        put("name", it.getString(nameCol) ?: File(path).name)

                        put("path", path)

                        put("date", it.getLong(dateCol))

                        put("size", it.getLong(sizeCol))

                    }

                    array.put(obj)

                    count++

                }

            }

        }

    } catch (e: Exception) { e.printStackTrace() }

    return array

}

fun getLocation(): JSONObject {

    val json = JSONObject().apply { put("type", "LOCATION") }

    try {

        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

        val loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)

            ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)

            ?: lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)

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

            val addressCol = it.getColumnIndex("address")

            val bodyCol = it.getColumnIndex("body")

            val dateCol = it.getColumnIndex("date")

            val typeCol = it.getColumnIndex("type")

            while (it.moveToNext()) {

                val obj = JSONObject().apply {

                    put("address", if (addressCol != -1) it.getString(addressCol) else "Unknown")

                    put("body", if (bodyCol != -1) it.getString(bodyCol) else "")

                    put("date", if (dateCol != -1) it.getLong(dateCol) else 0L)

                    put("type", if (typeCol != -1) it.getInt(typeCol) else 1)

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

        val parts = smsManager.divideMessage(message)

        if (parts.size > 1) {

            smsManager.sendMultipartTextMessage(to, null, parts, null, null)

        } else {

            smsManager.sendTextMessage(to, null, message, null, null)

        }

        true

    } catch (e: Exception) { false }

}

    // --- Phase 9: High-Speed Paginated SMS Conversations & Threads Engine ---
    private val contactNameCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun resolveContactName(phoneNumber: String): String {
        if (phoneNumber.isBlank() || phoneNumber == "Unknown") return phoneNumber
        contactNameCache[phoneNumber]?.let { return it }

        var resolvedName = phoneNumber
        try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(phoneNumber)
            )
            val cursor = context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null,
                null,
                null
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIdx = it.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
                    if (nameIdx != -1) {
                        val name = it.getString(nameIdx)
                        if (!name.isNullOrBlank()) {
                            resolvedName = name
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        contactNameCache[phoneNumber] = resolvedName
        return resolvedName
    }

    fun getSmsThreadsPaged(offset: Int = 0, limit: Int = 25): JSONObject {
        val result = JSONObject()
        val array = JSONArray()
        try {
            val uri = Uri.parse("content://sms")
            val projection = arrayOf(
                "_id",
                "thread_id",
                "address",
                "body",
                "date",
                "type",
                "read"
            )
            val cursor = context.contentResolver.query(
                uri,
                projection,
                null,
                null,
                "date DESC"
            )

            cursor?.use {
                val threadIdIdx = it.getColumnIndex("thread_id")
                val addrIdx = it.getColumnIndex("address")
                val bodyIdx = it.getColumnIndex("body")
                val dateIdx = it.getColumnIndex("date")
                val typeIdx = it.getColumnIndex("type")
                val readIdx = it.getColumnIndex("read")

                class ThreadSummary(
                    val threadId: Long,
                    val address: String,
                    val snippet: String,
                    val date: Long,
                    val type: Int,
                    var unreadCount: Int = 0,
                    var messageCount: Int = 0
                )

                val threadMap = LinkedHashMap<String, ThreadSummary>()

                while (it.moveToNext()) {
                    val tId = if (threadIdIdx != -1) it.getLong(threadIdIdx) else 0L
                    val rawAddr = if (addrIdx != -1) it.getString(addrIdx) ?: "Unknown" else "Unknown"
                    val body = if (bodyIdx != -1) it.getString(bodyIdx) ?: "" else ""
                    val date = if (dateIdx != -1) it.getLong(dateIdx) else 0L
                    val type = if (typeIdx != -1) it.getInt(typeIdx) else 1
                    val read = if (readIdx != -1) it.getInt(readIdx) else 1

                    val key = if (tId > 0) "tid_$tId" else "addr_$rawAddr"
                    val existing = threadMap[key]
                    if (existing == null) {
                        val summary = ThreadSummary(
                            threadId = tId,
                            address = rawAddr,
                            snippet = body,
                            date = date,
                            type = type,
                            unreadCount = if (read == 0 && type == 1) 1 else 0,
                            messageCount = 1
                        )
                        threadMap[key] = summary
                    } else {
                        existing.messageCount++
                        if (read == 0 && type == 1) {
                            existing.unreadCount++
                        }
                    }
                }

                val allThreads = threadMap.values.toList()
                val totalCount = allThreads.size
                val startIndex = Math.min(offset, totalCount)
                val endIndex = Math.min(startIndex + limit, totalCount)

                for (i in startIndex until endIndex) {
                    val t = allThreads[i]
                    val obj = JSONObject().apply {
                        put("threadId", t.threadId)
                        put("address", t.address)
                        put("contactName", resolveContactName(t.address))
                        put("snippet", t.snippet)
                        put("date", t.date)
                        put("type", t.type)
                        put("unreadCount", t.unreadCount)
                        put("messageCount", t.messageCount)
                    }
                    array.put(obj)
                }

                result.put("threads", array)
                result.put("offset", offset)
                result.put("limit", limit)
                result.put("total", totalCount)
                result.put("hasMore", endIndex < totalCount)
                return result
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        result.put("threads", array)
        result.put("offset", offset)
        result.put("limit", limit)
        result.put("total", 0)
        result.put("hasMore", false)
        return result
    }

    fun getThreadMessagesPaged(threadId: Long, address: String, offset: Int = 0, limit: Int = 50): JSONObject {
        val result = JSONObject()
        val array = JSONArray()
        var totalMessages = 0
        try {
            val uri = Uri.parse("content://sms")
            val projection = arrayOf(
                "_id",
                "thread_id",
                "address",
                "body",
                "date",
                "type",
                "read"
            )

            val selection: String?
            val selectionArgs: Array<String>?
            if (threadId > 0) {
                selection = "thread_id = ?"
                selectionArgs = arrayOf(threadId.toString())
            } else if (address.isNotBlank()) {
                selection = "address = ?"
                selectionArgs = arrayOf(address)
            } else {
                selection = null
                selectionArgs = null
            }

            val cursor = context.contentResolver.query(
                uri,
                projection,
                selection,
                selectionArgs,
                "date DESC"
            )

            cursor?.use {
                totalMessages = it.count
                if (offset < totalMessages) {
                    it.moveToPosition(offset - 1)
                    val idCol = it.getColumnIndex("_id")
                    val addrCol = it.getColumnIndex("address")
                    val bodyCol = it.getColumnIndex("body")
                    val dateCol = it.getColumnIndex("date")
                    val typeCol = it.getColumnIndex("type")
                    val readCol = it.getColumnIndex("read")

                    var count = 0
                    val pageList = mutableListOf<JSONObject>()
                    while (it.moveToNext() && (limit <= 0 || count < limit)) {
                        count++
                        val msgObj = JSONObject().apply {
                            put("id", if (idCol != -1) it.getLong(idCol) else 0L)
                            put("address", if (addrCol != -1) it.getString(addrCol) ?: address else address)
                            put("body", if (bodyCol != -1) it.getString(bodyCol) ?: "" else "")
                            put("date", if (dateCol != -1) it.getLong(dateCol) else 0L)
                            put("type", if (typeCol != -1) it.getInt(typeCol) else 1)
                            put("read", if (readCol != -1) it.getInt(readCol) else 1)
                        }
                        pageList.add(msgObj)
                    }

                    // Reverse so chronological ascending in chat
                    pageList.reverse()
                    pageList.forEach { obj -> array.put(obj) }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        result.put("threadId", threadId)
        val resolvedAddress = if (address.isNotBlank()) address else "Unknown"
        result.put("address", resolvedAddress)
        result.put("contactName", resolveContactName(resolvedAddress))
        result.put("messages", array)
        result.put("offset", offset)
        result.put("limit", limit)
        result.put("total", totalMessages)
        result.put("hasMore", (offset + array.length()) < totalMessages)
        return result
    }

    fun deleteSmsThread(threadId: Long, address: String): Boolean {
        return try {
            if (threadId > 0) {
                val uri = Uri.parse("content://sms/conversations/$threadId")
                context.contentResolver.delete(uri, null, null) > 0
            } else if (address.isNotBlank()) {
                val uri = Uri.parse("content://sms")
                context.contentResolver.delete(uri, "address = ?", arrayOf(address)) > 0
            } else false
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }


fun getContactsPaged(offset: Int = 0, limit: Int = 100): JSONObject {
    val result = JSONObject()
    val array = JSONArray()
    var totalCount = 0
    try {
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE,
            ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI
        )
        val cursor = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            null, null,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} COLLATE NOCASE ASC"
        )
        cursor?.use {
            totalCount = it.count
            if (offset < totalCount) {
                it.moveToPosition(offset - 1)
                val idIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                val nameIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val typeIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.TYPE)
                val photoIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI)
                
                val pagedIds = mutableListOf<Long>()
                val tempItems = mutableListOf<JSONObject>()
                var count = 0
                while (it.moveToNext() && (limit <= 0 || count < limit)) {
                    count++
                    val contactId = if (idIdx != -1) it.getLong(idIdx) else 0L
                    pagedIds.add(contactId)
                    val phoneType = when (if (typeIdx != -1) it.getInt(typeIdx) else 0) {
                        ContactsContract.CommonDataKinds.Phone.TYPE_HOME -> "Home"
                        ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE -> "Mobile"
                        ContactsContract.CommonDataKinds.Phone.TYPE_WORK -> "Work"
                        else -> "Mobile"
                    }
                    val obj = JSONObject().apply {
                        put("id", contactId)
                        put("name", if (nameIdx != -1) it.getString(nameIdx) ?: "Unknown" else "Unknown")
                        put("number", if (numIdx != -1) it.getString(numIdx) ?: "Unknown" else "Unknown")
                        put("type", phoneType)
                        put("photo", if (photoIdx != -1) it.getString(photoIdx) ?: "" else "")
                        put("email", "")
                    }
                    tempItems.add(obj)
                }

                // Batch fetch emails for paged contacts only (zero quota waste)
                if (pagedIds.isNotEmpty()) {
                    try {
                        val inClause = pagedIds.joinToString(",")
                        val emailCursor = context.contentResolver.query(
                            ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                            arrayOf(ContactsContract.CommonDataKinds.Email.CONTACT_ID, ContactsContract.CommonDataKinds.Email.DATA),
                            "${ContactsContract.CommonDataKinds.Email.CONTACT_ID} IN ($inClause)",
                            null, null
                        )
                        val emailMap = mutableMapOf<Long, String>()
                        emailCursor?.use { ec ->
                            val cIdIdx = ec.getColumnIndex(ContactsContract.CommonDataKinds.Email.CONTACT_ID)
                            val dataIdx = ec.getColumnIndex(ContactsContract.CommonDataKinds.Email.DATA)
                            while (ec.moveToNext()) {
                                val cId = ec.getLong(cIdIdx)
                                val em = ec.getString(dataIdx) ?: ""
                                if (em.isNotEmpty() && !emailMap.containsKey(cId)) {
                                    emailMap[cId] = em
                                }
                            }
                        }
                        for (item in tempItems) {
                            val cId = item.optLong("id", 0L)
                            if (emailMap.containsKey(cId)) {
                                item.put("email", emailMap[cId] ?: "")
                            }
                            array.put(item)
                        }
                    } catch (_: Exception) {
                        for (item in tempItems) array.put(item)
                    }
                }
            }
        }
    } catch (e: Exception) { e.printStackTrace() }

    result.put("data", array)
    result.put("offset", offset)
    result.put("limit", limit)
    result.put("total", totalCount)
    result.put("hasMore", (offset + array.length()) < totalCount)
    return result
}

fun getContacts(): JSONArray {
    return getContactsPaged(0, 5000).getJSONArray("data")
}

fun makeCall(number: String) {
    try {
        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val targetContext = RemoteInputService.instance ?: context
        targetContext.startActivity(intent)
    } catch (e: Exception) { e.printStackTrace() }
}

fun getCallLogsPaged(offset: Int = 0, limit: Int = 100): JSONObject {
    val result = JSONObject()
    val array = JSONArray()
    var totalCount = 0
    try {
        val cursor = context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(
                CallLog.Calls._ID,
                CallLog.Calls.NUMBER,
                CallLog.Calls.CACHED_NAME,
                CallLog.Calls.TYPE,
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION
            ),
            null,
            null,
            "${CallLog.Calls.DATE} DESC"
        )
        cursor?.use {
            val totalInDb = it.count
            totalCount = Math.min(totalInDb, 1000)
            if (offset < totalCount) {
                it.moveToPosition(offset - 1)
                val idIdx = it.getColumnIndex(CallLog.Calls._ID)
                val numIdx = it.getColumnIndex(CallLog.Calls.NUMBER)
                val nameIdx = it.getColumnIndex(CallLog.Calls.CACHED_NAME)
                val typeIdx = it.getColumnIndex(CallLog.Calls.TYPE)
                val dateIdx = it.getColumnIndex(CallLog.Calls.DATE)
                val durIdx = it.getColumnIndex(CallLog.Calls.DURATION)
                var count = 0
                while (it.moveToNext() && (limit <= 0 || count < limit) && (offset + count) < 1000) {
                    count++
                    val typeInt = if (typeIdx != -1) it.getInt(typeIdx) else 0
                    val typeStr = when (typeInt) {
                        CallLog.Calls.INCOMING_TYPE -> "Incoming"
                        CallLog.Calls.OUTGOING_TYPE -> "Outgoing"
                        CallLog.Calls.MISSED_TYPE -> "Missed"
                        CallLog.Calls.REJECTED_TYPE -> "Missed"
                        else -> "Other"
                    }
                    val durSec = if (durIdx != -1) it.getLong(durIdx) else 0L
                    val durFormatted = String.format("%02d:%02d", durSec / 60, durSec % 60)
                    val rawNum = if (numIdx != -1) it.getString(numIdx) ?: "Unknown" else "Unknown"
                    val rawName = if (nameIdx != -1 && it.getString(nameIdx) != null) it.getString(nameIdx) else rawNum
                    val obj = JSONObject().apply {
                        put("id", if (idIdx != -1) it.getLong(idIdx) else 0L)
                        put("number", rawNum)
                        put("name", rawName)
                        put("type", typeStr)
                        put("date", if (dateIdx != -1) it.getLong(dateIdx) else 0L)
                        put("duration", durFormatted)
                    }
                    array.put(obj)
                }
            }
        }
    } catch (e: Exception) { e.printStackTrace() }

    result.put("data", array)
    result.put("offset", offset)
    result.put("limit", limit)
    result.put("total", totalCount)
    result.put("hasMore", (offset + array.length()) < totalCount)
    return result
}

fun getCallLogs(limit: Int = 1000): JSONArray {
    return getCallLogsPaged(0, limit).getJSONArray("data")
}

fun deleteCallLog(callId: Long?, number: String?, date: Long?): Boolean {
    return try {
        var deletedCount = 0

        // 1. Primary: Delete by ID using direct ContentResolver where clause
        if (callId != null && callId > 0) {
            deletedCount = context.contentResolver.delete(
                CallLog.Calls.CONTENT_URI,
                "${CallLog.Calls._ID} = ?",
                arrayOf(callId.toString())
            )
            // Fallback to Uri with appended path if 0
            if (deletedCount == 0) {
                try {
                    val itemUri = Uri.withAppendedPath(CallLog.Calls.CONTENT_URI, callId.toString())
                    deletedCount = context.contentResolver.delete(itemUri, null, null)
                } catch (e: Exception) {}
            }
        }

        // 2. Secondary: Delete by Number AND Date timestamp
        if (deletedCount == 0 && !number.isNullOrEmpty() && date != null && date > 0) {
            deletedCount = context.contentResolver.delete(
                CallLog.Calls.CONTENT_URI,
                "${CallLog.Calls.NUMBER} = ? AND ${CallLog.Calls.DATE} = ?",
                arrayOf(number, date.toString())
            )
        }

        // 3. Tertiary: Delete by Number only
        if (deletedCount == 0 && !number.isNullOrEmpty()) {
            deletedCount = context.contentResolver.delete(
                CallLog.Calls.CONTENT_URI,
                "${CallLog.Calls.NUMBER} = ?",
                arrayOf(number)
            )
        }
        deletedCount > 0
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }
}

fun clearCallLogs(): Boolean {
    return try {
        context.contentResolver.delete(CallLog.Calls.CONTENT_URI, null, null) >= 0
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }
}

fun deleteContact(contactId: Long?, number: String?): Boolean {
    return try {
        if (contactId != null && contactId > 0) {
            val contactUri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_URI, contactId.toString())
            context.contentResolver.delete(contactUri, null, null) > 0
        } else if (!number.isNullOrEmpty()) {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            val cursor = context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup._ID), null, null, null)
            var deleted = false
            cursor?.use {
                while (it.moveToNext()) {
                    val id = it.getLong(0)
                    val cUri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_URI, id.toString())
                    if (context.contentResolver.delete(cUri, null, null) > 0) deleted = true
                }
            }
            deleted
        } else false
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }
}

fun addContact(name: String, number: String, email: String? = null): Boolean {
    return try {
        val ops = ArrayList<ContentProviderOperation>()
        ops.add(ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
            .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
            .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
            .build())

        ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
            .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
            .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
            .build())

        ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
            .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
            .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, number)
            .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
            .build())

        if (!email.isNullOrEmpty()) {
            ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Email.DATA, email)
                .withValue(ContactsContract.CommonDataKinds.Email.TYPE, ContactsContract.CommonDataKinds.Email.TYPE_HOME)
                .build())
        }

        context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
        true
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }
}

// Phase 13: Full Android ContentProvider Contact Update Engine
fun updateContact(contactId: Long, name: String, number: String, email: String? = null): Boolean {
    return try {
        val ops = ArrayList<ContentProviderOperation>()

        // 1. Update Display Name in StructuredName row
        ops.add(ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
            .withSelection(
                "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
                arrayOf(contactId.toString(), ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
            )
            .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
            .build())

        // 2. Update Phone Number in Phone row
        ops.add(ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
            .withSelection(
                "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
                arrayOf(contactId.toString(), ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
            )
            .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, number)
            .build())

        // 3. Update or Insert Email row
        if (!email.isNullOrEmpty()) {
            var emailExists = false
            try {
                val emailCheck = context.contentResolver.query(
                    ContactsContract.Data.CONTENT_URI,
                    arrayOf(ContactsContract.Data._ID),
                    "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
                    arrayOf(contactId.toString(), ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE),
                    null
                )
                emailExists = (emailCheck?.count ?: 0) > 0
                emailCheck?.close()
            } catch (_: Exception) {}

            if (emailExists) {
                ops.add(ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
                    .withSelection(
                        "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
                        arrayOf(contactId.toString(), ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                    )
                    .withValue(ContactsContract.CommonDataKinds.Email.DATA, email)
                    .build())
            } else {
                // Find raw contact ID to insert new email record
                var rawId: Long? = null
                try {
                    val rawCursor = context.contentResolver.query(
                        ContactsContract.RawContacts.CONTENT_URI,
                        arrayOf(ContactsContract.RawContacts._ID),
                        "${ContactsContract.RawContacts.CONTACT_ID} = ?",
                        arrayOf(contactId.toString()),
                        null
                    )
                    rawCursor?.use { if (it.moveToFirst()) rawId = it.getLong(0) }
                } catch (_: Exception) {}

                if (rawId != null) {
                    ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawId)
                        .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                        .withValue(ContactsContract.CommonDataKinds.Email.DATA, email)
                        .withValue(ContactsContract.CommonDataKinds.Email.TYPE, ContactsContract.CommonDataKinds.Email.TYPE_HOME)
                        .build())
                }
            }
        }

        context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
        true
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }
}

// Phase 13: Batch / Bulk Delete Contacts Engine
fun bulkDeleteContacts(ids: List<Long>): Int {
    var deletedCount = 0
    for (id in ids) {
        if (id > 0 && deleteContact(id, null)) {
            deletedCount++
        }
    }
    return deletedCount
}

// Phase 13: OpenDroid-Grade VCF (vCard 3.0) Export Engine
fun exportContactsToVcf(): String {
    val sb = StringBuilder()
    try {
        val cursor = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.TYPE
            ),
            null, null,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} COLLATE NOCASE ASC"
        )

        // Email mapping for all contacts
        val emailMap = mutableMapOf<Long, MutableList<String>>()
        try {
            val emailCursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Email.CONTACT_ID, ContactsContract.CommonDataKinds.Email.DATA),
                null, null, null
            )
            emailCursor?.use { ec ->
                val cIdIdx = ec.getColumnIndex(ContactsContract.CommonDataKinds.Email.CONTACT_ID)
                val dataIdx = ec.getColumnIndex(ContactsContract.CommonDataKinds.Email.DATA)
                while (ec.moveToNext()) {
                    val cId = ec.getLong(cIdIdx)
                    val email = ec.getString(dataIdx) ?: ""
                    if (email.isNotEmpty()) {
                        emailMap.getOrPut(cId) { mutableListOf() }.add(email)
                    }
                }
            }
        } catch (_: Exception) {}

        cursor?.use {
            val idIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
            val nameIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val processedIds = mutableSetOf<Long>()

            while (it.moveToNext()) {
                val id = if (idIdx != -1) it.getLong(idIdx) else 0L
                val name = if (nameIdx != -1) it.getString(nameIdx) ?: "Contact" else "Contact"
                val num = if (numIdx != -1) it.getString(numIdx) ?: "" else ""

                if (!processedIds.contains(id)) {
                    processedIds.add(id)
                    sb.append("BEGIN:VCARD\r\n")
                    sb.append("VERSION:3.0\r\n")
                    sb.append("FN:").append(name).append("\r\n")
                    sb.append("N:;").append(name).append(";;;\r\n")
                    if (num.isNotEmpty()) {
                        sb.append("TEL;TYPE=CELL:").append(num).append("\r\n")
                    }
                    val emails = emailMap[id]
                    emails?.forEach { mail ->
                        sb.append("EMAIL;TYPE=HOME:").append(mail).append("\r\n")
                    }
                    sb.append("END:VCARD\r\n")
                }
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return sb.toString()
}

// Phase 13: OpenDroid-Grade VCF (vCard 3.0) Bulk Import Engine
fun importContactsFromVcf(vcfText: String): Int {
    var importedCount = 0
    try {
        val lines = vcfText.lines()
        var currentName = ""
        var currentNumber = ""
        var currentEmail = ""
        var insideVcard = false

        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.equals("BEGIN:VCARD", ignoreCase = true)) {
                insideVcard = true
                currentName = ""
                currentNumber = ""
                currentEmail = ""
            } else if (line.equals("END:VCARD", ignoreCase = true)) {
                if (insideVcard && (currentName.isNotEmpty() || currentNumber.isNotEmpty())) {
                    val finalName = if (currentName.isNotEmpty()) currentName else currentNumber
                    val success = addContact(finalName, currentNumber, if (currentEmail.isNotEmpty()) currentEmail else null)
                    if (success) importedCount++
                }
                insideVcard = false
            } else if (insideVcard) {
                val upper = line.uppercase()
                when {
                    upper.startsWith("FN:") -> {
                        currentName = line.substring(3).trim()
                    }
                    upper.startsWith("N:") && currentName.isEmpty() -> {
                        val parts = line.substring(2).split(";")
                        val first = parts.getOrNull(1)?.trim() ?: ""
                        val last = parts.getOrNull(0)?.trim() ?: ""
                        currentName = "$first $last".trim()
                    }
                    upper.startsWith("TEL") && line.contains(":") -> {
                        if (currentNumber.isEmpty()) {
                            currentNumber = line.substring(line.indexOf(":") + 1).trim()
                        }
                    }
                    upper.startsWith("EMAIL") && line.contains(":") -> {
                        if (currentEmail.isEmpty()) {
                            currentEmail = line.substring(line.indexOf(":") + 1).trim()
                        }
                    }
                }
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return importedCount
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

        val path = context.filesDir?.absolutePath ?: Environment.getDataDirectory().path

        val stat = StatFs(path)

        val blockSize = stat.blockSizeLong

        val totalBlocks = stat.blockCountLong

        val availableBlocks = stat.availableBlocksLong

        val totalBytes = totalBlocks * blockSize

        val freeBytes = availableBlocks * blockSize

        val usedBytes = (totalBytes - freeBytes).coerceAtLeast(0L)

        val totalGB = String.format(java.util.Locale.US, "%.1f GB", totalBytes / (1024.0 * 1024 * 1024))

        val usedGB = String.format(java.util.Locale.US, "%.1f GB", usedBytes / (1024.0 * 1024 * 1024))

        val freeGB = String.format(java.util.Locale.US, "%.1f GB", freeBytes / (1024.0 * 1024 * 1024))

        val usedPercent = if (totalBytes > 0) ((usedBytes.toDouble() / totalBytes) * 100).toInt() else 0

        json.put("totalBytes", totalBytes)

        json.put("freeBytes", freeBytes)

        json.put("usedBytes", usedBytes)

        json.put("totalGB", totalGB)

        json.put("usedGB", usedGB)

        json.put("freeGB", freeGB)

        json.put("usedPercent", usedPercent)

    } catch (e: Exception) {

        e.printStackTrace()

    }

    return json

}


    // =========================================================================
    // --- Phase 15: Remote Media Gallery & High-Speed Streamer Engine ---
    // =========================================================================

    fun getGalleryPhotos(offset: Int = 0, limit: Int = 100, bucketName: String? = null): JSONObject {
        val result = JSONObject()
        val photosArray = JSONArray()
        val bucketsMap = mutableMapOf<String, Int>()
        var totalCount = 0

        try {
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATA,
                MediaStore.Images.Media.DATE_MODIFIED,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.MIME_TYPE,
                MediaStore.Images.Media.WIDTH,
                MediaStore.Images.Media.HEIGHT,
                MediaStore.Images.Media.BUCKET_DISPLAY_NAME
            )

            val cursor = context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                "${MediaStore.Images.Media.DATE_MODIFIED} DESC"
            )

            cursor?.use {
                val idCol = it.getColumnIndex(MediaStore.Images.Media._ID)
                val nameCol = it.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                val dataCol = it.getColumnIndex(MediaStore.Images.Media.DATA)
                val dateCol = it.getColumnIndex(MediaStore.Images.Media.DATE_MODIFIED)
                val dateAddedCol = it.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)
                val sizeCol = it.getColumnIndex(MediaStore.Images.Media.SIZE)
                val mimeCol = it.getColumnIndex(MediaStore.Images.Media.MIME_TYPE)
                val widthCol = it.getColumnIndex(MediaStore.Images.Media.WIDTH)
                val heightCol = it.getColumnIndex(MediaStore.Images.Media.HEIGHT)
                val bucketCol = it.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)

                var matchedIndex = 0

                while (it.moveToNext()) {
                    val id = if (idCol >= 0) it.getLong(idCol) else 0L
                    if (id <= 0L) continue

                    val path = if (dataCol >= 0) it.getString(dataCol) ?: "" else ""
                    val contentUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id).toString()

                    val rawBucket = if (bucketCol >= 0) it.getString(bucketCol) else null
                    val bucket = if (!rawBucket.isNullOrBlank()) {
                        rawBucket
                    } else if (path.isNotBlank()) {
                        File(path).parentFile?.name ?: "Camera"
                    } else {
                        "Camera"
                    }

                    bucketsMap[bucket] = (bucketsMap[bucket] ?: 0) + 1
                    bucketsMap["All"] = (bucketsMap["All"] ?: 0) + 1

                    val matchesFilter = bucketName.isNullOrEmpty() ||
                            bucketName.equals("All", ignoreCase = true) ||
                            bucket.equals(bucketName, ignoreCase = true)

                    if (matchesFilter) {
                        if (matchedIndex >= offset && photosArray.length() < limit) {
                            val name = if (nameCol >= 0) it.getString(nameCol) else null
                            val finalName = if (!name.isNullOrBlank()) name else if (path.isNotBlank()) File(path).name else "IMG_$id.jpg"

                            val timestampSec = if (dateCol >= 0 && it.getLong(dateCol) > 0) {
                                it.getLong(dateCol)
                            } else if (dateAddedCol >= 0 && it.getLong(dateAddedCol) > 0) {
                                it.getLong(dateAddedCol)
                            } else {
                                System.currentTimeMillis() / 1000L
                            }

                            val obj = JSONObject().apply {
                                put("id", id)
                                put("name", finalName)
                                put("path", if (path.isNotBlank()) path else contentUri)
                                put("uri", contentUri)
                                put("date", timestampSec * 1000L)
                                put("size", if (sizeCol >= 0) it.getLong(sizeCol) else 0L)
                                put("mime", if (mimeCol >= 0) it.getString(mimeCol) ?: "image/jpeg" else "image/jpeg")
                                put("width", if (widthCol >= 0) it.getInt(widthCol) else 0)
                                put("height", if (heightCol >= 0) it.getInt(heightCol) else 0)
                                put("bucket", bucket)
                            }
                            photosArray.put(obj)
                        }
                        matchedIndex++
                    }
                }
                totalCount = matchedIndex
            }

            val bucketsArray = JSONArray()
            val allCount = bucketsMap["All"] ?: 0
            bucketsArray.put(JSONObject().apply {
                put("name", "All")
                put("count", allCount)
            })
            bucketsMap.filterKeys { !it.equals("All", ignoreCase = true) }
                .toList()
                .sortedByDescending { it.second }
                .forEach { (bName, bCount) ->
                    bucketsArray.put(JSONObject().apply {
                        put("name", bName)
                        put("count", bCount)
                    })
                }

            result.put("status", "SUCCESS")
            result.put("photos", photosArray)
            result.put("buckets", bucketsArray)
            result.put("offset", offset)
            result.put("limit", limit)
            result.put("total", totalCount)
            result.put("hasMore", (offset + photosArray.length()) < totalCount)
        } catch (e: Exception) {
            result.put("status", "ERROR")
            result.put("error", e.message ?: "Failed to query photos")
        }
        return result
    }

    fun getPhotoThumbnailById(id: Long, maxDim: Int = 256): String? {
        if (id <= 0L) return null
        return getPhotoThumbnailBase64(id.toString(), maxDim)
    }

    fun getPhotoThumbnailBase64(pathOrUri: String, maxDim: Int = 256): String? {
        return try {
            val bitmap = resolvePhotoBitmap(pathOrUri, maxDim) ?: return null
            val baos = ByteArrayOutputStream()
            val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP
            }
            bitmap.compress(format, 70, baos)
            bitmap.recycle()
            Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            null
        }
    }

    private fun resolvePhotoBitmap(pathOrUri: String, maxDim: Int): Bitmap? {
        if (pathOrUri.isBlank()) return null

        val parsedUri: Uri? = if (pathOrUri.startsWith("content://")) {
            Uri.parse(pathOrUri)
        } else if (pathOrUri.all { it.isDigit() }) {
            ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, pathOrUri.toLong())
        } else null

        // 1. Android 10+ (API 29+) loadThumbnail via ContentResolver
        if (parsedUri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                return context.contentResolver.loadThumbnail(parsedUri, Size(maxDim, maxDim), null)
            } catch (_: Exception) {}
        }

        // 2. Stream decode via ContentResolver
        if (parsedUri != null) {
            try {
                context.contentResolver.openInputStream(parsedUri)?.use { stream ->
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeStream(stream, null, options)
                    var sampleSize = 1
                    val w = options.outWidth
                    val h = options.outHeight
                    if (w > maxDim || h > maxDim) {
                        val halfW = w / 2
                        val halfH = h / 2
                        while ((halfW / sampleSize) >= maxDim && (halfH / sampleSize) >= maxDim) {
                            sampleSize *= 2
                        }
                    }
                    context.contentResolver.openInputStream(parsedUri)?.use { stream2 ->
                        val decodeOpts = BitmapFactory.Options().apply {
                            inSampleSize = sampleSize
                            inPreferredConfig = Bitmap.Config.RGB_565
                        }
                        return BitmapFactory.decodeStream(stream2, null, decodeOpts)
                    }
                }
            } catch (_: Exception) {}
        }

        // 3. Direct filesystem path fallback
        try {
            val file = File(pathOrUri)
            if (file.exists()) {
                val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, boundsOptions)

                var sampleSize = 1
                val origWidth = boundsOptions.outWidth
                val origHeight = boundsOptions.outHeight
                if (origWidth > maxDim || origHeight > maxDim) {
                    val halfWidth = origWidth / 2
                    val halfHeight = origHeight / 2
                    while ((halfWidth / sampleSize) >= maxDim && (halfHeight / sampleSize) >= maxDim) {
                        sampleSize *= 2
                    }
                }

                val decodeOptions = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
                return BitmapFactory.decodeFile(file.absolutePath, decodeOptions)
            }
        } catch (_: Exception) {}

        // 4. Query MediaStore by DATA path to get ContentUri on Scoped Storage
        try {
            val proj = arrayOf(MediaStore.Images.Media._ID)
            val sel = "${MediaStore.Images.Media.DATA} = ?"
            val args = arrayOf(pathOrUri)
            context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj, sel, args, null)?.use { c ->
                if (c.moveToFirst()) {
                    val id = c.getLong(c.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                    val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        try {
                            return context.contentResolver.loadThumbnail(uri, Size(maxDim, maxDim), null)
                        } catch (_: Exception) {}
                    }
                    context.contentResolver.openInputStream(uri)?.use { s ->
                        return BitmapFactory.decodeStream(s)
                    }
                }
            }
        } catch (_: Exception) {}

        return null
    }

    fun setPhotoAsWallpaper(pathOrUri: String): JSONObject {
        val result = JSONObject()
        try {
            val bitmap = resolvePhotoBitmap(pathOrUri, 1920)
            if (bitmap == null) {
                result.put("status", "ERROR")
                result.put("error", "Failed to decode image bitmap")
                return result
            }

            val wallpaperManager = WallpaperManager.getInstance(context)
            wallpaperManager.setBitmap(bitmap)
            bitmap.recycle()

            result.put("status", "SUCCESS")
            result.put("message", "Wallpaper updated successfully")
        } catch (e: Exception) {
            result.put("status", "ERROR")
            result.put("error", e.message ?: "Failed to set wallpaper")
        }
        return result
    }

    fun getGalleryVideos(offset: Int = 0, limit: Int = 50, bucketName: String? = null): JSONObject {
        val result = JSONObject()
        val videosArray = JSONArray()
        val bucketsMap = mutableMapOf<String, Int>()
        var totalCount = 0

        try {
            val projection = arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.TITLE,
                MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.DATA,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.DATE_MODIFIED,
                MediaStore.Video.Media.DATE_ADDED,
                MediaStore.Video.Media.WIDTH,
                MediaStore.Video.Media.HEIGHT,
                MediaStore.Video.Media.BUCKET_DISPLAY_NAME
            )

            val cursor = context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                "${MediaStore.Video.Media.DATE_MODIFIED} DESC"
            )

            cursor?.use {
                val idCol = it.getColumnIndex(MediaStore.Video.Media._ID)
                val nameCol = it.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME)
                val titleCol = it.getColumnIndex(MediaStore.Video.Media.TITLE)
                val durCol = it.getColumnIndex(MediaStore.Video.Media.DURATION)
                val dataCol = it.getColumnIndex(MediaStore.Video.Media.DATA)
                val sizeCol = it.getColumnIndex(MediaStore.Video.Media.SIZE)
                val dateCol = it.getColumnIndex(MediaStore.Video.Media.DATE_MODIFIED)
                val dateAddedCol = it.getColumnIndex(MediaStore.Video.Media.DATE_ADDED)
                val widthCol = it.getColumnIndex(MediaStore.Video.Media.WIDTH)
                val heightCol = it.getColumnIndex(MediaStore.Video.Media.HEIGHT)
                val bucketCol = it.getColumnIndex(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)

                var matchedIndex = 0
                while (it.moveToNext()) {
                    val id = if (idCol >= 0) it.getLong(idCol) else 0L
                    if (id <= 0L) continue

                    val path = if (dataCol >= 0) it.getString(dataCol) ?: "" else ""
                    val contentUri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id).toString()

                    val rawBucket = if (bucketCol >= 0) it.getString(bucketCol) else null
                    val bucket = if (!rawBucket.isNullOrBlank()) {
                        rawBucket
                    } else if (path.isNotBlank()) {
                        File(path).parentFile?.name ?: "Camera"
                    } else {
                        "Camera"
                    }

                    bucketsMap[bucket] = (bucketsMap[bucket] ?: 0) + 1
                    bucketsMap["All"] = (bucketsMap["All"] ?: 0) + 1

                    val matchesFilter = bucketName.isNullOrEmpty() ||
                            bucketName.equals("All", ignoreCase = true) ||
                            bucket.equals(bucketName, ignoreCase = true)

                    if (matchesFilter) {
                        if (matchedIndex >= offset && videosArray.length() < limit) {
                            val durationMs = if (durCol >= 0) it.getLong(durCol) else 0L
                            val name = if (nameCol >= 0) it.getString(nameCol) else null
                            val finalName = if (!name.isNullOrBlank()) name else if (path.isNotBlank()) File(path).name else "VID_$id.mp4"
                            val timestampSec = if (dateCol >= 0 && it.getLong(dateCol) > 0) {
                                it.getLong(dateCol)
                            } else if (dateAddedCol >= 0 && it.getLong(dateAddedCol) > 0) {
                                it.getLong(dateAddedCol)
                            } else {
                                System.currentTimeMillis() / 1000L
                            }

                            val obj = JSONObject().apply {
                                put("id", id)
                                put("name", finalName)
                                put("title", if (titleCol >= 0) it.getString(titleCol) ?: "" else "")
                                put("path", if (path.isNotBlank()) path else contentUri)
                                put("uri", contentUri)
                                put("duration", durationMs)
                                put("durationFormatted", formatDuration(durationMs))
                                put("size", if (sizeCol >= 0) it.getLong(sizeCol) else 0L)
                                put("date", timestampSec * 1000L)
                                put("width", if (widthCol >= 0) it.getInt(widthCol) else 0)
                                put("height", if (heightCol >= 0) it.getInt(heightCol) else 0)
                                put("bucket", bucket)
                            }
                            videosArray.put(obj)
                        }
                        matchedIndex++
                    }
                }
                totalCount = matchedIndex
            }

            val bucketsArray = JSONArray()
            val allCount = bucketsMap["All"] ?: 0
            bucketsArray.put(JSONObject().apply {
                put("name", "All")
                put("count", allCount)
            })
            bucketsMap.filterKeys { !it.equals("All", ignoreCase = true) }
                .toList()
                .sortedByDescending { it.second }
                .forEach { (bName, bCount) ->
                    bucketsArray.put(JSONObject().apply {
                        put("name", bName)
                        put("count", bCount)
                    })
                }

            result.put("status", "SUCCESS")
            result.put("videos", videosArray)
            result.put("buckets", bucketsArray)
            result.put("offset", offset)
            result.put("limit", limit)
            result.put("total", totalCount)
            result.put("hasMore", (offset + videosArray.length()) < totalCount)
        } catch (e: Exception) {
            result.put("status", "ERROR")
            result.put("error", e.message ?: "Failed to query videos")
        }
        return result
    }

    fun getVideoThumbnailById(id: Long, maxDim: Int = 256): String? {
        if (id <= 0L) return null
        return getVideoThumbnailBase64(id.toString(), maxDim)
    }

    fun getVideoThumbnailBase64(pathOrUri: String, maxDim: Int = 256): String? {
        return try {
            val bitmap = resolveVideoBitmap(pathOrUri, maxDim) ?: return null
            val baos = ByteArrayOutputStream()
            val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP
            }
            bitmap.compress(format, 70, baos)
            bitmap.recycle()
            Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            null
        }
    }

    private fun resolveVideoBitmap(pathOrUri: String, maxDim: Int): Bitmap? {
        if (pathOrUri.isBlank()) return null

        val parsedUri: Uri? = if (pathOrUri.startsWith("content://")) {
            Uri.parse(pathOrUri)
        } else if (pathOrUri.all { it.isDigit() }) {
            ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, pathOrUri.toLong())
        } else null

        // 1. Android 10+ (API 29+) loadThumbnail via ContentResolver
        if (parsedUri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                return context.contentResolver.loadThumbnail(parsedUri, Size(maxDim, maxDim * 9 / 16), null)
            } catch (_: Exception) {}
        }

        // 2. MediaMetadataRetriever via URI
        if (parsedUri != null) {
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(context, parsedUri)
                val frame = retriever.getFrameAtTime(1000000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: retriever.frameAtTime
                retriever.release()
                if (frame != null) {
                    val scaled = Bitmap.createScaledBitmap(frame, maxDim, maxDim * 9 / 16, true)
                    frame.recycle()
                    return scaled
                }
            } catch (_: Exception) {}
        }

        // 3. MediaMetadataRetriever via direct path
        try {
            val file = File(pathOrUri)
            if (file.exists()) {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(file.absolutePath)
                val frame = retriever.getFrameAtTime(1000000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: retriever.frameAtTime
                retriever.release()
                if (frame != null) {
                    val scaled = Bitmap.createScaledBitmap(frame, maxDim, maxDim * 9 / 16, true)
                    frame.recycle()
                    return scaled
                }
            }
        } catch (_: Exception) {}

        // 4. Query MediaStore by DATA path to get ID on Scoped Storage
        try {
            val proj = arrayOf(MediaStore.Video.Media._ID)
            val sel = "${MediaStore.Video.Media.DATA} = ?"
            val args = arrayOf(pathOrUri)
            context.contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, proj, sel, args, null)?.use { c ->
                if (c.moveToFirst()) {
                    val id = c.getLong(c.getColumnIndexOrThrow(MediaStore.Video.Media._ID))
                    val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        try {
                            return context.contentResolver.loadThumbnail(uri, Size(maxDim, maxDim * 9 / 16), null)
                        } catch (_: Exception) {}
                    }
                    val retriever = MediaMetadataRetriever()
                    retriever.setDataSource(context, uri)
                    val frame = retriever.getFrameAtTime(1000000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: retriever.frameAtTime
                    retriever.release()
                    if (frame != null) {
                        val scaled = Bitmap.createScaledBitmap(frame, maxDim, maxDim * 9 / 16, true)
                        frame.recycle()
                        return scaled
                    }
                }
            }
        } catch (_: Exception) {}

        return null
    }

    fun getMusicTracksDetailed(offset: Int = 0, limit: Int = 100): JSONObject {
        val result = JSONObject()
        val tracksArray = JSONArray()
        var totalCount = 0

        try {
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.SIZE,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.DATE_MODIFIED
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
                val idCol = it.getColumnIndex(MediaStore.Audio.Media._ID)
                val titleCol = it.getColumnIndex(MediaStore.Audio.Media.TITLE)
                val artistCol = it.getColumnIndex(MediaStore.Audio.Media.ARTIST)
                val albumCol = it.getColumnIndex(MediaStore.Audio.Media.ALBUM)
                val durCol = it.getColumnIndex(MediaStore.Audio.Media.DURATION)
                val dataCol = it.getColumnIndex(MediaStore.Audio.Media.DATA)
                val sizeCol = it.getColumnIndex(MediaStore.Audio.Media.SIZE)
                val nameCol = it.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
                val dateCol = it.getColumnIndex(MediaStore.Audio.Media.DATE_MODIFIED)

                var idx = 0
                while (it.moveToNext()) {
                    val path = if (dataCol >= 0) it.getString(dataCol) else null
                    if (path == null || !File(path).exists()) continue

                    if (idx >= offset && tracksArray.length() < limit) {
                        val durMs = if (durCol >= 0) it.getLong(durCol) else 0L
                        val obj = JSONObject().apply {
                            put("id", if (idCol >= 0) it.getLong(idCol) else 0L)
                            put("title", if (titleCol >= 0) it.getString(titleCol) ?: "Unknown" else "Unknown")
                            put("artist", if (artistCol >= 0) it.getString(artistCol) ?: "Unknown Artist" else "Unknown Artist")
                            put("album", if (albumCol >= 0) it.getString(albumCol) ?: "Unknown Album" else "Unknown Album")
                            put("duration", durMs)
                            put("durationFormatted", formatDuration(durMs))
                            put("path", path)
                            put("size", if (sizeCol >= 0) it.getLong(sizeCol) else File(path).length())
                            put("name", if (nameCol >= 0) it.getString(nameCol) ?: File(path).name else File(path).name)
                            put("date", if (dateCol >= 0) it.getLong(dateCol) * 1000L else File(path).lastModified())
                        }
                        tracksArray.put(obj)
                    }
                    idx++
                }
                totalCount = idx
            }

            result.put("status", "SUCCESS")
            result.put("tracks", tracksArray)
            result.put("offset", offset)
            result.put("limit", limit)
            result.put("total", totalCount)
            result.put("hasMore", (offset + tracksArray.length()) < totalCount)
        } catch (e: Exception) {
            result.put("status", "ERROR")
            result.put("error", e.message ?: "Failed to query music")
        }
        return result
    }

    private fun formatDuration(durationMs: Long): String {
        val totalSec = durationMs / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        return String.format(java.util.Locale.US, "%02d:%02d", min, sec)
    }

    fun getDeviceRingtones(): JSONObject {
        val result = JSONObject()
        val ringtonesArray = JSONArray()
        val notificationsArray = JSONArray()
        val alarmsArray = JSONArray()

        try {
            val ringtoneManager = RingtoneManager(context)

            val currentRingtoneUri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE)?.toString() ?: ""
            val currentNotifUri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_NOTIFICATION)?.toString() ?: ""
            val currentAlarmUri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM)?.toString() ?: ""

            // 1. Phone Ringtones
            ringtoneManager.setType(RingtoneManager.TYPE_RINGTONE)
            ringtoneManager.cursor?.use { cursor ->
                while (cursor.moveToNext()) {
                    val title = cursor.getString(RingtoneManager.TITLE_COLUMN_INDEX)
                    val uri = ringtoneManager.getRingtoneUri(cursor.position).toString()
                    ringtonesArray.put(JSONObject().apply {
                        put("title", title)
                        put("uri", uri)
                        put("isDefault", uri == currentRingtoneUri)
                    })
                }
            }

            // 2. Notifications
            ringtoneManager.setType(RingtoneManager.TYPE_NOTIFICATION)
            ringtoneManager.cursor?.use { cursor ->
                while (cursor.moveToNext()) {
                    val title = cursor.getString(RingtoneManager.TITLE_COLUMN_INDEX)
                    val uri = ringtoneManager.getRingtoneUri(cursor.position).toString()
                    notificationsArray.put(JSONObject().apply {
                        put("title", title)
                        put("uri", uri)
                        put("isDefault", uri == currentNotifUri)
                    })
                }
            }

            // 3. Alarms
            ringtoneManager.setType(RingtoneManager.TYPE_ALARM)
            ringtoneManager.cursor?.use { cursor ->
                while (cursor.moveToNext()) {
                    val title = cursor.getString(RingtoneManager.TITLE_COLUMN_INDEX)
                    val uri = ringtoneManager.getRingtoneUri(cursor.position).toString()
                    alarmsArray.put(JSONObject().apply {
                        put("title", title)
                        put("uri", uri)
                        put("isDefault", uri == currentAlarmUri)
                    })
                }
            }

            result.put("status", "SUCCESS")
            result.put("ringtones", ringtonesArray)
            result.put("notifications", notificationsArray)
            result.put("alarms", alarmsArray)
            result.put("currentRingtoneUri", currentRingtoneUri)
            result.put("currentNotificationUri", currentNotifUri)
            result.put("currentAlarmUri", currentAlarmUri)
        } catch (e: Exception) {
            result.put("status", "ERROR")
            result.put("error", e.message ?: "Failed to query ringtones")
        }
        return result
    }

    fun setDeviceRingtone(uriString: String, typeString: String = "RINGTONE"): JSONObject {
        val result = JSONObject()
        try {
            val type = when (typeString.uppercase(java.util.Locale.US)) {
                "NOTIFICATION" -> RingtoneManager.TYPE_NOTIFICATION
                "ALARM" -> RingtoneManager.TYPE_ALARM
                else -> RingtoneManager.TYPE_RINGTONE
            }

            val targetUri = if (uriString.startsWith("content://") || uriString.startsWith("android.resource://")) {
                Uri.parse(uriString)
            } else {
                val file = File(uriString)
                if (file.exists()) Uri.fromFile(file) else Uri.parse(uriString)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (!Settings.System.canWrite(context)) {
                    result.put("status", "PERMISSION_REQUIRED")
                    result.put("error", "Write Settings permission is required on the phone to change ringtones.")
                    return result
                }
            }

            RingtoneManager.setActualDefaultRingtoneUri(context, type, targetUri)
            result.put("status", "SUCCESS")
            result.put("message", "Ringtone updated successfully")
        } catch (e: Exception) {
            result.put("status", "ERROR")
            result.put("error", e.message ?: "Failed to set ringtone")
        }
        return result
    }


    // =========================================================================
    // --- Phase 16: Remote Power Control & Hardware Sensor Telemetry Hub ---
    // =========================================================================
    private var lastCpuTotal: Long = 0L
    private var lastCpuIdle: Long = 0L

    fun getHardwareTelemetry(): JSONObject {
        val result = JSONObject()
        try {
            // 1. Live Battery Telemetry (temperature, voltage, charging state, health)
            val batteryFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus: Intent? = context.registerReceiver(null, batteryFilter)
            val batteryObj = JSONObject()
            if (batteryStatus != null) {
                val level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val batteryPct = if (level != -1 && scale != -1) Math.round((level.toFloat() / scale.toFloat()) * 100) else -1
                val status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
                val chargePlug = batteryStatus.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
                val plugType = when (chargePlug) {
                    BatteryManager.BATTERY_PLUGGED_AC -> "AC Wall Charger"
                    BatteryManager.BATTERY_PLUGGED_USB -> "USB Cable / PC"
                    BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless Dock"
                    else -> if (isCharging) "Charging" else "Unplugged (Battery)"
                }
                val rawTemp = batteryStatus.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
                val tempCelsius = if (rawTemp > 0) rawTemp / 10.0f else 0.0f
                val tempFahrenheit = (tempCelsius * 9 / 5) + 32
                val rawVoltage = batteryStatus.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
                val voltageVolts = if (rawVoltage > 0) rawVoltage / 1000.0f else 0.0f
                val health = batteryStatus.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN)
                val healthStr = when (health) {
                    BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
                    BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheat"
                    BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
                    BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over Voltage"
                    BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
                    else -> "Normal"
                }
                val tech = batteryStatus.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY) ?: "Li-ion"

                batteryObj.put("percent", batteryPct)
                batteryObj.put("isCharging", isCharging)
                batteryObj.put("plugType", plugType)
                batteryObj.put("tempCelsius", String.format(java.util.Locale.US, "%.1f", tempCelsius))
                batteryObj.put("tempFahrenheit", String.format(java.util.Locale.US, "%.1f", tempFahrenheit))
                batteryObj.put("voltageVolts", String.format(java.util.Locale.US, "%.2f", voltageVolts))
                batteryObj.put("health", healthStr)
                batteryObj.put("technology", tech)
            }
            result.put("battery", batteryObj)

            // 2. Real-Time RAM (Memory) Utilization
            val memoryObj = JSONObject()
            val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            if (actManager != null) {
                val memInfo = ActivityManager.MemoryInfo()
                actManager.getMemoryInfo(memInfo)
                val totalBytes = memInfo.totalMem
                val availBytes = memInfo.availBytesFallback(memInfo)
                val usedBytes = Math.max(0L, totalBytes - availBytes)
                val usedPercent = if (totalBytes > 0) Math.round((usedBytes.toDouble() / totalBytes) * 100).toInt() else 0
                val totalGb = String.format(java.util.Locale.US, "%.1f GB", totalBytes / (1024.0 * 1024 * 1024))
                val availGb = String.format(java.util.Locale.US, "%.1f GB", availBytes / (1024.0 * 1024 * 1024))
                val usedGb = String.format(java.util.Locale.US, "%.1f GB", usedBytes / (1024.0 * 1024 * 1024))

                memoryObj.put("totalBytes", totalBytes)
                memoryObj.put("availBytes", availBytes)
                memoryObj.put("usedBytes", usedBytes)
                memoryObj.put("usedPercent", usedPercent)
                memoryObj.put("totalFormatted", totalGb)
                memoryObj.put("availFormatted", availGb)
                memoryObj.put("usedFormatted", usedGb)
                memoryObj.put("isLowMemory", memInfo.lowMemory)
            }
            result.put("memory", memoryObj)

            // 3. CPU Utilization & Core Architecture
            val cpuObj = JSONObject()
            val cpuPercent = computeCpuUsagePercent()
            val coreCount = Runtime.getRuntime().availableProcessors()
            val abi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
            } else {
                Build.CPU_ABI
            }
            cpuObj.put("usagePercent", cpuPercent)
            cpuObj.put("coreCount", coreCount)
            cpuObj.put("architecture", abi)
            result.put("cpu", cpuObj)

            // 4. Storage Partition Breakdown (Internal vs External)
            val storageObj = JSONObject()
            try {
                val dataPath = Environment.getDataDirectory()
                val dataStat = StatFs(dataPath.path)
                val internalTotal = dataStat.blockCountLong * dataStat.blockSizeLong
                val internalAvail = dataStat.availableBlocksLong * dataStat.blockSizeLong
                val internalUsed = Math.max(0L, internalTotal - internalAvail)
                val internalPct = if (internalTotal > 0) Math.round((internalUsed.toDouble() / internalTotal) * 100).toInt() else 0

                storageObj.put("internalTotalBytes", internalTotal)
                storageObj.put("internalAvailBytes", internalAvail)
                storageObj.put("internalUsedBytes", internalUsed)
                storageObj.put("internalUsedPercent", internalPct)
                storageObj.put("internalTotalFormatted", String.format(java.util.Locale.US, "%.1f GB", internalTotal / (1024.0 * 1024 * 1024)))
                storageObj.put("internalAvailFormatted", String.format(java.util.Locale.US, "%.1f GB", internalAvail / (1024.0 * 1024 * 1024)))
                storageObj.put("internalUsedFormatted", String.format(java.util.Locale.US, "%.1f GB", internalUsed / (1024.0 * 1024 * 1024)))
            } catch (e: Exception) {}
            result.put("storage", storageObj)

            // 5. System Health, Power Mode & Uptime
            val sysObj = JSONObject()
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val isPowerSaveMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                powerManager?.isPowerSaveMode ?: false
            } else false
            val isInteractive = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
                powerManager?.isInteractive ?: true
            } else true
            val uptimeMs = SystemClock.elapsedRealtime()
            val totalSeconds = uptimeMs / 1000
            val hours = totalSeconds / 3600
            val minutes = (totalSeconds % 3600) / 60
            val uptimeStr = "${hours}h ${minutes}m"

            sysObj.put("isPowerSaveMode", isPowerSaveMode)
            sysObj.put("isInteractive", isInteractive)
            sysObj.put("uptimeFormatted", uptimeStr)
            sysObj.put("uptimeMs", uptimeMs)
            sysObj.put("osVersion", "Android ${Build.VERSION.RELEASE}")
            sysObj.put("deviceModel", "${Build.MANUFACTURER} ${Build.MODEL}")
            result.put("system", sysObj)

            result.put("status", "SUCCESS")
            result.put("timestamp", System.currentTimeMillis())
        } catch (e: Exception) {
            result.put("status", "ERROR")
            result.put("error", e.message ?: "Failed to read hardware telemetry")
        }
        return result
    }

    private fun ActivityManager.MemoryInfo.availBytesFallback(memInfo: ActivityManager.MemoryInfo): Long {
        return memInfo.availMem
    }

    private fun computeCpuUsagePercent(): Int {
        return try {
            val reader = BufferedReader(FileReader("/proc/stat"))
            val firstLine = reader.readLine() ?: ""
            reader.close()
            val tokens = firstLine.trim().split("\\s+".toRegex())
            if (tokens.size >= 8 && tokens[0] == "cpu") {
                val user = tokens[1].toLong()
                val nice = tokens[2].toLong()
                val system = tokens[3].toLong()
                val idle = tokens[4].toLong()
                val iowait = tokens[5].toLong()
                val irq = tokens[6].toLong()
                val softirq = tokens[7].toLong()

                val currentIdle = idle + iowait
                val currentTotal = user + nice + system + idle + iowait + irq + softirq

                val deltaIdle = currentIdle - lastCpuIdle
                val deltaTotal = currentTotal - lastCpuTotal

                lastCpuIdle = currentIdle
                lastCpuTotal = currentTotal

                if (deltaTotal > 0) {
                    val usage = Math.round(((deltaTotal - deltaIdle).toDouble() / deltaTotal) * 100).toInt()
                    Math.max(0, Math.min(100, usage))
                } else {
                    12
                }
            } else {
                12
            }
        } catch (e: Exception) {
            12
        }
    }

    fun executeRemotePowerAction(action: String): JSONObject {
        val result = JSONObject()
        try {
            when (action.uppercase(java.util.Locale.US)) {
                "LOCK_SCREEN" -> {
                    // Priority 1: Accessibility Service (Supported on Android 9+ / Android 15 without Device Admin)
                    val remoteInput = RemoteInputService.instance
                    if (remoteInput != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        try {
                            val locked = remoteInput.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
                            if (locked) {
                                result.put("status", "SUCCESS")
                                result.put("message", "Screen locked successfully via Remote Control (Accessibility)")
                                return result
                            }
                        } catch (e: Exception) {
                            Log.e("TelephonyManager", "Accessibility lock failed", e)
                        }
                    }

                    // Priority 2: Device Administrator DPM lockNow()
                    val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
                    val adminComponent = ComponentName(context, AdminReceiver::class.java)
                    if (dpm != null && dpm.isAdminActive(adminComponent)) {
                        try {
                            dpm.lockNow()
                            result.put("status", "SUCCESS")
                            result.put("message", "Screen locked successfully via Device Administrator")
                            return result
                        } catch (e: Exception) {
                            Log.e("TelephonyManager", "Device Admin lock failed", e)
                        }
                    }

                    // Priority 3: Root shell power toggle
                    try {
                        val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "input keyevent 26"))
                        proc.waitFor()
                        if (proc.exitValue() == 0) {
                            result.put("status", "SUCCESS")
                            result.put("message", "Screen locked via root shell")
                            return result
                        }
                    } catch (_: Exception) {}

                    // Guidance if neither privilege is active
                    result.put("status", "ERROR")
                    result.put("error", "Please enable 'Remote Control (Accessibility)' or 'Device Administrator' in OpenDroid app on your phone.")
                }
                "STANDBY_SLEEP" -> {
                    // Priority 1: Accessibility Service (Lock screen puts display to sleep)
                    val remoteInput = RemoteInputService.instance
                    if (remoteInput != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        try {
                            val slept = remoteInput.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
                            if (slept) {
                                result.put("status", "SUCCESS")
                                result.put("message", "Screen put to standby sleep via Accessibility")
                                return result
                            }
                        } catch (e: Exception) {
                            Log.e("TelephonyManager", "Accessibility sleep failed", e)
                        }
                    }

                    // Priority 2: Device Administrator lock
                    val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
                    val adminComponent = ComponentName(context, AdminReceiver::class.java)
                    if (dpm != null && dpm.isAdminActive(adminComponent)) {
                        try {
                            dpm.lockNow()
                            result.put("status", "SUCCESS")
                            result.put("message", "Screen put to standby sleep via Device Admin")
                            return result
                        } catch (e: Exception) {
                            Log.e("TelephonyManager", "Device Admin standby sleep failed", e)
                        }
                    }

                    // Priority 3: Root shell keyevent
                    try {
                        val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "input keyevent 26"))
                        proc.waitFor()
                        if (proc.exitValue() == 0) {
                            result.put("status", "SUCCESS")
                            result.put("message", "Standby sleep signal dispatched via root")
                            return result
                        }
                    } catch (_: Exception) {}

                    result.put("status", "ERROR")
                    result.put("error", "Please enable 'Remote Control (Accessibility)' or 'Device Administrator' in OpenDroid app on your phone.")
                }
                "REBOOT" -> {
                    // Priority 1: Root reboot (Direct silent restart)
                    try {
                        val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "reboot"))
                        result.put("status", "SUCCESS")
                        result.put("message", "Reboot initiated via root shell")
                        return result
                    } catch (_: Exception) {}

                    // Priority 2: PowerManager (If signed as system app)
                    try {
                        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                        pm?.reboot(null)
                        result.put("status", "SUCCESS")
                        result.put("message", "Reboot initiated via system PowerManager")
                        return result
                    } catch (_: Exception) {}

                    // Priority 3: Accessibility Service GLOBAL_ACTION_POWER_DIALOG (Brings up Restart menu on phone screen)
                    val remoteInput = RemoteInputService.instance
                    if (remoteInput != null) {
                        try {
                            val dialogOpened = remoteInput.performGlobalAction(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG)
                            if (dialogOpened) {
                                result.put("status", "SUCCESS")
                                result.put("message", "Power menu opened on phone screen (Tap Restart to confirm)")
                                return result
                            }
                        } catch (e: Exception) {
                            Log.e("TelephonyManager", "Power dialog failed", e)
                        }
                    }

                    result.put("status", "ERROR")
                    result.put("error", "Root access or 'Remote Control (Accessibility)' required for remote reboot.")
                }
                "SHUTDOWN" -> {
                    try {
                        Runtime.getRuntime().exec(arrayOf("su", "-c", "reboot -p"))
                        result.put("status", "SUCCESS")
                        result.put("message", "Shutdown initiated via root shell")
                        return result
                    } catch (e: Exception) {
                        val remoteInput = RemoteInputService.instance
                        if (remoteInput != null) {
                            try {
                                val dialogOpened = remoteInput.performGlobalAction(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG)
                                if (dialogOpened) {
                                    result.put("status", "SUCCESS")
                                    result.put("message", "Power menu opened on phone screen (Tap Power off to confirm)")
                                    return result
                                }
                            } catch (_: Exception) {}
                        }
                        result.put("status", "ERROR")
                        result.put("error", "Root permission or Accessibility required for remote shutdown.")
                    }
                }
                "STANDBY_WAKE" -> {
                    try {
                        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                        @Suppress("DEPRECATION")
                        val wakeLock = pm?.newWakeLock(
                            PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                            "OpenDroid:RemoteWakeLock"
                        )
                        wakeLock?.acquire(3000)
                        wakeLock?.release()
                        result.put("status", "SUCCESS")
                        result.put("message", "Device screen awakened successfully")
                    } catch (e: Exception) {
                        result.put("status", "ERROR")
                        result.put("error", e.message ?: "Failed to wake device screen")
                    }
                }
                else -> {
                    result.put("status", "INVALID_ACTION")
                    result.put("error", "Unknown power action: $action")
                }
            }
        } catch (e: Exception) {
            result.put("status", "ERROR")
            result.put("error", e.message ?: "Power action execution failed")
        }
        return result
    }

    // =========================================================================
    // Phase 17: Google Family Link-Grade Screen Time & Digital Wellbeing Engine
    // =========================================================================

    fun checkUsageAccessPermission(): Boolean {
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            if (appOps == null) false
            else {
                val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    appOps.unsafeCheckOpNoThrow(
                        AppOpsManager.OPSTR_GET_USAGE_STATS,
                        android.os.Process.myUid(),
                        context.packageName
                    )
                } else {
                    @Suppress("DEPRECATION")
                    appOps.checkOpNoThrow(
                        AppOpsManager.OPSTR_GET_USAGE_STATS,
                        android.os.Process.myUid(),
                        context.packageName
                    )
                }
                mode == AppOpsManager.MODE_ALLOWED
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun formatDurationMs(ms: Long): String {
        if (ms <= 0L) return "0m"
        val totalSec = ms / 1000L
        val hours = totalSec / 3600L
        val minutes = (totalSec % 3600L) / 60L
        val seconds = totalSec % 60L
        return when {
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> "${minutes}m ${seconds}s"
            else -> "${seconds}s"
        }
    }

    private fun distributeTimeToHourlyBuckets(startMs: Long, endMs: Long, buckets: LongArray) {
        if (endMs <= startMs) return
        val cal = Calendar.getInstance()
        var cur = startMs
        while (cur < endMs) {
            cal.timeInMillis = cur
            val hour = cal.get(Calendar.HOUR_OF_DAY)
            
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            cal.add(Calendar.HOUR_OF_DAY, 1)
            val nextHourMs = cal.timeInMillis
            
            val chunkEnd = Math.min(endMs, nextHourMs)
            val duration = chunkEnd - cur
            if (hour in 0..23 && duration > 0) {
                buckets[hour] = buckets[hour] + duration
            }
            cur = chunkEnd
        }
    }

    fun getScreenTimeSummary(dateStr: String? = null, daysAgo: Int = 0, rangeType: String = "day"): JSONObject {
        val result = JSONObject()
        val hasPerm = checkUsageAccessPermission()
        result.put("hasPermission", hasPerm)
        if (!hasPerm) {
            result.put("status", "PERMISSION_REQUIRED")
            result.put("error", "Usage Access permission is not granted on device. Please enable it in Settings.")
            return result
        }

        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
        if (usageStatsManager == null) {
            result.put("status", "UNAVAILABLE")
            result.put("error", "UsageStatsManager service not available on this device.")
            return result
        }

        try {
            val sdfDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val sdfTime = SimpleDateFormat("hh:mm a", Locale.getDefault())
            val sdfDisplay = SimpleDateFormat("EEEE, MMM d, yyyy", Locale.getDefault())
            val sdfShort = SimpleDateFormat("MMM d", Locale.getDefault())

            val isRange = rangeType == "7days" || rangeType == "30days" || daysAgo == 7 || daysAgo == 30
            val rangeDays = when {
                rangeType == "30days" || daysAgo == 30 -> 30
                rangeType == "7days" || daysAgo == 7 -> 7
                else -> 1
            }

            val startTime: Long
            val endTime: Long
            val displayLabel: String
            val now = System.currentTimeMillis()

            if (isRange) {
                val startCal = Calendar.getInstance()
                startCal.add(Calendar.DAY_OF_YEAR, -(rangeDays - 1))
                startCal.set(Calendar.HOUR_OF_DAY, 0)
                startCal.set(Calendar.MINUTE, 0)
                startCal.set(Calendar.SECOND, 0)
                startCal.set(Calendar.MILLISECOND, 0)
                startTime = startCal.timeInMillis
                endTime = now
                displayLabel = "Last $rangeDays Days (${sdfShort.format(Date(startTime))} - ${sdfShort.format(Date(endTime))})"
            } else {
                val targetCal = Calendar.getInstance()
                if (!dateStr.isNullOrEmpty()) {
                    try {
                        val parsed = sdfDate.parse(dateStr.trim())
                        if (parsed != null) targetCal.time = parsed
                    } catch (_: Exception) {}
                } else if (daysAgo > 0) {
                    targetCal.add(Calendar.DAY_OF_YEAR, -daysAgo)
                }
                targetCal.set(Calendar.HOUR_OF_DAY, 0)
                targetCal.set(Calendar.MINUTE, 0)
                targetCal.set(Calendar.SECOND, 0)
                targetCal.set(Calendar.MILLISECOND, 0)
                startTime = targetCal.timeInMillis

                targetCal.set(Calendar.HOUR_OF_DAY, 23)
                targetCal.set(Calendar.MINUTE, 59)
                targetCal.set(Calendar.SECOND, 59)
                targetCal.set(Calendar.MILLISECOND, 999)
                val dayEnd = targetCal.timeInMillis
                endTime = if (dayEnd > now) now else dayEnd
                
                val calToday = Calendar.getInstance()
                calToday.set(Calendar.HOUR_OF_DAY, 0)
                calToday.set(Calendar.MINUTE, 0)
                calToday.set(Calendar.SECOND, 0)
                calToday.set(Calendar.MILLISECOND, 0)
                val todayStart = calToday.timeInMillis
                val yesterdayStart = todayStart - (24 * 3600 * 1000L)

                displayLabel = when (startTime) {
                    todayStart -> "Today, ${sdfDisplay.format(Date(startTime))}"
                    yesterdayStart -> "Yesterday, ${sdfDisplay.format(Date(startTime))}"
                    else -> sdfDisplay.format(Date(startTime))
                }
            }

            result.put("status", "SUCCESS")
            result.put("dateIso", sdfDate.format(Date(startTime)))
            result.put("dateLabel", displayLabel)
            result.put("isRange", isRange)
            result.put("rangeDays", rangeDays)
            result.put("startTime", startTime)
            result.put("endTime", endTime)

            // 1. Query Usage Events for Locks/Unlocks, Launch Counts & Hourly Activity
            var unlockCount = 0
            var totalLaunches = 0
            var firstPickup = 0L
            var lastScreenOff = 0L
            val hourlyBucketsMs = LongArray(24)
            val launchCounts = mutableMapOf<String, Int>()

            var currentFgPkg: String? = null
            var currentFgStart = 0L

            try {
                val usageEvents = usageStatsManager.queryEvents(startTime, endTime)
                val event = UsageEvents.Event()

                while (usageEvents.hasNextEvent()) {
                    usageEvents.getNextEvent(event)
                    val eventTime = event.timeStamp
                    val eventType = event.eventType

                    // Unlocks & Screen Pickups
                    if (eventType == UsageEvents.Event.KEYGUARD_HIDDEN || eventType == UsageEvents.Event.SCREEN_INTERACTIVE) {
                        unlockCount++
                        if (firstPickup == 0L || eventTime < firstPickup) {
                            firstPickup = eventTime
                        }
                    }

                    if (eventType == UsageEvents.Event.SCREEN_NON_INTERACTIVE) {
                        lastScreenOff = eventTime
                    }

                    // App Resumed (Foreground launch)
                    if (eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                        val pkg = event.packageName ?: ""
                        if (pkg.isNotEmpty()) {
                            launchCounts[pkg] = (launchCounts[pkg] ?: 0) + 1
                            totalLaunches++
                        }

                        if (!isRange && currentFgPkg != null && currentFgStart > 0 && eventTime > currentFgStart) {
                            distributeTimeToHourlyBuckets(currentFgStart, eventTime, hourlyBucketsMs)
                        }
                        currentFgPkg = pkg
                        currentFgStart = eventTime
                    } else if (eventType == UsageEvents.Event.ACTIVITY_PAUSED ||
                               eventType == UsageEvents.Event.ACTIVITY_STOPPED ||
                               eventType == UsageEvents.Event.SCREEN_NON_INTERACTIVE) {
                        if (!isRange && currentFgPkg != null && currentFgStart > 0 && eventTime > currentFgStart) {
                            distributeTimeToHourlyBuckets(currentFgStart, eventTime, hourlyBucketsMs)
                        }
                        currentFgPkg = null
                        currentFgStart = 0L
                    }
                }

                if (!isRange && currentFgPkg != null && currentFgStart > 0 && endTime > currentFgStart) {
                    distributeTimeToHourlyBuckets(currentFgStart, endTime, hourlyBucketsMs)
                }
            } catch (e: Exception) {
                Log.e("TelephonyManager", "Error querying UsageEvents", e)
            }

            // 2. Query App Usage Stats
            class RawAppUsage(
                val packageName: String,
                var totalTimeMs: Long = 0L,
                var lastTimeUsed: Long = 0L
            )
            val appUsageMap = mutableMapOf<String, RawAppUsage>()

            try {
                val intervalType = if (isRange) UsageStatsManager.INTERVAL_WEEKLY else UsageStatsManager.INTERVAL_DAILY
                val rawStats = usageStatsManager.queryUsageStats(intervalType, startTime, endTime)
                if (!rawStats.isNullOrEmpty()) {
                    for (stat in rawStats) {
                        val pkg = stat.packageName ?: continue
                        val timeInFg = stat.totalTimeInForeground
                        if (timeInFg <= 0L) continue

                        val existing = appUsageMap[pkg]
                        if (existing == null) {
                            appUsageMap[pkg] = RawAppUsage(
                                packageName = pkg,
                                totalTimeMs = timeInFg,
                                lastTimeUsed = stat.lastTimeUsed
                            )
                        } else {
                            existing.totalTimeMs += timeInFg
                            if (stat.lastTimeUsed > existing.lastTimeUsed) {
                                existing.lastTimeUsed = stat.lastTimeUsed
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("TelephonyManager", "Error querying UsageStats", e)
            }

            // 3. Resolve App Metadata, System vs 3rd-Party & Categories
            val pm = context.packageManager
            val appsArray = JSONArray()
            var calculatedTotalScreenTimeMs = 0L

            val sortedApps = appUsageMap.values.sortedByDescending { it.totalTimeMs }
            for (rawApp in sortedApps) {
                val pkg = rawApp.packageName
                val timeMs = rawApp.totalTimeMs
                calculatedTotalScreenTimeMs += timeMs

                var appLabel = pkg
                var isSystem = false
                var category = "Other"

                try {
                    val appInfo = pm.getApplicationInfo(pkg, 0)
                    appLabel = pm.getApplicationLabel(appInfo).toString()
                    isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        category = when (appInfo.category) {
                            ApplicationInfo.CATEGORY_GAME -> "Games"
                            ApplicationInfo.CATEGORY_AUDIO -> "Audio & Music"
                            ApplicationInfo.CATEGORY_VIDEO -> "Video & Movies"
                            ApplicationInfo.CATEGORY_IMAGE -> "Photography"
                            ApplicationInfo.CATEGORY_SOCIAL -> "Social & Messaging"
                            ApplicationInfo.CATEGORY_NEWS -> "News & Reading"
                            ApplicationInfo.CATEGORY_MAPS -> "Maps & Travel"
                            ApplicationInfo.CATEGORY_PRODUCTIVITY -> "Productivity"
                            else -> if (isSystem) "System & Tools" else "General"
                        }
                    } else {
                        category = if (isSystem) "System & Tools" else "General"
                    }
                } catch (_: Exception) {}

                val appLimitsObj = getAppLimits()
                val appLimitMins = appLimitsObj.optInt(pkg, 0)
                val isLimitExceeded = appLimitMins > 0 && timeMs >= (appLimitMins * 60 * 1000L)
                val iconB64 = if (appsArray.length() < 35) (getAppIconBase64(pkg) ?: "") else ""

                val appObj = JSONObject().apply {
                    put("packageName", pkg)
                    put("appName", appLabel)
                    put("totalTimeMs", timeMs)
                    put("formattedTime", formatDurationMs(timeMs))
                    put("launchCount", launchCounts[pkg] ?: 0)
                    put("lastTimeUsed", rawApp.lastTimeUsed)
                    put("isSystemApp", isSystem)
                    put("category", category)
                    put("limitMinutes", appLimitMins)
                    put("isLimited", appLimitMins > 0)
                    put("isLimitExceeded", isLimitExceeded)
                    put("iconBase64", iconB64)
                }
                appsArray.put(appObj)
            }

            // 4. Assemble 24-Hour Hourly Timeline (Minutes active per hour)
            val hourlyArray = JSONArray()
            for (hour in 0..23) {
                val hourMinutes = Math.min(60, Math.round(hourlyBucketsMs[hour] / 60000.0).toInt())
                hourlyArray.put(hourMinutes)
            }

            // 5. Overall Totals & Metadata
            result.put("totalScreenTimeMs", calculatedTotalScreenTimeMs)
            result.put("formattedTotalTime", formatDurationMs(calculatedTotalScreenTimeMs))
            result.put("unlockCount", unlockCount)
            result.put("totalAppLaunches", totalLaunches)
            result.put("firstPickup", firstPickup)
            result.put("formattedFirstPickup", if (firstPickup > 0L) sdfTime.format(Date(firstPickup)) else "--")
            result.put("lastScreenOff", lastScreenOff)
            result.put("formattedLastScreenOff", if (lastScreenOff > 0L) sdfTime.format(Date(lastScreenOff)) else "--")
            result.put("hourlyActivity", hourlyArray)
            result.put("apps", appsArray)
            result.put("appLimits", getAppLimits())
            result.put("bedtimeConfig", getBedtimeConfig())
            result.put("focusConfig", getFocusModeConfig())
            result.put("dndStatus", getDndStatus())
            result.put("reminderConfig", getScreenTimeReminder())

            // Top most used app
            if (appsArray.length() > 0) {
                result.put("topApp", appsArray.getJSONObject(0))
            } else {
                result.put("topApp", JSONObject.NULL)
            }

        } catch (e: Exception) {
            Log.e("TelephonyManager", "Error compiling Screen Time summary", e)
            result.put("status", "ERROR")
            result.put("error", e.message ?: "Failed to compute Screen Time")
        }

        return result
    }


    // =========================================================================
    // Phase 18: Google Family Link-Grade App Limits, Bedtime & Controls
    // =========================================================================

    private val wellbeingPrefs: SharedPreferences by lazy {
        context.getSharedPreferences("opendroid_wellbeing_prefs", Context.MODE_PRIVATE)
    }

    private val appIconCache = mutableMapOf<String, String>()

    fun getAppIconBase64(packageName: String): String? {
        if (packageName.isEmpty()) return null
        appIconCache[packageName]?.let { return it }
        return try {
            val pm = context.packageManager
            val drawable = pm.getApplicationIcon(packageName)
            val bitmap = if (drawable is BitmapDrawable && drawable.bitmap != null) {
                Bitmap.createScaledBitmap(drawable.bitmap, 64, 64, true)
            } else {
                val w = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 64
                val h = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 64
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
                Bitmap.createScaledBitmap(bmp, 64, 64, true)
            }
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 85, stream)
            val base64 = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
            appIconCache[packageName] = base64
            base64
        } catch (_: Exception) {
            null
        }
    }

    fun setAppLimit(packageName: String, limitMinutes: Int): JSONObject {
        val result = JSONObject()
        try {
            val limitsJsonStr = wellbeingPrefs.getString("app_limits", "{}") ?: "{}"
            val limitsObj = JSONObject(limitsJsonStr)
            if (limitMinutes <= 0) {
                limitsObj.remove(packageName)
            } else {
                limitsObj.put(packageName, limitMinutes)
            }
            wellbeingPrefs.edit().putString("app_limits", limitsObj.toString()).apply()
            result.put("status", "SUCCESS")
            result.put("package", packageName)
            result.put("limitMinutes", limitMinutes)
            result.put("appLimits", limitsObj)
        } catch (e: Exception) {
            result.put("status", "ERROR")
            result.put("error", e.message ?: "Failed to set app limit")
        }
        return result
    }

    fun removeAppLimit(packageName: String): JSONObject {
        return setAppLimit(packageName, 0)
    }

    fun getAppLimits(): JSONObject {
        val limitsJsonStr = wellbeingPrefs.getString("app_limits", "{}") ?: "{}"
        return try {
            JSONObject(limitsJsonStr)
        } catch (_: Exception) {
            JSONObject()
        }
    }

    fun getDndStatus(): JSONObject {
        val result = JSONObject()
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            if (nm == null) {
                result.put("status", "UNAVAILABLE")
                return result
            }

            val hasPolicyAccess = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                nm.isNotificationPolicyAccessGranted
            } else {
                true
            }
            result.put("hasPolicyAccess", hasPolicyAccess)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val filter = nm.currentInterruptionFilter
                val modeStr = when (filter) {
                    NotificationManager.INTERRUPTION_FILTER_ALL -> "NORMAL"
                    NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "PRIORITY"
                    NotificationManager.INTERRUPTION_FILTER_NONE -> "TOTAL_SILENCE"
                    NotificationManager.INTERRUPTION_FILTER_ALARMS -> "ALARMS_ONLY"
                    else -> "UNKNOWN"
                }
                result.put("currentFilter", modeStr)
                result.put("isDndActive", filter != NotificationManager.INTERRUPTION_FILTER_ALL)
            } else {
                result.put("currentFilter", "NORMAL")
                result.put("isDndActive", false)
            }
            result.put("status", "SUCCESS")
        } catch (e: Exception) {
            result.put("status", "ERROR")
            result.put("error", e.message ?: "Failed to read DND status")
        }
        return result
    }

    fun setDndMode(mode: String): JSONObject {
        val result = JSONObject()
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            if (nm == null) {
                result.put("status", "UNAVAILABLE")
                result.put("error", "NotificationManager service unavailable")
                return result
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (!nm.isNotificationPolicyAccessGranted) {
                    result.put("status", "PERMISSION_REQUIRED")
                    result.put("error", "Notification Policy Access (DND) permission is required. Please grant it in device Settings.")
                    return result
                }

                val targetFilter = when (mode.uppercase(Locale.US)) {
                    "TOTAL_SILENCE", "NONE", "SILENT" -> NotificationManager.INTERRUPTION_FILTER_NONE
                    "PRIORITY" -> NotificationManager.INTERRUPTION_FILTER_PRIORITY
                    "ALARMS_ONLY", "ALARMS" -> NotificationManager.INTERRUPTION_FILTER_ALARMS
                    "NORMAL", "OFF", "ALL" -> NotificationManager.INTERRUPTION_FILTER_ALL
                    else -> NotificationManager.INTERRUPTION_FILTER_ALL
                }
                nm.setInterruptionFilter(targetFilter)
                result.put("status", "SUCCESS")
                result.put("mode", mode)
                result.put("isDndActive", targetFilter != NotificationManager.INTERRUPTION_FILTER_ALL)
            } else {
                result.put("status", "UNSUPPORTED_VERSION")
                result.put("error", "DND policy requires Android 6.0+")
            }
        } catch (e: Exception) {
            result.put("status", "ERROR")
            result.put("error", e.message ?: "Failed to set DND mode")
        }
        return result
    }

    fun setBedtimeConfig(
        enabled: Boolean,
        startHour: Int = 22,
        startMinute: Int = 0,
        endHour: Int = 6,
        endMinute: Int = 0,
        daysOfWeek: List<Int> = listOf(1, 2, 3, 4, 5, 6, 7),
        manualActive: Boolean = false,
        grayscale: Boolean = true,
        dndEnabled: Boolean = true
    ): JSONObject {
        val result = JSONObject()
        try {
            val daysArr = JSONArray()
            daysOfWeek.forEach { daysArr.put(it) }

            val config = JSONObject().apply {
                put("enabled", enabled)
                put("startHour", startHour)
                put("startMinute", startMinute)
                put("endHour", endHour)
                put("endMinute", endMinute)
                put("daysOfWeek", daysArr)
                put("manualActive", manualActive)
                put("grayscale", grayscale)
                put("dndEnabled", dndEnabled)
            }
            wellbeingPrefs.edit().putString("bedtime_config", config.toString()).apply()

            val isNowActive = isBedtimeActiveNow()
            if (isNowActive && dndEnabled) {
                setDndMode("PRIORITY")
            }

            result.put("status", "SUCCESS")
            result.put("config", config)
            result.put("isCurrentlyActive", isNowActive)
        } catch (e: Exception) {
            result.put("status", "ERROR")
            result.put("error", e.message ?: "Failed to save Bedtime config")
        }
        return result
    }

    fun isBedtimeActiveNow(): Boolean {
        val raw = wellbeingPrefs.getString("bedtime_config", null) ?: return false
        return try {
            val obj = JSONObject(raw)
            if (obj.optBoolean("manualActive", false)) return true
            if (!obj.optBoolean("enabled", false)) return false

            val cal = Calendar.getInstance()
            val todayDayOfWeek = cal.get(Calendar.DAY_OF_WEEK) // 1=Sunday..7=Saturday

            val daysArr = obj.optJSONArray("daysOfWeek")
            if (daysArr != null && daysArr.length() > 0) {
                var dayMatch = false
                for (i in 0 until daysArr.length()) {
                    if (daysArr.optInt(i) == todayDayOfWeek) {
                        dayMatch = true
                        break
                    }
                }
                if (!dayMatch) return false
            }

            val startH = obj.optInt("startHour", 22)
            val startM = obj.optInt("startMinute", 0)
            val endH = obj.optInt("endHour", 6)
            val endM = obj.optInt("endMinute", 0)

            val curMinutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
            val startMinutes = startH * 60 + startM
            val endMinutes = endH * 60 + endM

            if (startMinutes <= endMinutes) {
                curMinutes in startMinutes until endMinutes
            } else {
                curMinutes >= startMinutes || curMinutes < endMinutes
            }
        } catch (_: Exception) {
            false
        }
    }

    fun getBedtimeConfig(): JSONObject {
        val raw = wellbeingPrefs.getString("bedtime_config", null)
        return if (raw != null) {
            try {
                val obj = JSONObject(raw)
                obj.put("isCurrentlyActive", isBedtimeActiveNow())
                obj
            } catch (_: Exception) {
                defaultBedtimeConfig()
            }
        } else {
            defaultBedtimeConfig()
        }
    }

    private fun defaultBedtimeConfig(): JSONObject {
        val defaultDays = JSONArray()
        for (i in 1..7) defaultDays.put(i)
        return JSONObject().apply {
            put("enabled", false)
            put("startHour", 23)
            put("startMinute", 0)
            put("endHour", 7)
            put("endMinute", 0)
            put("daysOfWeek", defaultDays)
            put("manualActive", false)
            put("grayscale", true)
            put("dndEnabled", true)
            put("isCurrentlyActive", false)
        }
    }



    fun setFocusModeConfig(
        enabled: Boolean,
        blockedPackages: List<String>,
        durationMinutes: Int = 0
    ): JSONObject {
        val result = JSONObject()
        try {
            val pkgArray = JSONArray()
            blockedPackages.forEach { pkgArray.put(it) }

            val expireTime = if (enabled && durationMinutes > 0) {
                System.currentTimeMillis() + (durationMinutes * 60 * 1000L)
            } else 0L

            val config = JSONObject().apply {
                put("enabled", enabled)
                put("packages", pkgArray)
                put("durationMinutes", durationMinutes)
                put("expireTime", expireTime)
            }
            wellbeingPrefs.edit().putString("focus_config", config.toString()).apply()
            result.put("status", "SUCCESS")
            result.put("config", config)
            result.put("isCurrentlyActive", isFocusModeActiveNow())
        } catch (e: Exception) {
            result.put("status", "ERROR")
            result.put("error", e.message ?: "Failed to save Focus config")
        }
        return result
    }

    fun getFocusModeConfig(): JSONObject {
        val raw = wellbeingPrefs.getString("focus_config", null)
        return if (raw != null) {
            try {
                val obj = JSONObject(raw)
                obj.put("isCurrentlyActive", isFocusModeActiveNow())
                obj
            } catch (_: Exception) {
                defaultFocusConfig()
            }
        } else {
            defaultFocusConfig()
        }
    }

    private fun defaultFocusConfig(): JSONObject {
        return JSONObject().apply {
            put("enabled", false)
            put("packages", JSONArray())
            put("durationMinutes", 0)
            put("expireTime", 0L)
            put("isCurrentlyActive", false)
        }
    }

    fun isFocusModeActiveNow(): Boolean {
        val raw = wellbeingPrefs.getString("focus_config", null) ?: return false
        return try {
            val obj = JSONObject(raw)
            if (!obj.optBoolean("enabled", false)) return false
            val expireTime = obj.optLong("expireTime", 0L)
            if (expireTime > 0L && System.currentTimeMillis() > expireTime) {
                obj.put("enabled", false)
                wellbeingPrefs.edit().putString("focus_config", obj.toString()).apply()
                return false
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    fun isPackageBlockedByFocus(packageName: String): Boolean {
        if (!isFocusModeActiveNow()) return false
        val raw = wellbeingPrefs.getString("focus_config", null) ?: return false
        return try {
            val obj = JSONObject(raw)
            val arr = obj.optJSONArray("packages") ?: return false
            for (i in 0 until arr.length()) {
                if (arr.optString(i) == packageName) return true
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    fun setScreenTimeReminder(enabled: Boolean, targetMinutes: Int): JSONObject {
        val result = JSONObject()
        try {
            val config = JSONObject().apply {
                put("enabled", enabled)
                put("targetMinutes", targetMinutes)
            }
            wellbeingPrefs.edit().putString("reminder_config", config.toString()).apply()
            result.put("status", "SUCCESS")
            result.put("config", config)
        } catch (e: Exception) {
            result.put("status", "ERROR")
            result.put("error", e.message ?: "Failed to save reminder config")
        }
        return result
    }

    fun getScreenTimeReminder(): JSONObject {
        val raw = wellbeingPrefs.getString("reminder_config", null)
        return if (raw != null) {
            try {
                JSONObject(raw)
            } catch (_: Exception) {
                defaultReminderConfig()
            }
        } else {
            defaultReminderConfig()
        }
    }

    private fun defaultReminderConfig(): JSONObject {
        return JSONObject().apply {
            put("enabled", false)
            put("targetMinutes", 180)
        }
    }


    // Real-Time Active Parental Control Evaluator
    fun isPackageCurrentlyBlocked(packageName: String): Pair<Boolean, String> {
        if (packageName.isEmpty()) return Pair(false, "")

        // Whitelist critical system packages so phone never bricks
        val criticalWhitelist = setOf(
            context.packageName,
            "com.android.phone",
            "com.google.android.dialer",
            "com.android.server.telecom",
            "com.android.settings",
            "com.google.android.packageinstaller",
            "com.android.packageinstaller",
            "com.android.systemui"
        )
        if (criticalWhitelist.contains(packageName) || packageName.contains("dialer") || packageName.contains("telecom")) {
            return Pair(false, "")
        }

        // 1. Check Bedtime Mode
        if (isBedtimeActiveNow()) {
            val whitelistedApps = setOf(
                context.packageName,
                "com.android.phone",
                "com.google.android.dialer",
                "com.android.mms",
                "com.google.android.apps.messaging",
                "com.android.settings"
            )
            if (!whitelistedApps.contains(packageName) && !packageName.contains("dialer")) {
                return Pair(true, "Bedtime Mode is active")
            }
        }

        // 2. Check Focus Mode
        if (isPackageBlockedByFocus(packageName)) {
            return Pair(true, "App is paused by Focus Mode")
        }

        // 3. Check Daily App Limit
        try {
            val limits = getAppLimits()
            val limitMinutes = limits.optInt(packageName, 0)
            if (limitMinutes > 0) {
                val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                if (usageStatsManager != null) {
                    val cal = Calendar.getInstance()
                    cal.set(Calendar.HOUR_OF_DAY, 0)
                    cal.set(Calendar.MINUTE, 0)
                    cal.set(Calendar.SECOND, 0)
                    cal.set(Calendar.MILLISECOND, 0)
                    val startOfDay = cal.timeInMillis
                    val now = System.currentTimeMillis()

                    val stats = usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startOfDay, now)
                    var todayUsageMs = 0L
                    if (!stats.isNullOrEmpty()) {
                        for (s in stats) {
                            if (s.packageName == packageName) {
                                todayUsageMs += s.totalTimeInForeground
                            }
                        }
                    }
                    val limitMs = limitMinutes * 60 * 1000L
                    if (todayUsageMs >= limitMs) {
                        val hours = limitMinutes / 60
                        val mins = limitMinutes % 60
                        val limitStr = if (hours > 0) "${hours}h ${mins}m" else "${mins}m"
                        return Pair(true, "Daily limit of $limitStr reached")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("TelephonyManager", "Error evaluating limit for $packageName", e)
        }

        return Pair(false, "")
    }

}

