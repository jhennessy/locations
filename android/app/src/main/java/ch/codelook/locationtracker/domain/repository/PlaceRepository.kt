package ch.codelook.locationtracker.domain.repository

import ch.codelook.locationtracker.data.api.ApiService
import ch.codelook.locationtracker.data.api.models.PlaceInfo
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaceRepository @Inject constructor(
    private val apiService: ApiService
) {
    suspend fun getFrequentPlaces(limit: Int = 20): Result<List<PlaceInfo>> {
        return try {
            Result.success(apiService.getFrequentPlaces(limit))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
