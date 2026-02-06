package ch.codelook.locationtracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import ch.codelook.locationtracker.data.preferences.PreferencesManager
import ch.codelook.locationtracker.ui.navigation.AppNavigation
import ch.codelook.locationtracker.ui.theme.LocationTrackerTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var preferencesManager: PreferencesManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LocationTrackerTheme {
                AppNavigation(preferencesManager = preferencesManager)
            }
        }
    }
}
