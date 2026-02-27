package ch.codelook.locationtracker.ui.visits

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.codelook.locationtracker.data.api.models.VisitInfo
import ch.codelook.locationtracker.data.preferences.PreferencesManager
import ch.codelook.locationtracker.domain.repository.VisitRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class VisitsViewModel @Inject constructor(
    private val visitRepository: VisitRepository,
    private val preferencesManager: PreferencesManager
) : ViewModel() {

    var visits by mutableStateOf<List<VisitInfo>>(emptyList())
    var isLoading by mutableStateOf(true)
    var errorMessage by mutableStateOf<String?>(null)

    init {
        loadVisits()
    }

    fun loadVisits() {
        val deviceId = preferencesManager.selectedDeviceId
        if (deviceId <= 0) {
            isLoading = false
            return
        }

        viewModelScope.launch {
            isLoading = true
            visitRepository.getVisits(deviceId).fold(
                onSuccess = { visits = it },
                onFailure = { errorMessage = it.message }
            )
            isLoading = false
        }
    }
}
