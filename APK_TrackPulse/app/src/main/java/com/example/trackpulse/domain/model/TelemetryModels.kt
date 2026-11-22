package com.example.trackpulse.domain.model

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

enum class VehicleType(val code: Byte, val label: String) {
    KART(0x00, "Karting"),
    MOTO(0x01, "Moto"),
    CAR(0x02, "Voiture");

    companion object {
        fun fromCode(code: Byte): VehicleType = entries.firstOrNull { it.code == code } ?: KART
    }
}

data class TelemetrySample(
    val dtMs: Int,
    val latitude: Double,
    val longitude: Double,
    val speedKmh: Float,
    val axG: Float, // Longitudinal (+ accélération, - freinage)
    val ayG: Float  // Latéral (+ droite, - gauche)
) {
    val combinedG: Float get() = hypot(axG, ayG)
}

data class FlashSessionHeader(
    val sessionId: Int,
    val startUtcSec: Long,
    val durationMs: Long,
    val sampleCount: Int,
    val maxSpeedKmh: Float,
    val peakG: Float,
    val sampleRateHz: Int,
    val vehicleType: VehicleType
) {
    val sizeBytes: Long get() = sampleCount.toLong() * 16L
    val sizeKb: Float get() = sizeBytes / 1024f
}

data class LapSplit(
    val lapNumber: Int,
    val durationMs: Long,
    val maxSpeedKmh: Float,
    val peakLatG: Float,
    val peakBrakeG: Float
)

data class TrackSession(
    val header: FlashSessionHeader,
    val samples: List<TelemetrySample>,
    val laps: List<LapSplit>,
    val bestLapMs: Long?,
    val avgSpeedKmh: Float,
    val maxSpeedKmh: Float,
    val maxLatG: Float,
    val maxAccelG: Float,
    val maxBrakeG: Float,
    val totalDistanceMeters: Float
)

data class FlashTransferProgress(
    val sessionId: Int = 0,
    val bytesReceived: Int = 0,
    val totalBytes: Int = 0,
    val isTransferring: Boolean = false,
    val statusText: String = ""
) {
    val fraction: Float
        get() = if (totalBytes > 0) (bytesReceived.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
}

object TrackSessionAnalyzer {

    fun analyze(header: FlashSessionHeader, samples: List<TelemetrySample>): TrackSession {
        if (samples.isEmpty()) {
            return TrackSession(
                header = header,
                samples = emptyList(),
                laps = emptyList(),
                bestLapMs = null,
                avgSpeedKmh = 0f,
                maxSpeedKmh = header.maxSpeedKmh,
                maxLatG = 0f,
                maxAccelG = 0f,
                maxBrakeG = 0f,
                totalDistanceMeters = 0f
            )
        }

        var sumSpeed = 0f
        var maxSpeed = 0f
        var maxLatG = 0f
        var maxAccelG = 0f
        var maxBrakeG = 0f
        var totalDist = 0.0

        val startLat = samples.first().latitude
        val startLon = samples.first().longitude

        val laps = mutableListOf<LapSplit>()
        var elapsedMs = 0L
        var lapStartMs = 0L
        var leftStartZone = false
        var lapMaxSpeed = 0f
        var lapMaxLat = 0f
        var lapMaxBrake = 0f

        samples.forEachIndexed { idx, s ->
            elapsedMs += s.dtMs
            sumSpeed += s.speedKmh
            if (s.speedKmh > maxSpeed) maxSpeed = s.speedKmh
            val absLat = kotlin.math.abs(s.ayG)
            if (absLat > maxLatG) maxLatG = absLat
            if (s.axG > maxAccelG) maxAccelG = s.axG
            if (-s.axG > maxBrakeG) maxBrakeG = -s.axG

            lapMaxSpeed = max(lapMaxSpeed, s.speedKmh)
            lapMaxLat = max(lapMaxLat, absLat)
            lapMaxBrake = max(lapMaxBrake, -s.axG)

            if (idx > 0) {
                val prev = samples[idx - 1]
                totalDist += haversineMeters(prev.latitude, prev.longitude, s.latitude, s.longitude)
            }

            // Détection de passage sur la ligne départ/arrivée (rayon 18 m après éloignement > 45 m)
            val distToStart = haversineMeters(startLat, startLon, s.latitude, s.longitude)
            if (!leftStartZone && distToStart > 45.0) {
                leftStartZone = true
            } else if (leftStartZone && distToStart < 18.0 && (elapsedMs - lapStartMs) > 15_000L) {
                laps += LapSplit(
                    lapNumber = laps.size + 1,
                    durationMs = elapsedMs - lapStartMs,
                    maxSpeedKmh = lapMaxSpeed,
                    peakLatG = lapMaxLat,
                    peakBrakeG = lapMaxBrake
                )
                lapStartMs = elapsedMs
                leftStartZone = false
                lapMaxSpeed = 0f
                lapMaxLat = 0f
                lapMaxBrake = 0f
            }
        }

        val bestLap = laps.minOfOrNull { it.durationMs }
        return TrackSession(
            header = header,
            samples = samples,
            laps = laps,
            bestLapMs = bestLap,
            avgSpeedKmh = sumSpeed / samples.size,
            maxSpeedKmh = maxSpeed,
            maxLatG = maxLatG,
            maxAccelG = maxAccelG,
            maxBrakeG = maxBrakeG,
            totalDistanceMeters = totalDist.toFloat()
        )
    }

    fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * atan2(sqrt(a), sqrt(1 - a))
    }

    fun formatLapTime(durationMs: Long): String {
        val minutes = durationMs / 60_000
        val seconds = (durationMs % 60_000) / 1_000
        val millis = durationMs % 1_000
        return if (minutes > 0) {
            "%d:%02d.%03d".format(minutes, seconds, millis)
        } else {
            "%02d.%03d s".format(seconds, millis)
        }
    }
}
