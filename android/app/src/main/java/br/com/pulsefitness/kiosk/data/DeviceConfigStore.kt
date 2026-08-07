package br.com.pulsefitness.kiosk.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Persists the tablet's device token, set once during provisioning.
 * The token binds this tablet to one machine on the backend; students
 * never see or type it.
 */
private val Context.dataStore by preferencesDataStore(name = "device_config")

class DeviceConfigStore(private val context: Context) {
    private val deviceTokenKey = stringPreferencesKey("device_token")

    val deviceToken: Flow<String?> =
        context.dataStore.data.map { prefs -> prefs[deviceTokenKey] }

    suspend fun setDeviceToken(token: String) {
        context.dataStore.edit { prefs -> prefs[deviceTokenKey] = token }
    }
}
