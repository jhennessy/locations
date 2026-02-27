package ch.codelook.locationtracker.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import ch.codelook.locationtracker.data.preferences.PreferencesManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var preferencesManager: PreferencesManager

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        if (preferencesManager.trackingEnabled && preferencesManager.hasSelectedDevice) {
            Log.d("BootReceiver", "Auto-resuming tracking after boot")
            val serviceIntent = Intent(context, LocationTrackingService::class.java)
            context.startForegroundService(serviceIntent)
        }
    }
}
