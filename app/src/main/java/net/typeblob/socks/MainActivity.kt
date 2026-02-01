package net.typeblob.socks

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    private var commandService: CommandService? = null
    private var serviceBound = false
    
    private val slipstreamStatus = mutableStateOf<SlipstreamStatus>(SlipstreamStatus.Stopped)
    private val socksStatus = mutableStateOf<SocksStatus>(SocksStatus.Stopped)
    private val logMessages = mutableStateListOf<String>()
    
    private val profiles = mutableStateListOf(
        Profile("Default", "example.com", "1.1.1.1", 1081, isActive = true)
    )
    private val currentProfile = mutableStateOf(profiles.first())
    
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as CommandService.LocalBinder
            commandService = binder.getService()
            serviceBound = true
            
            commandService?.setStatusCallback { slipstream, socks ->
                slipstreamStatus.value = slipstream
                socksStatus.value = socks
            }
            
            commandService?.setLogCallback { message ->
                logMessages.add(message)
            }
            
            log("Connected to service")
        }
        
        override fun onServiceDisconnected(name: ComponentName?) {
            commandService = null
            serviceBound = false
            log("Disconnected from service")
        }
    }
    
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            log("Notification permission granted")
        } else {
            log("Notification permission denied")
            Toast.makeText(this, "Notification permission is required", Toast.LENGTH_SHORT).show()
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Request notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        
        // Load saved profiles
        loadProfiles()
        
        setContent {
            MaterialTheme {
                MainScreen()
            }
        }
    }
    
    override fun onStart() {
        super.onStart()
        Intent(this, CommandService::class.java).also { intent ->
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }
    
    override fun onStop() {
        super.onStop()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
    }
    
    @Composable
    fun MainScreen() {
        var showProfileDialog by remember { mutableStateOf(false) }
        var showLogSheet by remember { mutableStateOf(false) }
        
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("SlipstreamApp") },
                    actions = {
                        IconButton(onClick = { showLogSheet = true }) {
                            Icon(Icons.Default.List, "View Logs")
                        }
                    }
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Status Card
                StatusCard(
                    slipstreamStatus = slipstreamStatus.value,
                    socksStatus = socksStatus.value
                )
                
                Spacer(modifier = Modifier.height(24.dp))
                
                // Profile Selection
                ProfileSelector(
                    profiles = profiles,
                    currentProfile = currentProfile.value,
                    onProfileSelected = { profile ->
                        currentProfile.value = profile
                        profiles.forEach { it.isActive = (it == profile) }
                        saveProfiles()
                        log("Switched to profile: ${profile.name}")
                    },
                    onAddProfile = { showProfileDialog = true }
                )
                
                Spacer(modifier = Modifier.height(24.dp))
                
                // Control Buttons
                ControlButtons(
                    slipstreamStatus = slipstreamStatus.value,
                    onStart = { startProxy() },
                    onStop = { stopProxy() }
                )
            }
        }
        
        // Profile Dialog
        if (showProfileDialog) {
            ProfileDialog(
                onDismiss = { showProfileDialog = false },
                onSave = { profile ->
                    profiles.add(profile)
                    saveProfiles()
                    showProfileDialog = false
                }
            )
        }
        
        // Log Sheet
        if (showLogSheet) {
            LogBottomSheet(
                logs = logMessages,
                onDismiss = { showLogSheet = false }
            )
        }
    }
    
    @Composable
    fun StatusCard(slipstreamStatus: SlipstreamStatus, socksStatus: SocksStatus) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(4.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text("Status", style = MaterialTheme.typography.titleLarge)
                
                Spacer(modifier = Modifier.height(16.dp))
                
                StatusRow("Slipstream", slipstreamStatus.toDisplayString(), slipstreamStatus.getColor())
                Spacer(modifier = Modifier.height(8.dp))
                StatusRow("SOCKS5", socksStatus.toDisplayString(), socksStatus.getColor())
            }
        }
    }
    
    @Composable
    fun StatusRow(label: String, status: String, color: Color) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(color)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(status, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    
    @Composable
    fun ProfileSelector(
        profiles: List<Profile>,
        currentProfile: Profile,
        onProfileSelected: (Profile) -> Unit,
        onAddProfile: () -> Unit
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(4.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Profile", style = MaterialTheme.typography.titleLarge)
                    IconButton(onClick = onAddProfile) {
                        Icon(Icons.Default.Add, "Add Profile")
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                profiles.forEach { profile ->
                    ProfileItem(
                        profile = profile,
                        isSelected = profile == currentProfile,
                        onClick = { onProfileSelected(profile) }
                    )
                }
            }
        }
    }
    
    @Composable
    fun ProfileItem(profile: Profile, isSelected: Boolean, onClick: () -> Unit) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .clickable(onClick = onClick),
            shape = RoundedCornerShape(8.dp),
            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer 
                   else MaterialTheme.colorScheme.surface,
            tonalElevation = if (isSelected) 4.dp else 0.dp
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    profile.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Domain: ${profile.domain}",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "Resolvers: ${profile.resolvers}",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "Port: ${profile.socksPort}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
    
    @Composable
    fun ControlButtons(slipstreamStatus: SlipstreamStatus, onStart: () -> Unit, onStop: () -> Unit) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Button(
                onClick = onStart,
                modifier = Modifier.weight(1f),
                enabled = slipstreamStatus is SlipstreamStatus.Stopped,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Start")
            }
            
            Button(
                onClick = onStop,
                modifier = Modifier.weight(1f),
                enabled = slipstreamStatus !is SlipstreamStatus.Stopped,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Icon(Icons.Default.Close, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Stop")
            }
        }
    }
    
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun ProfileDialog(onDismiss: () -> Unit, onSave: (Profile) -> Unit) {
        var name by remember { mutableStateOf("") }
        var domain by remember { mutableStateOf("") }
        var resolvers by remember { mutableStateOf("1.1.1.1") }
        var port by remember { mutableStateOf("1081") }
        
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Add Profile") },
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
                        label = { Text("Resolvers") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it },
                        label = { Text("SOCKS5 Port") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (name.isNotBlank() && domain.isNotBlank()) {
                            val profile = Profile(
                                name = name,
                                domain = domain,
                                resolvers = resolvers,
                                socksPort = port.toIntOrNull() ?: 1081
                            )
                            onSave(profile)
                        }
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
    fun LogBottomSheet(logs: List<String>, onDismiss: () -> Unit) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val listState = rememberLazyListState()
        val coroutineScope = rememberCoroutineScope()
        
        LaunchedEffect(logs.size) {
            if (logs.isNotEmpty()) {
                listState.animateScrollToItem(logs.size - 1)
            }
        }
        
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxHeight(0.8f)
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Logs", style = MaterialTheme.typography.titleLarge)
                    IconButton(onClick = { logMessages.clear() }) {
                        Icon(Icons.Default.Delete, "Clear Logs")
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF1E1E1E),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.padding(12.dp)
                    ) {
                        items(logs) { log ->
                            Text(
                                text = log,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                color = when {
                                    log.contains("❌") || log.contains("ERROR") -> Color(0xFFFF6B6B)
                                    log.contains("✓") || log.contains("SUCCESS") -> Color(0xFF4ECDC4)
                                    log.contains("⚠️") || log.contains("WARNING") -> Color(0xFFFFA500)
                                    log.contains("ℹ️") -> Color(0xFF87CEEB)
                                    else -> Color(0xFFE0E0E0)
                                },
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }
    }
    
    private fun startProxy() {
        val profile = currentProfile.value
        
        if (profile.domain.isBlank() || profile.resolvers.isBlank()) {
            log("❌ Config missing - Resolvers: ${profile.resolvers.isNotBlank()}, Domain: ${profile.domain.isNotBlank()}")
            return
        }
        
        log("ℹ️ Starting SOCKS5 proxy service...")
        log("ℹ️ Starting - Domain: ${profile.domain}, Resolvers: ${profile.resolvers}, Port: ${profile.socksPort}")
        
        val intent = Intent(this, CommandService::class.java).apply {
            putExtra("domain", profile.domain)
            putExtra("resolvers", profile.resolvers)
            putExtra("port", profile.socksPort)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        
        slipstreamStatus.value = SlipstreamStatus.Starting()
        socksStatus.value = SocksStatus.Waiting
        
        log("ℹ️ SOCKS5 proxy service started on port ${profile.socksPort}")
    }
    
    private fun stopProxy() {
        log("ℹ️ Stopping SOCKS5 proxy service...")
        stopService(Intent(this, CommandService::class.java))
        slipstreamStatus.value = SlipstreamStatus.Stopping
        socksStatus.value = SocksStatus.Stopping
    }
    
    private fun log(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        logMessages.add("[$timestamp] $message")
    }
    
    private fun loadProfiles() {
        val prefs = getSharedPreferences("profiles", Context.MODE_PRIVATE)
        val savedProfiles = prefs.getString("profiles_json", null)
        
        if (savedProfiles != null) {
            // TODO: Parse JSON and load profiles
            log("ℹ️ Loaded profile: ${currentProfile.value.name}")
        }
    }
    
    private fun saveProfiles() {
        val prefs = getSharedPreferences("profiles", Context.MODE_PRIVATE)
        // TODO: Save profiles to JSON
    }
}

// Extension functions for status display
fun SlipstreamStatus.toDisplayString(): String = when (this) {
    is SlipstreamStatus.Stopped -> "Stopped"
    is SlipstreamStatus.Stopping -> "Stopping..."
    is SlipstreamStatus.Starting -> if (message.isNotEmpty()) message else "Starting..."
    is SlipstreamStatus.Running -> "Running"
    is SlipstreamStatus.Failed -> "Failed: $error"
}

fun SlipstreamStatus.getColor(): Color = when (this) {
    is SlipstreamStatus.Stopped -> Color.Gray
    is SlipstreamStatus.Stopping -> Color(0xFFFFA500)
    is SlipstreamStatus.Starting -> Color(0xFF87CEEB)
    is SlipstreamStatus.Running -> Color(0xFF4CAF50)
    is SlipstreamStatus.Failed -> Color(0xFFFF6B6B)
}

fun SocksStatus.toDisplayString(): String = when (this) {
    is SocksStatus.Stopped -> "Stopped"
    is SocksStatus.Stopping -> "Stopping..."
    is SocksStatus.Waiting -> "Waiting..."
    is SocksStatus.Running -> "Running"
}

fun SocksStatus.getColor(): Color = when (this) {
    is SocksStatus.Stopped -> Color.Gray
    is SocksStatus.Stopping -> Color(0xFFFFA500)
    is SocksStatus.Waiting -> Color(0xFF87CEEB)
    is SocksStatus.Running -> Color(0xFF4CAF50)
}

data class Profile(
    val name: String,
    val domain: String,
    val resolvers: String,
    val socksPort: Int,
    var isActive: Boolean = false
)