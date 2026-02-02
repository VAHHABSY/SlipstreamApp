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
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader

class CommandService : Service() {
    private val binder = LocalBinder()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var slipstreamProcess: Process? = null
    private var monitorJob: Job? = null

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
                delay(500)

                // Start slipstream
                startSlipstreamProcess(domain, resolvers, port)
                delay(1000)

                if (slipstreamProcess?.isAlive == true) {
                    updateStatus(SlipstreamStatus.Running, SocksStatus.Running)
                    updateNotification("Running", "SOCKS5 proxy on port $port")
                    log("[Service] Tunnel started successfully")
                } else {
                    throw IOException("Process terminated immediately")
                }

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

            val sourceBinary = File(applicationInfo.nativeLibraryDir, "libslipstream.so")
            log("[Service] Step 2: Source binary: ${sourceBinary.absolutePath}")

            if (!sourceBinary.exists()) {
                throw IOException("Binary not found at: ${sourceBinary.absolutePath}")
            }

            log("[Service] Step 3: Binary exists: true")
            log("[Service] Step 4: Binary size: ${sourceBinary.length()} bytes")

            // Copy to app-private dir instead of /data/local/tmp
            val destBinary = File(filesDir, "slipstream_bin")
            log("[Service] Step 5: Copying to ${destBinary.absolutePath}")

            sourceBinary.inputStream().use { input ->
                destBinary.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            // Make it executable (prefer Os.chmod, fallback to system chmod)
            try {
                android.system.Os.chmod(destBinary.absolutePath, 0o700)
            } catch (_: Throwable) {
                Runtime.getRuntime()
                    .exec(arrayOf("/system/bin/chmod", "700", destBinary.absolutePath))
                    .waitFor()
            }

            log("[Service] Step 6: Binary copied and made executable")

            // Use dynamic linker to execute the shared object reliably
            val linker = if (Build.SUPPORTED_64_BIT_ABIS.isNotEmpty())
                "/system/bin/linker64" else "/system/bin/linker"

            // Build command; pass arguments explicitly
            val command = listOf(
                linker,
                destBinary.absolutePath,
                domain,
                resolvers,
                "--socks-port", port.toString()
            )

            log("[Service] Step 7: Starting process via linker...")

            val processBuilder = ProcessBuilder(command)
                .directory(filesDir)
                .redirectErrorStream(true)

            // Ensure LD_LIBRARY_PATH points to nativeLibraryDir so dependencies resolve
            val env = processBuilder.environment()
            env["LD_LIBRARY_PATH"] = applicationInfo.nativeLibraryDir

            slipstreamProcess = processBuilder.start()

            log("[Service] Step 8: Process started")

            // Start monitoring
            startOutputMonitoring()

        } catch (e: Exception) {
            log("[Service] Error: ${e.javaClass.simpleName}: ${e.message}")
            e.printStackTrace()
            throw e
        }
    }

    private fun startOutputMonitoring() {
        monitorJob?.cancel()
        monitorJob = scope.launch {
            try {
                val reader = BufferedReader(InputStreamReader(slipstreamProcess?.inputStream))

                while (isActive) {
                    val line = reader.readLine() ?: break
                    log("[Slipstream] $line")
                }

                log("[Service] Process output stream ended")

            } catch (e: Exception) {
                if (isActive) {
                    log("[Service] Output monitoring error: ${e.message}")
                }
            }
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
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    fun stopTunnel() {
        log("[Service] Stopping tunnel...")

        try {
            slipstreamProcess?.destroy()
            slipstreamProcess = null

            monitorJob?.cancel()
            monitorJob = null

            killSlipstreamProcesses()

            updateStatus(SlipstreamStatus.Stopped, SocksStatus.Stopped)

        } catch (e: Exception) {
            log("[Service] Stop error: ${e.message}")
        }

        stopSelf()
    }
}