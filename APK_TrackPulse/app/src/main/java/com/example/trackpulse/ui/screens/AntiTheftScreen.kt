package com.example.trackpulse.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.GpsFixed
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.trackpulse.domain.model.TrackerMode
import com.example.trackpulse.domain.model.TransportPreference
import com.example.trackpulse.ui.components.AntiTheftOsmMap
import com.example.trackpulse.ui.components.CarbonCard
import com.example.trackpulse.ui.components.SectionHeader
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AntiTheftScreen(
    uiState: TrackPulseUiState,
    onArmAntiTheft: () -> Unit,
    onDisarm: () -> Unit,
    onRequestFix: () -> Unit,
    onAcknowledgeAlert: (Int) -> Unit,
    onSimulateAlert: () -> Unit,
    modifier: Modifier = Modifier
) {
    val status = uiState.status
    val isArmed = status.mode == TrackerMode.ANTITHEFT_SLEEP
    val timeFormatter = remember { SimpleDateFormat("HH:mm:ss", Locale.FRANCE) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Carte interactive OpenStreetMap (Tracker rouge + Téléphone hôte bleu + boutons de centrage)
        item {
            AntiTheftOsmMap(
                currentLat = status.latitude,
                currentLon = status.longitude,
                hostPhoneLocation = uiState.hostPhoneLocation,
                history = uiState.positionHistory,
                alerts = uiState.alerts
            )
        }

        // 2. Panneau Armement Antivol Stop 2 & Actualisation 4G à la demande
        item {
            CarbonCard(
                borderColor = if (isArmed) VoltGreen.copy(alpha = 0.6f) else CarbonBorder
            ) {
                SectionHeader(
                    title = "Veille Antivol Basse Consommation",
                    subtitle = "STM32 Stop 2 (~16,5 µA) • LIS3DH 10 Hz sur PA1 (ACC_INT1)",
                    accentColor = if (isArmed) VoltGreen else PitAmber
                )
                Spacer(Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = if (isArmed) onDisarm else onArmAntiTheft,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isArmed) CarbonElevated else VoltGreen,
                            contentColor = if (isArmed) TextPrimary else MaterialTheme.colorScheme.onTertiary
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(
                            imageVector = if (isArmed) Icons.Rounded.Shield else Icons.Rounded.Lock,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (isArmed) "Désarmer" else "Armer Antivol",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }

                    Button(
                        onClick = onRequestFix,
                        enabled = !uiState.isRefreshingFix,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = TelemetryCyan,
                            contentColor = MaterialTheme.colorScheme.onSecondary
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        if (uiState.isRefreshingFix) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onSecondary
                            )
                        } else {
                            Icon(
                                Icons.Rounded.GpsFixed,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "Actualiser (4G)",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                // 3 badges distincts et bien espacés (correction espacement sans collage de texte)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AntiTheftStatBadge(
                        label = "RÉVEIL RTC",
                        value = "${uiState.mqttSettings.rtcWakeIntervalSec / 60} min",
                        valueColor = TelemetryCyan,
                        modifier = Modifier.weight(1f)
                    )
                    AntiTheftStatBadge(
                        label = "SEUIL LIS3DH",
                        value = "${uiState.mqttSettings.motionThresholdMg} mg",
                        valueColor = VoltGreen,
                        modifier = Modifier.weight(1f)
                    )
                    AntiTheftStatBadge(
                        label = "COÛT FIX 4G",
                        value = "~110 o",
                        valueColor = PitAmber,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // 3. Alertes Mouvement Suspect (LIS3DH ACC_INT1)
        item {
            CarbonCard(
                borderColor = if (uiState.alerts.any { !it.acknowledged }) RacingRed else CarbonBorder
            ) {
                SectionHeader(
                    title = "Alertes Mouvement (LIS3DH)",
                    subtitle = "Réveil matériel EXTI1 (PA1) + publication MQTT QoS 1",
                    accentColor = RacingRed,
                    trailing = {
                        if (uiState.transportPreference == TransportPreference.DEMO_SIMULATOR) {
                            OutlinedButton(
                                onClick = onSimulateAlert,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                border = BorderStroke(1.dp, RacingRed.copy(alpha = 0.7f)),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = RacingRed)
                            ) {
                                Icon(Icons.Rounded.NotificationsActive, null, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Test", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                )
                Spacer(Modifier.height(10.dp))

                if (uiState.alerts.isEmpty()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 6.dp)
                    ) {
                        Icon(
                            Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            tint = VoltGreen,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Aucun mouvement suspect détecté. Véhicule sécurisé.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        uiState.alerts.take(5).forEach { alert ->
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (alert.acknowledged) CarbonElevated else RacingRed.copy(alpha = 0.14f),
                                border = BorderStroke(
                                    1.dp,
                                    if (alert.acknowledged) CarbonBorder else RacingRed
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(
                                            Icons.Rounded.Warning,
                                            contentDescription = null,
                                            tint = if (alert.acknowledged) TextSecondary else RacingRed,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Column {
                                            Text(
                                                text = "Choc / Mouvement %.2f G (%s)".format(
                                                    alert.peakAccelG,
                                                    alert.triggeredAxesLabel
                                                ),
                                                style = MaterialTheme.typography.titleMedium,
                                                color = TextPrimary,
                                                fontSize = 13.sp
                                            )
                                            Text(
                                                text = "${timeFormatter.format(Date(alert.timestampSec * 1000L))} • %.5f, %.5f • Bat ${alert.batteryPct}%"
                                                    .format(alert.latitude, alert.longitude),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = TextSecondary
                                            )
                                        }
                                    }
                                    if (!alert.acknowledged) {
                                        Spacer(Modifier.width(6.dp))
                                        OutlinedButton(
                                            onClick = { onAcknowledgeAlert(alert.alertId) },
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                            border = BorderStroke(1.dp, RacingRed)
                                        ) {
                                            Text("Acquitter", style = MaterialTheme.typography.labelSmall, color = RacingRed)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 4. Historique des positions récentes
        item {
            SectionHeader(
                title = "Historique des déplacements",
                subtitle = "${uiState.positionHistory.size} points enregistrés",
                accentColor = TelemetryCyan
            )
        }

        items(uiState.positionHistory.reversed().take(8)) { point ->
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = CarbonElevated,
                border = BorderStroke(1.dp, CarbonBorder)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.History,
                            contentDescription = null,
                            tint = TelemetryCyan,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "%.5f° N, %.5f° E".format(point.latitude, point.longitude),
                                style = TelemetryMonoStyle.copy(fontSize = 12.sp),
                                color = TextPrimary
                            )
                            Text(
                                text = "${timeFormatter.format(Date(point.timestampSec * 1000L))} • ${point.channel.label}",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary
                            )
                        }
                    }
                    Text(
                        text = "%.0f km/h".format(point.speedKmh),
                        style = TelemetryMonoStyle.copy(fontSize = 13.sp),
                        color = VoltGreen
                    )
                }
            }
        }
    }
}

@Composable
private fun AntiTheftStatBadge(
    label: String,
    value: String,
    valueColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = CarbonElevated,
        border = BorderStroke(1.dp, CarbonBorder)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                maxLines = 1
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = value,
                style = TelemetryMonoStyle.copy(fontSize = 12.sp),
                color = valueColor,
                maxLines = 1
            )
        }
    }
}
