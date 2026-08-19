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
    private val adminPinHashKey = stringPreferencesKey("admin_pin_hash")
    private val cachedConfigKey = stringPreferencesKey("cached_machine_config")

    val deviceToken: Flow<String?> =
        context.dataStore.data.map { prefs -> prefs[deviceTokenKey] }

    /**
     * Hash of the gym's maintenance code, cached from the last successful
     * config fetch. Cached rather than fetched on demand because staff need
     * to leave kiosk mode precisely when the network is broken.
     */
    val adminPinHash: Flow<String?> =
        context.dataStore.data.map { prefs -> prefs[adminPinHashKey] }

    suspend fun setDeviceToken(token: String) {
        context.dataStore.edit { prefs -> prefs[deviceTokenKey] = token }
    }

    suspend fun setAdminPinHash(hash: String) {
        context.dataStore.edit { prefs -> prefs[adminPinHashKey] = hash }
    }

    /**
     * Last machine config that the backend confirmed.
     *
     * The tablet must boot into a usable screen with the network down. Once
     * this app is the launcher and pinned, a "sem conexão" error screen would
     * BE the device: no Home, no Settings, no way to fix the wifi that caused
     * it. So the tablet boots from cache and refreshes in the background.
     */
    suspend fun cacheConfig(json: String) {
        context.dataStore.edit { prefs -> prefs[cachedConfigKey] = json }
    }

    val cachedConfig: Flow<String?> =
        context.dataStore.data.map { prefs -> prefs[cachedConfigKey] }

    /**
     * Unbinds the tablet from its machine so staff can point it at another
     * one, or fix a token typed wrong during install.
     *
     * There is no other way to do this on a provisioned tablet: Android
     * refuses `pm clear` for a Device Owner app, so without this the only
     * options would be un-enrolling or a factory reset.
     */
    suspend fun clearProvisioning() {
        context.dataStore.edit { prefs ->
            prefs.remove(deviceTokenKey)
            prefs.remove(cachedConfigKey)
            // adminPinHashKey is deliberately KEPT. The maintenance code
            // belongs to the gym, not to the machine, and it is the only way
            // back into a tablet that is already Device Owner and pinned.
            // Clearing it here created a deadlock on a wall mounted tablet:
            // no pairing without wi-fi, no wi-fi settings without the
            // maintenance code, and no way to restore the code without
            // pairing first.
        }
    }
}
