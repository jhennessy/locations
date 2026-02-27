package ch.codelook.locationtracker.domain.repository

import ch.codelook.locationtracker.data.api.ApiService
import ch.codelook.locationtracker.data.api.models.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PositionRepository @Inject constructor(
    private val apiService: ApiService
) {
    suspend fun updatePosition(
        deviceId: Int,
        latitude: Double,
        longitude: Double,
        altitude: Double?,
        accuracy: Double?,
        speed: Double?,
        timestamp: String
    ): Result<PositionUpdateResponse> {
        return try {
            val batch = PositionBatch(
                deviceId = deviceId,
                positions = listOf(
                    PositionPoint(
                        latitude = latitude,
                        longitude = longitude,
                        altitude = altitude,
                        accuracy = accuracy,
                        speed = speed,
                        timestamp = timestamp
                    )
                )
            )
            Result.success(apiService.updatePositions(batch))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchAllPositions(): Result<List<ServerPosition>> {
        return try {
            Result.success(apiService.getAllPositions())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun relayPositions(
        relayDeviceId: Int,
        positions: List<RelayedPosition>
    ): Result<RelayResponse> {
        return try {
            val batch = RelayBatch(relayDeviceId = relayDeviceId, positions = positions)
            Result.success(apiService.relayPositions(batch))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
