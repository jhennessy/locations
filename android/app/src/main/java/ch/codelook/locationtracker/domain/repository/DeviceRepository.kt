package ch.codelook.locationtracker.domain.repository

import ch.codelook.locationtracker.data.api.ApiService
import ch.codelook.locationtracker.data.api.models.DeviceCreateRequest
import ch.codelook.locationtracker.data.api.models.DeviceInfo
import ch.codelook.locationtracker.data.preferences.PreferencesManager
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceRepository @Inject constructor(
    private val apiService: ApiService,
    private val preferencesManager: PreferencesManager
) {
    val selectedDeviceId: Int get() = preferencesManager.selectedDeviceId
    val selectedDeviceName: String? get() = preferencesManager.selectedDeviceName
    val hasSelectedDevice: Boolean get() = preferencesManager.hasSelectedDevice

    suspend fun getDevices(): Result<List<DeviceInfo>> {
        return try {
            Result.success(apiService.getDevices())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun createDevice(name: String, identifier: String): Result<DeviceInfo> {
        return try {
            Result.success(apiService.createDevice(DeviceCreateRequest(name, identifier)))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteDevice(deviceId: Int): Result<Unit> {
        return try {
            apiService.deleteDevice(deviceId)
            if (preferencesManager.selectedDeviceId == deviceId) {
                preferencesManager.clearDevice()
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun selectDevice(device: DeviceInfo) {
        preferencesManager.selectedDeviceId = device.id
        preferencesManager.selectedDeviceName = device.name
    }

    fun clearDevice() {
        preferencesManager.clearDevice()
    }
}
