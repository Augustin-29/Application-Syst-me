package com.example.trackpulse.ui.components

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.example.trackpulse.domain.model.DeviceStatus
import com.example.trackpulse.domain.model.EPaperConfig
import com.example.trackpulse.domain.model.EPaperTemplate
import com.example.trackpulse.domain.model.EasterEggGame
import com.example.trackpulse.domain.model.TelemetrySample
import com.example.trackpulse.ui.theme.AsphaltBlack
import com.example.trackpulse.ui.theme.CarbonBorder
import com.example.trackpulse.ui.theme.CarbonElevated
import com.example.trackpulse.ui.theme.EPaperBezel
import com.example.trackpulse.ui.theme.EPaperInkBlack
import com.example.trackpulse.ui.theme.EPaperPaperWhite
import com.example.trackpulse.ui.theme.PitAmber
import com.example.trackpulse.ui.theme.RacingRed
import com.example.trackpulse.ui.theme.TelemetryCyan
import com.example.trackpulse.ui.theme.TextMuted
import com.example.trackpulse.ui.theme.TextSecondary
import com.example.trackpulse.ui.theme.VoltGreen
import kotlin.math.max

/**
 * Diagramme G-G (Friction Circle) : nuage des accélérations latérales (X) et longitudinales (Y).
 */
@Composable
fun GForceFrictionCircle(
    samples: List<TelemetrySample>,
    liveSample: TelemetrySample? = null,
    maxScaleG: Float = 2.0f,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(190.dp)
            .background(CarbonElevated, RoundedCornerShape(16.dp))
            .padding(10.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = size.minDimension / 2f - 10.dp.toPx()

            // Cercles 0.5G, 1.0G, 1.5G, 2.0G
            listOf(0.25f, 0.5f, 0.75f, 1.0f).forEach { frac ->
                drawCircle(
                    color = CarbonBorder,
                    radius = radius * frac,
                    center = center,
                    style = Stroke(width = 1.dp.toPx())
                )
            }
            // Axes X (latéral) et Y (accél / freinage)
            drawLine(CarbonBorder, Offset(center.x - radius, center.y), Offset(center.x + radius, center.y), 1.dp.toPx())
            drawLine(CarbonBorder, Offset(center.x, center.y - radius), Offset(center.x, center.y + radius), 1.dp.toPx())

            // Nuage des points de la session
            val step = max(1, samples.size / 260)
            var idx = 0
            while (idx < samples.size) {
                val s = samples[idx]
                val nx = (s.ayG / maxScaleG).coerceIn(-1f, 1f)
                val ny = (-s.axG / maxScaleG).coerceIn(-1f, 1f) // Haut = Accél, Bas = Freinage
                val pt = Offset(center.x + nx * radius, center.y + ny * radius)
                val dotColor = when {
                    s.combinedG > 1.4f -> RacingRed.copy(alpha = 0.75f)
                    s.combinedG > 0.9f -> PitAmber.copy(alpha = 0.65f)
                    else -> TelemetryCyan.copy(alpha = 0.45f)
                }
                drawCircle(dotColor, radius = 2.5.dp.toPx(), center = pt)
                idx += step
            }

            // Point temps réel si acquisition active
            liveSample?.let { s ->
                val nx = (s.ayG / maxScaleG).coerceIn(-1f, 1f)
                val ny = (-s.axG / maxScaleG).coerceIn(-1f, 1f)
                val pt = Offset(center.x + nx * radius, center.y + ny * radius)
                drawCircle(VoltGreen.copy(alpha = 0.25f), radius = 10.dp.toPx(), center = pt)
                drawCircle(VoltGreen, radius = 4.5.dp.toPx(), center = pt)
            }
        }

        Text("ACC", style = MaterialTheme.typography.labelSmall, color = VoltGreen, modifier = Modifier.align(Alignment.TopCenter))
        Text("FREIN", style = MaterialTheme.typography.labelSmall, color = RacingRed, modifier = Modifier.align(Alignment.BottomCenter))
        Text("G", style = MaterialTheme.typography.labelSmall, color = TextMuted, modifier = Modifier.align(Alignment.CenterStart))
        Text("D", style = MaterialTheme.typography.labelSmall, color = TextMuted, modifier = Modifier.align(Alignment.CenterEnd))
    }
}

/**
 * Courbe de vitesse sur la durée d'une session circuit.
 */
