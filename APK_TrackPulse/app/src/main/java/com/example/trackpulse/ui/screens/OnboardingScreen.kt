package com.example.trackpulse.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.BluetoothSearching
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeveloperBoard
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.trackpulse.data.permissions.PermissionHelper
import com.example.trackpulse.domain.protocol.ParsedPairingQr
import com.example.trackpulse.domain.protocol.QrPairingParser
import com.example.trackpulse.ui.components.CarbonCard
import com.example.trackpulse.ui.components.QrCodeScannerView
import com.example.trackpulse.ui.components.QrReticleOverlay
import com.example.trackpulse.ui.components.SegmentedProgressGauge
import com.example.trackpulse.ui.theme.AsphaltBlack
import com.example.trackpulse.ui.theme.CarbonBorder
import com.example.trackpulse.ui.theme.CarbonElevated
import com.example.trackpulse.ui.theme.CarbonSurface
import com.example.trackpulse.ui.theme.EPaperInkBlack
import com.example.trackpulse.ui.theme.EPaperPaperWhite
import com.example.trackpulse.ui.theme.PitAmber
import com.example.trackpulse.ui.theme.RacingRed
import com.example.trackpulse.ui.theme.TelemetryCyan
import com.example.trackpulse.ui.theme.TelemetryMonoStyle
import com.example.trackpulse.ui.theme.TextMuted
import com.example.trackpulse.ui.theme.TextPrimary
import com.example.trackpulse.ui.theme.TextSecondary
import com.example.trackpulse.ui.theme.VoltGreen
import kotlinx.coroutines.delay

private enum class OnboardingStep {
    BOOT_ANIMATION,
    QR_PAIRING,
    SIMULATING_DETECTION,
    PAIRING_SUCCESS
}

/**
 * Écran d'initialisation au premier lancement (Onboarding animé Motorsport + Scanner QR Code CameraX/ML Kit
 * + Mode Simulateur d'appairage `TP1:SIM1:123456` + Recherche BLE directe).
 */
