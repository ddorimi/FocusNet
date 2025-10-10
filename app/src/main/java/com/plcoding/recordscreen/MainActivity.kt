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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
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
import com.plcoding.recordscreen.ScreenRecordService.Companion.KEY_RECORDING_CONFIG
import com.plcoding.recordscreen.ScreenRecordService.Companion.START_RECORDING
import com.plcoding.recordscreen.ScreenRecordService.Companion.STOP_RECORDING
import com.plcoding.recordscreen.ui.theme.CoralRed
import com.plcoding.recordscreen.ui.theme.RecordScreenTheme

class MainActivity : ComponentActivity() {
    private val mediaProjectionManager by lazy {
        getSystemService<MediaProjectionManager>()!!
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            RecordScreenTheme {
                var currentScreen by remember { mutableStateOf(Screen.Home) }

                when (currentScreen) {
                    Screen.Home -> FocusNetHomeScreen(
                        onNavigateToHazardMenu = { currentScreen = Screen.HazardMenu },
                        onNavigateToAboutUs = { currentScreen = Screen.AboutUs }
                    )
                    Screen.HazardMenu -> HazardMenuScreen(
                        onBack = { currentScreen = Screen.Home },
                        onNavigateToDevMode = { currentScreen = Screen.DevMode },
                        mediaProjectionManager = mediaProjectionManager
                    )
                    Screen.AboutUs -> AboutUsScreen(
                        onBack = { currentScreen = Screen.Home }
                    )
                    Screen.DevMode -> DevModeScreen(
                        onBack = { currentScreen = Screen.Home }
                    )
                }
            }
        }
    }

    private fun ensureOverlayPermissionThenStart(projectionIntentLauncher: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                data = Uri.parse("package:$packageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            Toast.makeText(
                this,
                "Please grant 'Display over other apps' permission, then start recording again.",
                Toast.LENGTH_LONG
            ).show()
        } else {
            projectionIntentLauncher()
        }
    }
}

// Screen navigation enum
enum class Screen {
    Home, HazardMenu, AboutUs, DevMode
}

// ==================== HOME SCREEN ====================
@Composable
fun FocusNetHomeScreen(
    onNavigateToHazardMenu: () -> Unit,
    onNavigateToAboutUs: () -> Unit
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color(0xFF2D4059)
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(300.dp))

                // Logo
                Image(
                    painter = painterResource(id = R.drawable.fnlogo),
                    contentDescription = "FocusNet Logo",
                    modifier = Modifier
                        .fillMaxWidth(0.8f)
                        .aspectRatio(2.5f),
                    contentScale = ContentScale.Fit
                )

                Spacer(modifier = Modifier.height(30.dp))

                // Start Detection Button
                Button(
                    onClick = onNavigateToHazardMenu,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = Color(0xFFFF9800)
                    ),
                    shape = RoundedCornerShape(30.dp),
                    modifier = Modifier
                        .height(55.dp)
                        .width(230.dp)
                        .shadow(
                            elevation = 6.dp,
                            shape = RoundedCornerShape(30.dp),
                            clip = false
                        )
                ) {
                    Text(
                        text = "Start Detection",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                Text(
                    text = "About Us",
                    fontSize = 14.sp,
                    color = Color.White,
                    textDecoration = TextDecoration.Underline,
                    modifier = Modifier
                        .padding(bottom = 20.dp)
                        .clickable { onNavigateToAboutUs() }
                )
            }
        }
    }
}

