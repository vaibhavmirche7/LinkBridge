package com.vaibhavmirche.linkbridge.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.core.content.edit
import android.net.Uri
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.vaibhavmirche.linkbridge.util.Constants
import com.vaibhavmirche.linkbridge.MainActivity
import com.vaibhavmirche.linkbridge.R
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import timber.log.Timber

data class IpPermissionRequest(val ipAddress: String, val deferred: CompletableDeferred<Boolean>)

@kotlinx.serialization.Serializable
data class FileMeta(val name: String, val size: Long)
data class TransferRequest(
    val id: String,
    val deviceIp: String,
    val deviceName: String,
    val files: List<FileMeta>,
    val deferred: CompletableDeferred<Boolean>
)


class FileServerService : Service(), SharedPreferences.OnSharedPreferenceChangeListener {

    private val binder = LocalBinder()
    private var ktorServer: EmbeddedServer<*, *>? = null
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private val _serverState = MutableStateFlow<ServerState>(ServerState.UserStopped)
    val serverState: StateFlow<ServerState> = _serverState.asStateFlow()

    private val _ipPermissionRequests =
        MutableSharedFlow<IpPermissionRequest>(replay = 0, extraBufferCapacity = 1)
    val ipPermissionRequests = _ipPermissionRequests.asSharedFlow()

    private val _transferRequests =
        MutableSharedFlow<TransferRequest>(replay = 0, extraBufferCapacity = 1)
    val transferRequests = _transferRequests.asSharedFlow()
    private val approvedTransferTokens = mutableMapOf<String, Long>()

    private val _pullRefresh = MutableSharedFlow<Unit>(replay = 0)
    val pullRefresh: SharedFlow<Unit> = _pullRefresh.asSharedFlow()

    private lateinit var sharedPreferences: SharedPreferences
    private val connectedDevices = mutableMapOf<String, ConnectedDevice>()
    private val _connectedDevicesFlow = MutableStateFlow<List<ConnectedDevice>>(emptyList())
    val connectedDevicesFlow: StateFlow<List<ConnectedDevice>> = _connectedDevicesFlow.asStateFlow()
    private lateinit var networkHelper: NetworkHelper
    private val lanDiscoveryHelper by lazy { LanDiscoveryHelper(this) }

    var currentSharedFolderUri: Uri? = null
        private set

    @Volatile
    private var isActivityInForeground = false
    private val pendingNotificationRequests = mutableMapOf<String, CompletableDeferred<Boolean>>()
    private val pendingTransferRequests = mutableMapOf<String, TransferRequest>()

    private val pendingIntentFlags by lazy {
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    }

    inner class LocalBinder : Binder() {
        fun getService(): FileServerService = this@FileServerService
    }

    companion object {
        private val logger = Timber.tag("FileServerServiceKtor")

        // Constants for IP Permission Notification
        const val PERMISSION_NOTIFICATION_CHANNEL_ID = "ip_permission_channel"
        const val ACTION_IP_PERMISSION_RESPONSE =
            "com.vaibhavmirche.linkbridge.ACTION_IP_PERMISSION_RESPONSE"
        const val EXTRA_IP_ADDRESS = "extra_ip_address"
        const val EXTRA_IP_PERMISSION_APPROVED = "extra_ip_permission_approved"
        const val ACTION_TRANSFER_RESPONSE = "com.vaibhavmirche.linkbridge.ACTION_TRANSFER_RESPONSE"
        const val EXTRA_TRANSFER_REQUEST_ID = "extra_transfer_request_id"
        const val EXTRA_TRANSFER_APPROVED = "extra_transfer_approved"
    }

    override fun onCreate() {
        super.onCreate()
        sharedPreferences = getSharedPreferences(Constants.SHARED_PREFS_NAME, MODE_PRIVATE)

        sharedPreferences.registerOnSharedPreferenceChangeListener(this) // track changes
        createNotificationChannel()

        networkHelper = NetworkHelper(this)
        networkHelper.register()
        observeIpChanges()

        logger.d("FileServerService onCreate")
    }

