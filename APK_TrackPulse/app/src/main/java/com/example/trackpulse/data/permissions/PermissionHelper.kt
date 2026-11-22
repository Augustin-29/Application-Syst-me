package com.example.trackpulse.data.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Gestionnaire de permissions hybride compatible Android 10–11 (API 29–30) et Android 12+ (API 31+).
 */
object PermissionHelper {

    /** Permissions nécessaires pour le scan/connexion BLE et la localisation smartphone (A-GPS + carte). */
    fun requiredCorePermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        }

    fun hasBlePermissions(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            isGranted(context, Manifest.permission.BLUETOOTH_SCAN) &&
                isGranted(context, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            // Sur Android 10 et 11 (API 29-30), ACCESS_FINE_LOCATION est requis pour le scan BLE
            // tandis que BLUETOOTH et BLUETOOTH_ADMIN sont des permissions normales accordées à l'installation.
            isGranted(context, Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    fun hasLocationPermission(context: Context): Boolean =
        isGranted(context, Manifest.permission.ACCESS_FINE_LOCATION) ||
            isGranted(context, Manifest.permission.ACCESS_COARSE_LOCATION)

    fun hasCameraPermission(context: Context): Boolean =
        isGranted(context, Manifest.permission.CAMERA)

    private fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