@Composable
fun SpeedProfileChart(
    samples: List<TelemetrySample>,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("PROFIL VITESSE (KM/H)", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            val maxV = samples.maxOfOrNull { it.speedKmh } ?: 0f
            Text("MAX %.1f km/h".format(maxV), style = MaterialTheme.typography.labelSmall, color = RacingRed)
        }
        Spacer(Modifier.height(6.dp))
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(92.dp)
                .background(CarbonElevated, RoundedCornerShape(12.dp))
                .padding(8.dp)
        ) {
            if (samples.size < 2) return@Canvas
            val maxV = max(30f, samples.maxOf { it.speedKmh } * 1.08f)
            val step = max(1, samples.size / 140)
            val pts = mutableListOf<Offset>()
            var idx = 0
            while (idx < samples.size) {
                val x = (idx.toFloat() / (samples.size - 1).toFloat()) * size.width
                val y = size.height - (samples[idx].speedKmh / maxV).coerceIn(0f, 1f) * size.height
                pts += Offset(x, y)
                idx += step
            }
            if (pts.size < 2) return@Canvas

            val linePath = Path().apply {
                moveTo(pts.first().x, pts.first().y)
                pts.drop(1).forEach { lineTo(it.x, it.y) }
            }
            val fillPath = Path().apply {
                addPath(linePath)
                lineTo(pts.last().x, size.height)
                lineTo(pts.first().x, size.height)
                close()
            }
            drawPath(
                path = fillPath,
                brush = Brush.verticalGradient(
                    listOf(RacingRed.copy(alpha = 0.35f), TelemetryCyan.copy(alpha = 0.03f))
                )
            )
            drawPath(
                path = linePath,
                brush = Brush.horizontalGradient(listOf(TelemetryCyan, VoltGreen, PitAmber, RacingRed)),
                style = Stroke(width = 2.dp.toPx())
            )
        }
    }
}

/**
 * Aperçu fidèle 1:1 (200×200 px monochrome 1-bit) de l'écran Waveshare 1,54" E-Paper V2
 * intégré dans le boîtier TrackPulse.
 */
