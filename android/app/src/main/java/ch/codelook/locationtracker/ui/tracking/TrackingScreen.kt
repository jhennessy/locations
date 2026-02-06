package ch.codelook.locationtracker.ui.tracking

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
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

    // Configure osmdroid
    LaunchedEffect(Unit) {
        Configuration.getInstance().userAgentValue = context.packageName
    }

    // Permission launcher
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val bgGranted = permissions[Manifest.permission.ACCESS_BACKGROUND_LOCATION] == true
        if (fineGranted) {
            viewModel.startTracking()
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
                            "Stationary" -> Icons.Default.NightsStay to Color(0xFFFF9800)
                            "Moving" -> Icons.AutoMirrored.Filled.DirectionsWalk to Color(0xFF4CAF50)
                            else -> Icons.Default.LocationOff to Color.Gray
                        }
                        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
                        Text(viewModel.trackingMode, style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.weight(1f))

                        // Toggle
                        Switch(
                            checked = viewModel.isTracking,
                            onCheckedChange = { checked ->
                                if (checked) {
                                    locationPermissionLauncher.launch(
                                        arrayOf(
                                            Manifest.permission.ACCESS_FINE_LOCATION,
                                            Manifest.permission.ACCESS_COARSE_LOCATION,
                                            Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                                            Manifest.permission.ACTIVITY_RECOGNITION,
                                            Manifest.permission.POST_NOTIFICATIONS
                                        )
                                    )
                                } else {
                                    viewModel.stopTracking()
                                }
                            }
                        )
                    }

                    HorizontalDivider()

                    // Motion activity
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.DirectionsRun, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("Motion: ${viewModel.motionActivity}", style = MaterialTheme.typography.bodyMedium)
                    }

                    // Buffer info
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.Storage, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(
                            "Buffer: ${viewModel.bufferCount}/${viewModel.batchSize} points",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        TextButton(
                            onClick = { viewModel.flushNow() },
                            enabled = viewModel.bufferCount > 0
                        ) {
                            Text("Flush")
                        }
                    }

                    // Coordinates
                    viewModel.currentLocation?.let { loc ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.GpsFixed, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text(
                                String.format("%.5f, %.5f (%.0fm)", loc.latitude, loc.longitude, loc.accuracy),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace
                            )
                        }
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
