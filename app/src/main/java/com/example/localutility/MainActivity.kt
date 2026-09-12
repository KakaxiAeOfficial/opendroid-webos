package com.example.localutility

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlin.random.Random

class MainActivity : ComponentActivity() {

    private lateinit var nsdManager: NsdDiscoveryManager
    private lateinit var mediaProjectionManager: MediaProjectionManager

    // Permission States
    private val isStorageGrantedState = mutableStateOf(false)
    private val isBatteryIgnoredState = mutableStateOf(false)
    private val isAccessibilityGrantedState = mutableStateOf(false)
    private val isNotifAccessState = mutableStateOf(false)
    private val isCameraMicGrantedState = mutableStateOf(false)
    private val isPhoneSmsGrantedState = mutableStateOf(false)
    private val isLocationGrantedState = mutableStateOf(false)

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        updateAllPermissionStates()
    }

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, result.data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("opendroid_prefs", Context.MODE_PRIVATE)
        var code = prefs.getString("pairing_code", null)
        if (code == null) {
            code = (Random.nextInt(900000) + 100000).toString()
            prefs.edit().putString("pairing_code", code).apply()
        }
        LocalFileServerService.currentPairingCode = code

        nsdManager = NsdDiscoveryManager(this)
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        val fileServiceIntent = Intent(this, LocalFileServerService::class.java).apply {
            putExtra("PAIRING_CODE", code)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(fileServiceIntent)
        } else {
            startService(fileServiceIntent)
        }

        nsdManager.registerService()

        setContent {
            var selectedTab by remember { mutableStateOf(0) }
            var cloudOnline by remember { mutableStateOf(LocalFileServerService.isCloudConnected) }

            val isStorageGranted by isStorageGrantedState
            val isBatteryIgnored by isBatteryIgnoredState
            val isAccessibilityGranted by isAccessibilityGrantedState
            val isNotifGranted by isNotifAccessState
            val isCameraMicGranted by isCameraMicGrantedState
            val isPhoneSmsGranted by isPhoneSmsGrantedState
            val isLocationGranted by isLocationGrantedState

            DisposableEffect(Unit) {
                cloudOnline = LocalFileServerService.isCloudConnected
                LocalFileServerService.onCloudStatusChanged = { isOnline ->
                    runOnUiThread {
                        cloudOnline = isOnline
                    }
                }
                onDispose {
                    LocalFileServerService.onCloudStatusChanged = null
                }
            }

            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // Top Header
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 20.dp, bottom = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "OpenDroid WebOS",
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )

                            Spacer(modifier = Modifier.height(6.dp))

                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = if (cloudOnline) Color(0xFFE8F5E9) else Color(0xFFFFF3E0)
                            ) {
                                Text(
                                    text = if (cloudOnline) "🟢 Cloud: Connected & Online (5G)" else "🟡 Cloud: Connecting to Cloud...",
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (cloudOnline) Color(0xFF2E7D32) else Color(0xFFE65100)
                                )
                            }
                        }

                        // Navigation Tabs
                        TabRow(
                            selectedTabIndex = selectedTab,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Tab(
                                selected = selectedTab == 0,
                                onClick = { selectedTab = 0 },
                                text = { Text("📱 Dashboard", fontWeight = FontWeight.SemiBold) }
                            )
                            Tab(
                                selected = selectedTab == 1,
                                onClick = { selectedTab = 1 },
                                text = { Text("🛡️ Permissions", fontWeight = FontWeight.SemiBold) }
                            )
                        }

                        // Tab 0: Dashboard
                        if (selectedTab == 0) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(20.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Text("5G / Anywhere Remote Code:", fontSize = 14.sp)
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = code,
                                            fontSize = 40.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "Enter this code on your Web Controller",
                                            fontSize = 12.sp,
                                            color = Color.Gray
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(24.dp))

                                Button(
                                    onClick = {
                                        val captureIntent = mediaProjectionManager.createScreenCaptureIntent()
                                        screenCaptureLauncher.launch(captureIntent)
                                    },
                                    modifier = Modifier.fillMaxWidth(0.9f),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text("Start Screen Share", fontSize = 15.sp)
                                }

                                Spacer(modifier = Modifier.height(14.dp))

                                OutlinedButton(
                                    onClick = { selectedTab = 1 },
                                    modifier = Modifier.fillMaxWidth(0.9f),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text("⚙️ Open Permissions Setup Wizard", fontSize = 14.sp)
                                }
                            }
                        }

                        // Tab 1: Permissions Setup Wizard
                        if (selectedTab == 1) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(
                                    text = "Permissions Setup Wizard",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "Allow all permissions below for full remote management features.",
                                    fontSize = 12.sp,
                                    color = Color.Gray
                                )

                                Spacer(modifier = Modifier.height(4.dp))

                                // 1. Storage
                                PermissionCard(
                                    title = "All Files Access",
                                    description = "Required to browse, download, and upload files remotely.",
                                    isGranted = isStorageGranted,
                                    onGrantClick = { requestStoragePermission() }
                                )

                                // 2. Battery
                                PermissionCard(
                                    title = "Battery: No Restrictions",
                                    description = "Prevents system task-killer from stopping 5G connection when phone locks.",
                                    isGranted = isBatteryIgnored,
                                    onGrantClick = { requestIgnoreBatteryOptimizations() }
                                )

                                // 3. Accessibility (Remote Control)
                                PermissionCard(
                                    title = "Remote Control (Accessibility)",
                                    description = "Enables mouse clicks, tap, swipe, and navigation buttons from PC.",
                                    isGranted = isAccessibilityGranted,
                                    onGrantClick = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                                )

                                // 4. Notifications
                                PermissionCard(
                                    title = "Notification Sync",
                                    description = "Mirrors incoming WhatsApp/SMS notifications to PC with inline reply.",
                                    isGranted = isNotifGranted,
                                    onGrantClick = { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                                )

                                // 5. Camera & Microphone
                                PermissionCard(
                                    title = "Camera & Microphone",
                                    description = "Required for live remote camera view and ambient sound listening.",
                                    isGranted = isCameraMicGranted,
                                    onGrantClick = {
                                        requestPermissionsLauncher.launch(
                                            arrayOf(
                                                android.Manifest.permission.CAMERA,
                                                android.Manifest.permission.RECORD_AUDIO
                                            )
                                        )
                                    }
                                )

                                // 6. Phone, SMS & Call Logs
                                PermissionCard(
                                    title = "SMS, Contacts & Call Logs",
                                    description = "Allows reading/sending SMS, viewing contacts, and call history on PC.",
                                    isGranted = isPhoneSmsGranted,
                                    onGrantClick = {
                                        requestPermissionsLauncher.launch(
                                            arrayOf(
                                                android.Manifest.permission.READ_SMS,
                                                android.Manifest.permission.SEND_SMS,
                                                android.Manifest.permission.READ_CONTACTS,
                                                android.Manifest.permission.CALL_PHONE,
                                                android.Manifest.permission.READ_CALL_LOG
                                            )
                                        )
                                    }
                                )

                                // 7. Location (Find Phone)
                                PermissionCard(
                                    title = "GPS Location (Find Phone)",
                                    description = "Shows real-time satellite coordinates on live map when locating phone.",
                                    isGranted = isLocationGranted,
                                    onGrantClick = {
                                        requestPermissionsLauncher.launch(
                                            arrayOf(
                                                android.Manifest.permission.ACCESS_FINE_LOCATION,
                                                android.Manifest.permission.ACCESS_COARSE_LOCATION
                                            )
                                        )
                                    }
                                )

                                Spacer(modifier = Modifier.height(16.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun PermissionCard(
        title: String,
        description: String,
        isGranted: Boolean,
        onGrantClick: () -> Unit
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(text = title, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(text = description, fontSize = 11.sp, color = Color.Gray, lineHeight = 14.sp)
                }

                if (isGranted) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFFE8F5E9)
                    ) {
                        Text(
                            text = "Allowed ✓",
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF2E7D32)
                        )
                    }
                } else {
                    Button(
                        onClick = onGrantClick,
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)
                    ) {
                        Text("Grant", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateAllPermissionStates()
    }

    private fun updateAllPermissionStates() {
        isStorageGrantedState.value = checkStoragePermission()
        isBatteryIgnoredState.value = checkBatteryOptimization()
        isAccessibilityGrantedState.value = checkAccessibilityPermission()
        isNotifAccessState.value = checkNotificationAccess()
        isCameraMicGrantedState.value = checkPermissions(
            arrayOf(android.Manifest.permission.CAMERA, android.Manifest.permission.RECORD_AUDIO)
        )
        isPhoneSmsGrantedState.value = checkPermissions(
            arrayOf(
                android.Manifest.permission.READ_SMS,
                android.Manifest.permission.SEND_SMS,
                android.Manifest.permission.READ_CONTACTS,
                android.Manifest.permission.CALL_PHONE,
                android.Manifest.permission.READ_CALL_LOG
            )
        )
        isLocationGrantedState.value = checkPermissions(
            arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION)
        )
    }

    private fun checkStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            requestPermissionsLauncher.launch(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE))
        }
    }

    private fun checkBatteryOptimization(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(packageName)
        } else true
    }

    @SuppressLint("BatteryLife")
    private fun requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (e2: Exception) {
                    e2.printStackTrace()
                }
            }
        }
    }

    private fun checkAccessibilityPermission(): Boolean {
        return RemoteInputService.instance != null
    }

    private fun checkNotificationAccess(): Boolean {
        return NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
    }

    private fun checkPermissions(permissions: Array<String>): Boolean {
        return permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        nsdManager.unregisterService()
    }
}