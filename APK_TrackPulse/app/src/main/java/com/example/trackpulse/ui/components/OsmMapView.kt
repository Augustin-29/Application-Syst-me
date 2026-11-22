package com.example.trackpulse.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CropFree
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.toColorInt
import com.example.trackpulse.data.location.HostPhoneLocation
import com.example.trackpulse.domain.model.MotionAlert
import com.example.trackpulse.domain.model.PositionHistoryPoint
import com.example.trackpulse.domain.model.TelemetrySample
import com.example.trackpulse.domain.model.TrackSessionAnalyzer
import com.example.trackpulse.ui.theme.AsphaltBlack
import com.example.trackpulse.ui.theme.CarbonBorder
import com.example.trackpulse.ui.theme.CarbonSurface
import com.example.trackpulse.ui.theme.PitAmber
import com.example.trackpulse.ui.theme.RacingRed
import com.example.trackpulse.ui.theme.TelemetryCyan
import com.example.trackpulse.ui.theme.TextPrimary
import com.example.trackpulse.ui.theme.TextSecondary
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.io.File
import kotlin.math.max
import kotlin.math.min
import android.graphics.Color as AndroidColor

private enum class MapFocusTarget {
    TRACKER,
    PHONE,
    BOTH
}

/**
 * Initialise impérativement OSMdroid (load prefs + userAgentValue = packageName + dossier cache app)
 * AVANT toute création de MapView afin de corriger définitivement l'erreur HTTP 403 OSM.
 */
private fun ensureOsmdroidInitialized(context: Context) {
    val appCtx = context.applicationContext
    val prefs = appCtx.getSharedPreferences("osmdroid_prefs", Context.MODE_PRIVATE)
    val cfg = Configuration.getInstance()
    cfg.load(appCtx, prefs)
    cfg.userAgentValue = appCtx.packageName

    val basePath = File(appCtx.cacheDir, "osmdroid").apply { mkdirs() }
    val tileCache = File(basePath, "tiles").apply { mkdirs() }
    cfg.osmdroidBasePath = basePath
    cfg.osmdroidTileCache = tileCache
}

/**
 * Carte interactive OpenStreetMap (OSMdroid, 100 % gratuit sans clé API)
 * pour le mode Antivol :
 * - Position du boîtier TrackPulse (marqueur rouge racing)
 * - Position en temps réel du téléphone hôte (marqueur bleu + MyLocationNewOverlay)
 * - Historique des trajets & marqueurs d'alertes LIS3DH
 * - Boutons flottants de centrage : Tracker / Téléphone / Les deux simultanément
 */
