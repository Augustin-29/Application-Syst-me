package com.example.trackpulse.ui.components

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.trackpulse.domain.model.MotionAlert
import com.example.trackpulse.domain.model.PositionHistoryPoint
import com.example.trackpulse.domain.model.TelemetrySample
import com.example.trackpulse.ui.theme.AsphaltBlack
import com.example.trackpulse.ui.theme.CarbonBorder
import com.example.trackpulse.ui.theme.TelemetryCyan
import com.example.trackpulse.ui.theme.TextSecondary
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import kotlin.math.max
import kotlin.math.min

/**
 * Carte interactive OpenStreetMap (OSMdroid, 100 % gratuit sans clé API)
 * pour le mode Antivol : dernière position connue, historique de trajets et marqueurs d'alertes.
 */
@Composable
fun AntiTheftOsmMap(
    currentLat: Double,
    currentLon: Double,
    history: List<PositionHistoryPoint>,
    alerts: List<MotionAlert>,
    height: Dp = 270.dp,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val mapView = remember {
        Configuration.getInstance().userAgentValue = context.packageName
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(16.0)
        }
    }

    DisposableEffect(Unit) {
        mapView.onResume()
        onDispose {
            mapView.onPause()
            mapView.onDetach()
        }
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(height),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, CarbonBorder),
        color = AsphaltBlack
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                factory = { mapView },
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(16.dp)),
                update = { map ->
                    map.overlays.clear()

                    if (history.size >= 2) {
                        val line = Polyline(map).apply {
                            setPoints(history.map { GeoPoint(it.latitude, it.longitude) })
                            outlinePaint.color = AndroidColor.parseColor("#00E5FF")
                            outlinePaint.strokeWidth = 8f
                        }
                        map.overlays.add(line)
                    }

                    alerts.take(5).forEach { alert ->
                        val alertMarker = Marker(map).apply {
                            position = GeoPoint(alert.latitude, alert.longitude)
                            title = "Alerte LIS3DH #${alert.alertId}"
                            snippet = "%.2f G (%s)".format(alert.peakAccelG, alert.triggeredAxesLabel)
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        }
                        map.overlays.add(alertMarker)
                    }

                    val currentGeo = GeoPoint(currentLat, currentLon)
                    val trackerMarker = Marker(map).apply {
                        position = currentGeo
                        title = "TrackPulse"
                        snippet = "%.5f, %.5f".format(currentLat, currentLon)
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    }
                    map.overlays.add(trackerMarker)
                    map.controller.animateTo(currentGeo)
                    map.invalidate()
                }
            )

            Surface(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(10.dp),
                shape = RoundedCornerShape(8.dp),
                color = AsphaltBlack.copy(alpha = 0.82f),
                border = BorderStroke(1.dp, CarbonBorder)
            ) {
                Text(
                    text = "OSM • %.5f° N, %.5f° E".format(currentLat, currentLon),
                    style = MaterialTheme.typography.labelSmall,
                    color = TelemetryCyan,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
}

/**
 * Carte OpenStreetMap dédiée au mode Circuit :
 * affiche la trajectoire GPS découpée en segments avec dégradé de couleur selon la vitesse (Cyan → Vert → Ambre → Rouge).
 */
@Composable
fun TrackSpeedOsmMap(
    samples: List<TelemetrySample>,
    height: Dp = 260.dp,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val mapView = remember {
        Configuration.getInstance().userAgentValue = context.packageName
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(16.5)
        }
    }

    DisposableEffect(Unit) {
        mapView.onResume()
        onDispose {
            mapView.onPause()
            mapView.onDetach()
        }
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(height),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, CarbonBorder),
        color = AsphaltBlack
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (samples.size < 2) {
                Text(
                    text = "Téléchargez ou démarrez une session circuit pour afficher la trajectoire.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp)
                )
            } else {
                AndroidView(
                    factory = { mapView },
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(16.dp)),
                    update = { map ->
                        map.overlays.clear()

                        // Sous-échantillonnage léger pour maintenir 60 FPS sur des sessions longues
                        val step = max(1, samples.size / 220)
                        val minSpeed = samples.minOf { it.speedKmh }
                        val maxSpeed = max(minSpeed + 1f, samples.maxOf { it.speedKmh })

                        var north = -90.0
                        var south = 90.0
                        var east = -180.0
                        var west = 180.0

                        var i = 0
                        while (i + step < samples.size) {
                            val a = samples[i]
                            val b = samples[i + step]
                            north = max(north, max(a.latitude, b.latitude))
                            south = min(south, min(a.latitude, b.latitude))
                            east = max(east, max(a.longitude, b.longitude))
                            west = min(west, min(a.longitude, b.longitude))

                            val ratio = (((a.speedKmh + b.speedKmh) * 0.5f - minSpeed) / (maxSpeed - minSpeed))
                                .coerceIn(0f, 1f)
                            val segLine = Polyline(map).apply {
                                setPoints(listOf(GeoPoint(a.latitude, a.longitude), GeoPoint(b.latitude, b.longitude)))
                                outlinePaint.color = speedRatioToAndroidColor(ratio)
                                outlinePaint.strokeWidth = 10f
                            }
                            map.overlays.add(segLine)
                            i += step
                        }

                        // Marqueur ligne départ / arrivée
                        val start = samples.first()
                        val startMarker = Marker(map).apply {
                            position = GeoPoint(start.latitude, start.longitude)
                            title = "Départ / Arrivée"
                            snippet = "Vmax %.1f km/h".format(maxSpeed)
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        }
                        map.overlays.add(startMarker)

                        if (north > south && east > west) {
                            val padLat = (north - south) * 0.18 + 0.0003
                            val padLon = (east - west) * 0.18 + 0.0003
                            val box = BoundingBox(
                                north + padLat,
                                east + padLon,
                                south - padLat,
                                west - padLon
                            )
                            map.post {
                                runCatching { map.zoomToBoundingBox(box, false) }
                            }
                        }
                        map.invalidate()
                    }
                )
            }
        }
    }
}

private fun speedRatioToAndroidColor(ratio: Float): Int {
    // 0.0 = Cyan (#00E5FF) -> 0.45 = Vert (#00E676) -> 0.75 = Ambre (#FFB300) -> 1.0 = Rouge Racing (#FF2E4C)
    return when {
        ratio < 0.45f -> lerpColor(0x00, 0xE5, 0xFF, 0x00, 0xE6, 0x76, ratio / 0.45f)
        ratio < 0.75f -> lerpColor(0x00, 0xE6, 0x76, 0xFF, 0xB3, 0x00, (ratio - 0.45f) / 0.30f)
        else -> lerpColor(0xFF, 0xB3, 0x00, 0xFF, 0x2E, 0x4C, (ratio - 0.75f) / 0.25f)
    }
}

private fun lerpColor(r1: Int, g1: Int, b1: Int, r2: Int, g2: Int, b2: Int, t: Float): Int {
    val c = t.coerceIn(0f, 1f)
    val r = (r1 + (r2 - r1) * c).toInt()
    val g = (g1 + (g2 - g1) * c).toInt()
    val b = (b1 + (b2 - b1) * c).toInt()
    return AndroidColor.rgb(r, g, b)
}
