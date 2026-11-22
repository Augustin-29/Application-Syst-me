package com.example.trackpulse.data.transport

import com.example.trackpulse.domain.model.BuzzerMelody
import com.example.trackpulse.domain.model.ConnectionChannel
import com.example.trackpulse.domain.model.DeviceStatus
import com.example.trackpulse.domain.model.EasterEggGame
import com.example.trackpulse.domain.model.EasterEggScore
import com.example.trackpulse.domain.model.FlashSessionHeader
import com.example.trackpulse.domain.model.MotionAlert
import com.example.trackpulse.domain.model.TelemetrySample
import com.example.trackpulse.domain.model.TrackerMode
import com.example.trackpulse.domain.model.VehicleType
import com.example.trackpulse.domain.protocol.TrackPulseProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/**
 * Simulateur fidèle du boîtier STM32L433CCT6 (RN4871 + SIM7080G + W25Q64JV + LIS3DH + Waveshare 1,54").
 * Traite exactement les trames binaires de PROTOCOL.md pour valider l'application avant le flash du firmware C.
 */
class SimulatedTrackerTransport(
    private val scope: CoroutineScope
) : TrackerTransport {

    override val channel: ConnectionChannel = ConnectionChannel.SIMULATED

    private val _state = MutableStateFlow(TransportState.DISCONNECTED)
    override val state: StateFlow<TransportState> = _state.asStateFlow()

    private val _rssiDbm = MutableStateFlow<Int?>(null)
    override val rssiDbm: StateFlow<Int?> = _rssiDbm.asStateFlow()

    private val _statusMessage = MutableStateFlow("Simulateur STM32 prêt")
    override val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _incomingFrames = MutableSharedFlow<TrackPulseProtocol.Frame>(extraBufferCapacity = 128)
    override val incomingFrames: SharedFlow<TrackPulseProtocol.Frame> = _incomingFrames.asSharedFlow()

    private var heartbeatJob: Job? = null
    private var recordingJob: Job? = null
    private var simulatedOutOfBleRange = false

    private var currentStatus = DeviceStatus(
        mode = TrackerMode.IDLE_STANDBY,
        batteryMv = 4035,
        batteryPct = 82,
        isCharging = false,
        hasUsbFault = false,
        hasGpsFix = true,
        isRecording = false,
        isBleConnected = true,
        isLteAttached = true,
        isAgpsValid = false,
        flashUsedBytes = 0L,
        gpsSats = 11,
        latitude = 47.956012,
        longitude = 0.207845,
        speedKmh = 0f,
        uptimeSec = 3_420L,
        activeEPaperTemplateId = 0x01,
        rssiDbm = -58,
        channel = ConnectionChannel.SIMULATED
    )

    private val storedSessions = linkedMapOf<Int, Pair<FlashSessionHeader, List<TelemetrySample>>>()
    private val activeRecordingSamples = mutableListOf<TelemetrySample>()
    private var activeRecordingRateHz = 10
    private var activeRecordingVehicle = VehicleType.KART
    private var activeRecordingStartUtc = 0L
    private var nextSessionId = 104
    private var alertCounter = 1

    private val scores = mutableMapOf(
        EasterEggGame.PULSE_RUNNER to EasterEggScore(
            game = EasterEggGame.PULSE_RUNNER,
            lastScore = 420,
            highScore = 1280,
            gamesPlayed = 14,
            playTimeSec = 890
        ),
        EasterEggGame.TRACK_DODGE to EasterEggScore(
            game = EasterEggGame.TRACK_DODGE,
            lastScore = 310,
            highScore = 940,
            gamesPlayed = 9,
            playTimeSec = 540
        )
    )

    init {
        seedInitialFlashSessions()
        recomputeFlashUsed()
    }

    private fun seedInitialFlashSessions() {
        val nowSec = System.currentTimeMillis() / 1000L
        val session101 = generateCircuitSamples(
            centerLat = 47.956012,
            centerLon = 0.207845,
            lapCount = 3,
            baseSpeedKmh = 74f,
            maxBoostKmh = 24f,
            rateHz = 5,
            phaseJitter = 0.0
        )
        addStoredSession(
            id = 101,
            startUtcSec = nowSec - 86_400L,
            samples = session101,
            rateHz = 5,
            vehicle = VehicleType.KART
        )

        val session102 = generateCircuitSamples(
            centerLat = 47.956012,
            centerLon = 0.207845,
            lapCount = 2,
            baseSpeedKmh = 98f,
            maxBoostKmh = 38f,
            rateHz = 5,
            phaseJitter = 0.25
        )
        addStoredSession(
            id = 102,
            startUtcSec = nowSec - 7_200L,
            samples = session102,
            rateHz = 5,
            vehicle = VehicleType.MOTO
        )
    }

    private fun addStoredSession(
        id: Int,
        startUtcSec: Long,
        samples: List<TelemetrySample>,
        rateHz: Int,
        vehicle: VehicleType
    ) {
        val durationMs = samples.sumOf { it.dtMs.toLong() }
        val maxSpeed = samples.maxOfOrNull { it.speedKmh } ?: 0f
        val peakG = samples.maxOfOrNull { it.combinedG } ?: 0f
        val header = FlashSessionHeader(
            sessionId = id,
            startUtcSec = startUtcSec,
            durationMs = durationMs,
            sampleCount = samples.size,
            maxSpeedKmh = maxSpeed,
            peakG = peakG,
            sampleRateHz = rateHz,
            vehicleType = vehicle
        )
        storedSessions[id] = header to samples
    }

    private fun recomputeFlashUsed() {
        val sessionsBytes = storedSessions.values.sumOf { it.second.size.toLong() * 16L }
        val activeBytes = activeRecordingSamples.size.toLong() * 16L
        // Secteurs 0..15 réservés (64 Ko) + logs
        currentStatus = currentStatus.copy(flashUsedBytes = 65_536L + sessionsBytes + activeBytes)
    }

    override suspend fun connect(): Boolean {
        _state.value = TransportState.CONNECTING
        _statusMessage.value = "Initialisation simulateur STM32L433…"
        delay(220L)
        _state.value = TransportState.CONNECTED
        _rssiDbm.value = -58
        _statusMessage.value = "Simulateur STM32 connecté (BLE virtuel)"
        startTelemetryLoop()
        emitStatus()
        return true
    }

    override suspend fun disconnect() {
        heartbeatJob?.cancel()
        recordingJob?.cancel()
        _state.value = TransportState.DISCONNECTED
        _rssiDbm.value = null
        _statusMessage.value = "Simulateur arrêté"
    }

    fun setSimulatedChannelMode(simulate4gFallback: Boolean) {
        simulatedOutOfBleRange = simulate4gFallback
        if (simulate4gFallback) {
            _rssiDbm.value = null
            currentStatus = currentStatus.copy(
                isBleConnected = false,
                isLteAttached = true,
                channel = ConnectionChannel.LTE_MQTT
            )
            _statusMessage.value = "Simulateur : hors portée BLE → bascule 4G/MQTT"
        } else {
            _rssiDbm.value = -57
            currentStatus = currentStatus.copy(
                isBleConnected = true,
                channel = ConnectionChannel.BLE
            )
            _statusMessage.value = "Simulateur : BLE RN4871 prioritaire actif"
        }
        scope.launch { emitStatus() }
    }

    fun triggerSimulatedTheftAlert() {
        val alert = MotionAlert(
            alertId = alertCounter++,
            timestampSec = System.currentTimeMillis() / 1000L,
            peakAccelG = (1.35f + Random.nextFloat() * 1.1f),
            axisMask = 0x0B, // X + Y + inclinaison
            latitude = currentStatus.latitude + Random.nextDouble(-0.0003, 0.0003),
            longitude = currentStatus.longitude + Random.nextDouble(-0.0003, 0.0003),
            batteryPct = currentStatus.batteryPct,
            speedKmh = 14.5f
        )
        scope.launch {
            val payload = TrackPulseProtocol.encodeMotionAlertPayload(alert)
            emitRawFrame(TrackPulseProtocol.EVT_MOTION_ALERT, payload)
        }
    }

    fun simulateEasterEggGamePlayed(game: EasterEggGame) {
        val current = scores[game] ?: EasterEggScore(game)
        val newScore = (250..1850).random()
        val updated = current.copy(
            lastScore = newScore,
            highScore = maxOf(current.highScore, newScore),
            gamesPlayed = current.gamesPlayed + 1,
            playTimeSec = current.playTimeSec + (25..95).random()
        )
        scores[game] = updated
        scope.launch {
            emitRawFrame(
                TrackPulseProtocol.EVT_EASTER_EGG_SCORE,
                TrackPulseProtocol.encodeEasterEggScore(updated)
            )
        }
    }

    override suspend fun sendFrame(opcode: Byte, payload: ByteArray, requestAck: Boolean): Boolean {
        if (_state.value != TransportState.CONNECTED) return false
        // Passage par encodage/décodage réel afin de valider le protocole binaire à chaque appel
        val encoded = TrackPulseProtocol.encodeFrame(opcode, payload, requestAck = requestAck)
        val frame = TrackPulseProtocol.decodeFrame(encoded) ?: return false

        scope.launch(Dispatchers.Default) {
            handleFrame(frame)
        }
        return true
    }

    private suspend fun handleFrame(frame: TrackPulseProtocol.Frame) {
        when (frame.opcode) {
            TrackPulseProtocol.CMD_PING, TrackPulseProtocol.CMD_GET_STATUS -> {
                emitStatus()
            }

            TrackPulseProtocol.CMD_SET_MODE -> {
                if (frame.payload.isNotEmpty()) {
                    val mode = TrackerMode.fromCode(frame.payload[0])
                    if (mode != TrackerMode.TRACK_RECORDING && currentStatus.isRecording) {
                        stopSimulatedRecording()
                    }
                    val tpl = when (mode) {
                        TrackerMode.ANTITHEFT_SLEEP -> 0x00.toByte()
                        TrackerMode.TRACK_RECORDING -> 0x01.toByte()
                        TrackerMode.EASTER_EGG -> 0x04.toByte()
                        else -> currentStatus.activeEPaperTemplateId
                    }
                    currentStatus = currentStatus.copy(
                        mode = mode,
                        activeEPaperTemplateId = tpl
                    )
                    emitAck(frame.opcode, frame.seq, 0)
                    emitStatus()
                }
            }

            TrackPulseProtocol.CMD_INJECT_AGPS -> {
                if (frame.payload.size >= 19) {
                    val buf = ByteBuffer.wrap(frame.payload).order(ByteOrder.LITTLE_ENDIAN)
                    buf.int // utc sec
                    buf.short // millis
                    val lat = buf.int / 1e7
                    val lon = buf.int / 1e7
                    val flags = frame.payload[18].toInt()
                    currentStatus = currentStatus.copy(
                        isAgpsValid = true,
                        hasGpsFix = true,
                        gpsSats = 12,
                        latitude = if ((flags and 0x01) != 0 && lat != 0.0) lat else currentStatus.latitude,
                        longitude = if ((flags and 0x01) != 0 && lon != 0.0) lon else currentStatus.longitude
                    )
                    emitAck(frame.opcode, frame.seq, 0)
                    emitStatus()
                }
            }

            TrackPulseProtocol.CMD_BUZZER_PLAY -> {
                if (frame.payload.isNotEmpty()) {
                    val melody = BuzzerMelody.fromCode(frame.payload[0])
                    if (melody != BuzzerMelody.STOP && currentStatus.mode != TrackerMode.PROXIMITY_FIND) {
                        currentStatus = currentStatus.copy(mode = TrackerMode.PROXIMITY_FIND)
                    }
                    emitAck(frame.opcode, frame.seq, 0)
                    emitStatus()
                }
            }

            TrackPulseProtocol.CMD_REQUEST_FIX -> {
                delay(450L)
                // Légère dérive GPS réaliste lors d'un fix à la demande via SIM7080G
                currentStatus = currentStatus.copy(
                    hasGpsFix = true,
                    gpsSats = (8..13).random(),
                    latitude = currentStatus.latitude + Random.nextDouble(-0.00018, 0.00018),
                    longitude = currentStatus.longitude + Random.nextDouble(-0.00018, 0.00018),
                    speedKmh = if (currentStatus.mode == TrackerMode.ANTITHEFT_SLEEP) 0f else currentStatus.speedKmh
                )
                emitAck(frame.opcode, frame.seq, 0)
                emitStatus()
            }

            TrackPulseProtocol.CMD_CLEAR_ALERT -> {
                emitAck(frame.opcode, frame.seq, 0)
            }

            TrackPulseProtocol.CMD_TRACK_START -> {
                activeRecordingRateHz = frame.payload.getOrNull(0)?.toInt()?.coerceIn(1, 50) ?: 10
                activeRecordingVehicle = VehicleType.fromCode(frame.payload.getOrNull(2) ?: 0)
                startSimulatedRecording()
                emitAck(frame.opcode, frame.seq, 0)
                emitStatus()
            }

            TrackPulseProtocol.CMD_TRACK_STOP -> {
                stopSimulatedRecording()
                emitAck(frame.opcode, frame.seq, 0)
                emitStatus()
                emitSessionList()
            }

            TrackPulseProtocol.CMD_FLASH_LIST -> {
                emitSessionList()
            }

            TrackPulseProtocol.CMD_FLASH_READ -> {
                if (frame.payload.size >= 8) {
                    val buf = ByteBuffer.wrap(frame.payload).order(ByteOrder.LITTLE_ENDIAN)
                    val sessionId = buf.short.toInt() and 0xFFFF
                    val offsetBytes = buf.int
                    val maxLen = (buf.short.toInt() and 0xFFFF).coerceIn(16, 224)
                    val pair = storedSessions[sessionId]
                    if (pair != null) {
                        val allSamples = pair.second
                        val startIdx = (offsetBytes / 16).coerceIn(0, allSamples.size)
                        val count = (maxLen / 16).coerceAtMost(allSamples.size - startIdx).coerceAtMost(14)
                        val slice = if (count > 0) allSamples.subList(startIdx, startIdx + count) else emptyList()
                        val isLast = (startIdx + count) >= allSamples.size
                        delay(18L) // Débit réaliste RN4871 ~10 Ko/s
                        val chunkPayload = TrackPulseProtocol.encodeFlashChunk(
                            TrackPulseProtocol.FlashChunkResponse(
                                sessionId = sessionId,
                                offsetBytes = offsetBytes,
                                isLastChunk = isLast,
                                samples = slice
                            )
                        )
                        emitRawFrame(TrackPulseProtocol.RSP_FLASH_CHUNK, chunkPayload)
                    }
                }
            }

            TrackPulseProtocol.CMD_FLASH_ERASE_SESSION -> {
                if (frame.payload.size >= 2) {
                    val id = ByteBuffer.wrap(frame.payload).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
                    storedSessions.remove(id)
                    recomputeFlashUsed()
                    emitAck(frame.opcode, frame.seq, 0)
                    emitStatus()
                    emitSessionList()
                }
            }

            TrackPulseProtocol.CMD_EPAPER_CONFIG -> {
                if (frame.payload.isNotEmpty()) {
                    currentStatus = currentStatus.copy(activeEPaperTemplateId = frame.payload[0])
                    emitAck(frame.opcode, frame.seq, 0)
                    emitStatus()
                }
            }

            TrackPulseProtocol.CMD_EPAPER_BITMAP -> {
                emitAck(frame.opcode, frame.seq, 0)
            }

            TrackPulseProtocol.CMD_EASTER_EGG_CTRL -> {
                if (frame.payload.size >= 2) {
                    val action = frame.payload[0].toInt() and 0xFF
                    val game = EasterEggGame.fromCode(frame.payload[1])
                    when (action) {
                        0 -> currentStatus = currentStatus.copy(mode = TrackerMode.IDLE_STANDBY)
                        1 -> {
                            currentStatus = currentStatus.copy(
                                mode = TrackerMode.EASTER_EGG,
                                activeEPaperTemplateId = 0x04
                            )
                        }
                        3 -> {
                            scores[game] = EasterEggScore(game = game)
                        }
                    }
                    emitAck(frame.opcode, frame.seq, 0)
                    emitStatus()
                    scores.values.forEach { score ->
                        emitRawFrame(
                            TrackPulseProtocol.EVT_EASTER_EGG_SCORE,
                            TrackPulseProtocol.encodeEasterEggScore(score)
                        )
                    }
                }
            }
        }
    }

    private fun startSimulatedRecording() {
        recordingJob?.cancel()
        activeRecordingSamples.clear()
        activeRecordingStartUtc = System.currentTimeMillis() / 1000L
        currentStatus = currentStatus.copy(
            mode = TrackerMode.TRACK_RECORDING,
            isRecording = true,
            activeEPaperTemplateId = 0x01
        )
        val trackLoop = generateCircuitSamples(
            centerLat = currentStatus.latitude,
            centerLon = currentStatus.longitude,
            lapCount = 3,
            baseSpeedKmh = 78f,
            maxBoostKmh = 26f,
            rateHz = 5,
            phaseJitter = 0.08
        )
        recordingJob = scope.launch(Dispatchers.Default) {
            var idx = 0
            while (isActive && currentStatus.isRecording) {
                val sample = trackLoop[idx % trackLoop.size]
                activeRecordingSamples += sample
                recomputeFlashUsed()
                currentStatus = currentStatus.copy(
                    latitude = sample.latitude,
                    longitude = sample.longitude,
                    speedKmh = sample.speedKmh
                )
                emitRawFrame(
                    TrackPulseProtocol.EVT_LIVE_TELEMETRY,
                    TrackPulseProtocol.encodeTelemetrySample(sample)
                )
                idx++
                delay(200L)
            }
        }
    }

    private fun stopSimulatedRecording() {
        recordingJob?.cancel()
        recordingJob = null
        if (activeRecordingSamples.size >= 15) {
            val id = nextSessionId++
            addStoredSession(
                id = id,
                startUtcSec = activeRecordingStartUtc,
                samples = activeRecordingSamples.toList(),
                rateHz = activeRecordingRateHz,
                vehicle = activeRecordingVehicle
            )
        }
        activeRecordingSamples.clear()
        currentStatus = currentStatus.copy(
            mode = TrackerMode.IDLE_STANDBY,
            isRecording = false,
            speedKmh = 0f
        )
        recomputeFlashUsed()
    }

    private suspend fun emitSessionList() {
        val entries = storedSessions.values.toList()
        entries.forEachIndexed { idx, (header, _) ->
            val payload = TrackPulseProtocol.encodeFlashSessionEntry(
                TrackPulseProtocol.FlashSessionListEntry(
                    entryIndex = idx,
                    entryCount = entries.size,
                    header = header
                )
            )
            emitRawFrame(TrackPulseProtocol.RSP_FLASH_SESSION, payload)
        }
    }

    private fun startTelemetryLoop() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch(Dispatchers.Default) {
            var phase = 0.0
            while (isActive && _state.value == TransportState.CONNECTED) {
                phase += 0.35
                if (!simulatedOutOfBleRange) {
                    val base = if (currentStatus.mode == TrackerMode.PROXIMITY_FIND) -52 else -61
                    val wave = (sin(phase) * 9.0 + Random.nextInt(-2, 3)).toInt()
                    _rssiDbm.value = (base + wave).coerceIn(-94, -38)
                }
                currentStatus = currentStatus.copy(
                    uptimeSec = currentStatus.uptimeSec + 1L,
                    rssiDbm = _rssiDbm.value
                )
                if ((phase * 10).toInt() % 10 == 0) {
                    emitStatus()
                }
                delay(500L)
            }
        }
    }

    private suspend fun emitAck(origOpcode: Byte, origSeq: Int, status: Int) {
        emitRawFrame(
            TrackPulseProtocol.RSP_ACK,
            byteArrayOf(origOpcode, origSeq.toByte(), status.toByte())
        )
    }

    private suspend fun emitStatus() {
        emitRawFrame(
            TrackPulseProtocol.EVT_STATUS,
            TrackPulseProtocol.encodeStatusPayload(currentStatus)
        )
    }

    private suspend fun emitRawFrame(opcode: Byte, payload: ByteArray) {
        val encoded = TrackPulseProtocol.encodeFrame(
            opcode = opcode,
            payload = payload,
            is4gChannel = simulatedOutOfBleRange
        )
        val decoded = TrackPulseProtocol.decodeFrame(encoded) ?: return
        _incomingFrames.emit(decoded)
    }

    /**
     * Génère une trajectoire circuit réaliste (forme haricot/chicane style karting Le Mans)
     * avec vitesse corrélée à la courbure, accélérations longitudinales et latérales réalistes.
     */
    private fun generateCircuitSamples(
        centerLat: Double,
        centerLon: Double,
        lapCount: Int,
        baseSpeedKmh: Float,
        maxBoostKmh: Float,
        rateHz: Int,
        phaseJitter: Double
    ): List<TelemetrySample> {
        val dtMs = (1000 / rateHz).coerceAtLeast(20)
        val pointsPerLap = 115
        val totalPoints = pointsPerLap * lapCount
        val out = ArrayList<TelemetrySample>(totalPoints)

        // Rayon approximatif : ~170 m x 110 m (piste karting ~950 m au tour)
        val latScale = 0.00115
        val lonScale = 0.00235

        var prevSpeed = baseSpeedKmh
        for (i in 0 until totalPoints) {
            val lapIdx = i / pointsPerLap
            val t = (i % pointsPerLap).toDouble() / pointsPerLap.toDouble()
            val theta = t * 2.0 * PI

            // Courbe fermée complexe (ligne droite + grande courbe + épingle + chicane S)
            val rMod = 1.0 + 0.24 * cos(2.0 * theta + phaseJitter) - 0.14 * sin(3.0 * theta)
            val lat = centerLat + latScale * rMod * sin(theta) + 0.00018 * sin(2.0 * theta)
            val lon = centerLon + lonScale * rMod * cos(theta) + 0.00025 * cos(3.0 * theta)

            // Profil de courbure -> vitesse réaliste (freinage avant épingle, pleine charge ligne droite)
            val cornerIntensity = abs(sin(2.0 * theta + 0.4)) * 0.65 + abs(cos(3.0 * theta)) * 0.35
            val lapImprovement = lapIdx * 1.8f
            val speedKmh = (baseSpeedKmh + maxBoostKmh * (1.0 - cornerIntensity) + lapImprovement)
                .toFloat()
                .coerceIn(28f, 165f)

            val deltaVms = (speedKmh - prevSpeed) / 3.6f
            val dtSec = dtMs / 1000f
            val rawAxG = (deltaVms / dtSec) / 9.81f
            val axG = (rawAxG * 2.4f).coerceIn(-1.65f, 1.15f)

            val turnSign = if (cos(2.0 * theta) >= 0) 1f else -1f
            val ayG = (turnSign * (cornerIntensity.toFloat() * 1.75f + 0.15f) *
                (speedKmh / (baseSpeedKmh + maxBoostKmh))).coerceIn(-2.1f, 2.1f)

            prevSpeed = speedKmh
            out += TelemetrySample(
                dtMs = dtMs,
                latitude = lat,
                longitude = lon,
                speedKmh = speedKmh,
                axG = axG,
                ayG = ayG
            )
        }
        return out
    }
}