// ==================== HAZARD MENU SCREEN ====================
@Composable
fun HazardMenuScreen(
    onBack: () -> Unit,
    onNavigateToDevMode: () -> Unit,
    mediaProjectionManager: MediaProjectionManager
) {
    val context = LocalContext.current
    val isServiceRunning by ScreenRecordService.isServiceRunning.collectAsStateWithLifecycle()

    var isVoiceAlertEnabled by remember { mutableStateOf(true) }

    var hasNotificationPermission by remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            mutableStateOf(
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            )
        } else mutableStateOf(true)
    }

    val screenRecordLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val intent = result.data ?: return@rememberLauncherForActivityResult
        val config = ScreenRecordConfig(resultCode = result.resultCode, data = intent)
        val serviceIntent = Intent(context, ScreenRecordService::class.java).apply {
            action = START_RECORDING
            putExtra(KEY_RECORDING_CONFIG, config)
        }
        ContextCompat.startForegroundService(context, serviceIntent)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasNotificationPermission = isGranted
        if (hasNotificationPermission && !isServiceRunning) {
            if (!Settings.canDrawOverlays(context)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } else {
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
        // Back + Title
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White
                )
            }
            Text(
                text = "Types of Hazard",
                fontSize = 18.sp,
                color = Color.White
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Hazard Grid
        val hazards = listOf(
            HazardItem(R.drawable.pedestrian_logo, "Pedestrians"),
            HazardItem(R.drawable.potholeshumps_logo, "Potholes / Humps"),
            HazardItem(R.drawable.animals_logo, "Animals"),
            HazardItem(R.drawable.roadworks_logo, "Road Works")
        )

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            items(hazards) { item -> HazardButton(item) }
        }

        Spacer(modifier = Modifier.height(50.dp))

        // Voice Alert Toggle
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.CenterStart
        ) {
            Row(
                modifier = Modifier
                    .padding(vertical = 10.dp)
                    .padding(start = 40.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Switch(
                    checked = isVoiceAlertEnabled,
                    onCheckedChange = { isVoiceAlertEnabled = it },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Color(0xFF4CAF50),
                        uncheckedThumbColor = Color.White,
                        uncheckedTrackColor = Color(0xFF757575)
                    )
                )

                Text(
                    text = "Voice Alert",
                    fontSize = 16.sp,
                    color = Color.White,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Detect Now Button
        Button(
            onClick = {
                if (!hasNotificationPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    if (isServiceRunning) {
                        Intent(context, ScreenRecordService::class.java).also {
                            it.action = STOP_RECORDING
                            ContextCompat.startForegroundService(context, it)
                        }
                    } else {
                        if (!Settings.canDrawOverlays(context)) {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            ).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                        } else {
                            screenRecordLauncher.launch(
                                mediaProjectionManager.createScreenCaptureIntent()
                            )
                        }
                    }
                }
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isServiceRunning) CoralRed else Color(0xFFFD7014),
                contentColor = Color.White
            ),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 8.dp),
            shape = RoundedCornerShape(50.dp),
            modifier = Modifier
                .padding(horizontal = 40.dp)
                .height(55.dp)
                .fillMaxWidth(0.7f)
        ) {
            Text(
                text = if (isServiceRunning) "Stop Detecting" else "Detect Now",
                fontSize = 16.sp
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        // Dev Mode link instead of About Us
        Text(
            text = "Dev Mode",
            fontSize = 14.sp,
            color = Color.White,
            textDecoration = TextDecoration.Underline,
            modifier = Modifier
                .padding(bottom = 20.dp)
                .clickable { onNavigateToDevMode() }
        )
    }
}

// ==================== DEV MODE SCREEN ====================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevModeScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Dev Mode",
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF2D4059)
                )
            )
        },
        containerColor = Color(0xFF2D4059)
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.Center
        ) {
            // THIS IS WHERE YOU PLACE THE CONTENT OF THE DEV MODE SCREEN
        }
    }
}

// ==================== ABOUT US SCREEN ====================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutUsScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF687D99)
                )
            )
        },
        containerColor = Color(0xFF687D99)
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.Start
        ) {
            Text(
                text = "About Us",
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier.padding(top = 16.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Divider(color = Color.White, thickness = 1.5.dp)

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "FOCUSNet",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "FOCUSNet is a research project focused on improving road safety by enhancing object detection in low-light environments. The goal is to accurately detect road hazards at night using deep learning techniques.",
                fontSize = 16.sp,
                color = Color.White
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "This study presents a modified Single Shot MultiBox Detector (SSD) that combines MobileNetV3 and the Convolutional Block Attention Module (CBAM) to improve detection performance in low-light conditions. The performance of this model is compared with a baseline SSD using standard evaluation metrics.",
                fontSize = 16.sp,
                color = Color.White
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "FOCUSNet aims to support safer nighttime driving through intelligent and efficient object detection systems.",
                fontSize = 16.sp,
                color = Color.White
            )
        }
    }
}

// ==================== HELPER COMPONENTS ====================
data class HazardItem(val imageRes: Int, val label: String)

@Composable
fun HazardButton(item: HazardItem) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(100.dp)
                .background(Color(0xFFD9D9D9), shape = androidx.compose.foundation.shape.CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(id = item.imageRes),
                contentDescription = item.label,
                modifier = Modifier.size(60.dp)
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
        Text(text = item.label, color = Color.White, fontSize = 14.sp)
    }
}
