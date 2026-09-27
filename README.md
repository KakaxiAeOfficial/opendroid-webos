# 📱 OpenDroid WebOS

> **Next-Generation, Open-Source AirDroid Alternative for Android & Web**  
> Control your Android phone from any PC browser anywhere in the world over **5G, 4G, or Local Wi-Fi** with zero port forwarding, ultra-low latency, and military-grade privacy.

🌐 **Live Web Controller:** [https://kakaxiaeofficial.github.io/opendroid-webos/](https://kakaxiaeofficial.github.io/opendroid-webos/)  
📦 **Download Latest APK:** [Releases Page](https://github.com/KakaxiAeOfficial/opendroid-webos/releases)

---

## ✨ Features Overview

### 1. 🌐 Central Account & Multi-Device Hub
- **No More Re-typing Codes:** Sign in with your Account Email on both Phone and PC. Your phone permanently binds to your account.
- **Multi-Device Selector:** Manage multiple Android phones from a single desktop taskbar dropdown with live battery and online status badges.
- **Fast 5G Cloud Connectivity:** Powered by a dedicated private serverless EMQX MQTT cluster with SSL encryption (`ssl://...:8883` & `wss://...:8084`).

### 2. 📱 High-FPS Screen Mirroring & Remote Control
- **Direct Real-Time Mirroring:** ~30 FPS screen streaming with adaptive compression.
- **Full Touch Injection:** Mouse clicks translate to taps, mouse drag translates to swipes/scrolls.
- **Hardware Nav Keys:** Back, Home, and Recents buttons.
- **⏺️ Screen Video Recorder:** Record screen mirror sessions into high-definition `.webm` video files with live timer.
- **📸 1-Click Screenshot:** Capture crystal-clear PNG snapshots directly to PC storage.

### 3. 🎥 Remote Camera & Surveillance
- **Direct Camera Feed:** Front and Back camera streaming with on-demand flashlight toggle.
- **📸 High-Res Photo Capture:** Capture full sensor resolution photos silently and download them to PC.
- **🎙️ Two-Way Audio (Walkie-Talkie):** Push-to-Talk button in PC Web OS to speak directly through the phone's loudspeaker, plus live ambient phone mic listening.

### 4. 📁 Advanced File Explorer & Media Manager
- **Remote File Management:** Browse internal storage, upload files, delete, rename, and create folders.
- **📦 Batch ZIP Download:** Multi-select files or folders and compress them to ZIP for 1-click download.
- **📥 Remote APK Installer:** Upload or click any `.apk` file to launch the system package installer on the phone.
- **Media Lightboxes:** Instant preview for Photos, Audio player with seekbar, and Video streaming player.

### 5. 📞 Telephony, Messaging & GPS
- **SMS & Dialer:** Read incoming/outgoing messages, compose SMS, and initiate phone calls.
- **Call Logs:** Search through the last 200 call logs.
- **Live GPS Tracker:** Real-time location marker powered by Leaflet and high-performance CARTO basemaps with accuracy circle and 1-click coordinate copy.

### 6. 🥷 Stealth Mode & Anti-Theft Security
- **Launcher Icon Disguise:** Dynamically hide the app icon from the phone's launcher.
- **Secret Dialer Access:** Re-open the app anytime by dialling `*#*#9988#*#*` or `*#*#1234#*#*`.
- **Remote Screen Lock:** Instant force-lock via Device Administrator API.
- **⚠️ Emergency Remote Factory Reset:** Remotely wipe the device with dual confirmation if lost or stolen.

---

## 🛠️ Architecture & Tech Stack

- **Mobile Client:** Native Android (Kotlin, Jetpack Compose, Camera2 API, AudioTrack, DevicePolicyManager, AccessibilityService, NotificationListenerService).
- **Web Client:** Single-Page Web OS (HTML5, TailwindCSS, Web Audio API, Canvas MediaRecorder, MQTT.js, Leaflet).
- **Cloud Signaling:** Dedicated Private EMQX Serverless Cluster with TLS/SSL encryption.
- **CI/CD:** Automated GitHub Actions pipeline building signed production APKs.

---

## 📄 License
This project is open-source and free for personal use under the MIT License.

