package ch.codelook.locationtracker.ui.tracking

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackingScreen(
    viewModel: TrackingViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    var pendingStart by remember { mutableStateOf(false) }

    // Configure osmdroid
    LaunchedEffect(Unit) {
        Configuration.getInstance().userAgentValue = context.packageName
    }

    // Step 2: Background location (must be requested separately)
    val bgLocationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.startTracking()
    }

    // Step 1b: Notifications, then background location
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        val bgGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!bgGranted) {
            bgLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        } else {
            viewModel.startTracking()
        }
    }

    // Step 1: Fine + coarse location first
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (fineGranted) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun requestPermissionsAndStart() {
        val fineGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (fineGranted) {
            val bgGranted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            if (bgGranted) {
                viewModel.startTracking()
            } else {
                bgLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
        } else {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    DisposableEffect(Unit) {
        if (viewModel.isTracking) {
            viewModel.bindService()
        }
        onDispose {
            viewModel.unbindService()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Tracking") })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Map
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                val location = viewModel.currentLocation
                AndroidView(
                    factory = { ctx ->
                        MapView(ctx).apply {
                            setTileSource(TileSourceFactory.MAPNIK)
                            setMultiTouchControls(true)
                            controller.setZoom(15.0)
                        }
                    },
                    update = { mapView ->
                        location?.let { loc ->
                            val point = GeoPoint(loc.latitude, loc.longitude)
                            mapView.controller.animateTo(point)

                            mapView.overlays.clear()
                            val marker = Marker(mapView).apply {
                                position = point
                                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                                title = "Current Location"
                            }
                            mapView.overlays.add(marker)
                            mapView.invalidate()
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Status panel
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Tracking mode row
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val (icon, color) = when (viewModel.trackingMode) {
                            "Getting Fix" -> Icons.Default.CellTower to Color(0xFF2196F3)
                            "Sleeping" -> Icons.Default.NightsStay to Color(0xFFFF9800)
                            "Continuous" -> Icons.Default.SwapCalls to Color(0xFF4CAF50)
                            else -> Icons.Default.LocationOff to Color.Gray
                        }
                        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
                        Text(viewModel.trackingMode, style = MaterialTheme.typography.titleMedium)

                        if (viewModel.isCharging) {
                            Icon(
                                Icons.Default.BoltOutlined,
                                contentDescription = "Charging",
                                tint = Color(0xFF4CAF50),
                                modifier = Modifier.size(18.dp)
                            )
                            Text("Charging", style = MaterialTheme.typography.bodySmall, color = Color(0xFF4CAF50))
                        }

                        Spacer(modifier = Modifier.weight(1f))

                        // Toggle
                        Switch(
                            checked = viewModel.isTracking,
                            onCheckedChange = { checked ->
                                if (checked) {
                                    requestPermissionsAndStart()
                                } else {
                                    viewModel.stopTracking()
                                }
                            }
                        )
                    }

                    // Geofence / speed info
                    if (viewModel.isTracking && viewModel.trackingMode == "Sleeping") {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.FenceOutlined, contentDescription = null, modifier = Modifier.size(18.dp), tint = Color(0xFFFF9800))
                            Text(
                                "Geofence: ${viewModel.geofenceRadius.toInt()}m",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            if (viewModel.lastSpeed > 0.5) {
                                Text(
                                    " · ${String.format("%.0f km/h", viewModel.lastSpeed * 3.6)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    HorizontalDivider()

                    // Stats row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        StatItem("Buffered", "${viewModel.bufferCount}")
                        StatItem("Batch", "${viewModel.batchSize}")
                        viewModel.currentLocation?.let { loc ->
                            StatItem("Accuracy", "${loc.accuracy.toInt()}m")
                        }
                        if (viewModel.lastSpeed > 0) {
                            StatItem("Speed", String.format("%.1f m/s", viewModel.lastSpeed))
                        }
                    }

                    // Flush button
                    if (viewModel.bufferCount > 0) {
                        Button(
                            onClick = { viewModel.flushNow() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Upload Now (${viewModel.bufferCount} points)")
                        }
                    }

                    // Coordinates
                    viewModel.currentLocation?.let { loc ->
                        Text(
                            String.format("%.6f, %.6f", loc.latitude, loc.longitude),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Error
                    viewModel.lastError?.let { error ->
                        Text(
                            error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
