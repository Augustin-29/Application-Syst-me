package com.example.trackpulse.domain.protocol

/**
 * Parseur du QR Code compact d'appairage affiché sur l'écran Waveshare 1,54" E-Paper V2 (200×200 px).
 *
 * Format défini dans PROTOCOL.md (§4.5) :
 *   `TP1:<ID_BOITIER>:<CLE_PAIRING>`
 * Exemple matériel : `TP1:8F3A:492015` (15 caractères -> QR Code Version 1 / 21×21 modules).
 * Exemple simulateur : `TP1:SIM1:123456`.
 */
data class ParsedPairingQr(
    val rawValue: String,
    val protocolVersion: Int,
    val deviceId: String,
    val pairingKey: String,
    val bleNameFilter: String,
    val mqttDeviceId: String,
    val isSimulatedDevice: Boolean
) {
    val uplinkTopic: String get() = "tp/$mqttDeviceId/u"
    val downlinkTopic: String get() = "tp/$mqttDeviceId/d"
    val alertTopic: String get() = "tp/$mqttDeviceId/a"
}

object QrPairingParser {
    const val SIMULATOR_QR_CODE = "TP1:SIM1:123456"
    private val DEVICE_ID_REGEX = Regex("^[A-Z0-9]{2,8}$")
    private val PAIRING_KEY_REGEX = Regex("^[A-Z0-9]{4,10}$")

    fun parse(raw: String): ParsedPairingQr? {
        val normalized = raw.trim().uppercase()
        if (normalized.length > 30) return null

        val parts = normalized.split(':')
        if (parts.size != 3) return null
        if (parts[0] != "TP1") return null

        val deviceId = parts[1]
        val pairingKey = parts[2]
        if (!DEVICE_ID_REGEX.matches(deviceId)) return null
        if (!PAIRING_KEY_REGEX.matches(pairingKey)) return null

        val isSimulated = deviceId.startsWith("SIM")
        return ParsedPairingQr(
            rawValue = normalized,
            protocolVersion = 1,
            deviceId = deviceId,
            pairingKey = pairingKey,
            bleNameFilter = "TrackPulse-$deviceId",
            mqttDeviceId = deviceId.lowercase(),
            isSimulatedDevice = isSimulated
        )
    }
}
