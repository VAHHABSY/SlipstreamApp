package net.typeblob.socks

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.File
import java.io.IOException

class CommandService : Service() {
    private val binder = LocalBinder()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var slipstreamJob: Job? = null
    private var slipstreamProcess: Process? = null
    private var isSlipstreamRunning = false

    private var statusCallback: ((SlipstreamStatus, SocksStatus) -> Unit)? = null
    private var logCallback: ((String) -> Unit)? = null

    companion object {
        private const val TAG = "SlipstreamService"
        private const val NOTIFICATION_CHANNEL_ID = "slipstream_channel"
        private const val NOTIFICATION_CHANNEL_NAME = "Slipstream Service"
        private const val NOTIFICATION_ID = 1
    }

    inner class LocalBinder : Binder() {
        fun getService(): CommandService = this@CommandService
    }

    override fun onBind(intent: Intent): IBinder = binder

    fun setStatusCallback(callback: (SlipstreamStatus, SocksStatus) -> Unit) {
        statusCallback = callback
    }

    fun setLogCallback(callback: (String) -> Unit) {
        logCallback = callback
    }

    private fun log(message: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        val logMessage = "[$timestamp] $message"
        Log.d(TAG, message)
        logCallback?.invoke(logMessage)
    }

    private fun createNotification(title: String, text: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                NOTIFICATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            )
            mgr.createNotificationChannel(channel)
        }

        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(title: String, text: String) {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val updated = createNotification(title, text)
        mgr.notify(NOTIFICATION_ID, updated)
    }

    private fun updateStatus(slipstream: SlipstreamStatus, socks: SocksStatus) {
        statusCallback?.invoke(slipstream, socks)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        log("[Service] Service starting...")

        val domain = intent?.getStringExtra("domain") ?: ""
        val resolvers = intent?.getStringExtra("resolvers") ?: ""
        val port = intent?.getIntExtra("port", 1081) ?: 1081

        if (domain.isBlank() || resolvers.isBlank()) {
            log("[Service] ERROR: Missing domain or resolvers")
            updateStatus(SlipstreamStatus.Failed("Missing configuration"), SocksStatus.Stopped)
            stopSelf()
            return START_NOT_STICKY
        }

        log("[Service] Service starting - Domain: $domain, Port: $port")

        startForeground(NOTIFICATION_ID, createNotification("Starting...", "Initializing proxy"))

        scope.launch {
            try {
                // Clean up any existing processes
                log("[Service] Cleaning up old processes...")
                killSlipstreamProcesses()

                updateStatus(SlipstreamStatus.Starting("Starting tunnel..."), SocksStatus.Waiting)

                // Start slipstream
                startSlipstreamProcess(domain, resolvers, port)

                // Set status to running immediately after process starts and health check
                updateStatus(SlipstreamStatus.Running, SocksStatus.Running)
                updateNotification("Running", "SOCKS5 proxy on port $port")
                log("[Service] Tunnel started successfully")

            } catch (e: Exception) {
                log("[Service] ERROR: ${e.javaClass.simpleName} - ${e.message}")
                updateStatus(SlipstreamStatus.Failed("Slipstream failed"), SocksStatus.Stopped)
                stopSelf()
            }
        }

        return START_STICKY
    }

    private fun startSlipstreamProcess(domain: String, resolvers: String, port: Int) {
        try {
            log("[Service] Step 1: Native lib dir: ${applicationInfo.nativeLibraryDir}")

            val libPath = File(applicationInfo.nativeLibraryDir, "libslipstream.so")
            log("[Service] Step 2: Library path: ${libPath.absolutePath}")

            if (!libPath.exists()) {
                throw IOException("Library not found at: ${libPath.absolutePath}")
            }

            log("[Service] Step 3: Library exists: true")
            log("[Service] Step 4: Library size: ${libPath.length()} bytes")

            // Execute slipstream as a separate process instead of JNI
            log("[Service] Step 5: Starting slipstream process...")
            
            // Mark as running before launching
            isSlipstreamRunning = true
            
            // Build command arguments
            val command = arrayOf(
                libPath.absolutePath,
                domain,
                resolvers,
                "--socks-port",
                port.toString()
            )
            
            log("[Service] Command: ${command.joinToString(" ")}")
            
            // Start the process
            slipstreamProcess = ProcessBuilder(*command).start()
            
            // Check if process is still alive after a short delay
            delay(2000)  // Wait 2 seconds for startup
            if (!slipstreamProcess.isAlive()) {
                val exitCode = slipstreamProcess.exitValue()
                log("[Service] Slipstream process died immediately with exit code: $exitCode")
                throw IOException("Slipstream process failed to start (exit code: $exitCode)")
            }
            
            // Monitor the process in background
            slipstreamJob = scope.launch {
                try {
                    val exitCode = slipstreamProcess?.waitFor() ?: -1
                    log("[Service] Slipstream process exited with code: $exitCode")
                    
                    if (exitCode == 0) {
                        log("[Service] Slipstream completed successfully")
                    } else {
                        log("[Service] Slipstream failed with exit code: $exitCode")
                    }
                } catch (e: Exception) {
                    log("[Service] Slipstream process error: ${e.message}")
                } finally {
                    isSlipstreamRunning = false
                    log("[Service] Slipstream stopped, updating status")
                    updateStatus(SlipstreamStatus.Stopped, SocksStatus.Stopped)
                }
            }

            log("[Service] Step 6: Slipstream process started and healthy")

        } catch (e: Exception) {
            log("[Service] Error: ${e.javaClass.simpleName}: ${e.message}")
            isSlipstreamRunning = false
            e.printStackTrace()
            throw e
        }
    }

    private fun killSlipstreamProcesses() {
        // Try multiple variants; ignore errors if command not present
        val commands = listOf(
            arrayOf("killall", "slipstream"),
            arrayOf("/system/bin/killall", "slipstream"),
            arrayOf("toybox", "killall", "slipstream"),
            arrayOf("busybox", "killall", "slipstream"),
            arrayOf("killall", "libslipstream.so"),
            arrayOf("/system/bin/killall", "libslipstream.so")
        )
        for (cmd in commands) {
            try {
                Runtime.getRuntime().exec(cmd).waitFor()
            } catch (_: Exception) {
                // Ignore
            }
        }
    }

    fun stopTunnel() {
        log("[Service] Stopping tunnel...")

        try {
            slipstreamJob?.cancel()
            slipstreamJob = null
            
            // Destroy the process if running
            slipstreamProcess?.destroy()
            slipstreamProcess = null
            
            isSlipstreamRunning = false

            killSlipstreamProcesses()

            updateStatus(SlipstreamStatus.Stopped, SocksStatus.Stopped)

        } catch (e: Exception) {
            log("[Service] Stop error: ${e.message}")
        }

        stopSelf()
    }
}