    private fun observeIpChanges() {
        serviceScope.launch {
            networkHelper.networkInfo.collectLatest { info ->
                val ipAddress = info.mainIp

                val currentState = _serverState.value
                if (currentState is ServerState.Error || currentState is ServerState.UserStopped) { // ignore user stopped/error IP changes
                    return@collectLatest
                }

                if (currentState is ServerState.AwaitNetwork || currentState is ServerState.Running) {
                    if (ipAddress != null) {
                        if (currentState is ServerState.Running && currentState.hosts.mainIp != ipAddress) {
                            logger.i("IP address changed from ${currentState.hosts.mainIp} to $ipAddress. restarting server.")
                            startKtorServer()
                        }
                        else{ // ServerState.AwaitNetwork
                            logger.i("IP address changed to $ipAddress.  restarting server.")
                            startKtorServer()
                        }
                        updateNotification()
                    } else {
                        logger.w("WiFi disconnected. Stopping server.")
                        stopKtorServer(ServerState.AwaitNetwork) // This will also update notification
                    }

                }
            }
        }
    }

    fun activityResumed() {
        isActivityInForeground = true
        if (pendingNotificationRequests.isNotEmpty()) {
            serviceScope.launch {
                val requestsToForward = pendingNotificationRequests.toMap()
                pendingNotificationRequests.clear()
                requestsToForward.forEach { (ip, deferred) ->
                    val notificationManager =
                        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    notificationManager.cancel(ip.hashCode())
                    logger.d("Forwarding background IP request for $ip to foreground activity.")
                    _ipPermissionRequests.emit(IpPermissionRequest(ip, deferred))
                }
            }
        }
        if (pendingTransferRequests.isNotEmpty()) {
            serviceScope.launch {
                val requestsToForward = pendingTransferRequests.toMap()
                pendingTransferRequests.clear()
                requestsToForward.forEach { (id, request) ->
                    val notificationManager =
                        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    notificationManager.cancel(id.hashCode())
                    logger.d("Forwarding background transfer request $id to foreground activity.")
                    _transferRequests.emit(request)
                }
            }
        }
    }

    fun activityPaused() {
        isActivityInForeground = false
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        val passwordKey = getString(R.string.pref_key_server_password)
        val ipPermissionKey = getString(R.string.pref_key_ip_permission_enabled)
        val portKey = getString(R.string.pref_key_server_port)

        // Check if a setting that requires a server restart was changed
        if (key == passwordKey || key == ipPermissionKey || key == portKey) {
            // Only restart if the server is currently running
            if (ktorServer != null) {
                logger.i("A server setting changed. Restarting Ktor server.")
                startKtorServer() // The restart is now handled by startKtorServer
            }
        }
    }

