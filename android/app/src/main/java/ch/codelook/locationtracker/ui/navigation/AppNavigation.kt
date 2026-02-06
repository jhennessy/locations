package ch.codelook.locationtracker.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import ch.codelook.locationtracker.data.preferences.PreferencesManager
import ch.codelook.locationtracker.ui.auth.LoginScreen
import ch.codelook.locationtracker.ui.auth.RegisterScreen
import ch.codelook.locationtracker.ui.device.DeviceSelectionScreen
import ch.codelook.locationtracker.ui.places.FrequentPlacesScreen
import ch.codelook.locationtracker.ui.settings.SettingsScreen
import ch.codelook.locationtracker.ui.tracking.TrackingScreen
import ch.codelook.locationtracker.ui.visits.VisitsScreen

data class BottomNavItem(
    val screen: Screen,
    val label: String,
    val icon: @Composable () -> Unit
)

@Composable
fun AppNavigation(preferencesManager: PreferencesManager) {
    val navController = rememberNavController()

    val startDestination = when {
        !preferencesManager.isLoggedIn -> Screen.Login.route
        !preferencesManager.hasSelectedDevice -> Screen.DeviceSelection.route
        else -> Screen.Tracking.route
    }

    val bottomNavItems = listOf(
        BottomNavItem(Screen.Tracking, "Tracking") { Icon(Icons.Default.MyLocation, contentDescription = null) },
        BottomNavItem(Screen.Visits, "Visits") { Icon(Icons.Default.Place, contentDescription = null) },
        BottomNavItem(Screen.FrequentPlaces, "Places") { Icon(Icons.Default.Star, contentDescription = null) },
        BottomNavItem(Screen.Settings, "Settings") { Icon(Icons.Default.Settings, contentDescription = null) },
    )

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val showBottomBar = currentRoute in bottomNavItems.map { it.screen.route }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    bottomNavItems.forEach { item ->
                        NavigationBarItem(
                            selected = currentRoute == item.screen.route,
                            onClick = {
                                if (currentRoute != item.screen.route) {
                                    navController.navigate(item.screen.route) {
                                        popUpTo(Screen.Tracking.route) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            },
                            icon = item.icon,
                            label = { Text(item.label) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.padding(padding)
        ) {
            composable(Screen.Login.route) {
                LoginScreen(
                    onLoginSuccess = {
                        navController.navigate(Screen.DeviceSelection.route) {
                            popUpTo(Screen.Login.route) { inclusive = true }
                        }
                    },
                    onNavigateToRegister = {
                        navController.navigate(Screen.Register.route)
                    }
                )
            }

            composable(Screen.Register.route) {
                RegisterScreen(
                    onRegisterSuccess = {
                        navController.navigate(Screen.DeviceSelection.route) {
                            popUpTo(Screen.Login.route) { inclusive = true }
                        }
                    },
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            composable(Screen.DeviceSelection.route) {
                DeviceSelectionScreen(
                    onDeviceSelected = {
                        navController.navigate(Screen.Tracking.route) {
                            popUpTo(Screen.DeviceSelection.route) { inclusive = true }
                        }
                    },
                    onLoggedOut = {
                        navController.navigate(Screen.Login.route) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                )
            }

            composable(Screen.Tracking.route) {
                TrackingScreen()
            }

            composable(Screen.Visits.route) {
                VisitsScreen()
            }

            composable(Screen.FrequentPlaces.route) {
                FrequentPlacesScreen()
            }

            composable(Screen.Settings.route) {
                SettingsScreen(
                    onChangeDevice = {
                        navController.navigate(Screen.DeviceSelection.route) {
                            popUpTo(Screen.Tracking.route) { inclusive = true }
                        }
                    },
                    onLogout = {
                        navController.navigate(Screen.Login.route) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                )
            }
        }
    }
}
