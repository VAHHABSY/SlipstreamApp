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
        const val ACTION_LOG = "net.typeblob.socks.LOG"
        const val EXTRA_STATUS_SLIPSTREAM = "status_slipstream"
        const val EXTRA_STATUS_SOCKS5 = "status_socks5"
        const val EXTRA_ERROR_MESSAGE = "error_message"
        const val EXTRA_ERROR_OUTPUT = "error_output"
        const val EXTRA_LOG_MESSAGE = "log_message"
        const val EXTRA_LOG_IS_ERROR = "log_is_error"
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
            broadcastLog("Profile unchanged and alive. Skipping.")
            return START_STICKY
        }

        resolversConfig = newResolvers
        domainNameConfig = newDomain
        socks5Port = newSocks5Port

        broadcastLog("Service starting - Domain: $domainNameConfig, Port: $socks5Port")

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
                        broadcastLog("Main execution cancelled")
                    } catch (e: Exception) {
                        handleError("Setup failed", e.message ?: "Unknown error")
                    }
                }

        return START_STICKY
    }

    private suspend fun startTunnel() =
            tunnelMutex.withLock {
                if (slipstreamProcess?.isAlive == true) {
                    broadcastLog("Tunnel already running")
                    return
                }

                try {
                    broadcastLog("Step 1: Copying binary to cache dir")
                    val slipstreamPath = copyBinaryToCacheDir(SLIPSTREAM_BINARY_NAME)

                    if (slipstreamPath == null) {
                        handleError("Binary setup failed", "Could not copy slipstream binary")
                        return
                    }

                    broadcastLog("Step 2: Binary at: $slipstreamPath")
                    val slipstreamFile = File(slipstreamPath)
                    
                    broadcastLog("Step 3: Binary exists: ${slipstreamFile.exists()}")
                    broadcastLog("Step 4: Binary size: ${slipstreamFile.length()} bytes")
                    broadcastLog("Step 5: Binary executable: ${slipstreamFile.canExecute()}")
                    
                    if (!slipstreamFile.canExecute()) {
                        broadcastLog("Binary not executable, setting permissions...", isError = true)
                        try {
                            val chmodProcess = Runtime.getRuntime().exec("chmod 755 $slipstreamPath")
                            val exitCode = chmodProcess.waitFor()
                            broadcastLog("chmod exit code: $exitCode")
                        } catch (e: Exception) {
                            broadcastLog("chmod failed: ${e.message}", isError = true)
                        }
                    }

                    broadcastLog("Step 6: After chmod - executable: ${slipstreamFile.canExecute()}")

                    if (!slipstreamFile.canExecute()) {
                        handleError("Permission Error", "Cannot execute binary at $slipstreamPath")
                        return
                    }

                    val commandList = mutableListOf(slipstreamPath, domainNameConfig)
                    resolversConfig.forEach { commandList.add(it) }
                    commandList.add("--socks-port")
                    commandList.add(socks5Port)

                    broadcastLog("Step 7: Command: ${commandList.joinToString(" ")}")
                    broadcastLog("Step 8: Starting process from cache dir...")

                    slipstreamProcess = ProcessBuilder(commandList)
                            .redirectErrorStream(true)
                            .start()

                    broadcastLog("Step 9: Process started, alive: ${slipstreamProcess?.isAlive}")

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
                                        broadcastLog("[slipstream:$lineCount] $line")
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
                                    broadcastLog("Output stream closed after $lineCount lines")
                                } catch (e: Exception) {
                                    broadcastLog("Reader error: ${e.message}", isError = true)
                                }
                            }

                    delay(1500)
                    val isAliveAfterDelay = slipstreamProcess?.isAlive ?: false
                    broadcastLog("Step 10: After delay, alive: $isAliveAfterDelay")
                    
                    if (!isAliveAfterDelay) {
                        val exitValue = try {
                            slipstreamProcess?.exitValue()
                        } catch (e: Exception) {
                            "unknown"
                        }
                        broadcastLog("Process died! Exit code: $exitValue", isError = true)
                        handleError("Slipstream failed", "Process died. Exit code: $exitValue")
                        return
                    }

                    sendStatusUpdate("Running", "Running on port $socks5Port")
                    broadcastLog("Step 11: Starting monitoring")
                    startMonitoring()
                } catch (e: Exception) {
                    broadcastLog("Error: ${e.javaClass.simpleName}: ${e.message}", isError = true)
                    e.printStackTrace()
                    handleError("Slipstream failed", "${e.javaClass.simpleName}: ${e.message}")
                }
            }

    private suspend fun stopTunnel() =
            tunnelMutex.withLock {
                try {
                    broadcastLog("Stopping tunnel...")
                    sendStatusUpdate("Stopping...", "Stopping...")
                    tunnelMonitorJob?.cancel()
                    slipstreamReaderJob?.cancel()

                    slipstreamProcess?.destroy()
                    slipstreamProcess?.waitFor()
                    slipstreamProcess = null

                    sendStatusUpdate("Stopped", "Stopped")
                    broadcastLog("Tunnel stopped")
                } catch (e: Exception) {
                    broadcastLog("Error stopping: ${e.message}", isError = true)
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
                                broadcastLog("Process died unexpectedly!", isError = true)
                                handleError("Slipstream crashed", "Process terminated unexpectedly")
                                break
                            }
                        }
                    } catch (e: CancellationException) {
                        broadcastLog("Monitor cancelled")
                    }
                }
    }

    private fun handleError(message: String, detail: String) {
        broadcastLog("ERROR: $message - $detail", isError = true)
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
        broadcastLog("Status ($reason): Slip=$slipStatus, SOCKS5=$socksStatus")
        sendStatusUpdate(slipStatus, socksStatus)
    }

    override fun onDestroy() {
        broadcastLog("Service destroying")
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

    private fun broadcastLog(message: String, isError: Boolean = false) {
        Log.d(TAG, message)
        val intent = Intent(ACTION_LOG).apply {
            putExtra(EXTRA_LOG_MESSAGE, message)
            putExtra(EXTRA_LOG_IS_ERROR, isError)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun copyBinaryToCacheDir(name: String): String? {
        // Use cacheDir instead of filesDir - cacheDir doesn't have noexec mount option
        val file = File(cacheDir, name)
        return try {
            // Always recopy to ensure fresh binary with correct permissions
            if (file.exists()) {
                broadcastLog("Deleting existing binary in cache...")
                file.delete()
            }
            
            broadcastLog("Copying binary from assets to cache dir...")
            assets.open(name).use { input ->
                file.outputStream().use { output -> 
                    val bytes = input.copyTo(output)
                    broadcastLog("Copied $bytes bytes from assets")
                }
            }
            
            broadcastLog("Setting executable permissions...")
            
            // Set permissions using Java API
            file.setExecutable(true, false)
            file.setReadable(true, false)
            file.setWritable(true, true)
            
            // Also try chmod as backup
            try {
                val chmodProcess = Runtime.getRuntime().exec(arrayOf("chmod", "755", file.absolutePath))
                val exitCode = chmodProcess.waitFor()
                broadcastLog("chmod exit: $exitCode")
                
                // Read chmod output if any
                val output = chmodProcess.inputStream.bufferedReader().readText()
                if (output.isNotEmpty()) {
                    broadcastLog("chmod output: $output")
                }
                val error = chmodProcess.errorStream.bufferedReader().readText()
                if (error.isNotEmpty()) {
                    broadcastLog("chmod error: $error", isError = true)
                }
            } catch (e: Exception) {
                broadcastLog("chmod failed: ${e.message}", isError = true)
            }
            
            // Verify file permissions
            if (file.canExecute()) {
                broadcastLog("$name is executable ✓")
            } else {
                broadcastLog("$name NOT executable ✗", isError = true)
                
                // Try one more time with absolute chmod path
                try {
                    broadcastLog("Trying /system/bin/chmod...")
                    val chmodProcess2 = Runtime.getRuntime().exec(arrayOf("/system/bin/chmod", "777", file.absolutePath))
                    val exitCode2 = chmodProcess2.waitFor()
                    broadcastLog("chmod777 exit: $exitCode2")
                    
                    if (file.canExecute()) {
                        broadcastLog("Success after chmod 777 ✓")
                    } else {
                        broadcastLog("Still not executable after chmod 777 ✗", isError = true)
                    }
                } catch (e2: Exception) {
                    broadcastLog("chmod 777 failed: ${e2.message}", isError = true)
                }
            }
            
            file.absolutePath
        } catch (e: Exception) {
            broadcastLog("Copy failed: ${e.message}", isError = true)
            e.printStackTrace()
            null
        }
    }

    private fun cleanUpLingeringProcesses() {
        try {
            broadcastLog("Cleaning up old processes...")
            val killProcess = Runtime.getRuntime().exec(arrayOf("killall", "-9", SLIPSTREAM_BINARY_NAME))
            val exitCode = killProcess.waitFor()
            broadcastLog("Cleanup exit: $exitCode")
        } catch (e: Exception) {
            broadcastLog("Cleanup: ${e.message}")
        }
    }
}