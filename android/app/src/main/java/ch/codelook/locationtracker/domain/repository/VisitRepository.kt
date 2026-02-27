package ch.codelook.locationtracker.domain.repository

import ch.codelook.locationtracker.data.api.ApiService
import ch.codelook.locationtracker.data.api.models.VisitInfo
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VisitRepository @Inject constructor(
    private val apiService: ApiService
) {
    suspend fun getVisits(deviceId: Int, limit: Int = 100): Result<List<VisitInfo>> {
        return try {
            Result.success(apiService.getVisits(deviceId, limit))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
