package com.example.trackpulse.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.trackpulse.domain.model.MqttSettings
import com.example.trackpulse.domain.model.TransportPreference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.trackPulseDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "trackpulse_prefs"
)

data class StoredAppConfig(
    val onboardingCompleted: Boolean = false,
    val transportPreference: TransportPreference = TransportPreference.AUTO_HYBRID,
    val mqttSettings: MqttSettings = MqttSettings(),
    val pairingKey: String = ""
)

/**
 * Persistance locale DataStore pour l'onboarding premier lancement, l'appairage QR Code
 * et la stratégie de transport (Hybride Auto par défaut, sans simulation).
 */
class AppPreferencesRepository(private val context: Context) {

    private object Keys {
        val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        val TRANSPORT_PREF = stringPreferencesKey("transport_preference")
        val DEVICE_ID = stringPreferencesKey("paired_device_id")
        val BLE_FILTER = stringPreferencesKey("paired_ble_filter")
        val PAIRING_KEY = stringPreferencesKey("pairing_key")
        val BROKER_URI = stringPreferencesKey("mqtt_broker_uri")
        val RTC_WAKE_SEC = intPreferencesKey("rtc_wake_interval_sec")
        val MOTION_THRESH_MG = intPreferencesKey("motion_threshold_mg")
    }

    val configFlow: Flow<StoredAppConfig> = context.trackPulseDataStore.data.map { prefs ->
        val prefName = prefs[Keys.TRANSPORT_PREF] ?: TransportPreference.AUTO_HYBRID.name
        val transportPref = runCatching { TransportPreference.valueOf(prefName) }
            .getOrDefault(TransportPreference.AUTO_HYBRID)

        val settings = MqttSettings(
            brokerUri = prefs[Keys.BROKER_URI] ?: "tcp://broker.hivemq.com:1883",
            deviceId = prefs[Keys.DEVICE_ID] ?: "tp01",
            bleDeviceNameFilter = prefs[Keys.BLE_FILTER] ?: "TrackPulse",
            rtcWakeIntervalSec = prefs[Keys.RTC_WAKE_SEC] ?: 3600,
            motionThresholdMg = prefs[Keys.MOTION_THRESH_MG] ?: 160
        )

        StoredAppConfig(
            onboardingCompleted = prefs[Keys.ONBOARDING_COMPLETED] ?: false,
            transportPreference = transportPref,
            mqttSettings = settings,
            pairingKey = prefs[Keys.PAIRING_KEY] ?: ""
        )
    }

    suspend fun savePairingCompleted(
        deviceId: String,
        bleNameFilter: String,
        pairingKey: String,
        transportPreference: TransportPreference
    ) {
        context.trackPulseDataStore.edit { prefs ->
            prefs[Keys.ONBOARDING_COMPLETED] = true
            prefs[Keys.DEVICE_ID] = deviceId
            prefs[Keys.BLE_FILTER] = bleNameFilter
            prefs[Keys.PAIRING_KEY] = pairingKey
            prefs[Keys.TRANSPORT_PREF] = transportPreference.name
        }
    }

    suspend fun saveSettings(
        transportPreference: TransportPreference,
        settings: MqttSettings
    ) {
        context.trackPulseDataStore.edit { prefs ->
            prefs[Keys.TRANSPORT_PREF] = transportPreference.name
            prefs[Keys.BROKER_URI] = settings.brokerUri
            prefs[Keys.DEVICE_ID] = settings.deviceId
            prefs[Keys.BLE_FILTER] = settings.bleDeviceNameFilter
            prefs[Keys.RTC_WAKE_SEC] = settings.rtcWakeIntervalSec
            prefs[Keys.MOTION_THRESH_MG] = settings.motionThresholdMg
        }
    }

    suspend fun setOnboardingCompleted(completed: Boolean) {
        context.trackPulseDataStore.edit { prefs ->
            prefs[Keys.ONBOARDING_COMPLETED] = completed
        }
    }
}
