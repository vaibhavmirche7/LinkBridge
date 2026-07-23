package com.vaibhavmirche.linkbridge.ui

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import com.google.zxing.integration.android.IntentIntegrator
import com.vaibhavmirche.linkbridge.R
import com.vaibhavmirche.linkbridge.db.AppDatabase
import com.vaibhavmirche.linkbridge.db.TransferDirection
import com.vaibhavmirche.linkbridge.db.TransferLogEntity
import com.vaibhavmirche.linkbridge.db.TransferMode
import com.vaibhavmirche.linkbridge.db.TransferStatus
import com.vaibhavmirche.linkbridge.server.BleDiscoveryHelper
import com.vaibhavmirche.linkbridge.server.BlePeer
import com.vaibhavmirche.linkbridge.util.Constants
import com.vaibhavmirche.linkbridge.util.FileUtils
import com.vaibhavmirche.linkbridge.util.QRCodeGenerator
import com.vaibhavmirche.linkbridge.webrtc.DataChannelEvent
import com.vaibhavmirche.linkbridge.webrtc.PeerConnectionEvent
import com.vaibhavmirche.linkbridge.webrtc.SignalingClient
import com.vaibhavmirche.linkbridge.webrtc.SignalingEvent
import com.vaibhavmirche.linkbridge.webrtc.WebRtcClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.UUID
import kotlin.random.Random

@Serializable
private data class ControlMessage(
    val type: String,
    val code: String? = null,
    val ok: Boolean? = null,
    val fileName: String? = null,
    val fileSize: Long? = null
)

private const val CHUNK_SIZE = 16 * 1024 // 16KB - a safe message size across WebRTC stacks
private const val MAX_BUFFERED_BYTES = 1 * 1024 * 1024 // pause sending past 1MB queued
private const val BLE_SCAN_DURATION_MS = 6000L

class QrTransferActivity : AppCompatActivity() {

    private var isOfferer = false
    private var myCode = ""
    private var webRtcClient: WebRtcClient? = null
    private var signalingClient: SignalingClient? = null
    private var peerDeviceLabel = "the other device"
    private val bleHelper by lazy { BleDiscoveryHelper(this) }

