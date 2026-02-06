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
            val serviceIntent = Intent(context, LocationTrackingService::class.java).apply {
                putExtra(LocationTrackingService.EXTRA_ACTIVITY_TYPE, event.activityType)
                putExtra(LocationTrackingService.EXTRA_TRANSITION_TYPE, event.transitionType)
            }
            context.startForegroundService(serviceIntent)
        }
    }
}
