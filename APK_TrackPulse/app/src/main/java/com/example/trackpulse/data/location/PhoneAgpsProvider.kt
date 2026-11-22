package com.example.trackpulse.data.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import com.example.trackpulse.data.permissions.PermissionHelper
import com.example.trackpulse.domain.model.AgpsPayload
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class HostPhoneLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracyM: Float,
    val altitudeM: Double,
    val timestampMs: Long
)

/**
 * Fournit la position temps réel du téléphone hôte (via FusedLocationProviderClient + repli LocationManager)
 * pour :
 *  1. L'affichage du marqueur bleu sur la carte Antivol (avec centrage Téléphone / Tracker / Les deux).
 *  2. L'injection automatique A-GPS (UTC + coordonnées) vers le STM32 + SIM7080G dès la connexion BLE.
 */
class PhoneAgpsProvider(private val context: Context) {

    private val fusedClient = LocationServices.getFusedLocationProviderClient(context)

    private val _phoneLocation = MutableStateFlow<HostPhoneLocation?>(null)
    val phoneLocation: StateFlow<HostPhoneLocation?> = _phoneLocation.asStateFlow()

    private var isListening = false

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { updateLocation(it) }
        }
    }

    fun hasLocationPermission(): Boolean = PermissionHelper.hasLocationPermission(context)

    @SuppressLint("MissingPermission")
    fun startLocationUpdates() {
        if (!hasLocationPermission()) return
        if (isListening) return

        // Lecture immédiate de la dernière position connue (Fused puis LocationManager)
        runCatching {
            fusedClient.lastLocation.addOnSuccessListener { loc ->
                if (loc != null) updateLocation(loc)
            }
        }
        readLastKnownFromLocationManager()?.let { updateLocation(it) }

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3_500L)
            .setMinUpdateIntervalMillis(2_000L)
            .setMinUpdateDistanceMeters(2f)
            .build()

        runCatching {
            fusedClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
            isListening = true
        }
    }

    fun stopLocationUpdates() {
        if (!isListening) return
        runCatching { fusedClient.removeLocationUpdates(locationCallback) }
        isListening = false
    }

    fun captureAgpsPayload(): AgpsPayload {
        val nowMs = System.currentTimeMillis()
        val epochSec = nowMs / 1000L
        val millis = (nowMs % 1000L).toInt()

        val live = _phoneLocation.value
        if (live != null) {
            return AgpsPayload(
                utcEpochSec = epochSec,
                utcMillis = millis,
                latitude = live.latitude,
                longitude = live.longitude,
                altitudeM = live.altitudeM.toFloat(),
                accuracyM = live.accuracyM.toInt().coerceAtLeast(1),
                hasValidPosition = true,
                hasValidTime = true
            )
        }

        val fallbackLoc = readLastKnownFromLocationManager()
        return if (fallbackLoc != null) {
            updateLocation(fallbackLoc)
            AgpsPayload(
                utcEpochSec = epochSec,
                utcMillis = millis,
                latitude = fallbackLoc.latitude,
                longitude = fallbackLoc.longitude,
                altitudeM = fallbackLoc.altitude.toFloat(),
                accuracyM = fallbackLoc.accuracy.toInt().coerceAtLeast(1),
                hasValidPosition = true,
                hasValidTime = true
            )
        } else {
            AgpsPayload(
                utcEpochSec = epochSec,
                utcMillis = millis,
                latitude = 47.956012,
                longitude = 0.207845,
                altitudeM = 58.0f,
                accuracyM = 50,
                hasValidPosition = false,
                hasValidTime = true
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun readLastKnownFromLocationManager(): Location? {
        if (!hasLocationPermission()) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        var best: Location? = null
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )
        for (provider in providers) {
            runCatching { lm.getLastKnownLocation(provider) }.getOrNull()?.let { loc ->
                if (best == null || loc.accuracy < best.accuracy) {
                    best = loc
                }
            }
        }
        return best
    }

    private fun updateLocation(loc: Location) {
        _phoneLocation.value = HostPhoneLocation(
            latitude = loc.latitude,
            longitude = loc.longitude,
            accuracyM = loc.accuracy.coerceAtLeast(1f),
            altitudeM = loc.altitude,
            timestampMs = System.currentTimeMillis()
        )
    }
}
