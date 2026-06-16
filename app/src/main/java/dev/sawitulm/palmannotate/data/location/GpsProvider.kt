package dev.sawitulm.palmannotate.data.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * GPS location provider for capture flow.
 * Port of JS CaptureFlow._getPosition (GPS acquisition).
 *
 * Uses Android LocationManager (no Google Play Services dependency).
 * High accuracy, 15s timeout, graceful null on failure.
 */
class GpsProvider(private val context: Context) {

    data class GpsLocation(
        val latitude: Double,
        val longitude: Double,
        val accuracy: Float,
    )

    /**
     * Check if location permission is granted.
     */
    fun hasPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Get the last known location (fast, may be stale).
     * Returns null if no location available or permission not granted.
     */
    fun getLastKnownLocation(): GpsLocation? {
        if (!hasPermission()) return null

        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null

        // Try GPS provider first, then network
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        var bestLocation: Location? = null

        for (provider in providers) {
            try {
                val loc = lm.getLastKnownLocation(provider) ?: continue
                if (bestLocation == null || loc.accuracy < bestLocation.accuracy) {
                    bestLocation = loc
                }
            } catch (_: SecurityException) {
                // Permission might have been revoked
            } catch (_: IllegalArgumentException) {
                // Provider not available
            }
        }

        return bestLocation?.let {
            GpsLocation(it.latitude, it.longitude, it.accuracy)
        }
    }

    /**
     * Request a fresh location fix with timeout.
     * Port of JS _getPosition (high accuracy, 15s timeout, null on failure).
     *
     * Returns null on any failure/denial/timeout — never blocks capture.
     */
    suspend fun getCurrentLocation(timeoutMs: Long = 15_000): GpsLocation? {
        if (!hasPermission()) return null

        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                if (lm == null) {
                    cont.resume(null)
                    return@suspendCancellableCoroutine
                }

                // Try to get a single update from GPS or Network provider
                val provider = when {
                    lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                    lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                    else -> null
                }

                if (provider == null) {
                    cont.resume(null)
                    return@suspendCancellableCoroutine
                }

                val listener = object : android.location.LocationListener {
                    override fun onLocationChanged(location: Location) {
                        try {
                            lm.removeUpdates(this)
                        } catch (_: Exception) {}
                        if (cont.isActive) {
                            cont.resume(GpsLocation(location.latitude, location.longitude, location.accuracy))
                        }
                    }

                    @Deprecated("Deprecated in API")
                    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {
                        try {
                            lm.removeUpdates(this)
                        } catch (_: Exception) {}
                        if (cont.isActive) {
                            cont.resume(null)
                        }
                    }
                }

                cont.invokeOnCancellation {
                    try {
                        lm.removeUpdates(listener)
                    } catch (_: Exception) {}
                }

                try {
                    lm.requestLocationUpdates(
                        provider,
                        0L,    // minTime
                        0f,    // minDistance
                        listener,
                        Looper.getMainLooper(),
                    )
                } catch (e: SecurityException) {
                    cont.resume(null)
                } catch (e: IllegalArgumentException) {
                    cont.resume(null)
                }
            }
        }
    }

    /**
     * Get GPS — tries last known first, then requests fresh if stale.
     * Convenience wrapper for the capture flow.
     */
    suspend fun getBestLocation(): GpsLocation? {
        // Try last known first (fast)
        val lastKnown = getLastKnownLocation()
        if (lastKnown != null) return lastKnown

        // Request fresh
        return getCurrentLocation()
    }
}
