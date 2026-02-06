package ch.codelook.locationtracker.ui.device

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.codelook.locationtracker.data.api.models.DeviceInfo
import ch.codelook.locationtracker.domain.repository.AuthRepository
import ch.codelook.locationtracker.domain.repository.DeviceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class DeviceSelectionViewModel @Inject constructor(
    private val deviceRepository: DeviceRepository,
    private val authRepository: AuthRepository
) : ViewModel() {

    var devices by mutableStateOf<List<DeviceInfo>>(emptyList())
    var isLoading by mutableStateOf(true)
    var errorMessage by mutableStateOf<String?>(null)
    var selectedDeviceId by mutableStateOf(deviceRepository.selectedDeviceId)
    var deviceSelected by mutableStateOf(false)
    var loggedOut by mutableStateOf(false)

    init {
        loadDevices()
    }

    fun loadDevices() {
        viewModelScope.launch {
            isLoading = true
            errorMessage = null
            deviceRepository.getDevices().fold(
                onSuccess = { devices = it },
                onFailure = { errorMessage = it.message }
            )
            isLoading = false
        }
    }

    fun selectDevice(device: DeviceInfo) {
        deviceRepository.selectDevice(device)
        selectedDeviceId = device.id
        deviceSelected = true
    }

    fun createDevice(name: String) {
        val identifier = UUID.randomUUID().toString().take(12)
        viewModelScope.launch {
            deviceRepository.createDevice(name, identifier).fold(
                onSuccess = { loadDevices() },
                onFailure = { errorMessage = it.message }
            )
        }
    }

    fun deleteDevice(device: DeviceInfo) {
        viewModelScope.launch {
            deviceRepository.deleteDevice(device.id).fold(
                onSuccess = { loadDevices() },
                onFailure = { errorMessage = it.message }
            )
        }
    }

    fun logout() {
        authRepository.logout()
        deviceRepository.clearDevice()
        loggedOut = true
    }
}