    private val blePermissions: Array<String> =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            arrayOf(android.Manifest.permission.BLUETOOTH_ADVERTISE, android.Manifest.permission.BLUETOOTH_SCAN)
        } else {
            arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
        }
    private var pendingBleAction: (() -> Unit)? = null
    private val blePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val action = pendingBleAction
            pendingBleAction = null
            if (results.values.all { it }) {
                action?.invoke()
            } else {
                Toast.makeText(this, R.string.qr_ble_permission_denied, Toast.LENGTH_SHORT).show()
            }
        }

    private fun withBlePermissions(action: () -> Unit) {
        val missing = blePermissions.any {
            androidx.core.content.ContextCompat.checkSelfPermission(this, it) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing) {
            pendingBleAction = action
            blePermissionLauncher.launch(blePermissions)
        } else {
            action()
        }
    }

    // Outgoing file waiting on file-accept/file-decline
    private var pendingSendUri: Uri? = null
    private var pendingSendName: String? = null
    private var pendingSendSize: Long = 0L

    // Incoming file currently being received
    private var receivingName: String? = null
    private var receivingSize: Long = 0L
    private var receivingBytesSoFar: Long = 0L
    private var receivingOutputStream: java.io.OutputStream? = null
    private var receivingDocFile: DocumentFile? = null

    // Views
    private lateinit var chooserBlock: View
    private lateinit var showBlock: View
    private lateinit var statusBlock: View
    private lateinit var verifyEntryBlock: View
    private lateinit var transferBlock: View
    private lateinit var ivQrCode: ImageView
    private lateinit var tvMyCode: TextView
    private lateinit var progressStatus: ProgressBar
    private lateinit var tvStatus: TextView
    private lateinit var etCodeEntry: EditText
    private lateinit var btnPickFile: View
    private lateinit var tvTransferName: TextView
    private lateinit var progressTransfer: ProgressBar
    private lateinit var tvTransferStatus: TextView

    private val pickFileLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) offerFile(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_qr_transfer)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        chooserBlock = findViewById(R.id.chooserBlock)
        showBlock = findViewById(R.id.showBlock)
        statusBlock = findViewById(R.id.statusBlock)
        verifyEntryBlock = findViewById(R.id.verifyEntryBlock)
        transferBlock = findViewById(R.id.transferBlock)
        ivQrCode = findViewById(R.id.ivQrCode)
        tvMyCode = findViewById(R.id.tvMyCode)
        progressStatus = findViewById(R.id.progressStatus)
        tvStatus = findViewById(R.id.tvStatus)
        etCodeEntry = findViewById(R.id.etCodeEntry)
        btnPickFile = findViewById(R.id.btnPickFile)
        tvTransferName = findViewById(R.id.tvTransferName)
        progressTransfer = findViewById(R.id.progressTransfer)
        tvTransferStatus = findViewById(R.id.tvTransferStatus)

        findViewById<View>(R.id.btnShow).setOnClickListener { startShowFlow() }
        findViewById<View>(R.id.btnScan).setOnClickListener { startScanFlow() }
        findViewById<View>(R.id.btnNearby).setOnClickListener { startNearbyFlow() }
        findViewById<View>(R.id.btnVerifyCode).setOnClickListener { submitCodeEntry() }
        btnPickFile.setOnClickListener { pickFileLauncher.launch(arrayOf("*/*")) }
    }

    // ---------- Signaling server address (only needed by the "show" side) ----------

    private fun getSignalingUrl(): String? =
        getSharedPreferences(Constants.SHARED_PREFS_NAME, Context.MODE_PRIVATE)
            .getString(getString(R.string.pref_key_signaling_url), null)
            ?.takeIf { it.isNotBlank() }

    private fun promptForSignalingUrlThen(onSet: () -> Unit) {
        val existing = getSignalingUrl()
        if (existing != null) {
            onSet()
            return
        }
        val input = EditText(this).apply {
            hint = getString(R.string.qr_signaling_url_hint)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.qr_signaling_not_set)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val url = input.text?.toString()?.trim().orEmpty()
                if (url.startsWith("ws://") || url.startsWith("wss://")) {
                    getSharedPreferences(Constants.SHARED_PREFS_NAME, Context.MODE_PRIVATE).edit {
                        putString(getString(R.string.pref_key_signaling_url), url)
                    }
                    onSet()
                } else {
                    Toast.makeText(this, R.string.qr_signaling_not_set, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---------- Show flow (this device generates the session and is the WebRTC offerer) ----------

    private fun startShowFlow() {
        promptForSignalingUrlThen {
            val signalingUrl = getSignalingUrl() ?: return@promptForSignalingUrlThen
            isOfferer = true
            val sessionId = java.util.UUID.randomUUID().toString()
            myCode = (100000 + Random.nextInt(900000)).toString()

            val payload = "linkbridge://pair?session=$sessionId&signal=" +
                URLEncoder.encode(signalingUrl, "UTF-8")
            ivQrCode.setImageBitmap(QRCodeGenerator.generateQRCode(payload, 720))
            tvMyCode.text = myCode

            chooserBlock.visibility = View.GONE
            showBlock.visibility = View.VISIBLE
            setStatus(getString(R.string.qr_status_waiting), showProgress = true)

            if (bleHelper.isSupported()) {
                withBlePermissions {
                    runCatching { bleHelper.startAdvertising(UUID.fromString(sessionId)) }
                }
            }

            beginSignaling(signalingUrl, sessionId)
        }
    }

    // ---------- Nearby flow (BLE discovery instead of scanning a QR - same idea as Nearby Share) ----------

    private fun startNearbyFlow() {
        if (!bleHelper.isSupported()) {
            Toast.makeText(this, R.string.qr_ble_unsupported, Toast.LENGTH_SHORT).show()
            return
        }
        withBlePermissions {
            isOfferer = false
            Toast.makeText(this, R.string.qr_ble_scanning, Toast.LENGTH_SHORT).show()
            bleHelper.scanForPeers(BLE_SCAN_DURATION_MS, android.os.Handler(mainLooper)) { peers ->
                showNearbyPeers(peers)
            }
        }
    }

    private fun showNearbyPeers(peers: List<BlePeer>) {
        if (peers.isEmpty()) {
            Toast.makeText(this, R.string.qr_ble_none_found, Toast.LENGTH_SHORT).show()
            return
        }
        val labels = peers.map { it.deviceLabel }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.qr_ble_nearby_title)
            .setItems(labels) { _, index ->
                promptForSignalingUrlThen {
                    val signalingUrl = getSignalingUrl() ?: return@promptForSignalingUrlThen
                    chooserBlock.visibility = View.GONE
                    setStatus(getString(R.string.qr_status_connecting), showProgress = true)
                    beginSignaling(signalingUrl, peers[index].sessionId)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---------- Scan flow (this device joins an existing session and is the WebRTC answerer) ----------

    private fun startScanFlow() {
        isOfferer = false
        IntentIntegrator(this)
            .setCaptureActivity(PortraitCaptureActivity::class.java)
            .setOrientationLocked(true)
            .setBeepEnabled(false)
            .initiateScan()
    }

    @Deprecated("IntentIntegrator uses the classic onActivityResult callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data) ?: return
        val scanned = result.contents ?: return // user cancelled

        val uri = runCatching { Uri.parse(scanned) }.getOrNull()
        val sessionId = uri?.getQueryParameter("session")
        val signalRaw = uri?.getQueryParameter("signal")
        if (uri?.scheme != "linkbridge" || sessionId.isNullOrBlank() || signalRaw.isNullOrBlank()) {
            Toast.makeText(this, R.string.qr_invalid_qr, Toast.LENGTH_SHORT).show()
            return
        }
        val signalingUrl = runCatching { URLDecoder.decode(signalRaw, "UTF-8") }.getOrDefault(signalRaw)

        chooserBlock.visibility = View.GONE
        setStatus(getString(R.string.qr_status_connecting), showProgress = true)
        beginSignaling(signalingUrl, sessionId)
    }

    // ---------- Shared signaling + WebRTC wiring ----------

    private fun beginSignaling(signalingUrl: String, sessionId: String) {
        val webRtc = WebRtcClient(this).also { webRtcClient = it }
        val signaling = SignalingClient(signalingUrl).also { signalingClient = it }

        lifecycleScope.launch {
            signaling.events.collect { event -> handleSignalingEvent(event) }
        }
        lifecycleScope.launch {
            webRtc.peerConnectionEvents.collect { event ->
                when (event) {
                    is PeerConnectionEvent.IceCandidateGenerated ->
                        signalingClient?.sendSignal(WebRtcClient.encodeIceCandidate(event.candidate))
                    is PeerConnectionEvent.StateChanged -> Unit
                }
            }
        }
        lifecycleScope.launch {
            webRtc.dataChannelEvents.collect { event -> handleDataChannelEvent(event) }
        }

        signaling.connectAndJoin(sessionId)
    }

    private suspend fun handleSignalingEvent(event: SignalingEvent) {
        val webRtc = webRtcClient ?: return
        when (event) {
            SignalingEvent.Waiting -> setStatus(getString(R.string.qr_status_waiting), true)
            SignalingEvent.Paired -> {
                setStatus(getString(R.string.qr_status_paired), true)
                webRtc.createConnection(isOfferer)
                if (isOfferer) {
                    val offer = webRtc.createOffer()
                    signalingClient?.sendSignal(WebRtcClient.encodeSdp("sdp-offer", offer))
                }
            }
            is SignalingEvent.Signal -> {
                val wire = WebRtcClient.decode(event.payload) ?: return
                when (wire.kind) {
                    "sdp-offer" -> {
                        webRtc.setRemoteDescription(SessionDescription(SessionDescription.Type.OFFER, wire.sdp))
                        val answer = webRtc.createAnswer()
                        signalingClient?.sendSignal(WebRtcClient.encodeSdp("sdp-answer", answer))
                    }
                    "sdp-answer" -> {
                        webRtc.setRemoteDescription(SessionDescription(SessionDescription.Type.ANSWER, wire.sdp))
                    }
                    "ice-candidate" -> {
                        if (wire.candidate != null) {
                            webRtc.addIceCandidate(IceCandidate(wire.sdpMid, wire.sdpMLineIndex ?: 0, wire.candidate))
                        }
                    }
                }
            }
            SignalingEvent.PeerLeft -> setStatus(getString(R.string.qr_status_peer_left), false)
            is SignalingEvent.Error -> setStatus(getString(R.string.qr_status_failed, event.message), false)
            is SignalingEvent.ConnectionFailed -> setStatus(getString(R.string.qr_status_failed, event.reason), false)
        }
    }

    private fun handleDataChannelEvent(event: DataChannelEvent) {
        when (event) {
            DataChannelEvent.Open -> {
                bleHelper.stopAdvertising()
                statusBlock.visibility = View.GONE
                if (isOfferer) {
                    // The code is already visible on screen (showBlock); just wait for the
                    // scanning device to send back what they typed.
                    setStatus(getString(R.string.qr_status_connected), false)
                    statusBlock.visibility = View.VISIBLE
                } else {
                    verifyEntryBlock.visibility = View.VISIBLE
                }
            }
            DataChannelEvent.Closed -> setStatus(getString(R.string.qr_status_peer_left), false)
            is DataChannelEvent.TextReceived -> handleControlMessage(event.text)
            is DataChannelEvent.BinaryReceived -> handleIncomingChunk(event.data)
        }
    }

    private fun handleControlMessage(text: String) {
        val message = runCatching { Json.decodeFromString(ControlMessage.serializer(), text) }.getOrNull() ?: return
        when (message.type) {
            "verify-code" -> {
                // Only the offerer (the one who generated myCode) checks this.
                val ok = message.code == myCode
                sendControl(ControlMessage(type = "verify-result", ok = ok))
                if (ok) showReady()
            }
            "verify-result" -> {
                if (message.ok == true) {
                    showReady()
                } else {
                    runOnUiThread { Toast.makeText(this, R.string.qr_code_mismatch, Toast.LENGTH_SHORT).show() }
                }
            }
            "file-offer" -> {
                val name = message.fileName ?: return
                val size = message.fileSize ?: 0L
                runOnUiThread {
                    AlertDialog.Builder(this)
                        .setTitle(R.string.home_option_qr_title)
                        .setMessage(getString(R.string.qr_incoming_offer, peerDeviceLabel, name, FileUtils.formatFileSize(size)))
                        .setPositiveButton(R.string.accept) { _, _ -> acceptIncomingFile(name, size) }
                        .setNegativeButton(R.string.decline) { _, _ -> sendControl(ControlMessage(type = "file-decline")) }
                        .setCancelable(false)
                        .show()
                }
            }
            "file-accept" -> sendFileData()
            "file-decline" -> {
                pendingSendUri = null
                showTransferStatus(getString(R.string.decline), done = true)
            }
            "file-complete" -> finalizeReceivedFile()
        }
    }

    private fun sendControl(message: ControlMessage) {
        webRtcClient?.sendText(Json.encodeToString(ControlMessage.serializer(), message))
    }

    private fun submitCodeEntry() {
        val code = etCodeEntry.text?.toString()?.trim().orEmpty()
        if (code.length != 6 || code.toIntOrNull() == null) {
            Toast.makeText(this, R.string.qr_invalid_code, Toast.LENGTH_SHORT).show()
            return
        }
        sendControl(ControlMessage(type = "verify-code", code = code))
    }

    private fun showReady() {
        runOnUiThread {
            showBlock.visibility = View.GONE
            statusBlock.visibility = View.GONE
            verifyEntryBlock.visibility = View.GONE
            transferBlock.visibility = View.VISIBLE
        }
    }

    // ---------- Sending a file ----------

    private fun offerFile(uri: Uri) {
        val cursor = contentResolver.query(uri, null, null, null, null)
        var name = uri.lastPathSegment ?: "file"
        var size = 0L
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIdx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                val sizeIdx = it.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (nameIdx >= 0) name = it.getString(nameIdx) ?: name
                if (sizeIdx >= 0) size = it.getLong(sizeIdx)
            }
        }
        pendingSendUri = uri
        pendingSendName = name
        pendingSendSize = size
        sendControl(ControlMessage(type = "file-offer", fileName = name, fileSize = size))
        showTransferStatus(getString(R.string.qr_sending, name), done = false)
    }

    private fun sendFileData() {
        val uri = pendingSendUri ?: return
        val name = pendingSendName ?: "file"
        val totalSize = pendingSendSize
        lifecycleScope.launch(Dispatchers.IO) {
            var sent = 0L
            try {
                contentResolver.openInputStream(uri)?.use { input ->
                    val buffer = ByteArray(CHUNK_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        val chunk = if (read == buffer.size) buffer else buffer.copyOf(read)

                        // Pace against the data channel's own buffer so we don't flood it.
                        while ((webRtcClient?.bufferedAmount() ?: 0L) > MAX_BUFFERED_BYTES) {
                            kotlinx.coroutines.delay(20)
                        }
                        webRtcClient?.sendBinary(chunk)
                        sent += read
                        val percent = if (totalSize > 0) (sent * 100 / totalSize).toInt() else 0
                        withContext(Dispatchers.Main) { updateTransferProgress(percent) }
                    }
                }
                sendControl(ControlMessage(type = "file-complete"))
                logQrTransfer(name, totalSize, uploaded = true, success = true)
                withContext(Dispatchers.Main) { showTransferStatus(getString(R.string.qr_transfer_done), done = true) }
            } catch (e: Exception) {
                logQrTransfer(name, totalSize, uploaded = true, success = false)
                withContext(Dispatchers.Main) { showTransferStatus(getString(R.string.qr_transfer_failed), done = true) }
            } finally {
                pendingSendUri = null
            }
        }
    }

    // ---------- Receiving a file ----------

    private fun acceptIncomingFile(name: String, size: Long) {
        val folderUriString = getSharedPreferences(Constants.SHARED_PREFS_NAME, Context.MODE_PRIVATE)
            .getString(Constants.EXTRA_FOLDER_URI, null)
        val folderUri = folderUriString?.let { Uri.parse(it) }
        val baseDoc = folderUri?.let { DocumentFile.fromTreeUri(this, it) }
        val mime = android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(name.substringAfterLast('.', "")) ?: "application/octet-stream"
        val newFile = baseDoc?.createFile(mime, name)

        if (newFile == null) {
            Toast.makeText(this, R.string.qr_transfer_failed, Toast.LENGTH_SHORT).show()
            sendControl(ControlMessage(type = "file-decline"))
            return
        }
        receivingName = name
        receivingSize = size
        receivingBytesSoFar = 0L
        receivingDocFile = newFile
        receivingOutputStream = contentResolver.openOutputStream(newFile.uri)
        sendControl(ControlMessage(type = "file-accept"))
        showTransferStatus(getString(R.string.qr_receiving, name), done = false)
    }

    private fun handleIncomingChunk(bytes: ByteArray) {
        val out = receivingOutputStream ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                out.write(bytes)
                receivingBytesSoFar += bytes.size
                val percent = if (receivingSize > 0) (receivingBytesSoFar * 100 / receivingSize).toInt() else 0
                withContext(Dispatchers.Main) { updateTransferProgress(percent) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { showTransferStatus(getString(R.string.qr_transfer_failed), done = true) }
            }
        }
    }

    private fun finalizeReceivedFile() {
        val name = receivingName ?: return
        val size = receivingSize
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { receivingOutputStream?.close() }
            logQrTransfer(name, size, uploaded = false, success = true)
            withContext(Dispatchers.Main) { showTransferStatus(getString(R.string.qr_transfer_done), done = true) }
        }
        receivingOutputStream = null
        receivingName = null
        receivingDocFile = null
    }

    private suspend fun logQrTransfer(fileName: String, size: Long, uploaded: Boolean, success: Boolean) {
        runCatching {
            AppDatabase.getInstance(this).transferLogDao().insert(
                TransferLogEntity(
                    fileName = fileName,
                    fileSize = size,
                    direction = if (uploaded) TransferDirection.UPLOADED else TransferDirection.DOWNLOADED,
                    timestamp = System.currentTimeMillis(),
                    mode = TransferMode.QR,
                    deviceName = peerDeviceLabel,
                    status = if (success) TransferStatus.SUCCESS else TransferStatus.FAILED
                )
            )
        }
    }

    private fun updateTransferProgress(percent: Int) {
        progressTransfer.visibility = View.VISIBLE
        progressTransfer.progress = percent.coerceIn(0, 100)
    }

    private fun showTransferStatus(text: String, done: Boolean) {
        runOnUiThread {
            btnPickFile.isEnabled = done
            tvTransferName.visibility = View.VISIBLE
            tvTransferName.text = text
            tvTransferStatus.visibility = View.VISIBLE
            if (done) {
                progressTransfer.visibility = View.GONE
                tvTransferStatus.text = ""
            }
        }
    }

    private fun setStatus(text: String, showProgress: Boolean) {
        runOnUiThread {
            statusBlock.visibility = View.VISIBLE
            tvStatus.text = text
            progressStatus.visibility = if (showProgress) View.VISIBLE else View.GONE
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { receivingOutputStream?.close() }
        signalingClient?.close()
        webRtcClient?.close()
        bleHelper.stopAdvertising()
        bleHelper.stopScan()
    }
}