@Composable
fun OnboardingScreen(
    onQrPaired: (ParsedPairingQr) -> Unit,
    onSearchBluetoothWithoutQr: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var step by remember { mutableStateOf(OnboardingStep.BOOT_ANIMATION) }
    var bootProgressIndex by remember { mutableIntStateOf(0) }
    var hasCameraPermission by remember { mutableStateOf(PermissionHelper.hasCameraPermission(context)) }
    var invalidQrHint by remember { mutableStateOf<String?>(null) }
    var detectedQr by remember { mutableStateOf<ParsedPairingQr?>(null) }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    val bootModules = remember {
        listOf(
            "Initialisation noyau télémétrie TrackPulse",
            "Couche transport Hybride Auto (BLE RN4871 → 4G MQTT)",
            "Codec binaire CRC-16/CCITT • Flash NOR 8 Mo",
            "Prêt pour l'appairage QR E-Paper 200×200"
        )
    }

    // Séquence d'animation d'initialisation au lancement
    LaunchedEffect(step) {
        when (step) {
            OnboardingStep.BOOT_ANIMATION -> {
                for (i in 1..bootModules.size) {
                    delay(460L)
                    bootProgressIndex = i
                }
                delay(520L)
                step = OnboardingStep.QR_PAIRING
            }

            OnboardingStep.SIMULATING_DETECTION -> {
                delay(1_450L)
                val simulated = QrPairingParser.parse(QrPairingParser.SIMULATOR_QR_CODE)
                if (simulated != null) {
                    detectedQr = simulated
                    step = OnboardingStep.PAIRING_SUCCESS
                }
            }

            OnboardingStep.PAIRING_SUCCESS -> {
                delay(1_650L)
                detectedQr?.let { onQrPaired(it) }
            }

            OnboardingStep.QR_PAIRING -> Unit
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(AsphaltBlack, CarbonSurface, AsphaltBlack)
                )
            )
            .safeDrawingPadding()
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        AnimatedContent(
            targetState = step,
            transitionSpec = {
                (fadeIn(tween(380)) + scaleIn(initialScale = 0.96f, animationSpec = tween(380)))
                    .togetherWith(fadeOut(tween(260)))
            },
            label = "onboardingStep"
        ) { currentStep ->
            when (currentStep) {
                OnboardingStep.BOOT_ANIMATION -> {
                    BootSequenceView(
                        modules = bootModules,
                        completedCount = bootProgressIndex
                    )
                }

                OnboardingStep.QR_PAIRING -> {
                    QrPairingStepView(
                        hasCameraPermission = hasCameraPermission,
                        invalidQrHint = invalidQrHint,
                        onRequestCameraPermission = {
                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        },
                        onQrRawScanned = { raw ->
                            val parsed = QrPairingParser.parse(raw)
                            if (parsed != null) {
                                invalidQrHint = null
                                detectedQr = parsed
                                step = OnboardingStep.PAIRING_SUCCESS
                            } else {
                                invalidQrHint = "QR non reconnu ($raw). Format attendu : TP1:<ID>:<CLE>"
                            }
                        },
                        onSimulatePairing = {
                            invalidQrHint = null
                            step = OnboardingStep.SIMULATING_DETECTION
                        },
                        onSearchBluetoothDirect = onSearchBluetoothWithoutQr
                    )
                }

                OnboardingStep.SIMULATING_DETECTION -> {
                    SimulatedQrDetectionView()
                }

                OnboardingStep.PAIRING_SUCCESS -> {
                    detectedQr?.let { qr ->
                        PairingSuccessView(
                            parsed = qr,
                            onContinueNow = { onQrPaired(qr) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BootSequenceView(
    modules: List<String>,
    completedCount: Int
) {
    val infinite = rememberInfiniteTransition(label = "bootRadar")
    val pulse by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulse"
    )
    val progress by animateFloatAsState(
        targetValue = completedCount.toFloat() / modules.size.toFloat(),
        animationSpec = tween(380, easing = FastOutSlowInEasing),
        label = "bootProgress"
    )

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier.size(180.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val maxR = size.minDimension / 2f - 4.dp.toPx()

                drawCircle(CarbonBorder, maxR, center, style = Stroke(1.dp.toPx()))
                drawCircle(CarbonBorder, maxR * 0.66f, center, style = Stroke(1.dp.toPx()))
                drawCircle(
                    color = RacingRed.copy(alpha = (1f - pulse) * 0.65f),
                    radius = maxR * (0.28f + 0.72f * pulse),
                    center = center,
                    style = Stroke(3.dp.toPx())
                )
                drawCircle(
                    color = TelemetryCyan.copy(alpha = 0.14f),
                    radius = maxR * 0.56f,
                    center = center
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Rounded.Speed,
                    contentDescription = null,
                    tint = RacingRed,
                    modifier = Modifier.size(44.dp)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "TRACKPULSE",
                    style = TelemetryMonoStyle.copy(fontSize = 18.sp),
                    color = TextPrimary
                )
                Text(
                    text = "STM32 TELEMETRY",
                    style = MaterialTheme.typography.labelSmall,
                    color = TelemetryCyan
                )
            }
        }

        Spacer(Modifier.height(28.dp))
        Text(
            text = "INITIALISATION DU SYSTÈME",
            style = MaterialTheme.typography.labelMedium,
            color = TelemetryCyan
        )
        Spacer(Modifier.height(10.dp))
        SegmentedProgressGauge(
            progress = progress,
            color = RacingRed,
            modifier = Modifier.fillMaxWidth(0.85f)
        )
        Spacer(Modifier.height(20.dp))

        CarbonCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                modules.forEachIndexed { index, label ->
                    val done = index < completedCount
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (done) Icons.Rounded.CheckCircle else Icons.Rounded.DeveloperBoard,
                            contentDescription = null,
                            tint = if (done) VoltGreen else TextMuted,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (done) TextPrimary else TextMuted
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QrPairingStepView(
    hasCameraPermission: Boolean,
    invalidQrHint: String?,
    onRequestCameraPermission: () -> Unit,
    onQrRawScanned: (String) -> Unit,
    onSimulatePairing: () -> Unit,
    onSearchBluetoothDirect: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // En-tête appairage
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.QrCodeScanner,
                    contentDescription = null,
                    tint = RacingRed,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "APPAIRAGE TRACKPULSE",
                    style = MaterialTheme.typography.labelMedium,
                    color = RacingRed
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Scannez le QR Code affiché sur l'écran E-Paper",
                style = MaterialTheme.typography.headlineSmall,
                color = TextPrimary
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Format compact Waveshare 1,54\" (200×200) : TP1:<ID>:<CLÉ> — configure automatiquement le BLE RN4871 et les topics MQTT 4G.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
        }

        // Zone Scanner CameraX + ML Kit (ou demande d'autorisation Caméra)
        if (hasCameraPermission) {
            QrCodeScannerView(
                onQrDetected = onQrRawScanned,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(265.dp)
            )
        } else {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(235.dp),
                shape = RoundedCornerShape(18.dp),
                color = CarbonSurface,
                border = BorderStroke(1.dp, CarbonBorder)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CameraAlt,
                        contentDescription = null,
                        tint = TelemetryCyan,
                        modifier = Modifier.size(42.dp)
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "Accès caméra requis pour scanner le QR Code E-Paper",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = onRequestCameraPermission,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = TelemetryCyan,
                            contentColor = AsphaltBlack
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Autoriser la caméra", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }

        AnimatedVisibility(visible = invalidQrHint != null) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = RacingRed.copy(alpha = 0.16f),
                border = BorderStroke(1.dp, RacingRed)
            ) {
                Text(
                    text = invalidQrHint.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = TextPrimary,
                    modifier = Modifier.padding(10.dp)
                )
            }
        }

        // Bouton Mode Simulateur d'appairage (indispensable pour les tests sans firmware STM32)
        CarbonCard(borderColor = PitAmber.copy(alpha = 0.65f)) {
            Text(
                text = "FIRMWARE STM32 NON ENCORE FLASHÉ ?",
                style = MaterialTheme.typography.labelSmall,
                color = PitAmber
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Lancez la séquence de détection complète avec le QR Code virtuel TP1:SIM1:123456 pour tester l'ensemble de l'application.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onSimulatePairing,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics(mergeDescendants = true) {},
                colors = ButtonDefaults.buttonColors(
                    containerColor = PitAmber,
                    contentColor = AsphaltBlack
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Rounded.Science, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Simuler l'appairage (Test sans boîtier)",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.ExtraBold
                )
            }
        }

        // Bouton Recherche Bluetooth sans QR code (Hybride Auto par défaut)
        OutlinedButton(
            onClick = onSearchBluetoothDirect,
            modifier = Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {},
            border = BorderStroke(1.dp, TelemetryCyan),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(
                Icons.AutoMirrored.Rounded.BluetoothSearching,
                contentDescription = null,
                tint = TelemetryCyan,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Rechercher en Bluetooth sans QR code",
                style = MaterialTheme.typography.labelMedium,
                color = TelemetryCyan
            )
        }
    }
}

@Composable
private fun SimulatedQrDetectionView() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "SIMULATION LECTURE E-PAPER 200×200",
            style = MaterialTheme.typography.labelMedium,
            color = PitAmber
        )
        Spacer(Modifier.height(18.dp))

        Box(
            modifier = Modifier
                .size(250.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(CarbonElevated),
            contentAlignment = Alignment.Center
        ) {
            // Simulation d'un QR Code Version 1 affiché sur dalle E-Paper Waveshare
            Canvas(
                modifier = Modifier
                    .size(144.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(EPaperPaperWhite)
                    .padding(12.dp)
            ) {
                val pattern = listOf(
                    "111111101010101111111",
                    "100000100110001000001",
                    "101110101011001011101",
                    "101110100010101011101",
                    "101110101101001011101",
                    "100000101000101000001",
                    "111111101010101111111",
                    "000000001100100000000",
                    "101101110110110101101",
                    "010010001001001010010",
                    "110111101101011011011",
                    "001000010010100100100",
                    "101110111011011101110",
                    "000000001010110010011",
                    "111111100101011011001",
                    "100000101100100100110",
                    "101110101011101110101",
                    "101110100110010011010",
                    "101110101101101101101",
                    "100000100010010010010",
                    "111111101101101110111"
                )
                val cell = size.width / pattern.size
                pattern.forEachIndexed { r, row ->
                    row.forEachIndexed { c, ch ->
                        if (ch == '1') {
                            drawRect(
                                color = EPaperInkBlack,
                                topLeft = Offset(c * cell, r * cell),
                                size = Size(cell, cell)
                            )
                        }
                    }
                }
            }

            QrReticleOverlay(
                accentColor = PitAmber,
                laserColor = VoltGreen,
                modifier = Modifier.fillMaxSize()
            )
        }

        Spacer(Modifier.height(18.dp))
        Text(
            text = QrPairingParser.SIMULATOR_QR_CODE,
            style = TelemetryMonoStyle.copy(fontSize = 20.sp),
            color = VoltGreen
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Extraction identifiant BLE & topics MQTT en cours…",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )
    }
}

@Composable
private fun PairingSuccessView(
    parsed: ParsedPairingQr,
    onContinueNow: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Rounded.CheckCircle,
            contentDescription = null,
            tint = VoltGreen,
            modifier = Modifier.size(64.dp)
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "BOÎTIER APPAIRÉ",
            style = MaterialTheme.typography.headlineSmall,
            color = TextPrimary,
            fontWeight = FontWeight.ExtraBold
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (parsed.isSimulatedDevice) {
                "Mode Simulateur Matériel activé pour les tests"
            } else {
                "Connexion Hybride Auto (BLE prioritaire → 4G MQTT)"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = TelemetryCyan,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(18.dp))
        CarbonCard(borderColor = VoltGreen.copy(alpha = 0.6f)) {
            PairingDetailRow("QR Code lu", parsed.rawValue)
            PairingDetailRow("ID Boîtier", parsed.deviceId)
            PairingDetailRow("Nom BLE RN4871", parsed.bleNameFilter)
            PairingDetailRow("Topics MQTT 4G", "${parsed.uplinkTopic}  /  ${parsed.downlinkTopic}")
            PairingDetailRow("Clé appairage", parsed.pairingKey)
        }

        Spacer(Modifier.height(18.dp))
        Button(
            onClick = onContinueNow,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = VoltGreen,
                contentColor = AsphaltBlack
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("Accéder au Tableau de bord", style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun PairingDetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        Text(text = value, style = TelemetryMonoStyle.copy(fontSize = 12.sp), color = TextPrimary)
    }
}
