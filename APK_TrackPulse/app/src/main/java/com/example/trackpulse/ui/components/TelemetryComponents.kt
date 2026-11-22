package com.example.trackpulse.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BluetoothConnected
import androidx.compose.material.icons.rounded.CellTower
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.DeveloperBoard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.trackpulse.domain.model.ConnectionChannel
import com.example.trackpulse.ui.theme.CarbonBorder
import com.example.trackpulse.ui.theme.CarbonElevated
import com.example.trackpulse.ui.theme.CarbonSurface
import com.example.trackpulse.ui.theme.PitAmber
import com.example.trackpulse.ui.theme.RacingRed
import com.example.trackpulse.ui.theme.TelemetryCyan
import com.example.trackpulse.ui.theme.TelemetryMonoStyle
import com.example.trackpulse.ui.theme.TextMuted
import com.example.trackpulse.ui.theme.TextPrimary
import com.example.trackpulse.ui.theme.TextSecondary
import com.example.trackpulse.ui.theme.VoltGreen

@Composable
fun CarbonCard(
    modifier: Modifier = Modifier,
    borderColor: Color = CarbonBorder,
    containerColor: Color = CarbonSurface,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = containerColor,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            content = content
        )
    }
}

@Composable
fun SectionHeader(
    title: String,
    subtitle: String? = null,
    accentColor: Color = TelemetryCyan,
    trailing: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(20.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(accentColor)
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    text = title.uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.ExtraBold
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                        fontSize = 11.sp
                    )
                }
            }
        }
        trailing?.invoke()
    }
}

@Composable
fun ConnectionChannelBadge(
    channel: ConnectionChannel,
    rssiDbm: Int?,
    modifier: Modifier = Modifier
) {
    val (bg, fg, icon, text) = when (channel) {
        ConnectionChannel.BLE -> Quad(
            TelemetryCyan.copy(alpha = 0.16f),
            TelemetryCyan,
            Icons.Rounded.BluetoothConnected,
            if (rssiDbm != null) "BLE $rssiDbm dBm" else "BLE"
        )
        ConnectionChannel.LTE_MQTT -> Quad(
            PitAmber.copy(alpha = 0.16f),
            PitAmber,
            Icons.Rounded.CellTower,
            "4G MQTT"
        )
        ConnectionChannel.SIMULATED -> Quad(
            VoltGreen.copy(alpha = 0.15f),
            VoltGreen,
            Icons.Rounded.DeveloperBoard,
            if (rssiDbm != null) "SIM BLE $rssiDbm dBm" else "SIM 4G"
        )
        ConnectionChannel.DISCONNECTED -> Quad(
            RacingRed.copy(alpha = 0.16f),
            RacingRed,
            Icons.Rounded.CloudOff,
            "Hors ligne"
        )
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = bg,
        border = BorderStroke(1.dp, fg.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(5.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = fg,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun MetricTile(
    label: String,
    value: String,
    unit: String = "",
    accentColor: Color = TelemetryCyan,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = CarbonElevated,
        border = BorderStroke(1.dp, CarbonBorder)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(5.dp))
                }
                Text(
                    text = label.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = value,
                    style = TelemetryMonoStyle.copy(fontSize = 20.sp),
                    color = TextPrimary
                )
                if (unit.isNotEmpty()) {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = unit,
                        style = MaterialTheme.typography.labelSmall,
                        color = accentColor,
                        modifier = Modifier.padding(bottom = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun SegmentedProgressGauge(
    progress: Float,
    color: Color,
    modifier: Modifier = Modifier
) {
    val clamped = progress.coerceIn(0f, 1f)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(CarbonElevated)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(clamped)
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(
                    Brush.horizontalGradient(
                        listOf(color.copy(alpha = 0.65f), color)
                    )
                )
        )
    }
}

@Composable
fun ProximityRadar(
    rssiDbm: Int?,
    proximityStrength: Float,
    estimatedDistanceMeters: Float?,
    isBuzzerActive: Boolean,
    modifier: Modifier = Modifier
) {
    val infinite = rememberInfiniteTransition(label = "radarPulse")
    val pulse by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (isBuzzerActive) 950 else 1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulseRadius"
    )

    val ringColor = when {
        rssiDbm == null -> TextMuted
        proximityStrength > 0.72f -> VoltGreen
        proximityStrength > 0.42f -> TelemetryCyan
        else -> PitAmber
    }

    Box(
        modifier = modifier.size(164.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val maxR = size.minDimension / 2f - 4.dp.toPx()

            // Cercles radar concentriques
            listOf(0.33f, 0.66f, 1f).forEach { factor ->
                drawCircle(
                    color = CarbonBorder,
                    radius = maxR * factor,
                    center = center,
                    style = Stroke(width = 1.dp.toPx())
                )
            }

            // Onde dynamique si signal BLE actif
            if (rssiDbm != null) {
                drawCircle(
                    color = ringColor.copy(alpha = (1f - pulse) * 0.55f),
                    radius = maxR * (0.2f + 0.8f * pulse),
                    center = center,
                    style = Stroke(width = 2.5.dp.toPx())
                )
                // Anneau de force de proximité
                drawCircle(
                    color = ringColor.copy(alpha = 0.22f),
                    radius = maxR * (0.25f + 0.7f * proximityStrength),
                    center = center
                )
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(if (isBuzzerActive) RacingRed else ringColor)
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = if (rssiDbm != null) "$rssiDbm dBm" else "Hors BLE",
                style = TelemetryMonoStyle.copy(fontSize = 16.sp),
                color = TextPrimary
            )
            Text(
                text = if (estimatedDistanceMeters != null) {
                    "≈ %.1f m".format(estimatedDistanceMeters)
                } else {
                    "Signal 4G actif"
                },
                style = MaterialTheme.typography.labelSmall,
                color = ringColor
            )
        }
    }
}

private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
