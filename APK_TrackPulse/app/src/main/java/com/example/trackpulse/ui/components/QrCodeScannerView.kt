package com.example.trackpulse.ui.components

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FlashOff
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.trackpulse.ui.theme.AsphaltBlack
import com.example.trackpulse.ui.theme.CarbonBorder
import com.example.trackpulse.ui.theme.RacingRed
import com.example.trackpulse.ui.theme.TelemetryCyan
import com.example.trackpulse.ui.theme.TextPrimary
import com.example.trackpulse.ui.theme.VoltGreen
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode

/**
 * Scanner QR Code CameraX + ML Kit (MlKitAnalyzer) calibré pour lire le QR Code compact
 * Version 1/2 affiché sur la dalle Waveshare 1,54" E-Paper V2 (200×200 px).
 */
@Composable
fun QrCodeScannerView(
    onQrDetected: (String) -> Unit,
    modifier: Modifier = Modifier,
    isLockedOn: Boolean = false
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var torchEnabled by remember { mutableStateOf(false) }

    val barcodeScanner = remember {
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        BarcodeScanning.getClient(options)
    }

    val cameraController = remember(context) {
        LifecycleCameraController(context).apply {
            cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
        }
    }

    DisposableEffect(lifecycleOwner, cameraController, barcodeScanner) {
        val mainExecutor = ContextCompat.getMainExecutor(context)
        val analyzer = MlKitAnalyzer(
            listOf(barcodeScanner),
            ImageAnalysis.COORDINATE_SYSTEM_VIEW_REFERENCED,
            mainExecutor
        ) { result ->
            val barcodes = result.getValue(barcodeScanner) ?: return@MlKitAnalyzer
            val raw = barcodes.firstOrNull { !it.rawValue.isNullOrBlank() }?.rawValue ?: return@MlKitAnalyzer
            onQrDetected(raw)
        }

        cameraController.setImageAnalysisAnalyzer(mainExecutor, analyzer)
        runCatching { cameraController.bindToLifecycle(lifecycleOwner) }

        onDispose {
            runCatching {
                cameraController.clearImageAnalysisAnalyzer()
                cameraController.unbind()
            }
            runCatching { barcodeScanner.close() }
        }
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = AsphaltBlack,
        border = BorderStroke(1.5.dp, if (isLockedOn) VoltGreen else TelemetryCyan.copy(alpha = 0.65f))
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        controller = cameraController
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(18.dp))
            )

            // Réticule animé carré (proportion 1:1 de l'écran E-Paper 200×200)
            QrReticleOverlay(
                accentColor = if (isLockedOn) VoltGreen else TelemetryCyan,
                laserColor = if (isLockedOn) VoltGreen else RacingRed,
                modifier = Modifier.fillMaxSize()
            )

            // Bouton Torche (utile pour lire la dalle E-Paper sans rétroéclairage en atelier/garage)
            IconButton(
                onClick = {
                    torchEnabled = !torchEnabled
                    cameraController.enableTorch(torchEnabled)
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp)
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(AsphaltBlack.copy(alpha = 0.72f))
                    .semantics(mergeDescendants = true) {}
            ) {
                Icon(
                    imageVector = if (torchEnabled) Icons.Rounded.FlashOn else Icons.Rounded.FlashOff,
                    contentDescription = if (torchEnabled) "Couper la torche" else "Activer la torche",
                    tint = if (torchEnabled) TelemetryCyan else TextPrimary
                )
            }
        }
    }
}

@Composable
fun QrReticleOverlay(
    accentColor: Color,
    laserColor: Color,
    modifier: Modifier = Modifier
) {
    val infinite = rememberInfiniteTransition(label = "qrLaser")
    val scanProgress by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1650, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scanLineY"
    )

    Canvas(modifier = modifier) {
        val boxSize = size.minDimension * 0.68f
        val left = (size.width - boxSize) / 2f
        val top = (size.height - boxSize) / 2f
        val right = left + boxSize
        val bottom = top + boxSize
        val cornerLen = boxSize * 0.16f
        val stroke = 3.5.dp.toPx()

        // Coins du viseur carré E-Paper
        drawLine(accentColor, Offset(left, top), Offset(left + cornerLen, top), stroke, StrokeCap.Round)
        drawLine(accentColor, Offset(left, top), Offset(left, top + cornerLen), stroke, StrokeCap.Round)

        drawLine(accentColor, Offset(right - cornerLen, top), Offset(right, top), stroke, StrokeCap.Round)
        drawLine(accentColor, Offset(right, top), Offset(right, top + cornerLen), stroke, StrokeCap.Round)

        drawLine(accentColor, Offset(left, bottom), Offset(left + cornerLen, bottom), stroke, StrokeCap.Round)
        drawLine(accentColor, Offset(left, bottom - cornerLen), Offset(left, bottom), stroke, StrokeCap.Round)

        drawLine(accentColor, Offset(right - cornerLen, bottom), Offset(right, bottom), stroke, StrokeCap.Round)
        drawLine(accentColor, Offset(right, bottom - cornerLen), Offset(right, bottom), stroke, StrokeCap.Round)

        // Ligne laser télémétrique
        val laserY = top + 8.dp.toPx() + (boxSize - 16.dp.toPx()) * scanProgress
        drawLine(
            color = laserColor.copy(alpha = 0.85f),
            start = Offset(left + 10.dp.toPx(), laserY),
            end = Offset(right - 10.dp.toPx(), laserY),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round
        )

        // Cadre discret interne
        drawRect(
            color = CarbonBorder.copy(alpha = 0.35f),
            topLeft = Offset(left, top),
            size = androidx.compose.ui.geometry.Size(boxSize, boxSize),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx())
        )
    }
}
