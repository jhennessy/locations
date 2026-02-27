package ch.codelook.locationtracker.ui.tracking

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.location.Location
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.font.FontWeight
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
                val blePeers = viewModel.blePeers
                val serverPositions = viewModel.serverPositions
                AndroidView(
                    factory = { ctx ->
                        MapView(ctx).apply {
                            setTileSource(TileSourceFactory.MAPNIK)
                            setMultiTouchControls(true)
                            controller.setZoom(15.0)
                        }
                    },
                    update = { mapView ->
                        mapView.overlays.clear()

                        location?.let { loc ->
                            val point = GeoPoint(loc.latitude, loc.longitude)
                            mapView.controller.animateTo(point)

                            val marker = Marker(mapView).apply {
                                position = point
                                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                                title = "Current Location"
                            }
                            mapView.overlays.add(marker)
                        }

                        // BLE peer markers (green)
                        for (peer in blePeers) {
                            if (peer.isStale) continue
                            val peerMarker = Marker(mapView).apply {
                                position = GeoPoint(peer.latitude, peer.longitude)
                                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                                title = "${peer.username} (BLE)"
                                snippet = "Device ${peer.deviceId}"
                            }
                            mapView.overlays.add(peerMarker)
                        }

                        // Server position markers (orange)
                        for (pos in serverPositions) {
                            val posMarker = Marker(mapView).apply {
                                position = GeoPoint(pos.latitude, pos.longitude)
                                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                                title = "${pos.username ?: "Unknown"} (${pos.deviceName ?: "Device ${pos.deviceId}"})"
                            }
                            mapView.overlays.add(posMarker)
                        }

                        mapView.invalidate()
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

                        if (viewModel.blePeerCount > 0) {
                            Icon(
                                Icons.Default.Bluetooth,
                                contentDescription = "BLE Peers",
                                tint = Color(0xFF2196F3),
                                modifier = Modifier.size(18.dp)
                            )
                            Text("${viewModel.blePeerCount}", style = MaterialTheme.typography.bodySmall, color = Color(0xFF2196F3))
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

                    // Nearby people section
                    NearbyPeopleSection(viewModel)
                }
            }
        }
    }
}

@Composable
private fun NearbyPeopleSection(viewModel: TrackingViewModel) {
    val blePeers = viewModel.blePeers
    val serverPositions = viewModel.serverPositions
    val myLocation = viewModel.currentLocation

    // Merge BLE and server peers, dedup by device ID
    val bleDeviceIds = blePeers.map { it.deviceId }.toSet()
    val nearbyPeople = buildList {
        for (peer in blePeers) {
            if (peer.isStale) continue
            val dist = myLocation?.let { loc ->
                val results = FloatArray(1)
                Location.distanceBetween(loc.latitude, loc.longitude, peer.latitude, peer.longitude, results)
                results[0].toDouble()
            }
            add(NearbyPerson(
                id = "ble-${peer.deviceId}",
                name = peer.username,
                source = "BLE",
                sourceColor = Color(0xFF4CAF50),
                distance = dist,
                accuracy = peer.accuracy,
                secondsAgo = ((System.currentTimeMillis() - peer.discoveredAt.time) / 1000).toInt()
            ))
        }
        for (pos in serverPositions) {
            if (bleDeviceIds.contains(pos.deviceId)) continue
            val dist = myLocation?.let { loc ->
                val results = FloatArray(1)
                Location.distanceBetween(loc.latitude, loc.longitude, pos.latitude, pos.longitude, results)
                results[0].toDouble()
            }
            add(NearbyPerson(
                id = "srv-${pos.deviceId}",
                name = pos.username ?: pos.deviceName ?: "Device ${pos.deviceId}",
                source = "Server",
                sourceColor = Color(0xFFFF9800),
                distance = dist,
                accuracy = pos.accuracy,
                secondsAgo = null
            ))
        }
    }.sortedBy { it.distance ?: Double.MAX_VALUE }

    if (nearbyPeople.isEmpty()) return

    var expanded by remember { mutableStateOf(false) }

    HorizontalDivider()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.People, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            "Nearby (${nearbyPeople.size})",
            style = MaterialTheme.typography.titleSmall
        )
        Spacer(modifier = Modifier.weight(1f))
        Icon(
            if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    AnimatedVisibility(visible = expanded) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            nearbyPeople.forEach { person ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Source dot
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .padding(0.dp)
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            drawCircle(color = person.sourceColor)
                        }
                    }

                    // Name + source badge
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(person.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                            Surface(
                                color = person.sourceColor.copy(alpha = 0.15f),
                                shape = MaterialTheme.shapes.extraSmall
                            ) {
                                Text(
                                    person.source,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = person.sourceColor,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            person.distance?.let { dist ->
                                Text(
                                    formatDistance(dist),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            person.secondsAgo?.let { secs ->
                                Text(
                                    formatTimeAgo(secs),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    // Accuracy
                    person.accuracy?.let { acc ->
                        Text(
                            "${acc.toInt()}m",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

private data class NearbyPerson(
    val id: String,
    val name: String,
    val source: String,
    val sourceColor: Color,
    val distance: Double?,
    val accuracy: Double?,
    val secondsAgo: Int?
)

private fun formatDistance(meters: Double): String {
    return if (meters < 1000) "${meters.toInt()}m"
    else String.format("%.1fkm", meters / 1000)
}

private fun formatTimeAgo(seconds: Int): String {
    return when {
        seconds < 60 -> "${seconds}s ago"
        seconds < 3600 -> "${seconds / 60}m ago"
        else -> "${seconds / 3600}h ago"
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
