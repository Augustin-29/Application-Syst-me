package com.example.trackpulse.data.repository

import android.content.Context
import com.example.trackpulse.data.location.HostPhoneLocation
import com.example.trackpulse.data.preferences.AppPreferencesRepository
import com.example.trackpulse.data.transport.HybridTransportManager
import com.example.trackpulse.data.transport.TransportState
import com.example.trackpulse.domain.model.AgpsInjectionLog
import com.example.trackpulse.domain.model.BuzzerMelody
import com.example.trackpulse.domain.model.ConnectionChannel
import com.example.trackpulse.domain.model.DeviceStatus
import com.example.trackpulse.domain.model.EPaperConfig
import com.example.trackpulse.domain.model.EasterEggGame
import com.example.trackpulse.domain.model.EasterEggScore
import com.example.trackpulse.domain.model.FlashSessionHeader
import com.example.trackpulse.domain.model.FlashTransferProgress
import com.example.trackpulse.domain.model.MotionAlert
import com.example.trackpulse.domain.model.MqttSettings
import com.example.trackpulse.domain.model.PositionHistoryPoint
import com.example.trackpulse.domain.model.TelemetrySample
import com.example.trackpulse.domain.model.TrackSession
import com.example.trackpulse.domain.model.TrackSessionAnalyzer
import com.example.trackpulse.domain.model.TrackerMode
import com.example.trackpulse.domain.model.TransportPreference
import com.example.trackpulse.domain.model.VehicleType
import com.example.trackpulse.domain.protocol.ParsedPairingQr
import com.example.trackpulse.domain.protocol.TrackPulseProtocol
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Source unique de vérité de l'application TrackPulse.
 * Gère la persistance DataStore (onboarding / appairage QR), le transport Hybride Auto par défaut,
 * la position temps réel du téléphone hôte, les sessions Flash NOR, les alertes Antivol et l'E-Paper.
 */
