package com.example.localutility

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.util.Log
import org.json.JSONObject
import org.webrtc.*
import org.webrtc.CameraVideoCapturer.CameraSwitchHandler

class WebRtcManager private constructor(private val context: Context) {

    companion object {
        @Volatile
        private var instance: WebRtcManager? = null

        fun getInstance(context: Context): WebRtcManager {
            return instance ?: synchronized(this) {
                instance ?: WebRtcManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val rootEglBase: EglBase = EglBase.create()
    private var peerConnectionFactory: PeerConnectionFactory? = null
    var peerConnection: PeerConnection? = null

    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var currentVideoCapturer: VideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null

    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null

    var onSendMessage: ((String) -> Unit)? = null
    var isFrontCamera = false

    init {
        initWebRtc()
    }

    private fun initWebRtc() {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(true)
                .createInitializationOptions()
        )

        val encoderFactory = DefaultVideoEncoderFactory(rootEglBase.eglBaseContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(rootEglBase.eglBaseContext)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()

        createPeerConnection()
        startAudioCapture()
    }

    private fun createPeerConnection() {
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:openrelay.metered.ca:80").createIceServer(),
            PeerConnection.IceServer.builder("turn:openrelay.metered.ca:80")
                .setUsername("openrelayproject")
                .setPassword("openrelayproject")
                .createIceServer(),
            PeerConnection.IceServer.builder("turn:openrelay.metered.ca:443")
                .setUsername("openrelayproject")
                .setPassword("openrelayproject")
                .createIceServer(),
            PeerConnection.IceServer.builder("turn:openrelay.metered.ca:443?transport=tcp")
                .setUsername("openrelayproject")
                .setPassword("openrelayproject")
                .createIceServer()
        )

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        peerConnection = peerConnectionFactory?.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                val json = JSONObject().apply {
                    put("type", "candidate")
                    put("sdpMLineIndex", candidate.sdpMLineIndex)
                    put("sdpMid", candidate.sdpMid)
                    put("candidate", candidate.sdp)
                }
                onSendMessage?.invoke(json.toString())
            }
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                Log.d("WebRTC", "IceConnectionState: $state")
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(dataChannel: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {}
        })

        // Pre-allocate Video Transceiver so the video channel is ALWAYS open in SDP
        try {
            peerConnection?.addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY)
            )
        } catch (e: Exception) {
            Log.e("WebRTC", "Transceiver allocation error", e)
        }
    }

    private fun startAudioCapture() {
        try {
            val audioConstraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            }
            audioSource = peerConnectionFactory?.createAudioSource(audioConstraints)
            audioTrack = peerConnectionFactory?.createAudioTrack("ARDAMSa0", audioSource)
            peerConnection?.addTrack(audioTrack, listOf("ARDAMS"))
        } catch (e: Exception) {
            Log.e("WebRTC", "Audio init error", e)
        }
    }

    private fun replaceVideoTrack(newCapturer: VideoCapturer, isScreencast: Boolean) {
        try {
            currentVideoCapturer?.stopCapture()
            currentVideoCapturer?.dispose()
            surfaceTextureHelper?.dispose()

            currentVideoCapturer = newCapturer
            surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", rootEglBase.eglBaseContext)

            videoSource = peerConnectionFactory?.createVideoSource(isScreencast)
            currentVideoCapturer?.initialize(surfaceTextureHelper, context, videoSource!!.capturerObserver)
            currentVideoCapturer?.startCapture(1280, 720, 30)

            val newTrack = peerConnectionFactory?.createVideoTrack("ARDAMSv0", videoSource)
            newTrack?.setEnabled(true)

            // Inject the new camera track into the pre-allocated transceiver
            val transceiver = peerConnection?.transceivers?.find {
                it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO
            }

            if (transceiver != null) {
                transceiver.sender.setTrack(newTrack, true)
            } else {
                val sender = peerConnection?.senders?.find { it.track()?.kind() == "video" }
                if (sender != null) {
                    sender.setTrack(newTrack, true)
                } else {
                    peerConnection?.addTrack(newTrack, listOf("ARDAMS"))
                }
            }
            videoTrack = newTrack
        } catch (e: Exception) {
            Log.e("WebRTC", "replaceVideoTrack error", e)
        }
    }

    fun startScreenCapture(mediaProjectionIntent: Intent) {
        val screenCapturer = ScreenCapturerAndroid(mediaProjectionIntent, object : MediaProjection.Callback() {})
        replaceVideoTrack(screenCapturer, true)
    }

    fun startCameraCapture(frontFacing: Boolean) {
        isFrontCamera = frontFacing
        val enumerator = Camera2Enumerator(context)
        var targetDevice: String? = null

        for (deviceName in enumerator.deviceNames) {
            if (enumerator.isFrontFacing(deviceName) == frontFacing) {
                targetDevice = deviceName
                break
            }
        }
        if (targetDevice == null) targetDevice = enumerator.deviceNames.firstOrNull()

        targetDevice?.let {
            val cameraCapturer = enumerator.createCapturer(it, null)
            replaceVideoTrack(cameraCapturer, false)
        }
    }

    fun switchCamera() {
        if (currentVideoCapturer is CameraVideoCapturer) {
            (currentVideoCapturer as CameraVideoCapturer).switchCamera(object : CameraSwitchHandler {
                override fun onCameraSwitchDone(isFront: Boolean) { isFrontCamera = isFront }
                override fun onCameraSwitchError(error: String?) { Log.e("WebRTC", "Switch error: $error") }
            })
        }
    }

    fun handleRemoteOffer(sdp: String) {
        val sessionDescription = SessionDescription(SessionDescription.Type.OFFER, sdp)
        peerConnection?.setRemoteDescription(SimpleSdpObserver(), sessionDescription)

        peerConnection?.createAnswer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(desc: SessionDescription?) {
                desc?.let {
                    peerConnection?.setLocalDescription(SimpleSdpObserver(), it)
                    val json = JSONObject().apply {
                        put("type", "answer")
                        put("sdp", it.description)
                    }
                    onSendMessage?.invoke(json.toString())
                }
            }
        }, MediaConstraints())
    }

    fun handleRemoteIceCandidate(sdpMid: String, sdpMLineIndex: Int, sdp: String) {
        val candidate = IceCandidate(sdpMid, sdpMLineIndex, sdp)
        peerConnection?.addIceCandidate(candidate)
    }

    fun stopCapture() {
        try {
            currentVideoCapturer?.stopCapture()
            currentVideoCapturer?.dispose()
            surfaceTextureHelper?.dispose()
            peerConnection?.close()
        } catch (e: Exception) {
            Log.e("WebRTC", "Stop capture error", e)
        }
    }
}

open class SimpleSdpObserver : SdpObserver {
    override fun onCreateSuccess(desc: SessionDescription?) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String?) {}
    override fun onSetFailure(error: String?) {}
}