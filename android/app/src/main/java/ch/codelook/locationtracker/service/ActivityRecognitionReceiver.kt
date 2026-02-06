package ch.codelook.locationtracker.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.ActivityTransitionResult

class ActivityRecognitionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (!ActivityTransitionResult.hasResult(intent)) return

        val result = ActivityTransitionResult.extractResult(intent) ?: return

        for (event in result.transitionEvents) {
            // Forward to the service via a broadcast or direct service call
            val serviceIntent = Intent(context, LocationTrackingService::class.java).apply {
                putExtra("activity_type", event.activityType)
                putExtra("transition_type", event.transitionType)
            }
            context.startForegroundService(serviceIntent)
        }
    }
}
