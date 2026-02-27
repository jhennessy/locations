package ch.codelook.locationtracker.ui.visits

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import ch.codelook.locationtracker.data.api.models.VisitInfo
import ch.codelook.locationtracker.ui.components.EmptyState
import ch.codelook.locationtracker.ui.components.LoadingIndicator
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.time.Duration
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VisitsScreen(
    viewModel: VisitsViewModel = hiltViewModel()
) {
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Visits") })
        }
    ) { padding ->
        if (viewModel.isLoading) {
            LoadingIndicator("Loading visits...")
        } else if (viewModel.visits.isEmpty()) {
            EmptyState(
                title = "No Visits Yet",
                description = "Visits are detected when you stay in one place for at least 5 minutes.",
                icon = {
                    Icon(
                        Icons.Default.LocationOff,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            )
        } else {
            PullToRefreshBox(
                isRefreshing = viewModel.isLoading,
                onRefresh = { viewModel.loadVisits() },
                modifier = Modifier.padding(padding)
            ) {
                LazyColumn(
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Map
                    item {
                        val visits = viewModel.visits
                        AndroidView(
                            factory = { ctx ->
                                MapView(ctx).apply {
                                    setTileSource(TileSourceFactory.MAPNIK)
                                    setMultiTouchControls(true)
                                    controller.setZoom(13.0)
                                }
                            },
                            update = { mapView ->
                                mapView.overlays.clear()
                                visits.forEach { visit ->
                                    val marker = Marker(mapView).apply {
                                        position = GeoPoint(visit.latitude, visit.longitude)
                                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                                        title = formatDuration(visit.durationSeconds)
                                        snippet = visit.address ?: "${visit.latitude}, ${visit.longitude}"
                                    }
                                    mapView.overlays.add(marker)
                                }
                                visits.firstOrNull()?.let {
                                    mapView.controller.animateTo(GeoPoint(it.latitude, it.longitude))
                                }
                                mapView.invalidate()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(300.dp)
                        )
                    }

                    // Visit list
                    items(viewModel.visits, key = { it.id }) { visit ->
                        VisitRow(visit)
                    }
                }
            }
        }
    }
}

@Composable
private fun VisitRow(visit: VisitInfo) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Duration badge
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.primary
            ) {
                Text(
                    formatDuration(visit.durationSeconds),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    visit.address ?: String.format("%.4f, %.4f", visit.latitude, visit.longitude),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2
                )
                Text(
                    formatArrival(visit.arrival),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun formatDuration(seconds: Int): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}

private fun formatArrival(iso: String): String {
    return try {
        val dt = OffsetDateTime.parse(iso)
        dt.format(DateTimeFormatter.ofPattern("MMM d, HH:mm"))
    } catch (_: Exception) {
        iso
    }
}
