package ch.codelook.locationtracker.ui.auth

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.codelook.locationtracker.domain.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val authRepository: AuthRepository
) : ViewModel() {

    var username by mutableStateOf("")
    var password by mutableStateOf("")
    var isLoading by mutableStateOf(false)
    var errorMessage by mutableStateOf<String?>(null)
    var loginSuccess by mutableStateOf(false)

    fun login() {
        if (username.isBlank() || password.isBlank()) {
            errorMessage = "Please enter username and password"
            return
        }

        viewModelScope.launch {
            isLoading = true
            errorMessage = null
            val result = authRepository.login(username, password)
            result.fold(
                onSuccess = { loginSuccess = true },
                onFailure = { errorMessage = it.message ?: "Login failed" }
            )
            isLoading = false
        }
    }
}
