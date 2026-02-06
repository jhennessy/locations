package ch.codelook.locationtracker.ui.navigation

sealed class Screen(val route: String) {
    data object Login : Screen("login")
    data object Register : Screen("register")
    data object DeviceSelection : Screen("device_selection")
    data object Tracking : Screen("tracking")
    data object Visits : Screen("visits")
    data object FrequentPlaces : Screen("frequent_places")
    data object Settings : Screen("settings")
}
