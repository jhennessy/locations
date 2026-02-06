package ch.codelook.locationtracker.domain.repository

import ch.codelook.locationtracker.data.api.ApiService
import ch.codelook.locationtracker.data.api.models.BatchResponse
import ch.codelook.locationtracker.data.api.models.LocationBatch
import ch.codelook.locationtracker.data.api.models.LocationPoint
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocationRepository @Inject constructor(
    private val apiService: ApiService
) {
    suspend fun uploadLocations(deviceId: Int, locations: List<LocationPoint>): Result<BatchResponse> {
        return try {
            val batch = LocationBatch(deviceId = deviceId, locations = locations)
            Result.success(apiService.uploadLocations(batch))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
