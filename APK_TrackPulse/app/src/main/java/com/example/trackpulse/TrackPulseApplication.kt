package com.example.trackpulse

import android.app.Application
import org.osmdroid.config.Configuration
import java.io.File

/**
 * Point d'entrée applicatif : initialise impérativement OSMdroid (userAgentValue + cache tuiles)
 * avant toute instanciation de MapView afin d'éviter l'erreur HTTP 403 / Access blocked des serveurs OSM
 * et les restrictions Scoped Storage d'Android 10+ (API 29+).
 */
class TrackPulseApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        initOsmdroidConfiguration()
    }

    private fun initOsmdroidConfiguration() {
        val prefs = getSharedPreferences("osmdroid_prefs", MODE_PRIVATE)
        val osmConfig = Configuration.getInstance()
        osmConfig.load(applicationContext, prefs)
        osmConfig.userAgentValue = packageName

        val basePath = File(cacheDir, "osmdroid").apply { mkdirs() }
        val tileCache = File(basePath, "tiles").apply { mkdirs() }
        osmConfig.osmdroidBasePath = basePath
        osmConfig.osmdroidTileCache = tileCache
    }
}
