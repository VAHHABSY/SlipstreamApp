package net.typeblob.socks

import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import android.widget.Toast
import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    
    companion object {
        private const val TAG = "SlipstreamApp"
    }
    
    private var commandService: CommandService? = null
    private var isBound = false
    
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d(TAG, "Service connected")
            val binder = service as CommandService.LocalBinder
            commandService = binder.getService()
            isBound = true
            
            commandService?.setStatusCallback { slipstream, socks ->
                slipstreamStatus = slipstream
                socksStatus = socks
            }
            
            commandService?.setLogCallback { message ->
                logs = logs + message
            }
        }
        
        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(TAG, "Service disconnected")
            commandService = null
            isBound = false
        }
    }
    
    private var slipstreamStatus by mutableStateOf<SlipstreamStatus>(SlipstreamStatus.Stopped)
    private var socksStatus by mutableStateOf<SocksStatus>(SocksStatus.Stopped)
    private var logs by mutableStateOf<List<String>>(emptyList())
    
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Log.d(TAG, "Notification permission granted")
            addLog("✓ Notification permission granted")
        } else {
            Log.w(TAG, "Notification permission denied")
            addLog("✗ Notification permission denied")
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate called")
        
        requestNotificationPermission()
        
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen()
                }
            }
        }
    }
    
    override fun onStart() {
        super.onStart()
        Log.d(TAG, "onStart - binding to service")
        val intent = Intent(this, CommandService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }
    
    override fun onStop() {
        super.onStop()
        Log.d(TAG, "onStop - unbinding from service")
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }
    
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED -> {
                    Log.d(TAG, "Notification permission already granted")
                    addLog("✓ Notification permission granted")
                }
                else -> {
                    Log.d(TAG, "Requesting notification permission")
                    requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
    }
    
    private fun addLog(message: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        val logMessage = "[$timestamp] $message"
        Log.d(TAG, "Log: $message")
        logs = logs + logMessage
    }
    
    @Composable
    fun MainScreen() {
        var currentProfile by remember { mutableStateOf(ProfileManager.currentProfile) }
        var showProfileDialog by remember { mutableStateOf(false) }
        var showLogsSheet by remember { mutableStateOf(false) }
        
        LaunchedEffect(Unit) {
            Log.d(TAG, "Loading current profile")
            val profile = ProfileManager.loadCurrentProfile(this@MainActivity)
            currentProfile = profile
            addLog("ℹ️ Loaded profile: ${profile.name}")
        }
        
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header
            Text(
                text = "SlipstreamApp",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            
            Text(
                text = "SOCKS5 Proxy Mode (No VPN)",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Profile Card
            ProfileCard(
                profile = currentProfile,
                onEditClick = { 
                    Log.d(TAG, "Edit profile clicked")
                    showProfileDialog = true 
                }
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Status Cards
            StatusCard(
                title = "Slipstream",
                status = slipstreamStatus.toDisplayString(),
                color = slipstreamStatus.toColor()
            )
            
            StatusCard(
                title = "SOCKS5 Proxy",
                status = socksStatus.toDisplayString(),
                color = socksStatus.toColor()
            )
            
            Spacer(modifier = Modifier.weight(1f))
            
            // Control Buttons
            ControlButtons(
                isRunning = slipstreamStatus is SlipstreamStatus.Running,
                onStart = { 
                    Log.d(TAG, "Start button clicked")
                    startTunnel(currentProfile) 
                },
                onStop = { 
                    Log.d(TAG, "Stop button clicked")
                    stopTunnel() 
                },
                onShowLogs = { 
                    Log.d(TAG, "Show logs clicked")
                    showLogsSheet = true 
                }
            )
        }
        
        if (showProfileDialog) {
            ProfileDialog(
                profile = currentProfile,
                onDismiss = { 
                    Log.d(TAG, "Profile dialog dismissed")
                    showProfileDialog = false 
                },
                onSave = { newProfile ->
                    Log.d(TAG, "Saving profile: ${newProfile.name}")
                    ProfileManager.saveProfile(this@MainActivity, newProfile)
                    ProfileManager.setCurrentProfile(this@MainActivity, newProfile.name)
                    currentProfile = newProfile
                    showProfileDialog = false
                    addLog("✓ Profile saved: ${newProfile.name}")
                }
            )
        }
        
        if (showLogsSheet) {
            LogsBottomSheet(
                logs = logs,
                onDismiss = { 
                    Log.d(TAG, "Logs sheet dismissed")
                    showLogsSheet = false 
                }
            )
        }
    }
    
    @Composable
    fun ProfileCard(profile: Profile, onEditClick: () -> Unit) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(4.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Current Profile", style = MaterialTheme.typography.titleMedium)
                
                Spacer(modifier = Modifier.height(8.dp))
                
                ProfileInfo("Profile", profile.name)
                ProfileInfo("Domain", profile.domain.ifBlank { "Not set" })
                ProfileInfo("Resolvers", profile.resolvers.ifBlank { "Not set" })
                ProfileInfo("Port", profile.port.toString())
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Button(
                    onClick = onEditClick,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("Edit Profile")
                }
            }
        }
    }
    
    @Composable
    fun ProfileInfo(label: String, value: String) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
        }
    }
    
    @Composable
    fun StatusCard(title: String, status: String, color: Color) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(4.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .background(color, CircleShape)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(status, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
    
    @Composable
    fun ControlButtons(
        isRunning: Boolean,
        onStart: () -> Unit,
        onStop: () -> Unit,
        onShowLogs: () -> Unit
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onStart,
                enabled = !isRunning,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(modifier = Modifier.width(4.dp))
                Text("Start")
            }
            
            Button(
                onClick = onStop,
                enabled = isRunning,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Icon(Icons.Default.Stop, contentDescription = null)
                Spacer(modifier = Modifier.width(4.dp))
                Text("Stop")
            }
            
            Button(onClick = onShowLogs) {
                Text("Logs")
            }
        }
    }
    
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun ProfileDialog(
        profile: Profile,
        onDismiss: () -> Unit,
        onSave: (Profile) -> Unit
    ) {
        var name by remember { mutableStateOf(profile.name) }
        var domain by remember { mutableStateOf(profile.domain) }
        var resolvers by remember { mutableStateOf(profile.resolvers) }
        var port by remember { mutableStateOf(profile.port.toString()) }
        
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Edit Profile") },
            text = {
                Column {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Profile Name") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = domain,
                        onValueChange = { domain = it },
                        label = { Text("Domain") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = resolvers,
                        onValueChange = { resolvers = it },
                        label = { Text("Resolvers (IP)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it },
                        label = { Text("Port") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val newProfile = Profile(
                            name = name,
                            domain = domain,
                            resolvers = resolvers,
                            port = port.toIntOrNull() ?: 1081
                        )
                        onSave(newProfile)
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        )
    }
    
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsBottomSheet(logs: List<String>, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState()
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            // Using non-animated scroll for large lists to avoid jank
            listState.scrollToItem(logs.size - 1)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Logs", style = MaterialTheme.typography.titleLarge)
                Row {
                    IconButton(
                        onClick = {
                            if (logs.isNotEmpty()) {
                                val text = logs.joinToString("\n")
                                clipboard.setText(AnnotatedString(text))
                                Toast.makeText(context, "Logs copied to clipboard", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "No logs to copy", Toast.LENGTH_SHORT).show()
                            }
                        }
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy logs to clipboard")
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close logs")
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(400.dp),
                color = Color(0xFF1E1E1E),
                shape = RoundedCornerShape(8.dp)
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.padding(8.dp)
                ) {
                    items(logs) { log ->
                        Text(
                            text = log,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFEEEEEE),
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                }
            }
        }
    }
}
    
    private fun startTunnel(profile: Profile) {
        Log.d(TAG, "=== START BUTTON PRESSED ===")
        Log.d(TAG, "Profile: name=${profile.name}, domain=${profile.domain}, resolvers=${profile.resolvers}, port=${profile.port}")
        
        addLog("ℹ️ Starting SOCKS5 proxy service...")
        
        if (profile.domain.isBlank() || profile.resolvers.isBlank()) {
            Log.e(TAG, "ERROR: Config missing! domain=${profile.domain.isNotBlank()}, resolvers=${profile.resolvers.isNotBlank()}")
            addLog("❌ Config missing - Resolvers: ${profile.resolvers.isNotBlank()}, Domain: ${profile.domain.isNotBlank()}")
            return
        }
        
        Log.d(TAG, "Config validated - Creating service intent...")
        addLog("ℹ️ Starting - Domain: ${profile.domain}, Resolvers: ${profile.resolvers}, Port: ${profile.port}")
        
        val intent = Intent(this, CommandService::class.java).apply {
            putExtra("domain", profile.domain)
            putExtra("resolvers", profile.resolvers)
            putExtra("port", profile.port)
        }
        
        try {
            Log.d(TAG, "Attempting to start foreground service...")
            startForegroundService(intent)
            Log.d(TAG, "Service started successfully")
            
            slipstreamStatus = SlipstreamStatus.Starting("Initializing...")
            socksStatus = SocksStatus.Waiting
            
            addLog("ℹ️ SOCKS5 proxy service started on port ${profile.port}")
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException starting service", e)
            addLog("❌ Permission error: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Exception starting service", e)
            addLog("❌ ERROR: ${e.message}")
        }
    }
    
    private fun stopTunnel() {
        Log.d(TAG, "Stopping tunnel...")
        addLog("ℹ️ Stopping tunnel...")
        
        try {
            stopService(Intent(this, CommandService::class.java))
            
            slipstreamStatus = SlipstreamStatus.Stopped
            socksStatus = SocksStatus.Stopped
            
            Log.d(TAG, "Tunnel stopped successfully")
            addLog("✓ Tunnel stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping tunnel", e)
            addLog("❌ Error stopping: ${e.message}")
        }
    }
}

fun SlipstreamStatus.toDisplayString(): String = when (this) {
    is SlipstreamStatus.Stopped -> "Stopped"
    is SlipstreamStatus.Starting -> message
    is SlipstreamStatus.Running -> "Running"
    is SlipstreamStatus.Stopping -> "Stopping..."
    is SlipstreamStatus.Failed -> message
}

fun SlipstreamStatus.toColor(): Color = when (this) {
    is SlipstreamStatus.Stopped -> Color(0xFF757575)
    is SlipstreamStatus.Starting -> Color(0xFFFFA726)
    is SlipstreamStatus.Running -> Color(0xFF66BB6A)
    is SlipstreamStatus.Stopping -> Color(0xFFFFA726)
    is SlipstreamStatus.Failed -> Color(0xFFEF5350)
}

fun SocksStatus.toDisplayString(): String = when (this) {
    is SocksStatus.Stopped -> "Stopped"
    is SocksStatus.Waiting -> "Waiting..."
    is SocksStatus.Running -> "Running"
    is SocksStatus.Stopping -> "Stopping..."
}

fun SocksStatus.toColor(): Color = when (this) {
    is SocksStatus.Stopped -> Color(0xFF757575)
    is SocksStatus.Waiting -> Color(0xFFFFA726)
    is SocksStatus.Running -> Color(0xFF66BB6A)
    is SocksStatus.Stopping -> Color(0xFFFFA726)
}