@Composable
fun AntiTheftOsmMap(
    currentLat: Double,
    currentLon: Double,
    hostPhoneLocation: HostPhoneLocation?,
    history: List<PositionHistoryPoint>,
    alerts: List<MotionAlert>,
    modifier: Modifier = Modifier,
    height: Dp = 290.dp
) {
    val context = LocalContext.current

    var focusTarget by remember { mutableStateOf(MapFocusTarget.TRACKER) }
    var focusTriggerNonce by remember { mutableIntStateOf(1) }
    var lastAppliedNonce by remember { mutableIntStateOf(0) }

    val trackerIcon = remember(context) {
        createPinDrawable(
            context = context,
            fillColor = "#FF2E4C".toColorInt(),
            haloColor = "#55FF2E4C".toColorInt(),
            innerDotColor = AndroidColor.WHITE
        )
    }
    val phoneIcon = remember(context) {
        createPinDrawable(
            context = context,
            fillColor = "#2979FF".toColorInt(),
            haloColor = "#552979FF".toColorInt(),
            innerDotColor = AndroidColor.WHITE
        )
    }
    val alertIcon = remember(context) {
        createPinDrawable(
            context = context,
            fillColor = "#FFB300".toColorInt(),
            haloColor = "#44FFB300".toColorInt(),
            innerDotColor = "#141517".toColorInt()
        )
    }

    val mapBundle = remember(context) {
        ensureOsmdroidInitialized(context)
        val map = MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(16.0)
        }
        val myLocationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(context), map).apply {
            enableMyLocation()
            setDrawAccuracyEnabled(true)
        }
        map to myLocationOverlay
    }
    val mapView = mapBundle.first
    val myLocationOverlay = mapBundle.second

    DisposableEffect(mapView) {
        mapView.onResume()
        onDispose {
            runCatching { myLocationOverlay.disableMyLocation() }
            mapView.onPause()
            mapView.onDetach()
        }
    }

    val distanceToPhoneMeters = remember(currentLat, currentLon, hostPhoneLocation) {
        hostPhoneLocation?.let {
            TrackSessionAnalyzer.haversineMeters(currentLat, currentLon, it.latitude, it.longitude)
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

                    // 1. Couche native MyLocationNewOverlay (position hôte système)
                    map.overlays.add(myLocationOverlay)

                    // 2. Historique des déplacements
                    if (history.size >= 2) {
                        val line = Polyline(map).apply {
                            setPoints(history.map { GeoPoint(it.latitude, it.longitude) })
                            outlinePaint.color = "#00E5FF".toColorInt()
                            outlinePaint.strokeWidth = 7f
                        }
                        map.overlays.add(line)
                    }

                    // 3. Marqueurs d'alertes mouvement LIS3DH
                    alerts.take(5).forEach { alert ->
                        val alertMarker = Marker(map).apply {
                            position = GeoPoint(alert.latitude, alert.longitude)
                            title = "Alerte LIS3DH #${alert.alertId}"
                            snippet = "%.2f G (%s)".format(alert.peakAccelG, alert.triggeredAxesLabel)
                            icon = alertIcon
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        }
                        map.overlays.add(alertMarker)
                    }

                    // 4. Marqueur bleu distinct pour le téléphone hôte (FusedLocation)
                    val phoneGeo = hostPhoneLocation?.let { GeoPoint(it.latitude, it.longitude) }
                        ?: myLocationOverlay.myLocation
                    if (phoneGeo != null) {
                        val phoneMarker = Marker(map).apply {
                            position = phoneGeo
                            title = "Téléphone hôte"
                            snippet = hostPhoneLocation?.let {
                                "Précision ±%.0f m".format(it.accuracyM)
                            } ?: "GPS smartphone actif"
                            icon = phoneIcon
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        }
                        map.overlays.add(phoneMarker)
                    }

                    // 5. Marqueur rouge racing pour le boîtier TrackPulse
                    val trackerGeo = GeoPoint(currentLat, currentLon)
                    val trackerMarker = Marker(map).apply {
                        position = trackerGeo
                        title = "Boîtier TrackPulse"
                        snippet = "%.5f° N, %.5f° E".format(currentLat, currentLon)
                        icon = trackerIcon
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    }
                    map.overlays.add(trackerMarker)

                    // 6. Centrage caméra déclenché à l'initialisation ou sur clic des boutons flottants
                    if (focusTriggerNonce != lastAppliedNonce) {
                        lastAppliedNonce = focusTriggerNonce
                        when (focusTarget) {
                            MapFocusTarget.TRACKER -> {
                                map.controller.setZoom(16.5)
                                map.controller.animateTo(trackerGeo)
                            }

                            MapFocusTarget.PHONE -> {
                                if (phoneGeo != null) {
                                    map.controller.setZoom(16.5)
                                    map.controller.animateTo(phoneGeo)
                                } else {
                                    map.controller.animateTo(trackerGeo)
                                }
                            }

                            MapFocusTarget.BOTH -> {
                                if (phoneGeo != null) {
                                    val north = max(trackerGeo.latitude, phoneGeo.latitude)
                                    val south = min(trackerGeo.latitude, phoneGeo.latitude)
                                    val east = max(trackerGeo.longitude, phoneGeo.longitude)
                                    val west = min(trackerGeo.longitude, phoneGeo.longitude)
                                    val padLat = max((north - south) * 0.28, 0.0012)
                                    val padLon = max((east - west) * 0.28, 0.0012)
                                    val box = BoundingBox(
                                        north + padLat,
                                        east + padLon,
                                        south - padLat,
                                        west - padLon
                                    )
                                    map.post {
                                        runCatching { map.zoomToBoundingBox(box, true, 72) }
                                    }
                                } else {
                                    map.controller.setZoom(16.0)
                                    map.controller.animateTo(trackerGeo)
                                }
                            }
                        }
                    }

                    map.invalidate()
                }
            )

            // Boutons flottants de centrage : Tracker / Téléphone / Les deux
            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalAlignment = Alignment.End
            ) {
                MapFocusPill(
                    label = "Tracker",
                    icon = Icons.Rounded.Radar,
                    selected = focusTarget == MapFocusTarget.TRACKER,
                    accent = RacingRed,
                    onClick = {
                        focusTarget = MapFocusTarget.TRACKER
                        focusTriggerNonce++
                    }
                )
                MapFocusPill(
                    label = "Téléphone",
                    icon = Icons.Rounded.MyLocation,
                    selected = focusTarget == MapFocusTarget.PHONE,
                    accent = TelemetryCyan,
                    enabled = hostPhoneLocation != null || myLocationOverlay.myLocation != null,
                    onClick = {
                        focusTarget = MapFocusTarget.PHONE
                        focusTriggerNonce++
                    }
                )
                MapFocusPill(
                    label = "Les deux",
                    icon = Icons.Rounded.CropFree,
                    selected = focusTarget == MapFocusTarget.BOTH,
                    accent = PitAmber,
                    enabled = hostPhoneLocation != null || myLocationOverlay.myLocation != null,
                    onClick = {
                        focusTarget = MapFocusTarget.BOTH
                        focusTriggerNonce++
                    }
                )
            }

            // Bandeau coordonnées + distance téléphone ↔ tracker
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(10.dp),
                shape = RoundedCornerShape(10.dp),
                color = AsphaltBlack.copy(alpha = 0.86f),
                border = BorderStroke(1.dp, CarbonBorder)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "%.5f° N, %.5f° E".format(currentLat, currentLon),
                        style = MaterialTheme.typography.labelSmall,
                        color = TelemetryCyan
                    )
                    if (distanceToPhoneMeters != null) {
                        Spacer(Modifier.width(8.dp))
                        val distLabel = if (distanceToPhoneMeters >= 1000.0) {
                            "• %.2f km".format(distanceToPhoneMeters / 1000.0)
                        } else {
                            "• %.0f m".format(distanceToPhoneMeters)
                        }
                        Text(
                            text = distLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = TextPrimary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MapFocusPill(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    accent: androidx.compose.ui.graphics.Color,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(20.dp),
        color = if (selected) CarbonSurface.copy(alpha = 0.96f) else AsphaltBlack.copy(alpha = 0.82f),
        border = BorderStroke(
            1.dp,
            when {
                !enabled -> CarbonBorder.copy(alpha = 0.5f)
                selected -> accent
                else -> CarbonBorder
            }
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (enabled) accent else TextSecondary.copy(alpha = 0.45f),
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(5.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = if (enabled) TextPrimary else TextSecondary.copy(alpha = 0.45f)
            )
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
    modifier: Modifier = Modifier,
    height: Dp = 260.dp
) {
    val context = LocalContext.current
    val mapView = remember(context) {
        ensureOsmdroidInitialized(context)
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(16.5)
        }
    }

    DisposableEffect(mapView) {
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

private fun createPinDrawable(
    context: Context,
    fillColor: Int,
    haloColor: Int,
    innerDotColor: Int
): BitmapDrawable {
    val density = context.resources.displayMetrics.density
    val sizePx = (32f * density).toInt().coerceAtLeast(32)
    val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val c = sizePx / 2f

    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.color = haloColor
    canvas.drawCircle(c, c, c * 0.92f, paint)

    paint.color = AndroidColor.WHITE
    canvas.drawCircle(c, c, c * 0.56f, paint)

    paint.color = fillColor
    canvas.drawCircle(c, c, c * 0.45f, paint)

    paint.color = innerDotColor
    canvas.drawCircle(c, c, c * 0.17f, paint)

    return BitmapDrawable(context.resources, bmp)
}

private fun speedRatioToAndroidColor(ratio: Float): Int {
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
