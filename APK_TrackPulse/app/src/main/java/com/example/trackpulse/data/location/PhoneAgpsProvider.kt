package com.example.trackpulse.data.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import com.example.trackpulse.domain.model.AgpsPayload

/**
 * Récupère la position GNSS/Fused du smartphone et l'heure UTC système précise
 * afin d'alimenter l'injection A-GPS automatique vers le STM32 + SIM7080G au démarrage BLE.
 */
class PhoneAgpsProvider(private val context: Context) {

    fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    @SuppressLint("MissingPermission")
    fun captureAgpsPayload(): AgpsPayload {
        val nowMs = System.currentTimeMillis()
        val epochSec = nowMs / 1000L
        val millis = (nowMs % 1000L).toInt()

        var bestLocation: Location? = null
        if (hasLocationPermission()) {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            val providers = listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER
            )
            for (provider in providers) {
                runCatching { lm?.getLastKnownLocation(provider) }.getOrNull()?.let { loc ->
                    if (bestLocation == null || loc.accuracy < bestLocation.accuracy) {
                        bestLocation = loc
                    }
                }
            }
        }

        val loc = bestLocation
        return if (loc != null) {
            AgpsPayload(
                utcEpochSec = epochSec,
                utcMillis = millis,
                latitude = loc.latitude,
                longitude = loc.longitude,
                altitudeM = loc.altitude.toFloat(),
                accuracyM = loc.accuracy.toInt().coerceAtLeast(1),
                hasValidPosition = true,
                hasValidTime = true
            )
        } else {
            // Repli temps UTC + coordonnées de référence Le Mans si aucun fix smartphone récent
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
}
