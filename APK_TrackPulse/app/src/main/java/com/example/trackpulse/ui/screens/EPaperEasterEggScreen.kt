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
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import com.example.trackpulse.domain.model.EPaperConfig
import com.example.trackpulse.domain.model.EPaperRefreshMode
import com.example.trackpulse.domain.model.EPaperTemplate
import com.example.trackpulse.domain.model.EasterEggGame
import com.example.trackpulse.domain.model.TrackerMode
import com.example.trackpulse.domain.model.TransportPreference
import com.example.trackpulse.ui.components.CarbonCard
import com.example.trackpulse.ui.components.EPaperPreviewCanvas
import com.example.trackpulse.ui.components.SectionHeader
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
fun EPaperEasterEggScreen(
    uiState: TrackPulseUiState,
    onUpdateEPaperConfig: (EPaperConfig, Boolean) -> Unit,
    onStartEasterEgg: (EasterEggGame, Boolean) -> Unit,
    onStopEasterEgg: (EasterEggGame) -> Unit,
    onSyncScores: (EasterEggGame) -> Unit,
    onSimulateGamePlayed: (EasterEggGame) -> Unit,
    modifier: Modifier = Modifier
) {
    val config = uiState.epaperConfig
    var selectedGame by remember { mutableStateOf(EasterEggGame.PULSE_RUNNER) }
    var soundEnabled by remember { mutableStateOf(true) }
    val isEasterEggActive = uiState.status.mode == TrackerMode.EASTER_EGG
    val currentScore = uiState.easterEggScores[selectedGame]

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Aperçu 1:1 de la dalle Waveshare 1,54" E-Paper V2 (200x200 px)
        item {
            CarbonCard {
                SectionHeader(
                    title = "Écran Waveshare 1,54\" E-Paper V2",
                    subtitle = "200×200 px 1-bit • SPI1 TX-Only 5 MHz • Veille < 0,017 mW",
                    accentColor = TelemetryCyan
                )
                Spacer(Modifier.height(14.dp))

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    EPaperPreviewCanvas(
                        config = config,
                        status = uiState.status,
                        selectedGame = selectedGame,
                        highScore = currentScore?.highScore ?: 0
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "${config.template.label} • Rafraîchissement ${config.refreshMode.label} (${config.refreshMode.durationLabel})",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }

                Spacer(Modifier.height(14.dp))
                Text(
                    text = "GABARIT D'AFFICHAGE",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
                Spacer(Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    EPaperTemplate.entries.forEach { tpl ->
                        FilterChip(
                            selected = config.template == tpl,
                            onClick = { onUpdateEPaperConfig(config.copy(template = tpl), false) },
                            label = { Text(tpl.label, style = MaterialTheme.typography.labelSmall) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = TelemetryCyan.copy(alpha = 0.22f),
                                selectedLabelColor = TelemetryCyan,
                                containerColor = CarbonElevated,
                                labelColor = TextSecondary
                            )
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    EPaperRefreshMode.entries.forEach { mode ->
                        FilterChip(
                            selected = config.refreshMode == mode,
                            onClick = { onUpdateEPaperConfig(config.copy(refreshMode = mode), false) },
                            label = {
                                Text("${mode.label} (${mode.durationLabel})", style = MaterialTheme.typography.labelSmall)
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = PitAmber.copy(alpha = 0.2f),
                                selectedLabelColor = PitAmber,
                                containerColor = CarbonElevated,
                                labelColor = TextSecondary
                            )
                        )
                    }
                }

                if (config.template == EPaperTemplate.CUSTOM_CARD) {
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = config.customTitle,
                        onValueChange = { onUpdateEPaperConfig(config.copy(customTitle = it.take(23)), false) },
                        label = { Text("Titre (23 car. max)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = TelemetryCyan,
                            unfocusedBorderColor = CarbonBorder,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        )
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = config.customSubtitle,
                        onValueChange = { onUpdateEPaperConfig(config.copy(customSubtitle = it.take(31)), false) },
                        label = { Text("Sous-titre / Pilote (31 car. max)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = TelemetryCyan,
                            unfocusedBorderColor = CarbonBorder,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        )
                    )
                }

                Spacer(Modifier.height(10.dp))
                SettingToggleRow(
                    title = "Inverser Noir / Blanc",
                    subtitle = "Fond noir encre, texte blanc",
                    checked = config.invertColors,
                    onCheckedChange = { onUpdateEPaperConfig(config.copy(invertColors = it), false) }
                )
                SettingToggleRow(
                    title = "Changement auto selon le mode",
                    subtitle = "Antivol ↔ Circuit HUD automatiquement",
                    checked = config.autoSwitchWithMode,
                    onCheckedChange = { onUpdateEPaperConfig(config.copy(autoSwitchWithMode = it), false) }
                )

                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { onUpdateEPaperConfig(config, true) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = TelemetryCyan,
                        contentColor = AsphaltBlack
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("APPLIQUER SUR L'ÉCRAN E-PAPER", style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        // 2. Mode Mini-Jeu Easter Egg (E-Paper + BP1/BP2 + Buzzer TIM2)
        item {
            CarbonCard(
                borderColor = if (isEasterEggActive) PitAmber else CarbonBorder
            ) {
                SectionHeader(
                    title = "Mode Mini-Jeu Easter Egg",
                    subtitle = "Jouable sur le boîtier avec BP1 (PB2), BP2 (PC13) & Buzzer",
                    accentColor = PitAmber
                )
                Spacer(Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    EasterEggGame.entries.forEach { game ->
                        val isSel = selectedGame == game
                        Surface(
                            onClick = {
                                selectedGame = game
                                onUpdateEPaperConfig(config.copy(template = EPaperTemplate.EASTER_EGG), false)
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            color = if (isSel) PitAmber.copy(alpha = 0.16f) else CarbonElevated,
                            border = BorderStroke(1.dp, if (isSel) PitAmber else CarbonBorder)
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Rounded.SportsEsports,
                                        contentDescription = null,
                                        tint = if (isSel) PitAmber else TextSecondary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = game.title,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = if (isSel) PitAmber else TextPrimary,
                                        fontSize = 13.sp
                                    )
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = game.controlsHint,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                Text(
                    text = selectedGame.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )

                Spacer(Modifier.height(8.dp))
                SettingToggleRow(
                    title = "Effets sonores Buzzer TIM2",
                    subtitle = "Notes rétro sur CMT-8530S (Do6–Do7)",
                    checked = soundEnabled,
                    onCheckedChange = { soundEnabled = it }
                )

                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            if (isEasterEggActive) {
                                onStopEasterEgg(selectedGame)
                            } else {
                                onUpdateEPaperConfig(config.copy(template = EPaperTemplate.EASTER_EGG), false)
                                onStartEasterEgg(selectedGame, soundEnabled)
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isEasterEggActive) RacingRed else PitAmber,
                            contentColor = if (isEasterEggActive) TextPrimary else AsphaltBlack
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Rounded.SportsEsports, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (isEasterEggActive) "Quitter Mini-Jeu" else "Lancer sur Boîtier",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }

                    OutlinedButton(
                        onClick = { onSyncScores(selectedGame) },
                        border = BorderStroke(1.dp, TelemetryCyan),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Rounded.Sync, contentDescription = null, tint = TelemetryCyan, modifier = Modifier.size(18.dp))
                    }
                }

                if (uiState.transportPreference == TransportPreference.DEMO_SIMULATOR) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { onSimulateGamePlayed(selectedGame) },
                        modifier = Modifier.fillMaxWidth(),
                        border = BorderStroke(1.dp, VoltGreen.copy(alpha = 0.65f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = VoltGreen)
                    ) {
                        Text(
                            text = "Simuler une partie terminée sur le boîtier (Sync Score)",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }

        // 3. Tableau des Meilleurs Scores (synchronisé avec Flash secteur 0)
        item {
            CarbonCard {
                SectionHeader(
                    title = "Records Synchronisés (Flash Secteur 0)",
                    subtitle = "Trame binaire EVT_EASTER_EGG_SCORE (13 octets)",
                    accentColor = VoltGreen
                )
                Spacer(Modifier.height(10.dp))

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    EasterEggGame.entries.forEach { game ->
                        val score = uiState.easterEggScores[game]
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = CarbonElevated,
                            border = BorderStroke(1.dp, CarbonBorder)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Rounded.EmojiEvents,
                                        contentDescription = null,
                                        tint = PitAmber,
                                        modifier = Modifier.size(22.dp)
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Column {
                                        Text(
                                            text = game.title,
                                            style = MaterialTheme.typography.titleMedium,
                                            color = TextPrimary
                                        )
                                        Text(
                                            text = "Dernier : ${score?.lastScore ?: 0} • ${score?.gamesPlayed ?: 0} parties",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = TextSecondary
                                        )
                                    }
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        text = "${score?.highScore ?: 0}",
                                        style = TelemetryMonoStyle.copy(fontSize = 22.sp),
                                        color = VoltGreen,
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                    Text(
                                        text = "HIGH SCORE",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextSecondary
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

@Composable
private fun SettingToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
            Text(text = subtitle, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = AsphaltBlack,
                checkedTrackColor = TelemetryCyan
            )
        )
    }
}
