package ch.codelook.locationtracker.domain.repository

import ch.codelook.locationtracker.data.api.ApiService
import ch.codelook.locationtracker.data.api.models.*
import ch.codelook.locationtracker.data.preferences.PreferencesManager
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepository @Inject constructor(
    private val apiService: ApiService,
    private val preferencesManager: PreferencesManager
) {
    val isLoggedIn: Boolean get() = preferencesManager.isLoggedIn
    val username: String? get() = preferencesManager.username

    suspend fun login(username: String, password: String): Result<TokenResponse> {
        return try {
            val response = apiService.login(LoginRequest(username, password))
            preferencesManager.authToken = response.token
            preferencesManager.username = response.username
            preferencesManager.userId = response.userId
            Result.success(response)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun register(username: String, email: String, password: String): Result<TokenResponse> {
        return try {
            val response = apiService.register(RegisterRequest(username, email, password))
            preferencesManager.authToken = response.token
            preferencesManager.username = response.username
            preferencesManager.userId = response.userId
            Result.success(response)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun changePassword(currentPassword: String, newPassword: String): Result<Unit> {
        return try {
            apiService.changePassword(ChangePasswordRequest(currentPassword, newPassword))
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun logout() {
        preferencesManager.logout()
    }
}
