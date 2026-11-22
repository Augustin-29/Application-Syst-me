package com.example.trackpulse.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.trackpulse.data.location.HostPhoneLocation
import com.example.trackpulse.data.repository.TrackerRepository
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
import com.example.trackpulse.domain.model.TrackerMode
import com.example.trackpulse.domain.model.TransportPreference
import com.example.trackpulse.domain.model.VehicleType
import com.example.trackpulse.domain.protocol.ParsedPairingQr
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class TrackPulseUiState(
    val onboardingCompleted: Boolean? = null,
    val pairingKey: String = "",
    val status: DeviceStatus = DeviceStatus(),
    val hostPhoneLocation: HostPhoneLocation? = null,
    val transportPreference: TransportPreference = TransportPreference.AUTO_HYBRID,
    val transportState: TransportState = TransportState.DISCONNECTED,
    val transportStatusText: String = "",
    val activeChannel: ConnectionChannel = ConnectionChannel.DISCONNECTED,
    val rssiDbm: Int? = null,
    val isBulkFlashAllowed: Boolean = false,
    val lastAgpsLog: AgpsInjectionLog? = null,
    val activeBuzzerMelody: BuzzerMelody = BuzzerMelody.STOP,
    val alerts: List<MotionAlert> = emptyList(),
    val positionHistory: List<PositionHistoryPoint> = emptyList(),
    val isRefreshingFix: Boolean = false,
    val flashSessions: List<FlashSessionHeader> = emptyList(),
    val downloadedSessions: Map<Int, TrackSession> = emptyMap(),
    val selectedSession: TrackSession? = null,
    val transferProgress: FlashTransferProgress = FlashTransferProgress(),
    val liveSample: TelemetrySample? = null,
    val epaperConfig: EPaperConfig = EPaperConfig(),
    val easterEggScores: Map<EasterEggGame, EasterEggScore> = emptyMap(),
    val mqttSettings: MqttSettings = MqttSettings(),
    val bannerMessage: String? = null
)

class TrackPulseViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = TrackerRepository(application)

    private val connectionBundle = combine(
        repository.transportPreference,
        repository.transportState,
        repository.transportStatusText,
        repository.activeChannel,
        repository.rssiDbm
    ) { pref, st, txt, ch, rssi ->
        ConnectionBundle(pref, st, txt, ch, rssi)
    }

    private val onboardingAndLocationBundle = combine(
        repository.onboardingCompleted,
        repository.pairingKey,
        repository.hostPhoneLocation
    ) { onboarded, key, phoneLoc ->
        OnboardingLocationBundle(onboarded, key, phoneLoc)
    }

    private val hardwareBundle = combine(
        repository.deviceStatus,
        repository.lastAgpsLog,
        repository.activeBuzzerMelody,
        repository.mqttSettings,
        repository.userBannerMessage
    ) { status, agps, buzzer, mqtt, banner ->
        HardwareBundle(status, agps, buzzer, mqtt, banner)
    }

    private val antiTheftBundle = combine(
        repository.alerts,
        repository.positionHistory,
        repository.isRefreshingFix
    ) { alerts, history, refreshing ->
        AntiTheftBundle(alerts, history, refreshing)
    }

    private val telemetryBundle = combine(
        repository.flashSessions,
        repository.downloadedSessions,
        repository.selectedSessionId,
        repository.transferProgress,
        repository.liveSample
    ) { headers, downloaded, selectedId, progress, live ->
        TelemetryBundle(headers, downloaded, selectedId, progress, live)
    }

    private val epaperBundle = combine(
        repository.epaperConfig,
        repository.easterEggScores
    ) { cfg, scores ->
        EPaperBundle(cfg, scores)
    }

    private val combinedSystem = combine(
        connectionBundle,
        onboardingAndLocationBundle,
        hardwareBundle
    ) { conn, onb, hw ->
        Triple(conn, onb, hw)
    }

    val uiState: StateFlow<TrackPulseUiState> = combine(
        combinedSystem,
        antiTheftBundle,
        telemetryBundle,
        epaperBundle
    ) { (conn, onb, hw), theft, tel, epd ->
        val selected = tel.selectedId?.let { tel.downloaded[it] } ?: tel.downloaded.values.firstOrNull()
        TrackPulseUiState(
            onboardingCompleted = onb.onboardingCompleted,
            pairingKey = onb.pairingKey,
            status = hw.status,
            hostPhoneLocation = onb.phoneLocation,
            transportPreference = conn.preference,
            transportState = conn.state,
            transportStatusText = conn.statusText,
            activeChannel = conn.channel,
            rssiDbm = conn.rssi,
            isBulkFlashAllowed = repository.transportManager.isBulkFlashAllowed,
            lastAgpsLog = hw.agpsLog,
            activeBuzzerMelody = hw.buzzer,
            alerts = theft.alerts,
            positionHistory = theft.history,
            isRefreshingFix = theft.isRefreshingFix,
            flashSessions = tel.headers,
            downloadedSessions = tel.downloaded,
            selectedSession = selected,
            transferProgress = tel.progress,
            liveSample = tel.live,
            epaperConfig = epd.config,
            easterEggScores = epd.scores,
            mqttSettings = hw.mqtt,
            bannerMessage = hw.banner
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = TrackPulseUiState()
    )

    // ---- Onboarding & Permissions ----

    fun onPermissionsGranted() = repository.onPermissionsGranted()
    fun completeOnboardingWithQr(parsed: ParsedPairingQr) = repository.completeOnboardingWithQr(parsed)
    fun completeOnboardingDirectBluetooth() = repository.completeOnboardingDirectBluetooth()
    fun relaunchOnboarding() = repository.relaunchOnboarding()

    // ---- Commandes utilisateur ----

    fun saveSettingsAndConnect(pref: TransportPreference, settings: MqttSettings) =
        repository.saveSettingsAndConnect(pref, settings)

    fun reconnect() = repository.reconnect()
    fun toggleSimulated4gFallback(enable4g: Boolean) = repository.toggleSimulated4gFallback(enable4g)
    fun injectAgpsNow() = repository.triggerAgpsInjection()
    fun setTrackerMode(mode: TrackerMode) = repository.setTrackerMode(mode)
    fun playBuzzer(melody: BuzzerMelody, repeatCount: Int = 2) = repository.playBuzzer(melody, repeatCount)
    fun requestOnDemandFix() = repository.requestOnDemandGpsFix()
    fun acknowledgeAlert(alertId: Int) = repository.acknowledgeAlert(alertId)
    fun simulateMotionAlert() = repository.simulateMotionAlert()

    fun startTrackRecording(rateHz: Int, accelRangeG: Int, vehicleType: VehicleType) =
        repository.startTrackRecording(rateHz, accelRangeG, vehicleType)

    fun stopTrackRecording() = repository.stopTrackRecording()
    fun refreshFlashSessions() = repository.requestFlashSessionList()
    fun downloadFlashSession(header: FlashSessionHeader) = repository.downloadFlashSession(header)
    fun selectSession(sessionId: Int) = repository.selectDownloadedSession(sessionId)
    fun eraseFlashSession(sessionId: Int) = repository.eraseFlashSession(sessionId)

    fun updateEPaperConfig(config: EPaperConfig, push: Boolean = true) =
        repository.updateEPaperConfig(config, push)

    fun startEasterEgg(game: EasterEggGame, soundEnabled: Boolean) =
        repository.startEasterEgg(game, soundEnabled)

    fun stopEasterEgg(game: EasterEggGame) = repository.stopEasterEgg(game)
    fun syncEasterEggScores(game: EasterEggGame) = repository.syncEasterEggScores(game)
    fun simulateEasterEggMatch(game: EasterEggGame) = repository.simulateEasterEggMatch(game)
    fun clearBanner() = repository.clearBannerMessage()

    private data class ConnectionBundle(
        val preference: TransportPreference,
        val state: TransportState,
        val statusText: String,
        val channel: ConnectionChannel,
        val rssi: Int?
    )

    private data class OnboardingLocationBundle(
        val onboardingCompleted: Boolean?,
        val pairingKey: String,
        val phoneLocation: HostPhoneLocation?
    )

    private data class HardwareBundle(
        val status: DeviceStatus,
        val agpsLog: AgpsInjectionLog?,
        val buzzer: BuzzerMelody,
        val mqtt: MqttSettings,
        val banner: String?
    )

    private data class AntiTheftBundle(
        val alerts: List<MotionAlert>,
        val history: List<PositionHistoryPoint>,
        val isRefreshingFix: Boolean
    )

    private data class TelemetryBundle(
        val headers: List<FlashSessionHeader>,
        val downloaded: Map<Int, TrackSession>,
        val selectedId: Int?,
        val progress: FlashTransferProgress,
        val live: TelemetrySample?
    )

    private data class EPaperBundle(
        val config: EPaperConfig,
        val scores: Map<EasterEggGame, EasterEggScore>
    )
}
