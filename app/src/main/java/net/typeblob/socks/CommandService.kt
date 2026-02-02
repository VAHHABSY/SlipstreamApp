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
import android.system.OsConstants
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader

class CommandService : Service() {
    private val binder = LocalBinder()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var slipstreamJob: Job? = null
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
                // Clean up before starting (best effort)
                log("[Service] Cleaning up old processes...")
                killSlipstreamProcesses()

                updateStatus(SlipstreamStatus.Starting("Starting tunnel..."), SocksStatus.Waiting)
                delay(200)

                // Start slipstream via JNI in a background job to avoid blocking this coroutine
                slipstreamJob?.cancel()
                slipstreamJob = scope.launch(Dispatchers.Default) {
                    log("[Service] Starting slipstream via JNI...")
                    val rc = NativeRunner.runSlipstream(domain, resolvers, port)
                    log("[Service] Slipstream JNI finished with rc=$rc")
                    if (rc != 0) {
                        updateStatus(SlipstreamStatus.Failed("Slipstream rc=$rc"), SocksStatus.Stopped)
                        stopSelf()
                    }
                }

                // Consider it running once started
                delay(1000)
                updateStatus(SlipstreamStatus.Running, SocksStatus.Running)
                updateNotification("Running", "SOCKS5 proxy on port $port")
                log("[Service] Tunnel started successfully")

                // No external process output to monitor; optional placeholder
                startOutputMonitoringPlaceholder()

            } catch (e: Exception) {
                log("[Service] ERROR: ${e.javaClass.simpleName} - ${e.message}")
                updateStatus(SlipstreamStatus.Failed("Slipstream failed"), SocksStatus.Stopped)
                stopSelf()
            }
        }

        return START_STICKY
    }

    // Placeholder: keep structure consistent; JNI runs in-process, so there's no external stdout to read
    private fun startOutputMonitoringPlaceholder() {
        monitorJob?.cancel()
        monitorJob = scope.launch {
            // You can add periodic status checks here if the native code exposes signals/events
            delay(1000)
        }
    }

    private fun killSlipstreamProcesses() {
        // Previously attempted killall on external binaries; now running in-process via JNI.
        // Keep no-op or remove if not needed.
        val commands = listOf(
            arrayOf("killall", "slipstream"),
            arrayOf("/system/bin/killall", "slipstream"),
            arrayOf("toybox", "killall", "slipstream"),
            arrayOf("busybox", "killall", "slipstream")
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