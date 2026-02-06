package ch.codelook.locationtracker.ui.places

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import ch.codelook.locationtracker.data.api.models.PlaceInfo
import ch.codelook.locationtracker.ui.components.EmptyState
import ch.codelook.locationtracker.ui.components.LoadingIndicator
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FrequentPlacesScreen(
    viewModel: FrequentPlacesViewModel = hiltViewModel()
) {
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Frequent Places") })
        }
    ) { padding ->
        if (viewModel.isLoading) {
            LoadingIndicator("Loading places...")
        } else if (viewModel.places.isEmpty()) {
            EmptyState(
                title = "No Frequent Places",
                description = "Places visited two or more times will appear here.",
                icon = {
                    Icon(
                        Icons.Default.StarBorder,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            )
        } else {
            PullToRefreshBox(
                isRefreshing = viewModel.isLoading,
                onRefresh = { viewModel.loadPlaces() },
                modifier = Modifier.padding(padding)
            ) {
                LazyColumn(
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Map
                    item {
                        val places = viewModel.places
                        AndroidView(
                            factory = { ctx ->
                                MapView(ctx).apply {
                                    setTileSource(TileSourceFactory.MAPNIK)
                                    setMultiTouchControls(true)
                                    controller.setZoom(12.0)
                                }
                            },
                            update = { mapView ->
                                mapView.overlays.clear()
                                places.forEach { place ->
                                    val marker = Marker(mapView).apply {
                                        position = GeoPoint(place.latitude, place.longitude)
                                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                                        title = place.name ?: "Place"
                                        snippet = "${place.visitCount} visits"
                                    }
                                    mapView.overlays.add(marker)
                                }
                                places.firstOrNull()?.let {
                                    mapView.controller.animateTo(GeoPoint(it.latitude, it.longitude))
                                }
                                mapView.invalidate()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(300.dp)
                        )
                    }

                    // Ranked list
                    itemsIndexed(viewModel.places, key = { _, place -> place.id }) { index, place ->
                        PlaceRow(place, rank = index + 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaceRow(place: PlaceInfo, rank: Int) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "#$rank",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    place.name ?: place.address ?: String.format("%.4f, %.4f", place.latitude, place.longitude),
                    style = MaterialTheme.typography.bodyMedium
                )

                if (place.name != null && place.address != null) {
                    Text(
                        place.address,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        "${place.visitCount} visits",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        formatDuration(place.totalDurationSeconds),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

private fun formatDuration(seconds: Int): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}
