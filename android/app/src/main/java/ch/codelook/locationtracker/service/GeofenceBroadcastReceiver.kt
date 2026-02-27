package ch.codelook.locationtracker.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent

class GeofenceBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return

        if (event.hasError()) {
            Log.e("GeofenceReceiver", "Geofence error: ${event.errorCode}")
            return
        }

        if (event.geofenceTransition == Geofence.GEOFENCE_TRANSITION_EXIT) {
            Log.d("GeofenceReceiver", "Geofence exit detected")
            val serviceIntent = Intent(context, LocationTrackingService::class.java).apply {
                action = LocationTrackingService.ACTION_GEOFENCE_EXIT
            }
            context.startForegroundService(serviceIntent)
        }
    }
}
