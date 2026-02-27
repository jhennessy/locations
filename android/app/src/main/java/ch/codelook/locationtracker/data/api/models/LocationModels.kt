package ch.codelook.locationtracker.data.api.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LocationPoint(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double? = null,
    @SerialName("horizontal_accuracy") val horizontalAccuracy: Double? = null,
    @SerialName("vertical_accuracy") val verticalAccuracy: Double? = null,
    val speed: Double? = null,
    val course: Double? = null,
    val timestamp: String,
    val notes: String? = null
)

@Serializable
data class LocationBatch(
    @SerialName("device_id") val deviceId: Int,
    val locations: List<LocationPoint>
)

@Serializable
data class BatchResponse(
    val received: Int,
    @SerialName("batch_id") val batchId: String,
    @SerialName("visits_detected") val visitsDetected: Int = 0
)
