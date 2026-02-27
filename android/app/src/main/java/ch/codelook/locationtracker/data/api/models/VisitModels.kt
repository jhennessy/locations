package ch.codelook.locationtracker.data.api.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class VisitInfo(
    val id: Int,
    @SerialName("device_id") val deviceId: Int,
    @SerialName("place_id") val placeId: Int,
    val latitude: Double,
    val longitude: Double,
    val arrival: String,
    val departure: String,
    @SerialName("duration_seconds") val durationSeconds: Int,
    val address: String? = null
)
