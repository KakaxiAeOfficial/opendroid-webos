package com.example.localutility

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
import android.provider.MediaStore
import android.telephony.SmsManager
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile

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


fun getContactsPaged(offset: Int = 0, limit: Int = 150): JSONObject {
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
                var count = 0
                while (it.moveToNext() && (limit <= 0 || count < limit)) {
                    count++
                    val phoneType = when (if (typeIdx != -1) it.getInt(typeIdx) else 0) {
                        ContactsContract.CommonDataKinds.Phone.TYPE_HOME -> "Home"
                        ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE -> "Mobile"
                        ContactsContract.CommonDataKinds.Phone.TYPE_WORK -> "Work"
                        else -> "Mobile"
                    }
                    val obj = JSONObject().apply {
                        put("id", if (idIdx != -1) it.getLong(idIdx) else 0L)
                        put("name", if (nameIdx != -1) it.getString(nameIdx) ?: "Unknown" else "Unknown")
                        put("number", if (numIdx != -1) it.getString(numIdx) ?: "Unknown" else "Unknown")
                        put("type", phoneType)
                        put("photo", if (photoIdx != -1) it.getString(photoIdx) ?: "" else "")
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

}



