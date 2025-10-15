package com.plcoding.recordscreen

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.plcoding.recordscreen.ui.theme.CoralRed
import com.plcoding.recordscreen.ui.theme.RecordScreenTheme

class MainActivity : ComponentActivity() {
    private val mediaProjectionManager by lazy {
        getSystemService<MediaProjectionManager>()!!
    }

    private val mediaPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.entries.all { it.value }
        if (granted) {
            Toast.makeText(this, "Media permissions granted", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Media permission denied or limited", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestMediaPermissions() {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
                mediaPermissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.READ_MEDIA_IMAGES,
                        Manifest.permission.READ_MEDIA_VIDEO
                    )
                )
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                mediaPermissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.READ_MEDIA_IMAGES,
                        Manifest.permission.READ_MEDIA_VIDEO
                    )
                )
            }
            else -> {
                mediaPermissionLauncher.launch(
                    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestMediaPermissions()

        setContent {
            RecordScreenTheme {
                var currentScreen by remember { mutableStateOf(Screen.Home) }

                when (currentScreen) {
                    Screen.Home -> HomeScreen(
                        onNavigateToDetection = { currentScreen = Screen.Detection },
                        onNavigateToAbout = { currentScreen = Screen.About }
                    )
                    Screen.Detection -> DetectionScreen(
                        onBack = { currentScreen = Screen.Home },
                        onNavigateToAbout = { currentScreen = Screen.About },
                        onNavigateToDevMode = { currentScreen = Screen.DevMode },
                        mediaProjectionManager = mediaProjectionManager
                    )
                    Screen.About -> AboutScreen(onBack = { currentScreen = Screen.Home })
                    Screen.DevMode -> DevModeScreen(onBack = { currentScreen = Screen.Detection })
                }
            }
        }
    }
}

enum class Screen { Home, Detection, About, DevMode }

@Composable
fun HomeScreen(onNavigateToDetection: () -> Unit, onNavigateToAbout: () -> Unit) {
    Scaffold(containerColor = Color(0xFF2D4059)) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(300.dp))

            Image(
                painter = painterResource(id = R.drawable.fnlogo),
                contentDescription = "FocusNet Logo",
                modifier = Modifier
                    .fillMaxWidth(0.8f)
                    .aspectRatio(2.5f),
                contentScale = ContentScale.Fit
            )

            Spacer(modifier = Modifier.height(30.dp))

            Button(
                onClick = onNavigateToDetection,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = Color(0xFFFF9800)
                ),
                shape = RoundedCornerShape(30.dp),
                modifier = Modifier
                    .height(55.dp)
                    .width(230.dp)
                    .shadow(6.dp, RoundedCornerShape(30.dp), clip = false)
            ) {
                Text("Start Detection", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }

            Spacer(modifier = Modifier.weight(1f))

            Text(
                text = "About Us",
                fontSize = 14.sp,
                color = Color.White,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier
                    .padding(bottom = 20.dp)
                    .clickable { onNavigateToAbout() }
            )
        }
    }
}

