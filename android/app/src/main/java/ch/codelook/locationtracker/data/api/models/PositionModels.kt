package ch.codelook.locationtracker.data.api.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PositionPoint(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double? = null,
    val accuracy: Double? = null,
    val speed: Double? = null,
    val timestamp: String
)

@Serializable
data class PositionBatch(
    @SerialName("device_id") val deviceId: Int,
    val positions: List<PositionPoint>
)

@Serializable
data class PositionUpdateResponse(
    val updated: Int
)

@Serializable
data class ServerPosition(
    @SerialName("device_id") val deviceId: Int,
    @SerialName("device_name") val deviceName: String? = null,
    @SerialName("user_id") val userId: Int,
    val username: String? = null,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double? = null,
    val accuracy: Double? = null,
    val speed: Double? = null,
    val timestamp: String? = null,
    @SerialName("is_stale") val isStale: Boolean = false
)

@Serializable
data class RelayedPosition(
    @SerialName("device_id") val deviceId: Int,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double? = null,
    val accuracy: Double? = null,
    val speed: Double? = null,
    val timestamp: String
)

@Serializable
data class RelayBatch(
    @SerialName("relay_device_id") val relayDeviceId: Int,
    val positions: List<RelayedPosition>
)

@Serializable
data class RelayResponse(
    val relayed: Int
)
