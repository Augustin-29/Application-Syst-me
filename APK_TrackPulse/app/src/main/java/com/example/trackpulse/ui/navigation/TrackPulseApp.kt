package com.example.trackpulse.ui.navigation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.DisplaySettings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.trackpulse.domain.model.MqttSettings
import com.example.trackpulse.domain.model.TrackerMode
import com.example.trackpulse.domain.model.TransportPreference
import com.example.trackpulse.ui.components.ConnectionChannelBadge
import com.example.trackpulse.ui.screens.AntiTheftScreen
import com.example.trackpulse.ui.screens.DashboardScreen
import com.example.trackpulse.ui.screens.EPaperEasterEggScreen
import com.example.trackpulse.ui.screens.TrackTelemetryScreen
import com.example.trackpulse.ui.theme.AsphaltBlack
import com.example.trackpulse.ui.theme.CarbonBorder
import com.example.trackpulse.ui.theme.CarbonElevated
import com.example.trackpulse.ui.theme.CarbonSurface
import com.example.trackpulse.ui.theme.RacingRed
import com.example.trackpulse.ui.theme.TelemetryCyan
import com.example.trackpulse.ui.theme.TextPrimary
import com.example.trackpulse.ui.theme.TextSecondary
import com.example.trackpulse.ui.viewmodel.TrackPulseViewModel

