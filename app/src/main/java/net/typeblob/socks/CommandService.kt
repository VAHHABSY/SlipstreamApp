package net.typeblob.socks

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class CommandResult(val exitCode: Int, val output: String)

class CommandService : LifecycleService(), CoroutineScope {

    private val job = SupervisorJob()
    override val coroutineContext: CoroutineContext = job + Dispatchers.IO

    private val TAG = "CommandService"
    private val NOTIFICATION_CHANNEL_ID = "CommandServiceChannel"
    private val NOTIFICATION_ID = 101

    private val mainHandler = Handler(Looper.getMainLooper())

    private var slipstreamProcess: Process? = null
    private var proxyProcess: Process? = null
    private var slipstreamReaderJob: Job? = null
    private var proxyReaderJob: Job? = null
    private var tunnelMonitorJob: Job? = null
    private var mainExecutionJob: Job? = null

    private val tunnelMutex = Mutex()

    private var resolversConfig: ArrayList<String> = arrayListOf()
    private var domainNameConfig: String = ""
    private var privateKeyPath: String = ""
    private var isRestarting = false

    companion object {
        const val EXTRA_RESOLVERS = "extra_ip_addresses_list"
        const val EXTRA_DOMAIN = "domain_name"
        const val EXTRA_KEY_PATH = "private_key_path"
        const val SLIPSTREAM_BINARY_NAME = "slipstream-client"
        const val PROXY_CLIENT_BINARY_NAME = "proxy-client"
        const val ACTION_STATUS_UPDATE = "net.typeblob.socks.STATUS_UPDATE"
        const val ACTION_ERROR = "net.typeblob.socks.ERROR"
        const val ACTION_REQUEST_STATUS = "net.typeblob.socks.REQUEST_STATUS"
        const val EXTRA_STATUS_SLIPSTREAM = "status_slipstream"
        const val EXTRA_STATUS_SSH = "status_ssh"
        const val EXTRA_ERROR_MESSAGE = "error_message"
        const val EXTRA_ERROR_OUTPUT = "error_output"
        private const val MONITOR_INTERVAL_MS = 2000L
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (intent?.action == ACTION_REQUEST_STATUS) {
            sendCurrentStatus("Request")
            return START_STICKY
        }

        val newResolvers = intent?.getStringArrayListExtra(EXTRA_RESOLVERS) ?: arrayListOf()
        val newDomain = intent?.getStringExtra(EXTRA_DOMAIN) ?: ""
        val newPrivateKeyPath = intent?.getStringExtra(EXTRA_KEY_PATH) ?: ""

        if (newResolvers == resolversConfig &&
                        newDomain == domainNameConfig &&
                        slipstreamProcess?.isAlive == true
        ) {
            Log.d(TAG, "Profile unchanged and alive. Skipping.")
            return START_STICKY
        }

        resolversConfig = newResolvers
        domainNameConfig = newDomain
        privateKeyPath = newPrivateKeyPath

        Log.d(TAG, "Service starting/updating profile. Domain: $domainNameConfig")

        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)

        cleanUpLingeringProcesses()
        sendStatusUpdate("Cleaning up...", "Waiting...")

        mainExecutionJob?.cancel()
        mainExecutionJob =
                launch {
                    try {
                        delay(1000)
                        stopTunnel()
                        delay(500)
                        startTunnel()
                    } catch (e: CancellationException) {
                        Log.d(TAG, "Main execution cancelled")
                    } catch (e: Exception) {
                        handleError("Setup failed", e.message ?: "Unknown error")
                    }
                }

