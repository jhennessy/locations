package ch.codelook.locationtracker.ui.places

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.codelook.locationtracker.data.api.models.PlaceInfo
import ch.codelook.locationtracker.domain.repository.PlaceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FrequentPlacesViewModel @Inject constructor(
    private val placeRepository: PlaceRepository
) : ViewModel() {

    var places by mutableStateOf<List<PlaceInfo>>(emptyList())
    var isLoading by mutableStateOf(true)
    var errorMessage by mutableStateOf<String?>(null)

    init {
        loadPlaces()
    }

    fun loadPlaces() {
        viewModelScope.launch {
            isLoading = true
            placeRepository.getFrequentPlaces().fold(
                onSuccess = { places = it },
                onFailure = { errorMessage = it.message }
            )
            isLoading = false
        }
    }
}
