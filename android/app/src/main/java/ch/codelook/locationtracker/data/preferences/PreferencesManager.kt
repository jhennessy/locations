package ch.codelook.locationtracker.data.preferences

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PreferencesManager @Inject constructor(
    @ApplicationContext context: Context
) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "location_tracker_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    var authToken: String?
        get() = prefs.getString(KEY_AUTH_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_AUTH_TOKEN, value).apply()

    var username: String?
        get() = prefs.getString(KEY_USERNAME, null)
        set(value) = prefs.edit().putString(KEY_USERNAME, value).apply()

    var userId: Int
        get() = prefs.getInt(KEY_USER_ID, -1)
        set(value) = prefs.edit().putInt(KEY_USER_ID, value).apply()

    var selectedDeviceId: Int
        get() = prefs.getInt(KEY_SELECTED_DEVICE_ID, -1)
        set(value) = prefs.edit().putInt(KEY_SELECTED_DEVICE_ID, value).apply()

    var selectedDeviceName: String?
        get() = prefs.getString(KEY_SELECTED_DEVICE_NAME, null)
        set(value) = prefs.edit().putString(KEY_SELECTED_DEVICE_NAME, value).apply()

    var serverUrl: String
        get() = prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
        set(value) = prefs.edit().putString(KEY_SERVER_URL, value).apply()

    var batchSize: Int
        get() = prefs.getInt(KEY_BATCH_SIZE, DEFAULT_BATCH_SIZE)
        set(value) = prefs.edit().putInt(KEY_BATCH_SIZE, value).apply()

    var maxBufferAgeSec: Int
        get() = prefs.getInt(KEY_MAX_BUFFER_AGE, DEFAULT_MAX_BUFFER_AGE)
        set(value) = prefs.edit().putInt(KEY_MAX_BUFFER_AGE, value).apply()

    var aggressiveUpload: Boolean
        get() = prefs.getBoolean(KEY_AGGRESSIVE_UPLOAD, false)
        set(value) = prefs.edit().putBoolean(KEY_AGGRESSIVE_UPLOAD, value).apply()

    var trackingEnabled: Boolean
        get() = prefs.getBoolean(KEY_TRACKING_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_TRACKING_ENABLED, value).apply()

    val isLoggedIn: Boolean
        get() = authToken != null

    val hasSelectedDevice: Boolean
        get() = selectedDeviceId > 0

    fun logout() {
        prefs.edit()
            .remove(KEY_AUTH_TOKEN)
            .remove(KEY_USERNAME)
            .remove(KEY_USER_ID)
            .remove(KEY_SELECTED_DEVICE_ID)
            .remove(KEY_SELECTED_DEVICE_NAME)
            .apply()
    }

    fun clearDevice() {
        prefs.edit()
            .remove(KEY_SELECTED_DEVICE_ID)
            .remove(KEY_SELECTED_DEVICE_NAME)
            .apply()
    }

    companion object {
        private const val KEY_AUTH_TOKEN = "auth_token"
        private const val KEY_USERNAME = "username"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_SELECTED_DEVICE_ID = "selected_device_id"
        private const val KEY_SELECTED_DEVICE_NAME = "selected_device_name"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_BATCH_SIZE = "batch_size"
        private const val KEY_MAX_BUFFER_AGE = "max_buffer_age"
        private const val KEY_AGGRESSIVE_UPLOAD = "aggressive_upload"
        private const val KEY_TRACKING_ENABLED = "tracking_enabled"

        const val DEFAULT_SERVER_URL = "https://locations.codelook.ch"
        const val DEFAULT_BATCH_SIZE = 10
        const val DEFAULT_MAX_BUFFER_AGE = 300
    }
}
