package ch.codelook.locationtracker.data.api.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DeviceInfo(
    val id: Int,
    val name: String,
    val identifier: String,
    @SerialName("last_seen") val lastSeen: String? = null
)

@Serializable
data class DeviceCreateRequest(
    val name: String,
    val identifier: String
)