private enum class TrackPulseTab(val label: String, val icon: ImageVector) {
    DASHBOARD("Boîtier", Icons.Rounded.Dashboard),
    ANTITHEFT("Antivol", Icons.Rounded.Shield),
    CIRCUIT("Circuit", Icons.Rounded.Speed),
    EPAPER("E-Paper", Icons.Rounded.DisplaySettings)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackPulseApp(
    viewModel: TrackPulseViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var currentTab by remember { mutableStateOf(TrackPulseTab.DASHBOARD) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    val unreadAlerts = uiState.alerts.count { !it.acknowledged }

    LaunchedEffect(uiState.bannerMessage) {
        uiState.bannerMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearBanner()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = AsphaltBlack,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = CarbonSurface,
                    titleContentColor = TextPrimary
                ),
                title = {
                    Column {
                        Text(
                            text = "TRACKPULSE",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = TextPrimary
                        )
                        Text(
                            text = uiState.status.mode.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = TelemetryCyan
                        )
                    }
                },
                actions = {
                    ConnectionChannelBadge(
                        channel = uiState.activeChannel,
                        rssiDbm = uiState.rssiDbm
                    )
                    Spacer(Modifier.width(4.dp))
                    IconButton(onClick = { showSettingsDialog = true }) {
                        Icon(
                            Icons.Rounded.Tune,
                            contentDescription = "Paramètres connectivité BLE/MQTT",
                            tint = TextPrimary
                        )
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = CarbonSurface,
                tonalElevation = 0.dp
            ) {
                TrackPulseTab.entries.forEach { tab ->
                    val selected = currentTab == tab
                    NavigationBarItem(
                        selected = selected,
                        onClick = { currentTab = tab },
                        icon = {
                            if (tab == TrackPulseTab.ANTITHEFT && unreadAlerts > 0) {
                                BadgedBox(
                                    badge = {
                                        Badge(containerColor = RacingRed, contentColor = TextPrimary) {
                                            Text("$unreadAlerts")
                                        }
                                    }
                                ) {
                                    Icon(tab.icon, contentDescription = tab.label, modifier = Modifier.size(22.dp))
                                }
                            } else {
                                Icon(tab.icon, contentDescription = tab.label, modifier = Modifier.size(22.dp))
                            }
                        },
                        label = {
                            Text(
                                text = tab.label,
                                style = MaterialTheme.typography.labelSmall
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = RacingRed,
                            selectedTextColor = RacingRed,
                            indicatorColor = RacingRed.copy(alpha = 0.16f),
                            unselectedIconColor = TextSecondary,
                            unselectedTextColor = TextSecondary
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        when (currentTab) {
            TrackPulseTab.DASHBOARD -> DashboardScreen(
                uiState = uiState,
                onSelectMode = viewModel::setTrackerMode,
                onPlayBuzzer = { melody -> viewModel.playBuzzer(melody) },
                onInjectAgps = viewModel::injectAgpsNow,
                onToggleSim4g = viewModel::toggleSimulated4gFallback,
                onReconnect = viewModel::reconnect,
                modifier = Modifier.padding(innerPadding)
            )

            TrackPulseTab.ANTITHEFT -> AntiTheftScreen(
                uiState = uiState,
                onArmAntiTheft = { viewModel.setTrackerMode(TrackerMode.ANTITHEFT_SLEEP) },
                onDisarm = { viewModel.setTrackerMode(TrackerMode.IDLE_STANDBY) },
                onRequestFix = viewModel::requestOnDemandFix,
                onAcknowledgeAlert = viewModel::acknowledgeAlert,
                onSimulateAlert = viewModel::simulateMotionAlert,
                modifier = Modifier.padding(innerPadding)
            )

            TrackPulseTab.CIRCUIT -> TrackTelemetryScreen(
                uiState = uiState,
                onStartRecording = viewModel::startTrackRecording,
                onStopRecording = viewModel::stopTrackRecording,
                onRefreshFlashList = viewModel::refreshFlashSessions,
                onDownloadSession = viewModel::downloadFlashSession,
                onSelectSession = viewModel::selectSession,
                onEraseSession = viewModel::eraseFlashSession,
                modifier = Modifier.padding(innerPadding)
            )

            TrackPulseTab.EPAPER -> EPaperEasterEggScreen(
                uiState = uiState,
                onUpdateEPaperConfig = viewModel::updateEPaperConfig,
                onStartEasterEgg = viewModel::startEasterEgg,
                onStopEasterEgg = viewModel::stopEasterEgg,
                onSyncScores = viewModel::syncEasterEggScores,
                onSimulateGamePlayed = viewModel::simulateEasterEggMatch,
                modifier = Modifier.padding(innerPadding)
            )
        }
    }

    if (showSettingsDialog) {
        ConnectivitySettingsDialog(
            currentPreference = uiState.transportPreference,
            settings = uiState.mqttSettings,
            onDismiss = { showSettingsDialog = false },
            onSave = { pref, updatedSettings ->
                viewModel.updateSettings(updatedSettings)
                viewModel.selectTransportPreference(pref)
                showSettingsDialog = false
            }
        )
    }
}

@Composable
private fun ConnectivitySettingsDialog(
    currentPreference: TransportPreference,
    settings: MqttSettings,
    onDismiss: () -> Unit,
    onSave: (TransportPreference, MqttSettings) -> Unit
) {
    var pref by remember { mutableStateOf(currentPreference) }
    var brokerUri by remember { mutableStateOf(settings.brokerUri) }
    var deviceId by remember { mutableStateOf(settings.deviceId) }
    var bleName by remember { mutableStateOf(settings.bleDeviceNameFilter) }
    var wakeMinutes by remember { mutableStateOf((settings.rtcWakeIntervalSec / 60).toString()) }
    var threshMg by remember { mutableStateOf(settings.motionThresholdMg.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CarbonSurface,
        titleContentColor = TextPrimary,
        title = { Text("Connectivité Hybride BLE / 4G") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "STRATÉGIE DE TRANSPORT",
                    style = MaterialTheme.typography.labelSmall,
                    color = TelemetryCyan
                )
                TransportPreference.entries.forEach { item ->
                    Surface(
                        onClick = { pref = item },
                        shape = RoundedCornerShape(10.dp),
                        color = if (pref == item) TelemetryCyan.copy(alpha = 0.14f) else CarbonElevated,
                        border = BorderStroke(1.dp, if (pref == item) TelemetryCyan else CarbonBorder)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = pref == item,
                                onClick = { pref = item },
                                colors = RadioButtonDefaults.colors(selectedColor = TelemetryCyan)
                            )
                            Text(
                                text = item.label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextPrimary
                            )
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))
                Text(
                    text = "CONFIGURATION RN4871 & SIM7080G MQTT",
                    style = MaterialTheme.typography.labelSmall,
                    color = TelemetryCyan
                )
                OutlinedTextField(
                    value = bleName,
                    onValueChange = { bleName = it },
                    label = { Text("Filtre nom BLE (RN4871)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = brokerUri,
                    onValueChange = { brokerUri = it },
                    label = { Text("Broker MQTT (tcp://...)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = deviceId,
                    onValueChange = { deviceId = it },
                    label = { Text("ID Boîtier (topics tp/<id>/u,d,a)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = wakeMinutes,
                        onValueChange = { wakeMinutes = it.filter(Char::isDigit) },
                        label = { Text("Réveil RTC (min)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = threshMg,
                        onValueChange = { threshMg = it.filter(Char::isDigit) },
                        label = { Text("Seuil LIS3DH (mg)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val wakeSec = (wakeMinutes.toIntOrNull() ?: 60).coerceIn(1, 1080) * 60
                    val mg = (threshMg.toIntOrNull() ?: 160).coerceIn(32, 2000)
                    onSave(
                        pref,
                        settings.copy(
                            brokerUri = brokerUri.trim(),
                            deviceId = deviceId.trim().ifEmpty { "tp01" },
                            bleDeviceNameFilter = bleName.trim().ifEmpty { "TrackPulse" },
                            rtcWakeIntervalSec = wakeSec,
                            motionThresholdMg = mg
                        )
                    )
                }
            ) {
                Text("Appliquer", color = TelemetryCyan, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Annuler", color = TextSecondary)
            }
        }
    )
}
