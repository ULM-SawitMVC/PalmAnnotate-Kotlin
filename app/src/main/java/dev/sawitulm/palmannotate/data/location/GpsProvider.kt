package dev.sawitulm.palmannotate.data.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import dev.sawitulm.palmannotate.domain.model.GpsFreshnessPolicy
import dev.sawitulm.palmannotate.domain.model.GpsProvenance
import dev.sawitulm.palmannotate.domain.model.GpsSource
import dev.sawitulm.palmannotate.domain.model.GpsStatus
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * GPS location provider for capture flow.
 * Port of JS CaptureFlow._getPosition (GPS acquisition).
 *
 * Uses Android LocationManager (no Google Play Services dependency).
 * High accuracy, 15s timeout, graceful degradation — capture is never blocked.
 *
 * WS-13: [bestProvenance] replaced the old `getBestLocation(): GpsLocation?`. The old signature
 * could not express "this is a real coordinate but it is 40 minutes old", so its stale
 * last-known fallback was indistinguishable from a fresh fix at the call site.
 */
class GpsProvider(private val context: Context) {

    data class GpsLocation(
        val latitude: Double,
        val longitude: Double,
        val accuracy: Float,
        val timestampMillis: Long,
        val provider: String = "",
    )

    companion object {
        private const val MAX_LAST_KNOWN_AGE_MS = GpsFreshnessPolicy.MAX_FIX_AGE_MS

        internal fun isFresh(timestampMillis: Long, nowMillis: Long): Boolean =
            GpsFreshnessPolicy.classify(timestampMillis, nowMillis, MAX_LAST_KNOWN_AGE_MS) ==
                GpsFreshnessPolicy.Freshness.FRESH
    }

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
     * Whether the device has at least one location provider (GPS or network) turned on.
     * Lets the capture flow distinguish "Location is off" from "no fix yet" / "permission denied".
     */
    fun isLocationEnabled(): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        val gpsOn = try { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) } catch (_: Exception) { false }
        val netOn = try { lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) } catch (_: Exception) { false }
        return gpsOn || netOn
    }

    /**
     * Get the last known location (fast, may be stale).
     * Returns null if no location available or permission not granted.
     */
    fun getLastKnownLocation(): GpsLocation? {
        if (!hasPermission()) return null

        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null

        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        val now = System.currentTimeMillis()
        val candidates = providers.mapNotNull { provider ->
            try {
                lm.getLastKnownLocation(provider)
            } catch (_: SecurityException) {
                null // Permission might have been revoked.
            } catch (_: IllegalArgumentException) {
                null // Provider not available.
            }
        }
        // A fresh fix is always more useful than a stale but slightly more accurate one.
        val bestLocation = candidates.minWithOrNull(
            compareBy<Location> { !isFresh(it.time, now) }
                .thenBy { it.accuracy.takeIf(Float::isFinite) ?: Float.MAX_VALUE },
        )

        return bestLocation?.let {
            GpsLocation(it.latitude, it.longitude, it.accuracy, it.time, it.provider ?: "")
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
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { provider -> runCatching { lm.isProviderEnabled(provider) }.getOrDefault(false) }
        if (providers.isEmpty()) return null

        val perProviderTimeout = (timeoutMs / providers.size).coerceAtLeast(1_000L)
        for (provider in providers) {
            requestCurrentLocation(lm, provider, perProviderTimeout)?.let { return it }
        }
        return null
    }

    private suspend fun requestCurrentLocation(
        lm: LocationManager,
        provider: String,
        timeoutMs: Long,
    ): GpsLocation? = withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine { cont ->
            val listener = object : android.location.LocationListener {
                override fun onLocationChanged(location: Location) {
                    runCatching { lm.removeUpdates(this) }
                    if (cont.isActive) {
                        cont.resume(
                            GpsLocation(
                                location.latitude,
                                location.longitude,
                                location.accuracy,
                                location.time,
                                location.provider ?: provider,
                            )
                        )
                    }
                }

                @Deprecated("Deprecated in API")
                override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {
                    runCatching { lm.removeUpdates(this) }
                    if (cont.isActive) cont.resume(null)
                }
            }

            cont.invokeOnCancellation { runCatching { lm.removeUpdates(listener) } }
            try {
                lm.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
            } catch (_: SecurityException) {
                cont.resume(null)
            } catch (_: IllegalArgumentException) {
                cont.resume(null)
            }
        }
    }

    /**
     * WS-13 — the capture flow's single entry point. Always returns a record, never a bare
     * coordinate, and never claims freshness it cannot prove.
     *
     * Order of preference:
     *   1. a last-known fix that is still inside the freshness window (free, instant);
     *   2. otherwise a fresh single-shot request;
     *   3. otherwise the last-known fix, reported as [GpsStatus.STALE] WITH its real age.
     *
     * Step 3 is where the old implementation silently lied: it returned that coordinate through
     * the same type as a fresh one. Here the coordinate is still carried — losing a real
     * measurement helps nobody — but the status makes it unusable as a fresh fix, and
     * [GpsProvenance.publishableCoordinates] keeps it out of the unqualified lat/lng fields.
     *
     * Failure is always a status, never an exception: the operator must be able to keep
     * collecting with an honest UNAVAILABLE/PERMISSION_DENIED/LOCATION_OFF record.
     */
    suspend fun bestProvenance(nowMillis: Long = System.currentTimeMillis()): GpsProvenance {
        if (!hasPermission()) return GpsProvenance.unavailable(GpsStatus.PERMISSION_DENIED)

        val lastKnown = getLastKnownLocation()
        if (lastKnown != null && isFresh(lastKnown.timestampMillis, nowMillis)) {
            return lastKnown.toProvenance(GpsSource.LAST_KNOWN, nowMillis)
        }

        val current = getCurrentLocation()
        if (current != null) {
            return current.toProvenance(GpsSource.CURRENT_FIX, System.currentTimeMillis())
        }

        if (lastKnown != null) {
            // Real coordinate, known age, explicitly not fresh.
            return lastKnown.toProvenance(GpsSource.LAST_KNOWN, System.currentTimeMillis())
                .let { if (it.isFresh) it.copy(status = GpsStatus.STALE) else it }
        }

        return GpsProvenance.unavailable(
            if (!isLocationEnabled()) GpsStatus.LOCATION_OFF else GpsStatus.UNAVAILABLE,
        )
    }

    private fun GpsLocation.toProvenance(source: GpsSource, nowMillis: Long): GpsProvenance =
        GpsFreshnessPolicy.forFix(
            latitude = latitude,
            longitude = longitude,
            accuracyM = accuracy.takeIf { it > 0f },
            fixTimeMillis = timestampMillis,
            provider = provider,
            source = source,
            nowMillis = nowMillis,
            maxAgeMs = MAX_LAST_KNOWN_AGE_MS,
        )
}
