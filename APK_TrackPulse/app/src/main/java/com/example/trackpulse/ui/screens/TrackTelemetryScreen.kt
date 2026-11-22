package com.example.trackpulse.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.trackpulse.domain.model.FlashSessionHeader
import com.example.trackpulse.domain.model.TrackSessionAnalyzer
import com.example.trackpulse.domain.model.VehicleType
import com.example.trackpulse.ui.components.CarbonCard
import com.example.trackpulse.ui.components.GForceFrictionCircle
import com.example.trackpulse.ui.components.MetricTile
import com.example.trackpulse.ui.components.SectionHeader
import com.example.trackpulse.ui.components.SegmentedProgressGauge
import com.example.trackpulse.ui.components.SpeedProfileChart
import com.example.trackpulse.ui.components.TrackSpeedOsmMap
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TrackTelemetryScreen(
    uiState: TrackPulseUiState,
    onStartRecording: (rateHz: Int, accelRangeG: Int, vehicle: VehicleType) -> Unit,
    onStopRecording: () -> Unit,
    onRefreshFlashList: () -> Unit,
    onDownloadSession: (FlashSessionHeader) -> Unit,
    onSelectSession: (Int) -> Unit,
    onEraseSession: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedVehicle by remember { mutableStateOf(VehicleType.KART) }
    var selectedRateHz by remember { mutableIntStateOf(10) }
    var selectedRangeG by remember { mutableIntStateOf(8) }

    val isRecording = uiState.status.isRecording
    val session = uiState.selectedSession
    val dateFmt = remember { SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Panneau d'acquisition haute fréquence vers Flash NOR 8 Mo
        item {
            CarbonCard(
                borderColor = if (isRecording) RacingRed else CarbonBorder
            ) {
                SectionHeader(
                    title = "Acquisition Circuit (Flash NOR 8 Mo)",
                    subtitle = "16 octets/point • GNSS + LIS3DH 3 axes en QSPI 40 MHz",
                    accentColor = RacingRed
                )
                Spacer(Modifier.height(10.dp))

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    VehicleType.entries.forEach { v ->
                        FilterChip(
                            selected = selectedVehicle == v,
                            onClick = { if (!isRecording) selectedVehicle = v },
                            label = { Text(v.label, style = MaterialTheme.typography.labelSmall) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = TelemetryCyan.copy(alpha = 0.2f),
                                selectedLabelColor = TelemetryCyan,
                                containerColor = CarbonElevated,
                                labelColor = TextSecondary
                            )
                        )
                    }
                    listOf(10, 20, 50).forEach { hz ->
                        FilterChip(
                            selected = selectedRateHz == hz,
                            onClick = { if (!isRecording) selectedRateHz = hz },
                            label = { Text("$hz Hz", style = MaterialTheme.typography.labelSmall) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = PitAmber.copy(alpha = 0.2f),
                                selectedLabelColor = PitAmber,
                                containerColor = CarbonElevated,
                                labelColor = TextSecondary
                            )
                        )
                    }
                    listOf(4, 8, 16).forEach { g ->
                        FilterChip(
                            selected = selectedRangeG == g,
                            onClick = { if (!isRecording) selectedRangeG = g },
                            label = { Text("±${g}G", style = MaterialTheme.typography.labelSmall) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = VoltGreen.copy(alpha = 0.2f),
                                selectedLabelColor = VoltGreen,
                                containerColor = CarbonElevated,
                                labelColor = TextSecondary
                            )
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        if (isRecording) {
                            onStopRecording()
                        } else {
                            onStartRecording(selectedRateHz, selectedRangeG, selectedVehicle)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isRecording) CarbonElevated else RacingRed,
                        contentColor = TextPrimary
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        imageVector = if (isRecording) Icons.Rounded.Stop else Icons.Rounded.FiberManualRecord,
                        contentDescription = null,
                        tint = if (isRecording) RacingRed else TextPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (isRecording) "ARRÊTER & SAUVEGARDER EN FLASH" else "DÉMARRER SESSION CIRCUIT",
                        style = MaterialTheme.typography.labelMedium
                    )
                }

                // Télémétrie temps réel durant l'enregistrement
                uiState.liveSample?.let { live ->
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        MetricTile(
                            label = "Vitesse Live",
                            value = "%.1f".format(live.speedKmh),
                            unit = "km/h",
                            accentColor = RacingRed,
                            modifier = Modifier.weight(1f)
                        )
                        MetricTile(
                            label = "G Latéral",
                            value = "%+.2f".format(live.ayG),
                            unit = "G",
                            accentColor = TelemetryCyan,
                            modifier = Modifier.weight(1f)
                        )
                        MetricTile(
                            label = "G Long.",
                            value = "%+.2f".format(live.axG),
                            unit = "G",
                            accentColor = VoltGreen,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        // 2. Sessions enregistrées en Flash NOR (Téléchargement BLE uniquement)
        item {
            CarbonCard {
                SectionHeader(
                    title = "Sessions Flash NOR W25Q64JV",
                    subtitle = if (uiState.isBulkFlashAllowed) {
                        "Transfert BLE RN4871 autorisé (0 octet 4G)"
                    } else {
                        "Connectez-vous en BLE pour télécharger les logs (protection 500 Mo)"
                    },
                    accentColor = TelemetryCyan,
                    trailing = {
                        IconButton(onClick = onRefreshFlashList) {
                            Icon(Icons.Rounded.Refresh, contentDescription = "Actualiser", tint = TelemetryCyan)
                        }
                    }
                )

                if (uiState.transferProgress.isTransferring) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = uiState.transferProgress.statusText,
                        style = MaterialTheme.typography.labelSmall,
                        color = TelemetryCyan
                    )
                    Spacer(Modifier.height(4.dp))
                    SegmentedProgressGauge(
                        progress = uiState.transferProgress.fraction,
                        color = TelemetryCyan
                    )
                }

                Spacer(Modifier.height(10.dp))

                if (uiState.flashSessions.isEmpty()) {
                    Text(
                        text = "Aucune session listée. Appuyez sur Actualiser (en BLE) ou enregistrez une session.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        uiState.flashSessions.forEach { header ->
                            val isDownloaded = uiState.downloadedSessions.containsKey(header.sessionId)
                            val isSelected = uiState.selectedSession?.header?.sessionId == header.sessionId
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (isSelected) TelemetryCyan.copy(alpha = 0.14f) else CarbonElevated,
                                border = BorderStroke(1.dp, if (isSelected) TelemetryCyan else CarbonBorder)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "#${header.sessionId} • ${header.vehicleType.label} (${header.sampleRateHz} Hz)",
                                            style = MaterialTheme.typography.titleMedium,
                                            color = TextPrimary,
                                            fontSize = 13.sp
                                        )
                                        Text(
                                            text = "${dateFmt.format(Date(header.startUtcSec * 1000L))} • %.1f km/h max • %.1f Ko"
                                                .format(header.maxSpeedKmh, header.sizeKb),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = TextSecondary
                                        )
                                    }

                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (isDownloaded) {
                                            OutlinedButton(
                                                onClick = { onSelectSession(header.sessionId) },
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                                border = BorderStroke(1.dp, VoltGreen)
                                            ) {
                                                Text(
                                                    text = if (isSelected) "Affichée" else "Analyser",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = VoltGreen
                                                )
                                            }
                                        } else {
                                            Button(
                                                onClick = { onDownloadSession(header) },
                                                enabled = uiState.isBulkFlashAllowed && !uiState.transferProgress.isTransferring,
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = TelemetryCyan,
                                                    contentColor = MaterialTheme.colorScheme.onSecondary
                                                ),
                                                shape = RoundedCornerShape(10.dp)
                                            ) {
                                                Icon(
                                                    imageVector = if (uiState.isBulkFlashAllowed) {
                                                        Icons.Rounded.Download
                                                    } else {
                                                        Icons.Rounded.Bluetooth
                                                    },
                                                    contentDescription = null,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                                Spacer(Modifier.width(4.dp))
                                                Text("BLE", style = MaterialTheme.typography.labelSmall)
                                            }
                                        }
                                        IconButton(onClick = { onEraseSession(header.sessionId) }) {
                                            Icon(
                                                Icons.Rounded.DeleteOutline,
                                                contentDescription = "Supprimer",
                                                tint = TextSecondary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 3. Visualisation & Analyse de la session téléchargée
        if (session != null) {
            item {
                CarbonCard {
                    SectionHeader(
                        title = "Trajectoire & Dégradé Vitesse (#${session.header.sessionId})",
                        subtitle = "%.2f km parcourus • ${session.samples.size} échantillons"
                            .format(session.totalDistanceMeters / 1000f),
                        accentColor = RacingRed
                    )
                    Spacer(Modifier.height(10.dp))

                    TrackSpeedOsmMap(samples = session.samples)

                    Spacer(Modifier.height(8.dp))
                    // Légende dégradé couleur vitesse
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("LENT", style = MaterialTheme.typography.labelSmall, color = TelemetryCyan)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(
                                    Brush.horizontalGradient(
                                        listOf(TelemetryCyan, VoltGreen, PitAmber, RacingRed)
                                    )
                                )
                        )
                        Text(
                            "%.0f KM/H".format(session.maxSpeedKmh),
                            style = MaterialTheme.typography.labelSmall,
                            color = RacingRed
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    // KPI Vitesse & Chrono
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        MetricTile(
                            label = "Vitesse Max",
                            value = "%.1f".format(session.maxSpeedKmh),
                            unit = "km/h",
                            accentColor = RacingRed,
                            icon = Icons.Rounded.Speed,
                            modifier = Modifier.weight(1f)
                        )
                        MetricTile(
                            label = "Meilleur Tour",
                            value = session.bestLapMs?.let { TrackSessionAnalyzer.formatLapTime(it) } ?: "--:--",
                            unit = "",
                            accentColor = VoltGreen,
                            icon = Icons.Rounded.Timer,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    // Diagramme G-G + Jauges Latérale / Longitudinale Max
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        GForceFrictionCircle(
                            samples = session.samples,
                            liveSample = uiState.liveSample
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            MetricTile(
                                label = "G Latéral Max",
                                value = "%.2f".format(session.maxLatG),
                                unit = "G",
                                accentColor = TelemetryCyan
                            )
                            MetricTile(
                                label = "Freinage Max",
                                value = "-%.2f".format(session.maxBrakeG),
                                unit = "G",
                                accentColor = RacingRed
                            )
                            MetricTile(
                                label = "Accél. Max",
                                value = "+%.2f".format(session.maxAccelG),
                                unit = "G",
                                accentColor = VoltGreen
                            )
                        }
                    }

                    Spacer(Modifier.height(14.dp))
                    SpeedProfileChart(samples = session.samples)
                }
            }

            // 4. Temps au tour (Lap Splits)
            if (session.laps.isNotEmpty()) {
                item {
                    CarbonCard {
                        SectionHeader(
                            title = "Chronométrage & Temps au Tour",
                            subtitle = "Détection automatique ligne départ/arrivée",
                            accentColor = VoltGreen
                        )
                        Spacer(Modifier.height(10.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            session.laps.forEach { lap ->
                                val isBest = lap.durationMs == session.bestLapMs
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (isBest) VoltGreen.copy(alpha = 0.14f) else CarbonElevated,
                                    border = BorderStroke(1.dp, if (isBest) VoltGreen else CarbonBorder)
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
                                                Icons.Rounded.Flag,
                                                contentDescription = null,
                                                tint = if (isBest) VoltGreen else TextSecondary,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Column {
                                                Text(
                                                    text = if (isBest) "TOUR ${lap.lapNumber} • RECORD" else "TOUR ${lap.lapNumber}",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = if (isBest) VoltGreen else TextPrimary
                                                )
                                                Text(
                                                    text = "Vmax %.1f km/h • Lat %.2f G • Frein %.2f G".format(
                                                        lap.maxSpeedKmh,
                                                        lap.peakLatG,
                                                        lap.peakBrakeG
                                                    ),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = TextSecondary
                                                )
                                            }
                                        }
                                        Text(
                                            text = TrackSessionAnalyzer.formatLapTime(lap.durationMs),
                                            style = TelemetryMonoStyle.copy(fontSize = 15.sp),
                                            color = if (isBest) VoltGreen else TextPrimary,
                                            fontWeight = FontWeight.ExtraBold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
