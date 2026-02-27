package ch.codelook.locationtracker.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import ch.codelook.locationtracker.data.preferences.PreferencesManager
import ch.codelook.locationtracker.domain.repository.AuthRepository
import ch.codelook.locationtracker.domain.repository.DeviceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferencesManager: PreferencesManager,
    private val authRepository: AuthRepository,
    private val deviceRepository: DeviceRepository
) : ViewModel() {

    var serverUrl by mutableStateOf(preferencesManager.serverUrl)
    var batchSize by mutableFloatStateOf(preferencesManager.batchSize.toFloat())
    var maxBufferAge by mutableFloatStateOf(preferencesManager.maxBufferAgeSec.toFloat())
    var aggressiveUpload by mutableStateOf(preferencesManager.aggressiveUpload)
    var loggedOut by mutableStateOf(false)
    var deviceCleared by mutableStateOf(false)

    val username: String? get() = preferencesManager.username
    val deviceName: String? get() = preferencesManager.selectedDeviceName

    fun updateServerUrl(url: String) {
        serverUrl = url
        preferencesManager.serverUrl = url
    }

    fun updateBatchSize(size: Float) {
        batchSize = size
        preferencesManager.batchSize = size.toInt()
    }

    fun updateMaxBufferAge(age: Float) {
        maxBufferAge = age
        preferencesManager.maxBufferAgeSec = age.toInt()
    }

    fun toggleAggressiveUpload(enabled: Boolean) {
        aggressiveUpload = enabled
        preferencesManager.aggressiveUpload = enabled
        if (enabled) {
            batchSize = 1f
            maxBufferAge = 30f
            preferencesManager.batchSize = 1
            preferencesManager.maxBufferAgeSec = 30
        }
    }

    fun changeDevice() {
        deviceRepository.clearDevice()
        deviceCleared = true
    }

    fun logout() {
        authRepository.logout()
        deviceRepository.clearDevice()
        loggedOut = true
    }
}
