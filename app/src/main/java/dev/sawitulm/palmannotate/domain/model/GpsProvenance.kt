package dev.sawitulm.palmannotate.domain.model

/**
 * WS-13 — how a coordinate got into a package, and whether it can be trusted as "where this
 * tree is".
 *
 * The 27 Jul 2026 packages each carry ONE coordinate repeated across 42 (resp. 90) trees,
 * because `getBestLocation()` fell back to an unbounded-age last-known fix and the caller
 * persisted only lat/lng. There was no field in the output that could tell the ML engineer that
 * those 42 rows are one fix from the start of the day rather than 42 measurements.
 *
 * [GpsStatus] is therefore part of the data, not a UI detail: a coordinate is only ever
 * published as a plain `lat`/`lng` pair when its status is [GpsStatus.FRESH].
 */
enum class GpsStatus {
    /** A fix whose own timestamp is within the freshness window at commit time. */
    FRESH,

    /** A real fix, but older than the window. Coordinates are kept, never claimed as current. */
    STALE,

    /** Location services reported nothing usable (no signal / timeout). */
    UNAVAILABLE,

    /** The location permission is not granted. */
    PERMISSION_DENIED,

    /** Every location provider is switched off on the device. */
    LOCATION_OFF,

    /** Legacy package, or one whose sidecar predates provenance. Never invented. */
    UNKNOWN;

    val hasCoordinates: Boolean get() = this == FRESH || this == STALE || this == UNKNOWN

    companion object {
        fun fromPersisted(raw: String?): GpsStatus =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: UNKNOWN
    }
}

/** Which query produced the fix. */
enum class GpsSource {
    /** A fresh single-shot request served by the OS during this capture. */
    CURRENT_FIX,

    /** The provider's cached last-known fix. */
    LAST_KNOWN,

    /** Coordinates recovered from a legacy sidecar that recorded no source. */
    LEGACY_SIDECAR,

    /** No coordinate at all. */
    NONE;

    companion object {
        fun fromPersisted(raw: String?): GpsSource =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: NONE
    }
}

/**
 * A coordinate together with everything needed to judge it. Every field is nullable/defaulted so
 * a legacy package produces an honest [GpsStatus.UNKNOWN] record instead of fabricated values.
 */
data class GpsProvenance(
    val status: GpsStatus = GpsStatus.UNKNOWN,
    val latitude: Double? = null,
    val longitude: Double? = null,
    /** Reported horizontal accuracy in metres. */
    val accuracyM: Float? = null,
    /** Epoch millis of the FIX itself — not of the commit or the export. */
    val fixTimeMillis: Long? = null,
    /** Age of the fix at the moment it was committed to a package. */
    val ageMs: Long? = null,
    /** Provider name as reported by the platform ("gps", "network", …). */
    val provider: String = "",
    val source: GpsSource = GpsSource.NONE,
) {
    val isFresh: Boolean get() = status == GpsStatus.FRESH

    /**
     * Every coordinate this record actually holds, at any status.
     *
     * This is what the metadata sidecar's top-level `lat`/`lng` are written from. Those two keys
     * exist in every already-collected package, so suppressing them for a non-fresh fix would
     * remove a key from ~100% of new records AND strip it from a legacy sidecar the moment folder
     * resume rewrites it — data loss on evidence that is already in the ML engineer's hands. The
     * freshness qualifier lives in the always-present `gps.status` / `gps.ageMs` / `gps.source`
     * instead, which is what the 27 Jul report actually asked for: not fewer coordinates, but a
     * statement about them.
     */
    val recordedCoordinates: Pair<Double, Double>?
        get() = if (latitude != null && longitude != null) latitude to longitude else null

    /**
     * Coordinates a consumer may treat as "where the operator was standing" with no further
     * checks. Strictly narrower than [recordedCoordinates]; used for decisions, not for storage.
     */
    val publishableCoordinates: Pair<Double, Double>?
        get() = if (isFresh) recordedCoordinates else null

    companion object {
        val UNKNOWN = GpsProvenance()

        fun unavailable(status: GpsStatus): GpsProvenance = GpsProvenance(status = status)
    }
}

/**
 * Pure freshness rules. Separated from [dev.sawitulm.palmannotate.data.location.GpsProvider] so
 * every boundary (exactly at the limit, a future timestamp, a fix that ages out between the GPS
 * read and the commit) is testable on the JVM.
 */
object GpsFreshnessPolicy {

    /** A fix older than this is not "where the operator is standing" for a per-tree capture. */
    const val MAX_FIX_AGE_MS = 60_000L

    enum class Freshness { FRESH, STALE, INVALID_FUTURE }

    /**
     * Classify a fix by its own timestamp. A timestamp in the future is [Freshness.INVALID_FUTURE]
     * rather than "very fresh": it means the device clock moved, so the age is unknowable.
     */
    fun classify(
        fixTimeMillis: Long,
        nowMillis: Long,
        maxAgeMs: Long = MAX_FIX_AGE_MS,
    ): Freshness {
        val age = nowMillis - fixTimeMillis
        return when {
            age < 0L -> Freshness.INVALID_FUTURE
            age <= maxAgeMs -> Freshness.FRESH
            else -> Freshness.STALE
        }
    }

    /**
     * Build the provenance for a fix that a provider actually returned.
     * A future-dated fix degrades to [GpsStatus.STALE] with a null age — the coordinate is kept
     * (it is real) but nothing claims to know how old it is.
     */
    fun forFix(
        latitude: Double,
        longitude: Double,
        accuracyM: Float?,
        fixTimeMillis: Long,
        provider: String,
        source: GpsSource,
        nowMillis: Long,
        maxAgeMs: Long = MAX_FIX_AGE_MS,
    ): GpsProvenance {
        val freshness = classify(fixTimeMillis, nowMillis, maxAgeMs)
        return GpsProvenance(
            status = if (freshness == Freshness.FRESH) GpsStatus.FRESH else GpsStatus.STALE,
            latitude = latitude,
            longitude = longitude,
            accuracyM = accuracyM,
            fixTimeMillis = fixTimeMillis,
            ageMs = if (freshness == Freshness.INVALID_FUTURE) null else nowMillis - fixTimeMillis,
            provider = provider,
            source = source,
        )
    }

    /**
     * Re-judge a provenance at commit time.
     *
     * The GPS read happens when the capture screen opens; the commit can be many minutes later,
     * after four or eight photos. Without this the package would record FRESH for a fix that had
     * already aged out. Downgrades only — a STALE fix can never become FRESH again.
     */
    fun recheckAtCommit(
        provenance: GpsProvenance,
        nowMillis: Long,
        maxAgeMs: Long = MAX_FIX_AGE_MS,
    ): GpsProvenance {
        val fixTime = provenance.fixTimeMillis ?: return provenance
        if (provenance.status != GpsStatus.FRESH && provenance.status != GpsStatus.STALE) {
            return provenance
        }
        val freshness = classify(fixTime, nowMillis, maxAgeMs)
        val stillFresh = provenance.status == GpsStatus.FRESH && freshness == Freshness.FRESH
        return provenance.copy(
            status = if (stillFresh) GpsStatus.FRESH else GpsStatus.STALE,
            ageMs = if (freshness == Freshness.INVALID_FUTURE) null else nowMillis - fixTime,
        )
    }
}
