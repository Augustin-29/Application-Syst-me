package com.example.trackpulse.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.BatteryStd
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.CellTower
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.SatelliteAlt
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.trackpulse.domain.model.BuzzerMelody
import com.example.trackpulse.domain.model.ConnectionChannel
import com.example.trackpulse.domain.model.TrackerMode
import com.example.trackpulse.domain.model.TransportPreference
import com.example.trackpulse.ui.components.CarbonCard
import com.example.trackpulse.ui.components.ConnectionChannelBadge
import com.example.trackpulse.ui.components.MetricTile
import com.example.trackpulse.ui.components.ProximityRadar
import com.example.trackpulse.ui.components.SectionHeader
import com.example.trackpulse.ui.components.SegmentedProgressGauge
import com.example.trackpulse.ui.theme.AsphaltBlack
import com.example.trackpulse.ui.theme.CarbonBorder
import com.example.trackpulse.ui.theme.CarbonElevated
import com.example.trackpulse.ui.theme.PitAmber
import com.example.trackpulse.ui.theme.RacingRed
import com.example.trackpulse.ui.theme.TelemetryCyan
import com.example.trackpulse.ui.theme.TelemetryMonoStyle
import com.example.trackpulse.ui.theme.TextPrimary
import com.example.trackpulse.ui.theme.TextSecondary
import com.example.trackpulse.ui.theme.VoltGreen
import com.example.trackpulse.ui.viewmodel.TrackPulseUiState

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DashboardScreen(
    uiState: TrackPulseUiState,
    onSelectMode: (TrackerMode) -> Unit,
    onPlayBuzzer: (BuzzerMelody) -> Unit,
    onInjectAgps: () -> Unit,
    onToggleSim4g: (Boolean) -> Unit,
    onReconnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val status = uiState.status
    var selectedMelody by remember { mutableStateOf(BuzzerMelody.PING_2730HZ) }
    val isBuzzerPlaying = uiState.activeBuzzerMelody != BuzzerMelody.STOP

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Carte État Matériel & Connectivité Hybride
        item {
            CarbonCard {
                SectionHeader(
                    title = "Télémétrie Boîtier STM32L433",
                    subtitle = uiState.transportStatusText,
                    accentColor = TelemetryCyan,
                    trailing = {
                        ConnectionChannelBadge(
                            channel = uiState.activeChannel,
                            rssiDbm = uiState.rssiDbm
                        )
                    }
                )
                Spacer(Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val battColor = when {
                        status.batteryPct >= 50 -> VoltGreen
                        status.batteryPct >= 20 -> PitAmber
                        else -> RacingRed
                    }
                    MetricTile(
                        label = if (status.isCharging) "Batterie (Charge)" else "Batterie Li-Ion",
                        value = "${status.batteryPct}%",
                        unit = "${status.batteryMv} mV",
                        accentColor = battColor,
                        icon = if (status.isCharging) Icons.Rounded.BatteryChargingFull else Icons.Rounded.BatteryStd,
                        modifier = Modifier.weight(1f)
                    )
                    MetricTile(
                        label = "GNSS SIM7080G",
                        value = "${status.gpsSats}",
                        unit = if (status.hasGpsFix) "SAT FIX" else "NO FIX",
                        accentColor = if (status.hasGpsFix) VoltGreen else PitAmber,
                        icon = Icons.Rounded.SatelliteAlt,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(Modifier.height(12.dp))

                // Jauge Flash NOR W25Q64JV (8 Mo)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.Memory,
                            contentDescription = null,
                            tint = TelemetryCyan,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "FLASH NOR W25Q64JV (8 Mo)",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                    Text(
                        text = "%.2f / %.0f Mo (%.0f%%)".format(
                            status.flashUsedMb,
                            status.flashTotalMb,
                            status.flashUsageRatio * 100f
                        ),
                        style = TelemetryMonoStyle.copy(fontSize = 12.sp),
                        color = TextPrimary
                    )
                }
                Spacer(Modifier.height(6.dp))
                SegmentedProgressGauge(
                    progress = status.flashUsageRatio,
                    color = if (status.flashUsageRatio > 0.85f) RacingRed else TelemetryCyan
                )

                Spacer(Modifier.height(12.dp))

                // Forfait 4G LTE 500 Mo + Bascule Simulateur BLE/4G
                val usedMb = uiState.mqttSettings.estimatedLteBytesUsed / (1024f * 1024f)
                val quotaRatio = (usedMb / 500f).coerceIn(0f, 1f)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.CellTower,
                            contentDescription = null,
                            tint = PitAmber,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "DATA 4G MQTT (FORFAIT 500 Mo)",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                    Text(
                        text = "%.2f / 500 Mo".format(usedMb),
                        style = TelemetryMonoStyle.copy(fontSize = 12.sp),
                        color = PitAmber
                    )
                }
                Spacer(Modifier.height(6.dp))
                SegmentedProgressGauge(progress = quotaRatio.coerceAtLeast(0.02f), color = PitAmber)

                if (uiState.transportPreference == TransportPreference.DEMO_SIMULATOR) {
                    Spacer(Modifier.height(12.dp))
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = CarbonElevated,
                        border = BorderStroke(1.dp, CarbonBorder)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Simuler hors portée BLE (Bascule 4G)",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextPrimary,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = "Teste la bascule hybride BLE ↔ MQTT et le blocage dump Flash",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary
                                )
                            }
                            Switch(
                                checked = uiState.activeChannel == ConnectionChannel.LTE_MQTT,
                                onCheckedChange = onToggleSim4g,
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = AsphaltBlack,
                                    checkedTrackColor = PitAmber
                                )
                            )
                        }
                    }
                }
            }
        }

        // 2. Injection A-GPS Automatique (Cold Start SIM7080G)
        item {
            CarbonCard {
                SectionHeader(
                    title = "A-GPS (Aide au Cold Start GNSS)",
                    subtitle = "Injection UTC + position smartphone dès la connexion BLE",
                    accentColor = VoltGreen
                )
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        val agps = uiState.lastAgpsLog
                        Text(
                            text = agps?.message ?: "En attente de connexion BLE…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (status.isAgpsValid) VoltGreen else TextSecondary,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (agps != null) {
                            Text(
                                text = "%.5f° N, %.5f° E • Trame binaire 19 octets".format(
                                    agps.latitude,
                                    agps.longitude
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = onInjectAgps,
                        border = BorderStroke(1.dp, VoltGreen.copy(alpha = 0.7f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = VoltGreen)
                    ) {
                        Text("Injecter", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }

        // 3. Sélecteur de mode du boîtier
        item {
            CarbonCard {
                SectionHeader(
                    title = "Mode de fonctionnement",
                    subtitle = status.mode.description,
                    accentColor = RacingRed
                )
                Spacer(Modifier.height(10.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    TrackerMode.entries.forEach { mode ->
                        val selected = status.mode == mode
                        FilterChip(
                            selected = selected,
                            onClick = { onSelectMode(mode) },
                            label = {
                                Text(
                                    text = mode.shortLabel,
                                    style = MaterialTheme.typography.labelMedium
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = RacingRed.copy(alpha = 0.22f),
                                selectedLabelColor = RacingRed,
                                containerColor = CarbonElevated,
                                labelColor = TextSecondary
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = selected,
                                borderColor = CarbonBorder,
                                selectedBorderColor = RacingRed
                            )
                        )
                    }
                }
            }
        }

        // 4. Proximity Finder (Radar RSSI BLE + Pilotage Buzzer CMT-8530S)
        item {
            CarbonCard(
                borderColor = if (isBuzzerPlaying) RacingRed else CarbonBorder
            ) {
                SectionHeader(
                    title = "Localiser à proximité (Proximity Finder)",
                    subtitle = "Radar RSSI BLE RN4871 + Buzzer PWM CMT-8530S (2 730 Hz)",
                    accentColor = if (isBuzzerPlaying) RacingRed else TelemetryCyan
                )
                Spacer(Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    ProximityRadar(
                        rssiDbm = uiState.rssiDbm,
                        proximityStrength = status.proximityStrength,
                        estimatedDistanceMeters = status.estimatedDistanceMeters,
                        isBuzzerActive = isBuzzerPlaying
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val proximityLabel = when {
                            uiState.rssiDbm == null -> "Hors portée BLE"
                            status.proximityStrength > 0.72f -> "TRÈS PROCHE (< 2 m)"
                            status.proximityStrength > 0.42f -> "À PROXIMITÉ (2–8 m)"
                            else -> "ÉLOIGNÉ (> 8 m)"
                        }
                        Text(
                            text = proximityLabel,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (uiState.rssiDbm != null) TelemetryCyan else PitAmber
                        )
                        SegmentedProgressGauge(
                            progress = status.proximityStrength,
                            color = if (status.proximityStrength > 0.7f) VoltGreen else TelemetryCyan
                        )
                        Text(
                            text = selectedMelody.subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                        Button(
                            onClick = {
                                if (isBuzzerPlaying) {
                                    onPlayBuzzer(BuzzerMelody.STOP)
                                } else {
                                    onPlayBuzzer(selectedMelody)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isBuzzerPlaying) CarbonElevated else RacingRed,
                                contentColor = TextPrimary
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(
                                imageVector = if (isBuzzerPlaying) {
                                    Icons.AutoMirrored.Rounded.VolumeOff
                                } else {
                                    Icons.Rounded.NotificationsActive
                                },
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = if (isBuzzerPlaying) "Couper Buzzer" else "Faire Sonner",
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text(
                    text = "CHOIX DE MÉLODIE / BIP (TIM2_CH1 PWM)",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
                Spacer(Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    BuzzerMelody.entries.filter { it != BuzzerMelody.STOP }.forEach { melody ->
                        val isSel = selectedMelody == melody
                        FilterChip(
                            selected = isSel,
                            onClick = { selectedMelody = melody },
                            leadingIcon = {
                                Icon(
                                    Icons.Rounded.GraphicEq,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp)
                                )
                            },
                            label = { Text(melody.label, style = MaterialTheme.typography.labelSmall) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = TelemetryCyan.copy(alpha = 0.2f),
                                selectedLabelColor = TelemetryCyan,
                                selectedLeadingIconColor = TelemetryCyan,
                                containerColor = CarbonElevated,
                                labelColor = TextSecondary
                            )
                        )
                    }
                }

                if (uiState.activeChannel == ConnectionChannel.DISCONNECTED) {
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = onReconnect,
                        modifier = Modifier.fillMaxWidth(),
                        border = BorderStroke(1.dp, TelemetryCyan)
                    ) {
                        Icon(Icons.Rounded.Bluetooth, contentDescription = null, tint = TelemetryCyan)
                        Spacer(Modifier.width(8.dp))
                        Text("Reconnecter le boîtier", color = TelemetryCyan)
                    }
                }
            }
        }
    }
}
