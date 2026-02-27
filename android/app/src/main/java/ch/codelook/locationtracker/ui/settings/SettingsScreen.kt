package ch.codelook.locationtracker.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onChangeDevice: () -> Unit,
    onLogout: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    LaunchedEffect(viewModel.loggedOut) {
        if (viewModel.loggedOut) onLogout()
    }
    LaunchedEffect(viewModel.deviceCleared) {
        if (viewModel.deviceCleared) onChangeDevice()
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Settings") })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Server section
            Text("Server", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            OutlinedTextField(
                value = viewModel.serverUrl,
                onValueChange = { viewModel.updateServerUrl(it) },
                label = { Text("Server URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            HorizontalDivider()

            // Tracking section
            Text("Tracking", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)

            Column {
                Text("Batch size: ${viewModel.batchSize.toInt()} points")
                Slider(
                    value = viewModel.batchSize,
                    onValueChange = { viewModel.updateBatchSize(it) },
                    valueRange = 1f..50f,
                    steps = 48
                )
            }

            Column {
                Text("Max buffer age: ${viewModel.maxBufferAge.toInt()}s")
                Slider(
                    value = viewModel.maxBufferAge,
                    onValueChange = { viewModel.updateMaxBufferAge(it) },
                    valueRange = 30f..1800f,
                    steps = 58
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Aggressive Upload")
                    Text(
                        "Upload every point immediately (batch=1, age=30s)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = viewModel.aggressiveUpload,
                    onCheckedChange = { viewModel.toggleAggressiveUpload(it) }
                )
            }

            HorizontalDivider()

            // Device section
            Text("Device", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)

            viewModel.deviceName?.let {
                Text("Current device: $it", style = MaterialTheme.typography.bodyMedium)
            }

            OutlinedButton(
                onClick = { viewModel.changeDevice() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.PhoneAndroid, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Change Device")
            }

            HorizontalDivider()

            // Account section
            Text("Account", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)

            viewModel.username?.let {
                Text("Logged in as $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Button(
                onClick = { viewModel.logout() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Logout")
            }
        }
    }
}
