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
    private var slipstreamReaderJob: Job? = null
    private var tunnelMonitorJob: Job? = null
    private var mainExecutionJob: Job? = null

    private val tunnelMutex = Mutex()

    private var resolversConfig: ArrayList<String> = arrayListOf()
    private var domainNameConfig: String = ""
    private var socks5Port: String = "1080"
    private var isRestarting = false

    companion object {
        const val EXTRA_RESOLVERS = "extra_ip_addresses_list"
        const val EXTRA_DOMAIN = "domain_name"
        const val EXTRA_SOCKS5_PORT = "socks5_port"
        const val SLIPSTREAM_BINARY_NAME = "slipstream-client"
        const val ACTION_STATUS_UPDATE = "net.typeblob.socks.STATUS_UPDATE"
        const val ACTION_ERROR = "net.typeblob.socks.ERROR"
        const val ACTION_REQUEST_STATUS = "net.typeblob.socks.REQUEST_STATUS"
        const val EXTRA_STATUS_SLIPSTREAM = "status_slipstream"
        const val EXTRA_STATUS_SOCKS5 = "status_socks5"
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
        val newSocks5Port = intent?.getStringExtra(EXTRA_SOCKS5_PORT) ?: "1080"

        if (newResolvers == resolversConfig &&
                        newDomain == domainNameConfig &&
                        newSocks5Port == socks5Port &&
                        slipstreamProcess?.isAlive == true
        ) {
            Log.d(TAG, "Profile unchanged and alive. Skipping.")
            return START_STICKY
        }

        resolversConfig = newResolvers
        domainNameConfig = newDomain
        socks5Port = newSocks5Port

        Log.d(TAG, "Service starting/updating profile. Domain: $domainNameConfig, SOCKS5 Port: $socks5Port")

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
                    Log.d(TAG, "Step 1: Copying binary to files dir")
                    val slipstreamPath = copyBinaryToFilesDir(SLIPSTREAM_BINARY_NAME)

                    if (slipstreamPath == null) {
                        handleError("Binary setup failed", "Could not copy slipstream binary")
                        return
                    }

                    Log.d(TAG, "Step 2: Binary copied to: $slipstreamPath")
                    val slipstreamFile = File(slipstreamPath)
                    
                    Log.d(TAG, "Step 3: Checking if binary exists: ${slipstreamFile.exists()}")
                    Log.d(TAG, "Step 4: Binary size: ${slipstreamFile.length()} bytes")
                    Log.d(TAG, "Step 5: Checking if binary is executable: ${slipstreamFile.canExecute()}")
                    
                    if (!slipstreamFile.canExecute()) {
                        Log.e(TAG, "Slipstream binary not executable, attempting to set permissions")
                        try {
                            val chmodProcess = Runtime.getRuntime().exec("chmod 755 $slipstreamPath")
                            val exitCode = chmodProcess.waitFor()
                            Log.d(TAG, "chmod exit code: $exitCode")
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to chmod slipstream: ${e.message}")
                        }
                    }

                    Log.d(TAG, "Step 6: After chmod - canExecute: ${slipstreamFile.canExecute()}")

                    if (!slipstreamFile.canExecute()) {
                        handleError("Permission Error", "Cannot execute slipstream binary at $slipstreamPath")
                        return
                    }

                    val commandList = mutableListOf(slipstreamPath, domainNameConfig)
                    resolversConfig.forEach { commandList.add(it) }
                    commandList.add("--socks-port")
                    commandList.add(socks5Port)

                    Log.d(TAG, "Step 7: Starting slipstream with command: ${commandList.joinToString(" ")}")
                    Log.d(TAG, "Step 8: Working directory: ${filesDir.absolutePath}")

                    slipstreamProcess = ProcessBuilder(commandList)
                            .redirectErrorStream(true)
                            .directory(filesDir)
                            .start()

                    Log.d(TAG, "Step 9: Process started, checking if alive: ${slipstreamProcess?.isAlive}")

                    slipstreamReaderJob =
                            launch {
                                val reader =
                                        BufferedReader(
                                                InputStreamReader(
                                                        slipstreamProcess?.inputStream
                                                )
                                        )
                                try {
                                    var lineCount = 0
                                    reader.forEachLine { line ->
                                        lineCount++
                                        Log.d(TAG, "[slipstream:$lineCount] $line")
                                        if (line.contains(
                                                        "ListenerBind_Init failed",
                                                        ignoreCase = true
                                                ) || line.contains("error", ignoreCase = true)
                                        ) {
                                            launch { handleError("Slipstream error", line) }
                                        }
                                        if (line.contains("SOCKS", ignoreCase = true) && 
                                            line.contains("listening", ignoreCase = true)) {
                                            sendStatusUpdate("Running", "Running on port $socks5Port")
                                        }
                                    }
                                    Log.d(TAG, "Slipstream output stream closed after $lineCount lines")
                                } catch (e: Exception) {
                                    Log.e(TAG, "Slipstream reader error: ${e.message}", e)
                                }
                            }

                    delay(1500)
                    val isAliveAfterDelay = slipstreamProcess?.isAlive ?: false
                    Log.d(TAG, "Step 10: After 1.5s delay, process alive: $isAliveAfterDelay")
                    
                    if (!isAliveAfterDelay) {
                        val exitValue = try {
                            slipstreamProcess?.exitValue()
                        } catch (e: Exception) {
                            "unknown"
                        }
                        handleError("Slipstream failed", "Process died immediately. Exit code: $exitValue")
                        return
                    }

                    sendStatusUpdate("Running", "Running on port $socks5Port")
                    Log.d(TAG, "Step 11: Starting monitoring")
                    startMonitoring()
                } catch (e: Exception) {
                    Log.e(TAG, "Error starting tunnel: ${e.message}", e)
                    e.printStackTrace()
                    handleError("Slipstream failed", "${e.javaClass.simpleName}: ${e.message}")
                }
            }

    private suspend fun stopTunnel() =
            tunnelMutex.withLock {
                try {
                    Log.d(TAG, "Stopping tunnel...")
                    sendStatusUpdate("Stopping...", "Stopping...")
                    tunnelMonitorJob?.cancel()
                    slipstreamReaderJob?.cancel()

                    slipstreamProcess?.destroy()
                    slipstreamProcess?.waitFor()
                    slipstreamProcess = null

                    sendStatusUpdate("Stopped", "Stopped")
                    Log.d(TAG, "Tunnel stopped successfully")
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
                                Log.e(TAG, "Slipstream process died unexpectedly")
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
        val socksStatus = 
                if (slipstreamProcess?.isAlive == true) "Running on port $socks5Port" else "Stopped"
        Log.d(TAG, "Status request ($reason): Slip=$slipStatus, SOCKS5=$socksStatus")
        sendStatusUpdate(slipStatus, socksStatus)
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
                .setContentText("SOCKS5 proxy running on port $socks5Port")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pendingIntent)
                .build()
    }

    private fun sendStatusUpdate(slipstreamStatus: String, socksStatus: String) {
        val intent =
                Intent(ACTION_STATUS_UPDATE).apply {
                    putExtra(EXTRA_STATUS_SLIPSTREAM, slipstreamStatus)
                    putExtra(EXTRA_STATUS_SOCKS5, socksStatus)
                }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun copyBinaryToFilesDir(name: String): String? {
        val file = File(filesDir, name)
        return try {
            if (!file.exists()) {
                Log.d(TAG, "Binary does not exist in filesDir, copying from assets...")
                assets.open(name).use { input ->
                    file.outputStream().use { output -> 
                        val bytes = input.copyTo(output)
                        Log.d(TAG, "Copied $bytes bytes from assets")
                    }
                }
            } else {
                Log.d(TAG, "Binary already exists in filesDir, size: ${file.length()} bytes")
            }
            
            // Set executable permissions using multiple methods
            Log.d(TAG, "Setting executable permissions...")
            file.setExecutable(true, false)
            file.setReadable(true, false)
            file.setWritable(true, true)
            
            // Also try chmod as fallback
            try {
                val chmodProcess = Runtime.getRuntime().exec("chmod 755 ${file.absolutePath}")
                val exitCode = chmodProcess.waitFor()
                Log.d(TAG, "Set permissions for $name using chmod (exit: $exitCode)")
            } catch (e: Exception) {
                Log.w(TAG, "chmod failed for $name: ${e.message}")
            }
            
            // Verify permissions
            if (file.canExecute()) {
                Log.d(TAG, "$name is executable ✓")
            } else {
                Log.e(TAG, "$name is NOT executable ✗")
            }
            
            file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy binary $name: ${e.message}", e)
            e.printStackTrace()
            null
        }
    }

    private fun cleanUpLingeringProcesses() {
        try {
            Log.d(TAG, "Cleaning up lingering processes...")
            val killProcess = Runtime.getRuntime().exec(arrayOf("killall", "-9", SLIPSTREAM_BINARY_NAME))
            val exitCode = killProcess.waitFor()
            Log.d(TAG, "Cleanup exit code: $exitCode")
        } catch (e: Exception) {
            Log.d(TAG, "Process cleanup: ${e.message}")
        }
    }
}