@Composable
fun EPaperPreviewCanvas(
    config: EPaperConfig,
    status: DeviceStatus,
    selectedGame: EasterEggGame,
    highScore: Int,
    modifier: Modifier = Modifier
) {
    val bgColor = if (config.invertColors) EPaperInkBlack else EPaperPaperWhite
    val inkColor = if (config.invertColors) EPaperPaperWhite else EPaperInkBlack

    Surface(
        modifier = modifier
            .size(224.dp)
            .aspectRatio(1f),
        shape = RoundedCornerShape(18.dp),
        color = EPaperBezel,
        border = BorderStroke(2.dp, CarbonBorder)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
                .background(bgColor, RoundedCornerShape(6.dp))
                .padding(4.dp)
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val inkArgb = inkColor.toArgb()
                val bgArgb = bgColor.toArgb()

                val titlePaint = Paint().apply {
                    isAntiAlias = false
                    color = inkArgb
                    textSize = h * 0.085f
                    typeface = Typeface.MONOSPACE
                    isFakeBoldText = true
                }
                val bigPaint = Paint().apply {
                    isAntiAlias = false
                    color = inkArgb
                    textSize = h * 0.23f
                    typeface = Typeface.MONOSPACE
                    isFakeBoldText = true
                }
                val smallPaint = Paint().apply {
                    isAntiAlias = false
                    color = inkArgb
                    textSize = h * 0.068f
                    typeface = Typeface.MONOSPACE
                }
                val invPaint = Paint().apply {
                    isAntiAlias = false
                    color = bgArgb
                    textSize = h * 0.072f
                    typeface = Typeface.MONOSPACE
                    isFakeBoldText = true
                }

                // Barre d'état supérieure 1-bit
                drawRect(color = inkColor, topLeft = Offset.Zero, size = Size(w, h * 0.14f))
                val headerText = buildString {
                    append("TP ")
                    if (config.showGpsSats) append("SAT:${status.gpsSats} ")
                    if (config.showBattery) append("${status.batteryPct}%")
                }
                drawContext.canvas.nativeCanvas.drawText(headerText, w * 0.04f, h * 0.10f, invPaint)

                when (config.template) {
                    EPaperTemplate.ANTITHEFT_STATUS -> {
                        drawContext.canvas.nativeCanvas.drawText("MODE ANTIVOL", w * 0.08f, h * 0.28f, titlePaint)
                        // Icône cadenas vectorielle 1-bit
                        val lockX = w * 0.38f
                        val lockY = h * 0.44f
                        drawArc(
                            color = inkColor,
                            startAngle = 180f,
                            sweepAngle = 180f,
                            useCenter = false,
                            topLeft = Offset(lockX + w * 0.04f, lockY - h * 0.10f),
                            size = Size(w * 0.16f, h * 0.18f),
                            style = Stroke(width = 4.dp.toPx())
                        )
                        drawRoundRect(
                            color = inkColor,
                            topLeft = Offset(lockX, lockY),
                            size = Size(w * 0.24f, h * 0.22f),
                            cornerRadius = CornerRadius(4f, 4f)
                        )
                        drawContext.canvas.nativeCanvas.drawText("ARMÉ • STOP2", w * 0.16f, h * 0.78f, titlePaint)
                        drawContext.canvas.nativeCanvas.drawText("LIS3DH 10Hz ACTIF", w * 0.09f, h * 0.90f, smallPaint)
                    }

                    EPaperTemplate.TRACK_HUD -> {
                        drawContext.canvas.nativeCanvas.drawText("CIRCUIT HUD", w * 0.06f, h * 0.26f, smallPaint)
                        val spd = if (status.speedKmh > 1f) status.speedKmh.toInt() else 94
                        drawContext.canvas.nativeCanvas.drawText("%3d".format(spd), w * 0.08f, h * 0.56f, bigPaint)
                        drawContext.canvas.nativeCanvas.drawText("KM/H", w * 0.60f, h * 0.54f, titlePaint)
                        drawLine(inkColor, Offset(0f, h * 0.64f), Offset(w, h * 0.64f), 2f)
                        drawContext.canvas.nativeCanvas.drawText("MAX G : 1.78 G", w * 0.06f, h * 0.77f, titlePaint)
                        drawContext.canvas.nativeCanvas.drawText("BEST  : 0:46.210", w * 0.06f, h * 0.91f, smallPaint)
                    }

                    EPaperTemplate.CUSTOM_CARD -> {
                        drawRect(
                            color = inkColor,
                            topLeft = Offset(w * 0.04f, h * 0.20f),
                            size = Size(w * 0.92f, h * 0.74f),
                            style = Stroke(width = 2.dp.toPx())
                        )
                        drawContext.canvas.nativeCanvas.drawText(
                            config.customTitle.take(15),
                            w * 0.09f,
                            h * 0.44f,
                            titlePaint
                        )
                        drawLine(inkColor, Offset(w * 0.09f, h * 0.50f), Offset(w * 0.91f, h * 0.50f), 2f)
                        drawContext.canvas.nativeCanvas.drawText(
                            config.customSubtitle.take(18),
                            w * 0.09f,
                            h * 0.66f,
                            smallPaint
                        )
                        drawContext.canvas.nativeCanvas.drawText(
                            "BAT ${status.batteryMv} mV",
                            w * 0.09f,
                            h * 0.84f,
                            smallPaint
                        )
                    }

                    EPaperTemplate.MINIMAL_QR -> {
                        drawContext.canvas.nativeCanvas.drawText("ID: TRACKPULSE", w * 0.08f, h * 0.27f, titlePaint)
                        // Motif QR binaire stylisé 1-bit
                        val cell = w * 0.055f
                        val startX = w * 0.25f
                        val startY = h * 0.34f
                        val pattern = listOf(
                            "111010111",
                            "101001101",
                            "111011111",
                            "000101000",
                            "101110101",
                            "011001011",
                            "111010110",
                            "101101001",
                            "111011101"
                        )
                        pattern.forEachIndexed { r, row ->
                            row.forEachIndexed { c, ch ->
                                if (ch == '1') {
                                    drawRect(
                                        color = inkColor,
                                        topLeft = Offset(startX + c * cell, startY + r * cell),
                                        size = Size(cell - 1f, cell - 1f)
                                    )
                                }
                            }
                        }
                        drawContext.canvas.nativeCanvas.drawText("FLASH %.1f/8 Mo".format(status.flashUsedMb), w * 0.14f, h * 0.93f, smallPaint)
                    }

                    EPaperTemplate.EASTER_EGG -> {
                        drawContext.canvas.nativeCanvas.drawText(
                            selectedGame.title.uppercase(),
                            w * 0.06f,
                            h * 0.27f,
                            titlePaint
                        )
                        drawContext.canvas.nativeCanvas.drawText("HI:$highScore", w * 0.62f, h * 0.27f, smallPaint)
                        if (selectedGame == EasterEggGame.PULSE_RUNNER) {
                            // Sol + personnage pixel + obstacle
                            drawLine(inkColor, Offset(0f, h * 0.78f), Offset(w, h * 0.78f), 3f)
                            drawRect(inkColor, Offset(w * 0.18f, h * 0.50f), Size(w * 0.11f, h * 0.15f))
                            drawRect(inkColor, Offset(w * 0.64f, h * 0.64f), Size(w * 0.08f, h * 0.14f))
                            drawRect(inkColor, Offset(w * 0.80f, h * 0.68f), Size(w * 0.07f, h * 0.10f))
                        } else {
                            // Route 3 voies Track Dodge
                            drawLine(inkColor, Offset(w * 0.34f, h * 0.32f), Offset(w * 0.34f, h * 0.84f), 2f)
                            drawLine(inkColor, Offset(w * 0.66f, h * 0.32f), Offset(w * 0.66f, h * 0.84f), 2f)
                            // Voiture joueur voie centrale
                            drawRect(inkColor, Offset(w * 0.44f, h * 0.66f), Size(w * 0.12f, h * 0.16f))
                            // Obstacles voies gauche/droite
                            drawRect(inkColor, Offset(w * 0.12f, h * 0.38f), Size(w * 0.12f, h * 0.13f))
                            drawRect(inkColor, Offset(w * 0.76f, h * 0.52f), Size(w * 0.12f, h * 0.13f))
                        }
                        drawContext.canvas.nativeCanvas.drawText("BP1 / BP2 • TIM2", w * 0.11f, h * 0.93f, smallPaint)
                    }
                }
            }
        }
    }
}
