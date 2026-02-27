package ch.codelook.locationtracker.data.api.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PlaceInfo(
    val id: Int,
    val latitude: Double,
    val longitude: Double,
    val name: String? = null,
    val address: String? = null,
    @SerialName("visit_count") val visitCount: Int,
    @SerialName("total_duration_seconds") val totalDurationSeconds: Int
)