@Composable
fun DetectionScreen(
    onBack: () -> Unit,
    onNavigateToAbout: () -> Unit,
    onNavigateToDevMode: () -> Unit,
    mediaProjectionManager: MediaProjectionManager
) {
    val context = LocalContext.current
    val isServiceRunning by ScreenRecordService.isServiceRunning.collectAsStateWithLifecycle()

    var isVoiceAlertEnabled by remember { mutableStateOf(true) }
    var selectedModel by remember { mutableStateOf("focusnet_mobile.ptl") }
    var isModelMenuExpanded by remember { mutableStateOf(false) }

    var hasNotificationPermission by remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            mutableStateOf(
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                        == PackageManager.PERMISSION_GRANTED
            )
        } else mutableStateOf(true)
    }

    val screenRecordLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val intent = result.data ?: return@rememberLauncherForActivityResult
        val config = ScreenRecordConfig(
            resultCode = result.resultCode,
            data = intent,
            modelFileName = selectedModel,
            isVoiceAlertEnabled = isVoiceAlertEnabled
        )
        val serviceIntent = Intent(context, ScreenRecordService::class.java).apply {
            action = ScreenRecordService.START_RECORDING
            putExtra(ScreenRecordService.KEY_RECORDING_CONFIG, config)
        }
        ContextCompat.startForegroundService(context, serviceIntent)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasNotificationPermission = isGranted
        if (hasNotificationPermission && !isServiceRunning) {
            if (Settings.canDrawOverlays(context)) {
                screenRecordLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF2D4059))
            .padding(20.dp)
            .systemBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Header
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
            }
            Text("Road Hazards", fontSize = 18.sp, color = Color.White)
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Hazard Grid
        val hazards = listOf(
            HazardItem(R.drawable.pedestrian_logo, "Pedestrians"),
            HazardItem(R.drawable.potholeshumps_logo, "Potholes"),
            HazardItem(R.drawable.potholeshumps_logo, "Humps"),
            HazardItem(R.drawable.animals_logo, "Animals"),
            HazardItem(R.drawable.roadworks_logo, "Road Works")
        )

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier
                .fillMaxWidth()
                .height(380.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(15.dp)
        ) {
            items(hazards) { hazard ->
                HazardButton(hazard)
            }
        }

        Spacer(modifier = Modifier.height(30.dp))

        // Model Selection
        Column(modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 40.dp)
        ) {
            Text("Select Model", fontSize = 14.sp, color = Color.White)
            Spacer(modifier = Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF3D5A80), RoundedCornerShape(8.dp))
                    .clickable { isModelMenuExpanded = true }
                    .padding(12.dp)
            ) {
                Text(
                    when (selectedModel) {
                        "focusnet_mobile.ptl" -> "FocusNet"
                        "baseline_mobile.ptl" -> "Baseline SSD"
                        else -> "Unknown"
                    },
                    color = Color.White,
                    fontSize = 14.sp
                )
            }
            DropdownMenu(
                expanded = isModelMenuExpanded,
                onDismissRequest = { isModelMenuExpanded = false }
            ) {
                DropdownMenuItem(
                    text = { Text("FocusNet (Recommended)") },
                    onClick = {
                        selectedModel = "focusnet_mobile.ptl"
                        isModelMenuExpanded = false
                    }
                )
                DropdownMenuItem(
                    text = { Text("Baseline SSD") },
                    onClick = {
                        selectedModel = "baseline_mobile.ptl"
                        isModelMenuExpanded = false
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Voice Alert Toggle
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 40.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Switch(
                checked = isVoiceAlertEnabled,
                onCheckedChange = { isVoiceAlertEnabled = it },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = Color(0xFF4CAF50)
                )
            )
            Text("Voice Alert", fontSize = 16.sp, color = Color.White)
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Detect Button
        Button(
            onClick = {
                if (!hasNotificationPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else if (isServiceRunning) {
                    Intent(context, ScreenRecordService::class.java).also {
                        it.action = ScreenRecordService.STOP_RECORDING
                        ContextCompat.startForegroundService(context, it)
                    }
                } else {
                    if (Settings.canDrawOverlays(context)) {
                        screenRecordLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
                    } else {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        )
                        context.startActivity(intent)
                    }
                }
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isServiceRunning) CoralRed else Color(0xFFFD7014)
            ),
            shape = RoundedCornerShape(50.dp),
            modifier = Modifier
                .padding(horizontal = 40.dp)
                .height(55.dp)
                .fillMaxWidth(0.7f)
        ) {
            Text(if (isServiceRunning) "Stop" else "Detect Now", fontSize = 16.sp)
        }

        Spacer(modifier = Modifier.weight(1f))

        // Footer Links
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 20.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Text(
                "About Us",
                fontSize = 14.sp,
                color = Color.White,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.clickable { onNavigateToAbout() }
            )
            Text(
                "Dev Mode",
                fontSize = 14.sp,
                color = Color.White,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.clickable { onNavigateToDevMode() }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF687D99))
            )
        },
        containerColor = Color(0xFF687D99)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text("About FocusNet", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(color = Color.White, thickness = 1.5.dp)
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "FocusNet improves road safety by detecting hazards in low-light conditions using deep learning. " +
                        "It combines SSD, MobileNetV3, and CBAM for efficient real-time detection.",
                fontSize = 16.sp,
                color = Color.White,
                lineHeight = 24.sp
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevModeScreen(onBack: () -> Unit) {
    val metrics by ScreenRecordService.performanceMetrics.collectAsStateWithLifecycle()
    val hazardStats by ScreenRecordService.hazardStats.collectAsStateWithLifecycle()
    val detections by ScreenRecordService.recentDetections.collectAsStateWithLifecycle()
    val isRunning by ScreenRecordService.isServiceRunning.collectAsStateWithLifecycle()
    val threshold by ScreenRecordService.confidenceThreshold.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Dev Mode", color = Color.White, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF2D4059))
            )
        },
        containerColor = Color(0xFF2D4059)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Status
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (isRunning) Color(0xFF4CAF50) else Color(0xFF757575)
                )
            ) {
                Text(
                    if (isRunning) "🟢 Active" else "⚪ Inactive",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(16.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Confidence Slider
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF3D5A80))) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Confidence", color = Color.White, fontWeight = FontWeight.Bold)
                        Text("${(threshold * 100).toInt()}%", color = Color(0xFF4CAF50), fontWeight = FontWeight.Bold)
                    }
                    Slider(
                        value = threshold,
                        onValueChange = { ScreenRecordService.setConfidenceThreshold(it) },
                        valueRange = 0.3f..0.95f,
                        steps = 12
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Metrics
            Text("Performance", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(modifier = Modifier.height(8.dp))
            MetricCard("FPS", String.format("%.1f", metrics.fps))
            MetricCard("Processing", "${String.format("%.0f", metrics.processingTimeMs)} ms")
            MetricCard("Total Detections", metrics.totalDetections.toString())

            Spacer(modifier = Modifier.height(16.dp))

            // Hazard Stats
            Text("Hazard Counts", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(modifier = Modifier.height(8.dp))
            MetricCard("Pedestrians", hazardStats.pedestrians.toString())
            MetricCard("Potholes", hazardStats.potholes.toString())
            MetricCard("Humps", hazardStats.humps.toString())
            MetricCard("Animals", hazardStats.animals.toString())
            MetricCard("Road Works", hazardStats.roadWorks.toString())

            Spacer(modifier = Modifier.height(16.dp))

            // Recent Detections
            Text("Recent (${detections.size})", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(modifier = Modifier.height(8.dp))
            if (detections.isEmpty()) {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF3D5A80))) {
                    Text(
                        "No detections yet",
                        color = Color.White.copy(alpha = 0.6f),
                        modifier = Modifier.padding(16.dp)
                    )
                }
            } else {
                detections.forEach { det ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF3D5A80))
                    ) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(det.label, color = Color.White)
                            Text(
                                String.format("%.0f%%", det.score * 100),
                                color = Color(0xFF4CAF50),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MetricCard(label: String, value: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF3D5A80))
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, color = Color.White)
            Text(value, color = Color.White, fontWeight = FontWeight.Bold)
        }
    }
}

data class HazardItem(val imageRes: Int, val label: String)

@Composable
fun HazardButton(item: HazardItem) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(100.dp)
                .background(Color(0xFFD9D9D9), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painterResource(item.imageRes),
                item.label,
                modifier = Modifier.size(60.dp)
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
        Text(item.label, color = Color.White, fontSize = 14.sp)
    }
}