    // Called from the server to create a “refresh” event
    fun notifyFilePushed() {
        CoroutineScope(Dispatchers.Default).launch {
            _pullRefresh.emit(Unit)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(Constants.NOTIFICATION_ID, createNotification())
        logger.d("onStartCommand: ${intent?.action}")
        when (intent?.action) {
            Constants.ACTION_START_SERVICE -> {
                val folderUriString = intent.getStringExtra(Constants.EXTRA_FOLDER_URI)
                if (folderUriString != null) {
                    currentSharedFolderUri = folderUriString.toUri()
                    startKtorServer()
                } else {
                    logger.e("Folder URI missing, stopping service")
                    _serverState.value = ServerState.Error("Folder URI missing.")
                    stopSelf()
                }
            }

            Constants.ACTION_STOP_SERVICE -> {
                stopKtorServer(ServerState.UserStopped)
                stopSelf()
            }

            ACTION_IP_PERMISSION_RESPONSE -> handleIpPermissionResponse(intent)
            ACTION_TRANSFER_RESPONSE -> handleTransferResponse(intent)
        }
        return START_NOT_STICKY
    }

    private fun handleTransferResponse(intent: Intent) {
        val requestId = intent.getStringExtra(EXTRA_TRANSFER_REQUEST_ID) ?: return
        val approved = intent.getBooleanExtra(EXTRA_TRANSFER_APPROVED, false)
        val request = pendingTransferRequests.remove(requestId)
        val deferred = request?.deferred
        if (deferred != null && !deferred.isCompleted) {
            logger.i("Completing transfer request $requestId with result: $approved via notification.")
            deferred.complete(approved)
        }
        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(requestId.hashCode())
    }

    private fun handleIpPermissionResponse(intent: Intent) {
        val ipAddress = intent.getStringExtra(EXTRA_IP_ADDRESS)
        val approved = intent.getBooleanExtra(EXTRA_IP_PERMISSION_APPROVED, false)
        if (ipAddress == null) {
            logger.e("IP address missing in permission response intent.")
            return
        }
        val deferred = pendingNotificationRequests.remove(ipAddress)
        if (deferred != null) {
            if (!deferred.isCompleted) {
                logger.i("Completing permission for $ipAddress with result: $approved via notification.")
                deferred.complete(approved)
            }
        }
        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(ipAddress.hashCode())
    }

    private fun getServerPort(): Int {
        return sharedPreferences.getInt(
            getString(R.string.pref_key_server_port),
            Constants.DEFAULT_SERVER_PORT
        )
    }

    private fun startKtorServer() {
        serviceScope.launch {
            _serverState.value = ServerState.Starting
            updateNotification()

            if (ktorServer != null) {
                logger.i("Stopping existing Ktor server for restart...")
                ktorServer?.stop(500, 1000)
                ktorServer = null
            }

            if (currentSharedFolderUri == null) {
                _serverState.value = ServerState.Error("Shared folder not set.")
                updateNotification()
                return@launch
            }
            val baseDocFile =
                DocumentFile.fromTreeUri(this@FileServerService, currentSharedFolderUri!!)
            if (baseDocFile == null || !baseDocFile.canRead()) {
                _serverState.value = ServerState.Error("Shared folder not accessible.")
                updateNotification()
                return@launch
            }

            try {
                val networkState = networkHelper.networkInfo.value

                val ipAddress = networkState.mainIp
                if (ipAddress == null) {
                    _serverState.value = ServerState.AwaitNetwork
                    logger.e("Failed to get local IP address.")
                    updateNotification()
                    return@launch
                }

                val serviceProvider = { this@FileServerService }
                val port = getServerPort()
                ktorServer =
                    embeddedServer(CIO, port = port, host = "0.0.0.0", module = {
                        ktorServer(
                            applicationContext, serviceProvider, currentSharedFolderUri!!
                        )
                    }).apply {
                        start(wait = false)
                    }

                _serverState.value = ServerState.Running(networkState, port)
                lanDiscoveryHelper.advertise(port, android.os.Build.MODEL ?: "LinkBridge")
                logger.i("Ktor Server started on $ipAddress:$port")
                updateNotification()
            } catch (e: Exception) {
                val cause = e.cause;
                if (cause is java.net.BindException){
                    val port = getServerPort()
                    logger.e("Port $port is already in use. ")
                    _serverState.value = ServerState.Error("Port $port is already in use.")
                };
                else{
                logger.e(e)
                _serverState.value = ServerState.Error("INTERNAL: Failed to start server: ${e.localizedMessage}")

                }
                ktorServer?.stop(1000, 2000)
                ktorServer = null
                updateNotification()
            }
        }
    }

    private fun stopKtorServer(state: ServerState) {
        serviceScope.launch {
            try {
                ktorServer?.stop(1000, 2000)
            } catch (e: Exception) {
                logger.e(e, "Exception while stopping Ktor server $e")
            } finally {
                ktorServer = null
                lanDiscoveryHelper.stopAdvertising()
                _serverState.value = state
                logger.i("Ktor Server stopped.")
                stopForeground(STOP_FOREGROUND_REMOVE)
                if (state != ServerState.UserStopped) updateNotification() // if user stopped, the notification can be removed
            }

        }
    }

    suspend fun requestIpApprovalFromClient(ipAddress: String, deviceName: String? = null): Boolean {
        logger.d("Requesting IP approval from $ipAddress")
        val now = System.currentTimeMillis()
        val hadExpired = connectedDevices.entries.removeIf { (_, device) -> device.expiresAt <= now }
        if (hadExpired) publishConnectedDevices()

        connectedDevices[ipAddress]?.let { existing ->
            // Already approved - refresh the name/expiry rather than asking again.
            connectedDevices[ipAddress] = existing.copy(
                name = deviceName?.takeIf { it.isNotBlank() } ?: existing.name,
                expiresAt = now + Constants.IP_PERMISSION_VALIDITY_MS
            )
            publishConnectedDevices()
            return true
        }

        val deferred = CompletableDeferred<Boolean>()
        if (isActivityInForeground) {
            logger.d("Activity is in foreground. Emitting request to UI.")
            _ipPermissionRequests.emit(IpPermissionRequest(ipAddress, deferred))
        } else {
            logger.d("Activity is in background. Showing notification for IP permission.")
            pendingNotificationRequests[ipAddress] = deferred
            showIpPermissionNotification(ipAddress)
        }

        val approved = try {
            deferred.await()
        } catch (e: CancellationException) {
            logger.w("IP approval for $ipAddress was cancelled.")
            pendingNotificationRequests.remove(ipAddress)
            false
        }

        if (approved) {
            val resolvedName = deviceName?.takeIf { it.isNotBlank() } ?: ipAddress
            connectedDevices[ipAddress] = ConnectedDevice(
                ip = ipAddress,
                name = resolvedName,
                connectedAt = now,
                expiresAt = now + Constants.IP_PERMISSION_VALIDITY_MS
            )
            publishConnectedDevices()
            serviceScope.launch {
                runCatching {
                    com.vaibhavmirche.linkbridge.db.AppDatabase.getInstance(this@FileServerService)
                        .connectionLogDao()
                        .insert(
                            com.vaibhavmirche.linkbridge.db.ConnectionLogEntity(
                                deviceName = resolvedName,
                                ip = ipAddress,
                                connectedAt = now
                            )
                        )
                }
            }
        }
        return approved
    }

    private fun publishConnectedDevices() {
        _connectedDevicesFlow.value = connectedDevices.values.sortedByDescending { it.connectedAt }
    }

    /** Records one file transfer (upload or download) to the History table. */
    fun logTransfer(fileName: String, size: Long, deviceName: String, uploaded: Boolean, success: Boolean) {
        serviceScope.launch {
            runCatching {
                com.vaibhavmirche.linkbridge.db.AppDatabase.getInstance(this@FileServerService)
                    .transferLogDao()
                    .insert(
                        com.vaibhavmirche.linkbridge.db.TransferLogEntity(
                            fileName = fileName,
                            fileSize = size,
                            direction = if (uploaded) com.vaibhavmirche.linkbridge.db.TransferDirection.UPLOADED
                                        else com.vaibhavmirche.linkbridge.db.TransferDirection.DOWNLOADED,
                            timestamp = System.currentTimeMillis(),
                            mode = com.vaibhavmirche.linkbridge.db.TransferMode.LAN,
                            deviceName = deviceName,
                            status = if (success) com.vaibhavmirche.linkbridge.db.TransferStatus.SUCCESS
                                      else com.vaibhavmirche.linkbridge.db.TransferStatus.FAILED
                        )
                    )
            }
        }
    }

    /**
     * Asks the user (via the same foreground/notification path as IP approval) whether to
     * accept an incoming batch of files, showing names and sizes. Returns a short-lived token
     * to authorize the actual upload(s) if accepted, or null if declined/cancelled.
     */
    suspend fun requestTransferApproval(
        deviceIp: String,
        deviceName: String,
        files: List<FileMeta>
    ): String? {
        val now = System.currentTimeMillis()
        approvedTransferTokens.entries.removeIf { (_, expiry) -> expiry <= now }

        val requestId = java.util.UUID.randomUUID().toString()
        val deferred = CompletableDeferred<Boolean>()
        val request = TransferRequest(requestId, deviceIp, deviceName, files, deferred)
        logger.d("Requesting transfer approval from $deviceName ($deviceIp) for ${files.size} file(s)")

        if (isActivityInForeground) {
            _transferRequests.emit(request)
        } else {
            // No foreground UI to show a rich dialog - fall back to a notification with
            // direct Accept/Decline actions, same pattern as IP permission requests.
            pendingTransferRequests[requestId] = request
            showTransferRequestNotification(request)
        }

        val approved = try {
            withTimeoutOrNull(Constants.TRANSFER_APPROVAL_TIMEOUT_MS) { deferred.await() } ?: false
        } catch (e: CancellationException) {
            false
        } finally {
            pendingTransferRequests.remove(requestId)
        }

        if (!approved) return null
        val token = java.util.UUID.randomUUID().toString()
        approvedTransferTokens[token] = now + Constants.TRANSFER_TOKEN_VALIDITY_MS
        return token
    }

    fun isTransferTokenValid(token: String?): Boolean {
        if (token == null) return false
        val now = System.currentTimeMillis()
        approvedTransferTokens.entries.removeIf { (_, expiry) -> expiry <= now }
        return approvedTransferTokens.containsKey(token)
    }

    private fun showTransferRequestNotification(request: TransferRequest) {
        val notificationManager =
            getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val uniqueId = request.id.hashCode()
        val openIntent = Intent(this, MainActivity::class.java)
        val openPendingIntent =
            PendingIntent.getActivity(this, uniqueId, openIntent, pendingIntentFlags)

        val acceptIntent = Intent(this, FileServerService::class.java).apply {
            action = ACTION_TRANSFER_RESPONSE
            putExtra(EXTRA_TRANSFER_REQUEST_ID, request.id)
            putExtra(EXTRA_TRANSFER_APPROVED, true)
        }
        val acceptPendingIntent =
            PendingIntent.getService(this, uniqueId * 2, acceptIntent, pendingIntentFlags)

        val declineIntent = Intent(this, FileServerService::class.java).apply {
            action = ACTION_TRANSFER_RESPONSE
            putExtra(EXTRA_TRANSFER_REQUEST_ID, request.id)
            putExtra(EXTRA_TRANSFER_APPROVED, false)
        }
        val declinePendingIntent =
            PendingIntent.getService(this, uniqueId * 2 + 1, declineIntent, pendingIntentFlags)

        val notification = NotificationCompat.Builder(this, PERMISSION_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_name)
            .setContentTitle(getString(R.string.transfer_request_notification_title, request.deviceName))
            .setContentText(
                resources.getQuantityString(
                    R.plurals.transfer_request_file_count, request.files.size, request.files.size
                )
            )
            .setContentIntent(openPendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(0, getString(R.string.accept), acceptPendingIntent)
            .addAction(0, getString(R.string.decline), declinePendingIntent)
            .build()
        notificationManager.notify(uniqueId, notification)
    }

    fun isIpPermissionRequired(): Boolean =
        sharedPreferences.getBoolean(getString(R.string.pref_key_ip_permission_enabled), true)

    /**
     * Web login is now mandatory for every connection (see #settings), so there is always a
     * password: if the user never set one, a random one is generated and persisted the first
     * time it's needed, rather than leaving the server open.
     */
    fun getServerPassword(): String {
        val existing = sharedPreferences.getString(getString(R.string.pref_key_server_password), null)
        if (!existing.isNullOrEmpty()) return existing
        val generated = generateRandomPassword()
        sharedPreferences.edit { putString(getString(R.string.pref_key_server_password), generated) }
        return generated
    }

    fun checkPassword(providedPassword: String): Boolean = getServerPassword() == providedPassword

    private fun generateRandomPassword(): String {
        val chars = "ABCDEFGHJKMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789"
        return (1..8).map { chars.random() }.joinToString("")
    }

    private fun createNotificationChannel() {
        val serviceChannel = NotificationChannel(
            Constants.NOTIFICATION_CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        serviceChannel.description = getString(R.string.notification_channel_description)
        val permissionChannel = NotificationChannel(
            PERMISSION_NOTIFICATION_CHANNEL_ID,
            "IP Permission Requests",
            NotificationManager.IMPORTANCE_HIGH
        )
        permissionChannel.description =
            "Shows notifications to allow or deny connections from new IP addresses."
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(serviceChannel)
        manager.createNotificationChannel(permissionChannel)
    }

    private fun showIpPermissionNotification(ipAddress: String) {
        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val uniqueId = ipAddress.hashCode()
        val contentIntent = Intent(this, MainActivity::class.java).let {
            PendingIntent.getActivity(this, uniqueId, it, pendingIntentFlags)
        }
        val allowIntent = Intent(this, FileServerService::class.java).apply {
            action = ACTION_IP_PERMISSION_RESPONSE
            putExtra(EXTRA_IP_ADDRESS, ipAddress)
            putExtra(EXTRA_IP_PERMISSION_APPROVED, true)
        }
        val allowPendingIntent =
            PendingIntent.getService(this, uniqueId * 2, allowIntent, pendingIntentFlags)
        val denyIntent = Intent(this, FileServerService::class.java).apply {
            action = ACTION_IP_PERMISSION_RESPONSE
            putExtra(EXTRA_IP_ADDRESS, ipAddress)
            putExtra(EXTRA_IP_PERMISSION_APPROVED, false)
        }
        val denyPendingIntent =
            PendingIntent.getService(this, uniqueId * 2 + 1, denyIntent, pendingIntentFlags)
        val notification = NotificationCompat.Builder(this, PERMISSION_NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.permission_request_title))
            .setContentText(getString(R.string.permission_request_message, ipAddress))
            .setSmallIcon(R.drawable.ic_stat_name).setContentIntent(contentIntent)
            .setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(0, getString(R.string.allow), allowPendingIntent)
            .addAction(0, getString(R.string.deny), denyPendingIntent).build()
        notificationManager.notify(uniqueId, notification)
    }

    private fun updateNotification() {
        val notification = createNotification()
        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(Constants.NOTIFICATION_ID, notification)
    }

    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent =
            PendingIntent.getActivity(this, 0, notificationIntent, pendingIntentFlags)
        val stopIntent = Intent(this, FileServerService::class.java).apply {
            action = Constants.ACTION_STOP_SERVICE
        }
        val stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, pendingIntentFlags)

        val (title, text) = when (val state = _serverState.value) {
            is ServerState.Running -> getString(R.string.file_server_notification_title) to getString(
                R.string.file_server_notification_text, state.hosts.mainIp, state.port
            )

            is ServerState.Starting -> getString(R.string.file_server_notification_title) to getString(
                R.string.server_starting
            )

            is ServerState.UserStopped -> getString(R.string.file_server_notification_title) to getString(
                R.string.server_stopped
            )
            is ServerState.AwaitNetwork -> getString(R.string.file_server_notification_title) to getString(
                R.string.waiting_for_network
            )

            is ServerState.Error -> getString(R.string.file_server_notification_title) to getString(
                R.string.server_error_format, state.message
            )
        }

        return NotificationCompat.Builder(this, Constants.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title).setContentText(text).setSmallIcon(R.drawable.ic_stat_name)
            .setContentIntent(pendingIntent).setOngoing(true)
            .addAction(R.drawable.ic_stop_black, getString(R.string.stop_server), stopPendingIntent)
            .build()
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        logger.d("FileServerService onDestroy")
        networkHelper.unregister()
        sharedPreferences.unregisterOnSharedPreferenceChangeListener(this)
        stopKtorServer(ServerState.UserStopped) // Ensure server is stopped
        serviceJob.cancel() // Cancel all coroutines in this scope
        super.onDestroy()
    }

}