class TrackerRepository(context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val preferences = AppPreferencesRepository(context.applicationContext)

    private val _mqttSettings = MutableStateFlow(MqttSettings())
    val mqttSettings: StateFlow<MqttSettings> = _mqttSettings.asStateFlow()

    val transportManager = HybridTransportManager(
        context = context.applicationContext,
        scope = scope,
        settingsProvider = { _mqttSettings.value }
    )

    // Null tant que DataStore n'a pas encore émis la valeur persistée (évite tout flash d'écran)
    private val _onboardingCompleted = MutableStateFlow<Boolean?>(null)
    val onboardingCompleted: StateFlow<Boolean?> = _onboardingCompleted.asStateFlow()

    private val _pairingKey = MutableStateFlow("")
    val pairingKey: StateFlow<String> = _pairingKey.asStateFlow()

    private val _deviceStatus = MutableStateFlow(DeviceStatus())
    val deviceStatus: StateFlow<DeviceStatus> = _deviceStatus.asStateFlow()

    private val _alerts = MutableStateFlow<List<MotionAlert>>(emptyList())
    val alerts: StateFlow<List<MotionAlert>> = _alerts.asStateFlow()

    private val _positionHistory = MutableStateFlow<List<PositionHistoryPoint>>(seedInitialHistory())
    val positionHistory: StateFlow<List<PositionHistoryPoint>> = _positionHistory.asStateFlow()

    private val _flashSessions = MutableStateFlow<List<FlashSessionHeader>>(emptyList())
    val flashSessions: StateFlow<List<FlashSessionHeader>> = _flashSessions.asStateFlow()

    private val _downloadedSessions = MutableStateFlow<Map<Int, TrackSession>>(emptyMap())
    val downloadedSessions: StateFlow<Map<Int, TrackSession>> = _downloadedSessions.asStateFlow()

    private val _selectedSessionId = MutableStateFlow<Int?>(null)
    val selectedSessionId: StateFlow<Int?> = _selectedSessionId.asStateFlow()

    private val _transferProgress = MutableStateFlow(FlashTransferProgress())
    val transferProgress: StateFlow<FlashTransferProgress> = _transferProgress.asStateFlow()

    private val _liveSample = MutableStateFlow<TelemetrySample?>(null)
    val liveSample: StateFlow<TelemetrySample?> = _liveSample.asStateFlow()

    private val _epaperConfig = MutableStateFlow(EPaperConfig())
    val epaperConfig: StateFlow<EPaperConfig> = _epaperConfig.asStateFlow()

    private val _easterEggScores = MutableStateFlow(
        mapOf(
            EasterEggGame.PULSE_RUNNER to EasterEggScore(EasterEggGame.PULSE_RUNNER, 420, 1280, 14, 890),
            EasterEggGame.TRACK_DODGE to EasterEggScore(EasterEggGame.TRACK_DODGE, 310, 940, 9, 540)
        )
    )
    val easterEggScores: StateFlow<Map<EasterEggGame, EasterEggScore>> = _easterEggScores.asStateFlow()

    private val _activeBuzzerMelody = MutableStateFlow(BuzzerMelody.STOP)
    val activeBuzzerMelody: StateFlow<BuzzerMelody> = _activeBuzzerMelody.asStateFlow()

    private val _userBannerMessage = MutableStateFlow<String?>(null)
    val userBannerMessage: StateFlow<String?> = _userBannerMessage.asStateFlow()

    private val _isRefreshingFix = MutableStateFlow(false)
    val isRefreshingFix: StateFlow<Boolean> = _isRefreshingFix.asStateFlow()

    val transportPreference: StateFlow<TransportPreference> get() = transportManager.preference
    val activeChannel: StateFlow<ConnectionChannel> get() = transportManager.activeChannel
    val transportState: StateFlow<TransportState> get() = transportManager.transportState
    val transportStatusText: StateFlow<String> get() = transportManager.statusMessage
    val rssiDbm: StateFlow<Int?> get() = transportManager.rssiDbm
    val lastAgpsLog: StateFlow<AgpsInjectionLog?> get() = transportManager.lastAgpsLog
    val hostPhoneLocation: StateFlow<HostPhoneLocation?> get() = transportManager.hostPhoneLocation

    private val sessionListAccumulator = linkedMapOf<Int, FlashSessionHeader>()
    private var pendingChunkDeferred: CompletableDeferred<TrackPulseProtocol.FlashChunkResponse>? = null

    init {
        observeTransport()
        transportManager.startPhoneLocationTracking()
        scope.launch {
            val stored = preferences.configFlow.first()
            _mqttSettings.value = stored.mqttSettings
            _pairingKey.value = stored.pairingKey
            _onboardingCompleted.value = stored.onboardingCompleted

            if (stored.onboardingCompleted) {
                transportManager.setPreferenceAndConnect(stored.transportPreference)
                if (stored.transportPreference == TransportPreference.DEMO_SIMULATOR) {
                    requestFlashSessionList()
                }
            }
        }
    }

    fun onPermissionsGranted() {
        transportManager.startPhoneLocationTracking()
        if (_onboardingCompleted.value == true &&
            transportState.value != TransportState.CONNECTED &&
            transportPreference.value != TransportPreference.DEMO_SIMULATOR
        ) {
            reconnect()
        }
    }

    private fun observeTransport() {
        scope.launch {
            transportManager.incomingFrames.collect { frame ->
                handleIncomingFrame(frame)
            }
        }
        scope.launch {
            transportManager.rssiDbm.collect { rssi ->
                _deviceStatus.update { it.copy(rssiDbm = rssi) }
            }
        }
        scope.launch {
            transportManager.activeChannel.collect { ch ->
                _deviceStatus.update { it.copy(channel = ch) }
            }
        }
        scope.launch {
            transportManager.mqttSessionBytes.collect { extraBytes ->
                _mqttSettings.update {
                    it.copy(estimatedLteBytesUsed = 1_420_000L + extraBytes)
                }
            }
        }
    }

    private fun handleIncomingFrame(frame: TrackPulseProtocol.Frame) {
        when (frame.opcode) {
            TrackPulseProtocol.EVT_STATUS -> {
                val decoded = TrackPulseProtocol.decodeStatusPayload(frame.payload) ?: return
                val ch = transportManager.activeChannel.value
                val merged = decoded.copy(
                    rssiDbm = transportManager.rssiDbm.value,
                    channel = ch
                )
                _deviceStatus.value = merged
                _isRefreshingFix.value = false
                if (merged.hasGpsFix && merged.latitude != 0.0 && merged.longitude != 0.0) {
                    appendHistoryIfMoved(merged)
                }
            }

            TrackPulseProtocol.EVT_MOTION_ALERT -> {
                val alert = TrackPulseProtocol.decodeMotionAlertPayload(frame.payload) ?: return
                _alerts.update { current -> listOf(alert) + current.take(24) }
                _userBannerMessage.value =
                    "Alerte mouvement LIS3DH : %.2f G (%s)".format(alert.peakAccelG, alert.triggeredAxesLabel)
            }

            TrackPulseProtocol.EVT_LIVE_TELEMETRY -> {
                val sample = TrackPulseProtocol.decodeTelemetrySample(frame.payload) ?: return
                _liveSample.value = sample
            }

            TrackPulseProtocol.RSP_FLASH_SESSION -> {
                val entry = TrackPulseProtocol.decodeFlashSessionEntry(frame.payload) ?: return
                if (entry.entryIndex == 0) sessionListAccumulator.clear()
                sessionListAccumulator[entry.header.sessionId] = entry.header
                if (sessionListAccumulator.size >= entry.entryCount || entry.entryIndex == entry.entryCount - 1) {
                    _flashSessions.value = sessionListAccumulator.values.sortedByDescending { it.startUtcSec }
                }
            }

            TrackPulseProtocol.RSP_FLASH_CHUNK -> {
                val chunk = TrackPulseProtocol.decodeFlashChunk(frame.payload) ?: return
                pendingChunkDeferred?.complete(chunk)
            }

            TrackPulseProtocol.EVT_EASTER_EGG_SCORE -> {
                val score = TrackPulseProtocol.decodeEasterEggScore(frame.payload) ?: return
                _easterEggScores.update { it + (score.game to score) }
            }
        }
    }

    private fun appendHistoryIfMoved(status: DeviceStatus) {
        _positionHistory.update { list ->
            val last = list.lastOrNull()
            val moved = if (last == null) {
                true
            } else {
                TrackSessionAnalyzer.haversineMeters(
                    last.latitude, last.longitude, status.latitude, status.longitude
                ) > 8.0
            }
            if (moved) {
                (list + PositionHistoryPoint(
                    timestampSec = System.currentTimeMillis() / 1000L,
                    latitude = status.latitude,
                    longitude = status.longitude,
                    speedKmh = status.speedKmh,
                    channel = status.channel
                )).takeLast(120)
            } else {
                list
            }
        }
    }

    // ---- Onboarding & Appairage QR Code ----

    fun completeOnboardingWithQr(parsed: ParsedPairingQr) {
        val targetPref = if (parsed.isSimulatedDevice) {
            TransportPreference.DEMO_SIMULATOR
        } else {
            TransportPreference.AUTO_HYBRID
        }
        val updatedSettings = _mqttSettings.value.copy(
            deviceId = parsed.mqttDeviceId,
            bleDeviceNameFilter = parsed.bleNameFilter
        )
        _mqttSettings.value = updatedSettings
        _pairingKey.value = parsed.pairingKey
        _onboardingCompleted.value = true

        scope.launch {
            preferences.savePairingCompleted(
                deviceId = parsed.mqttDeviceId,
                bleNameFilter = parsed.bleNameFilter,
                pairingKey = parsed.pairingKey,
                transportPreference = targetPref
            )
            transportManager.setPreferenceAndConnect(targetPref)
            if (parsed.isSimulatedDevice) {
                _userBannerMessage.value = "Boîtier simulé ${parsed.deviceId} appairé (mode test)"
                requestFlashSessionList()
            } else {
                _userBannerMessage.value = "Boîtier ${parsed.deviceId} appairé — Connexion Hybride Auto"
            }
        }
    }

    fun completeOnboardingDirectBluetooth() {
        _onboardingCompleted.value = true
        scope.launch {
            val cur = _mqttSettings.value
            preferences.savePairingCompleted(
                deviceId = cur.deviceId,
                bleNameFilter = cur.bleDeviceNameFilter,
                pairingKey = _pairingKey.value,
                transportPreference = TransportPreference.AUTO_HYBRID
            )
            transportManager.setPreferenceAndConnect(TransportPreference.AUTO_HYBRID)
            _userBannerMessage.value = "Recherche BLE RN4871 (Hybride Auto actif)"
        }
    }

    fun relaunchOnboarding() {
        _onboardingCompleted.value = false
        scope.launch {
            preferences.setOnboardingCompleted(false)
        }
    }

    // ---- Actions publiques pilotées par le ViewModel ----

    fun saveSettingsAndConnect(pref: TransportPreference, settings: MqttSettings) {
        _mqttSettings.value = settings
        scope.launch {
            preferences.saveSettings(pref, settings)
            transportManager.setPreferenceAndConnect(pref)
            if (pref == TransportPreference.DEMO_SIMULATOR) {
                requestFlashSessionList()
            }
        }
    }

    fun reconnect() {
        scope.launch {
            transportManager.connectActiveStrategy()
            if (transportManager.isBulkFlashAllowed) {
                requestFlashSessionList()
            }
        }
    }

    fun toggleSimulated4gFallback(enable4g: Boolean) {
        if (transportPreference.value == TransportPreference.DEMO_SIMULATOR) {
            transportManager.simulatedTransport.setSimulatedChannelMode(enable4g)
        }
    }

    fun triggerAgpsInjection() {
        scope.launch {
            val ok = transportManager.injectAgpsNow()
            _userBannerMessage.value = if (ok) {
                "Trame A-GPS (19 o) injectée dans le boîtier"
            } else {
                "Connexion active requise pour l'injection A-GPS"
            }
        }
    }

    fun setTrackerMode(mode: TrackerMode) {
        scope.launch {
            val cfg = _mqttSettings.value
            val payload = TrackPulseProtocol.encodeSetMode(
                mode = mode,
                rtcWakeSec = cfg.rtcWakeIntervalSec,
                motionThresholdMg = cfg.motionThresholdMg
            )
            transportManager.sendFrame(TrackPulseProtocol.CMD_SET_MODE, payload, requestAck = true)
        }
    }

    fun playBuzzer(melody: BuzzerMelody, repeatCount: Int = 2) {
        _activeBuzzerMelody.value = melody
        scope.launch {
            val payload = TrackPulseProtocol.encodeBuzzer(melody, repeatCount = repeatCount)
            transportManager.sendFrame(TrackPulseProtocol.CMD_BUZZER_PLAY, payload, requestAck = true)
        }
    }

    fun requestOnDemandGpsFix() {
        _isRefreshingFix.value = true
        scope.launch {
            val payload = TrackPulseProtocol.encodeRequestFix(timeoutSec = 45)
            val ok = transportManager.sendFrame(TrackPulseProtocol.CMD_REQUEST_FIX, payload, requestAck = true)
            if (!ok) {
                _isRefreshingFix.value = false
                _userBannerMessage.value = "Impossible d'interroger le boîtier (vérifiez BLE/4G)"
            }
        }
    }

    fun acknowledgeAlert(alertId: Int) {
        _alerts.update { list ->
            list.map { if (it.alertId == alertId) it.copy(acknowledged = true) else it }
        }
        scope.launch {
            transportManager.sendFrame(
                TrackPulseProtocol.CMD_CLEAR_ALERT,
                byteArrayOf(alertId.toByte()),
                requestAck = true
            )
        }
    }

    fun simulateMotionAlert() {
        transportManager.simulatedTransport.triggerSimulatedTheftAlert()
    }

    fun startTrackRecording(rateHz: Int, accelRangeG: Int, vehicleType: VehicleType) {
        scope.launch {
            val payload = TrackPulseProtocol.encodeTrackStart(
                sampleRateHz = rateHz,
                accelRangeG = accelRangeG,
                vehicleType = vehicleType,
                streamLiveOverBle = true
            )
            transportManager.sendFrame(TrackPulseProtocol.CMD_TRACK_START, payload, requestAck = true)
        }
    }

    fun stopTrackRecording() {
        scope.launch {
            transportManager.sendFrame(TrackPulseProtocol.CMD_TRACK_STOP, requestAck = true)
            _liveSample.value = null
        }
    }

    fun requestFlashSessionList() {
        if (!transportManager.isBulkFlashAllowed) {
            _userBannerMessage.value = "Lecture Flash NOR verrouillée en BLE (économie 500 Mo 4G)"
            return
        }
        scope.launch {
            transportManager.sendFrame(TrackPulseProtocol.CMD_FLASH_LIST)
        }
    }

    fun downloadFlashSession(header: FlashSessionHeader) {
        if (!transportManager.isBulkFlashAllowed) {
            _userBannerMessage.value = "Téléchargement circuit autorisé uniquement en BLE"
            return
        }
        if (_transferProgress.value.isTransferring) return

        scope.launch {
            val totalBytes = header.sizeBytes.toInt().coerceAtLeast(16)
            _transferProgress.value = FlashTransferProgress(
                sessionId = header.sessionId,
                bytesReceived = 0,
                totalBytes = totalBytes,
                isTransferring = true,
                statusText = "Transfert BLE Flash NOR #${header.sessionId}…"
            )

            val collected = ArrayList<TelemetrySample>(header.sampleCount)
            var offset = 0
            var finished = false
            var failed = false

            while (!finished && offset < totalBytes + 256) {
                val deferred = CompletableDeferred<TrackPulseProtocol.FlashChunkResponse>()
                pendingChunkDeferred = deferred

                val req = TrackPulseProtocol.encodeFlashRead(
                    sessionId = header.sessionId,
                    offsetBytes = offset,
                    maxLen = 224
                )
                val sent = transportManager.sendFrame(TrackPulseProtocol.CMD_FLASH_READ, req)
                if (!sent) {
                    failed = true
                    break
                }

                val chunk = withTimeoutOrNull(3_000L) { deferred.await() }
                if (chunk == null) {
                    failed = true
                    break
                }

                collected.addAll(chunk.samples)
                offset += chunk.samples.size * 16
                finished = chunk.isLastChunk || chunk.samples.isEmpty()

                _transferProgress.value = FlashTransferProgress(
                    sessionId = header.sessionId,
                    bytesReceived = offset.coerceAtMost(totalBytes),
                    totalBytes = totalBytes,
                    isTransferring = !finished,
                    statusText = "Transfert BLE : ${collected.size}/${header.sampleCount} pts"
                )
            }
            pendingChunkDeferred = null

            if (failed || collected.isEmpty()) {
                _transferProgress.value = FlashTransferProgress(
                    sessionId = header.sessionId,
                    isTransferring = false,
                    statusText = "Échec transfert BLE"
                )
                _userBannerMessage.value = "Transfert Flash interrompu"
            } else {
                val analyzed = TrackSessionAnalyzer.analyze(header, collected)
                _downloadedSessions.update { it + (header.sessionId to analyzed) }
                _selectedSessionId.value = header.sessionId
                _transferProgress.value = FlashTransferProgress(
                    sessionId = header.sessionId,
                    bytesReceived = totalBytes,
                    totalBytes = totalBytes,
                    isTransferring = false,
                    statusText = "Session #${header.sessionId} chargée (${collected.size} pts)"
                )
            }
        }
    }

    fun selectDownloadedSession(sessionId: Int) {
        _selectedSessionId.value = sessionId
    }

    fun eraseFlashSession(sessionId: Int) {
        if (!transportManager.isBulkFlashAllowed) {
            _userBannerMessage.value = "Effacement Flash disponible uniquement en BLE"
            return
        }
        scope.launch {
            val payload = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN)
                .putShort(sessionId.toShort())
                .array()
            transportManager.sendFrame(TrackPulseProtocol.CMD_FLASH_ERASE_SESSION, payload, requestAck = true)
            _downloadedSessions.update { it - sessionId }
            if (_selectedSessionId.value == sessionId) {
                _selectedSessionId.value = _downloadedSessions.value.keys.firstOrNull()
            }
        }
    }

    fun updateEPaperConfig(config: EPaperConfig, pushToDevice: Boolean = true) {
        _epaperConfig.value = config
        if (pushToDevice) {
            scope.launch {
                val payload = TrackPulseProtocol.encodeEPaperConfig(config)
                val ok = transportManager.sendFrame(TrackPulseProtocol.CMD_EPAPER_CONFIG, payload, requestAck = true)
                _userBannerMessage.value = if (ok) {
                    "Configuration E-Paper envoyée (${config.refreshMode.label})"
                } else {
                    "Échec envoi configuration E-Paper"
                }
            }
        }
    }

    fun startEasterEgg(game: EasterEggGame, soundEnabled: Boolean) {
        scope.launch {
            val payload = TrackPulseProtocol.encodeEasterEggControl(
                action = 1,
                game = game,
                soundEnabled = soundEnabled
            )
            transportManager.sendFrame(TrackPulseProtocol.CMD_EASTER_EGG_CTRL, payload, requestAck = true)
        }
    }

    fun stopEasterEgg(game: EasterEggGame) {
        scope.launch {
            val payload = TrackPulseProtocol.encodeEasterEggControl(
                action = 0,
                game = game,
                soundEnabled = false
            )
            transportManager.sendFrame(TrackPulseProtocol.CMD_EASTER_EGG_CTRL, payload, requestAck = true)
        }
    }

    fun syncEasterEggScores(game: EasterEggGame) {
        scope.launch {
            val payload = TrackPulseProtocol.encodeEasterEggControl(
                action = 2,
                game = game,
                soundEnabled = true
            )
            transportManager.sendFrame(TrackPulseProtocol.CMD_EASTER_EGG_CTRL, payload, requestAck = true)
        }
    }

    fun simulateEasterEggMatch(game: EasterEggGame) {
        transportManager.simulatedTransport.simulateEasterEggGamePlayed(game)
    }

    fun clearBannerMessage() {
        _userBannerMessage.value = null
    }

    private fun seedInitialHistory(): List<PositionHistoryPoint> {
        val now = System.currentTimeMillis() / 1000L
        return listOf(
            PositionHistoryPoint(now - 7200, 47.953200, 0.205100, 32f, ConnectionChannel.LTE_MQTT),
            PositionHistoryPoint(now - 5400, 47.954450, 0.206320, 46f, ConnectionChannel.LTE_MQTT),
            PositionHistoryPoint(now - 3600, 47.955310, 0.207180, 28f, ConnectionChannel.LTE_MQTT),
            PositionHistoryPoint(now - 1800, 47.955890, 0.207690, 12f, ConnectionChannel.BLE),
            PositionHistoryPoint(now - 300, 47.956012, 0.207845, 0f, ConnectionChannel.BLE)
        )
    }
}