        return START_STICKY
    }

    private suspend fun startTunnel() =
            tunnelMutex.withLock {
                if (slipstreamProcess?.isAlive == true) {
                    Log.d(TAG, "Tunnel already running")
                    return
                }

                try {
                    val slipstreamPath = copyBinaryToFilesDir(SLIPSTREAM_BINARY_NAME)
                    val proxyPath = copyBinaryToFilesDir(PROXY_CLIENT_BINARY_NAME)

                    if (slipstreamPath == null || proxyPath == null) {
                        handleError("Binary setup failed", "Could not copy binaries")
                        return
                    }

                    // Verify executable permissions
                    val slipstreamFile = File(slipstreamPath)
                    val proxyFile = File(proxyPath)
                    
                    if (!slipstreamFile.canExecute()) {
                        Log.e(TAG, "Slipstream binary not executable, attempting to set permissions")
                        Runtime.getRuntime().exec("chmod 755 $slipstreamPath").waitFor()
                    }
                    
                    if (!proxyFile.canExecute()) {
                        Log.e(TAG, "Proxy binary not executable, attempting to set permissions")
                        Runtime.getRuntime().exec("chmod 755 $proxyPath").waitFor()
                    }

                    // Verify again after chmod
                    if (!slipstreamFile.canExecute()) {
                        handleError("Permission Error", "Cannot execute slipstream binary")
                        return
                    }

                    val commandList = mutableListOf(slipstreamPath, domainNameConfig)
                    resolversConfig.forEach { commandList.add(it) }

                    Log.d(TAG, "Starting slipstream: ${commandList.joinToString(" ")}")

                    slipstreamProcess = ProcessBuilder(commandList).redirectErrorStream(true).start()

                    slipstreamReaderJob =
                            launch {
                                val reader =
                                        BufferedReader(
                                                InputStreamReader(
                                                        slipstreamProcess?.inputStream
                                                )
                                        )
                                try {
                                    reader.forEachLine { line ->
                                        Log.d(TAG, "[slipstream] $line")
                                        if (line.contains(
                                                        "ListenerBind_Init failed",
                                                        ignoreCase = true
                                                )
                                        ) {
                                            launch { handleError("Listener bind failed", line) }
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.d(TAG, "Slipstream reader stopped: ${e.message}")
                                }
                            }

                    delay(1000)
                    if (slipstreamProcess?.isAlive != true) {
                        handleError("Slipstream failed", "Process not alive after start")
                        return
                    }

                    sendStatusUpdate("Running", "Waiting...")

                    // Start SSH proxy if key is provided
                    if (privateKeyPath.isNotBlank() && File(privateKeyPath).exists()) {
                        val proxyCommand = listOf(proxyPath, privateKeyPath)
                        Log.d(TAG, "Starting proxy: ${proxyCommand.joinToString(" ")}")

                        proxyProcess =
                                ProcessBuilder(proxyCommand).redirectErrorStream(true).start()

                        proxyReaderJob =
                                launch {
                                    val reader =
                                            BufferedReader(
                                                    InputStreamReader(proxyProcess?.inputStream)
                                            )
                                    try {
                                        reader.forEachLine { line ->
                                            Log.d(TAG, "[proxy] $line")
                                            if (line.contains("error", ignoreCase = true)) {
                                                launch { handleError("Proxy error", line) }
                                            }
                                        }
                                    } catch (e: Exception) {
                                        Log.d(TAG, "Proxy reader stopped: ${e.message}")
                                    }
                                }

                        delay(500)
                        if (proxyProcess?.isAlive == true) {
                            sendStatusUpdate("Running", "Running")
                        } else {
                            sendStatusUpdate("Running", "Failed")
                        }
                    } else {
                        Log.d(TAG, "No valid SSH key provided, skipping proxy")
                        sendStatusUpdate("Running", "Stopped (No Key)")
                    }

                    startMonitoring()
                } catch (e: Exception) {
                    Log.e(TAG, "Error starting tunnel: ${e.message}", e)
                    handleError("Slipstream failed", e.message ?: "Unknown error")
                }
            }

    private suspend fun stopTunnel() =
            tunnelMutex.withLock {
                try {
                    sendStatusUpdate("Stopping...", "Stopping...")
                    tunnelMonitorJob?.cancel()
                    slipstreamReaderJob?.cancel()
                    proxyReaderJob?.cancel()

                    proxyProcess?.destroy()
                    proxyProcess?.waitFor()
                    proxyProcess = null

                    slipstreamProcess?.destroy()
                    slipstreamProcess?.waitFor()
                    slipstreamProcess = null

                    sendStatusUpdate("Stopped", "Stopped")
                } catch (e: Exception) {
                    Log.e(TAG, "Error stopping tunnel", e)
                }
            }

    private fun startMonitoring() {
        tunnelMonitorJob?.cancel()
        tunnelMonitorJob =
                launch {
                    try {
                        while (isActive) {
                            delay(MONITOR_INTERVAL_MS)
                            if (slipstreamProcess?.isAlive == false) {
                                handleError("Slipstream crashed", "Process terminated unexpectedly")
                                break
                            }
                        }
                    } catch (e: CancellationException) {
                        Log.d(TAG, "Monitor cancelled")
                    }
                }
    }

    private fun handleError(message: String, detail: String) {
        Log.e(TAG, "Error: $message - $detail")
        sendStatusUpdate("Failed: $message", "Stopped")
        val intent =
                Intent(ACTION_ERROR).apply {
                    putExtra(EXTRA_ERROR_MESSAGE, message)
                    putExtra(EXTRA_ERROR_OUTPUT, detail)
                }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        launch { stopTunnel() }
    }

    private fun sendCurrentStatus(reason: String) {
        val slipStatus =
                if (slipstreamProcess?.isAlive == true) "Running" else "Stopped"
        val sshStatus = if (proxyProcess?.isAlive == true) "Running" else "Stopped"
        Log.d(TAG, "Status request ($reason): Slip=$slipStatus, SSH=$sshStatus")
        sendStatusUpdate(slipStatus, sshStatus)
    }

    override fun onBind(intent: Intent?): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onDestroy() {
        Log.d(TAG, "Service onDestroy")
        job.cancel()
        runBlocking { stopTunnel() }
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel =
                    NotificationChannel(
                                    NOTIFICATION_CHANNEL_ID,
                                    "Command Service",
                                    NotificationManager.IMPORTANCE_LOW
                            )
                            .apply { description = "Background command execution" }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent =
                PendingIntent.getActivity(
                        this,
                        0,
                        intent,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle("SlipstreamApp")
                .setContentText("Running in background...")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pendingIntent)
                .build()
    }

    private fun sendStatusUpdate(slipstreamStatus: String, sshStatus: String) {
        val intent =
                Intent(ACTION_STATUS_UPDATE).apply {
                    putExtra(EXTRA_STATUS_SLIPSTREAM, slipstreamStatus)
                    putExtra(EXTRA_STATUS_SSH, sshStatus)
                }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun copyBinaryToFilesDir(name: String): String? {
        val file = File(filesDir, name)
        return try {
            if (!file.exists()) {
                Log.d(TAG, "Copying $name from assets to ${file.absolutePath}")
                assets.open(name).use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                }
            }
            
            // Set executable permissions using multiple methods
            file.setExecutable(true, false) // Java method
            file.setReadable(true, false)
            file.setWritable(true, true)
            
            // Also try chmod as fallback
            try {
                Runtime.getRuntime().exec("chmod 755 ${file.absolutePath}").waitFor()
                Log.d(TAG, "Set permissions for $name using chmod")
            } catch (e: Exception) {
                Log.w(TAG, "chmod failed for $name: ${e.message}")
            }
            
            // Verify permissions
            if (file.canExecute()) {
                Log.d(TAG, "$name is executable")
            } else {
                Log.e(TAG, "$name is NOT executable after permission setting")
            }
            
            file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy binary $name: ${e.message}", e)
            null
        }
    }

    private fun cleanUpLingeringProcesses() {
        try {
            Runtime.getRuntime().exec(arrayOf("killall", "-9", SLIPSTREAM_BINARY_NAME)).waitFor()
            Runtime.getRuntime().exec(arrayOf("killall", "-9", PROXY_CLIENT_BINARY_NAME)).waitFor()
        } catch (e: Exception) {
            Log.d(TAG, "Process cleanup: ${e.message}")
        }
    }
}