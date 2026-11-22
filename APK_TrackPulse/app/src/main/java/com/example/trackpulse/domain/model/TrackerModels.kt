package com.example.trackpulse.domain.model

enum class TrackerMode(val code: Byte, val label: String, val shortLabel: String, val description: String) {
    ANTITHEFT_SLEEP(0x00, "Antivol (Stop 2)", "Antivol", "Veille profonde ~16,5 µA, réveil LIS3DH & RTC"),
    TRACK_RECORDING(0x01, "Acquisition Circuit", "Circuit", "Logs GNSS + LIS3DH haute fréquence en Flash NOR"),
    PROXIMITY_FIND(0x02, "Localisation Proche", "Proximité", "BLE actif haute fréquence + guidage Buzzer"),
    EASTER_EGG(0x03, "Mini-Jeu E-Paper", "Easter Egg", "Jeu embarqué sur Waveshare 1,54\" + BP1/BP2"),
    IDLE_STANDBY(0x04, "Veille Connectée", "Standby", "Boîtier prêt en attente de commandes BLE");

    companion object {
        fun fromCode(code: Byte): TrackerMode = entries.firstOrNull { it.code == code } ?: IDLE_STANDBY
    }
}

enum class ConnectionChannel(val label: String) {
    BLE("BLE (RN4871)"),
    LTE_MQTT("4G LTE / MQTT"),
    SIMULATED("Simulateur STM32"),
    DISCONNECTED("Hors ligne")
}

enum class TransportPreference(val label: String) {
    AUTO_HYBRID("Hybride Auto (BLE → 4G)"),
    FORCE_BLE("BLE Uniquement"),
    FORCE_MQTT("4G MQTT Uniquement"),
    DEMO_SIMULATOR("Simulateur Matériel")
}

enum class BuzzerMelody(
    val code: Byte,
    val label: String,
    val subtitle: String
) {
    STOP(0x00, "Arrêter", "Coupe TIM2_CH1 (CCR1 = 0)"),
    PING_2730HZ(0x01, "Bip Résonance", "2 730 Hz max dB (CMT-8530S)"),
    STARTUP_JINGLE(0x02, "Jingle Pulse", "Do6 → Mi6 → Sol6 → Do7"),
    SIREN_ALERT(0x03, "Sirène Antivol", "Balayage aigu haute visibilité"),
    SOS_MORSE(0x04, "Signal S.O.S.", "Séquence Morse 2 730 Hz"),
    VICTORY(0x05, "Fanfare Tour", "Mélodie meilleur temps / record");

    companion object {
        fun fromCode(code: Byte): BuzzerMelody = entries.firstOrNull { it.code == code } ?: PING_2730HZ
    }
}

data class DeviceStatus(
    val mode: TrackerMode = TrackerMode.IDLE_STANDBY,
    val batteryMv: Int = 3980,
    val batteryPct: Int = 76,
    val isCharging: Boolean = false,
    val hasUsbFault: Boolean = false,
    val hasGpsFix: Boolean = true,
    val isRecording: Boolean = false,
    val isBleConnected: Boolean = false,
    val isLteAttached: Boolean = true,
    val isAgpsValid: Boolean = false,
    val flashUsedBytes: Long = 1_642_496L,
    val flashCapacityBytes: Long = 8_388_608L, // Winbond W25Q64JV = 8 Mo exacts
    val gpsSats: Int = 9,
    val latitude: Double = 47.956012,
    val longitude: Double = 0.207845,
    val speedKmh: Float = 0f,
    val uptimeSec: Long = 14_320L,
    val activeEPaperTemplateId: Byte = 0x00,
    val rssiDbm: Int? = null,
    val channel: ConnectionChannel = ConnectionChannel.DISCONNECTED,
    val lastUpdateMs: Long = System.currentTimeMillis()
) {
    val flashUsageRatio: Float
        get() = (flashUsedBytes.toFloat() / flashCapacityBytes.toFloat()).coerceIn(0f, 1f)

    val flashUsedMb: Float
        get() = flashUsedBytes / (1024f * 1024f)

    val flashTotalMb: Float
        get() = flashCapacityBytes / (1024f * 1024f)

    /** Estimation intuitive de distance depuis le RSSI BLE (-45 dBm = très proche, -95 dBm = loin). */
    val proximityStrength: Float
        get() {
            val rssi = rssiDbm ?: return 0f
            return ((rssi + 100f) / 55f).coerceIn(0f, 1f)
        }

    val estimatedDistanceMeters: Float?
        get() {
            val rssi = rssiDbm ?: return null
            // Modèle log-distance path loss simple (TxPower @ 1m ≈ -59 dBm, n = 2.4)
            val ratio = (-59.0 - rssi) / (10.0 * 2.4)
            return Math.pow(10.0, ratio).toFloat().coerceIn(0.2f, 50f)
        }
}

data class AgpsPayload(
    val utcEpochSec: Long,
    val utcMillis: Int,
    val latitude: Double,
    val longitude: Double,
    val altitudeM: Float,
    val accuracyM: Int,
    val hasValidPosition: Boolean = true,
    val hasValidTime: Boolean = true
)

data class MotionAlert(
    val alertId: Int,
    val timestampSec: Long,
    val peakAccelG: Float,
    val axisMask: Int,
    val latitude: Double,
    val longitude: Double,
    val batteryPct: Int,
    val speedKmh: Float,
    val acknowledged: Boolean = false
) {
    val triggeredAxesLabel: String
        get() = buildList {
            if (axisMask and 0x01 != 0) add("X")
            if (axisMask and 0x02 != 0) add("Y")
            if (axisMask and 0x04 != 0) add("Z")
            if (axisMask and 0x08 != 0) add("Inclinaison")
        }.ifEmpty { listOf("Mouvement") }.joinToString(" / ")
}

data class PositionHistoryPoint(
    val timestampSec: Long,
    val latitude: Double,
    val longitude: Double,
    val speedKmh: Float,
    val channel: ConnectionChannel
)

data class MqttSettings(
    val brokerUri: String = "tcp://broker.hivemq.com:1883",
    val deviceId: String = "tp01",
    val username: String = "",
    val password: String = "",
    val bleDeviceNameFilter: String = "TrackPulse",
    val rtcWakeIntervalSec: Int = 3600,
    val motionThresholdMg: Int = 160,
    val estimatedLteBytesUsed: Long = 1_420_000L, // ~1,35 Mo sur 500 Mo
    val monthlyQuotaBytes: Long = 500L * 1024L * 1024L
) {
    val uplinkTopic: String get() = "tp/$deviceId/u"
    val downlinkTopic: String get() = "tp/$deviceId/d"
    val alertTopic: String get() = "tp/$deviceId/a"
}

data class AgpsInjectionLog(
    val timestampMs: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracyM: Int,
    val success: Boolean,
    val message: String
)
