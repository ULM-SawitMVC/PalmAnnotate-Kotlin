package dev.sawitulm.palmannotate.data.storage

import dev.sawitulm.palmannotate.domain.model.CaptureSetIdentity
import dev.sawitulm.palmannotate.domain.model.CaptureSetPolicy
import dev.sawitulm.palmannotate.domain.model.GpsProvenance
import dev.sawitulm.palmannotate.domain.model.GpsSource
import dev.sawitulm.palmannotate.domain.model.GpsStatus
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * WS-12 + WS-13 — the one place that turns capture-set identity, operator and GPS provenance
 * into sidecar JSON and back.
 *
 * ## Compatibility contract
 * Everything written here is **additive**. The pre-existing keys (`name`, `variety`, `blok`,
 * `treeId`, `operator`, `timestamp`, `lat`, `lng`, `artifacts`, `artifactSchemaVersion`) keep
 * their names, positions in the schema and meaning, so the already-collected 42- and 90-tree
 * packages and every consumer that parses them are unaffected. `artifactSchemaVersion` stays 1
 * because the `artifacts` block it describes did not change.
 *
 * Two rules are load-bearing:
 *  1. Top-level `lat`/`lng` are emitted **only** for a [GpsStatus.FRESH] fix. They have no
 *     freshness qualifier, so writing a stale coordinate there would be the exact lie WS-13
 *     removes. A stale fix still keeps its coordinates — inside `gps`, labelled.
 *  2. Reading a legacy sidecar that has bare `lat`/`lng` and no `gps` block yields
 *     [GpsStatus.UNKNOWN] with [GpsSource.LEGACY_SIDECAR]. The coordinate is preserved; the
 *     claim about it is not invented.
 */
internal object PackageProvenanceCodec {

    const val UNKNOWN_OPERATOR = "UNKNOWN"

    private val isoFormat: SimpleDateFormat
        get() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

    private val dateOnlyFormat: SimpleDateFormat
        get() = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    /** Device-local capture day. Distinct from the UTC export instant. */
    fun captureDate(atMillis: Long): String = dateOnlyFormat.format(Date(atMillis))

    // ─── Capture-set identity ────────────────────────────────────────────────

    fun identityJson(identity: CaptureSetIdentity): JSONObject = JSONObject().apply {
        put("schema", CaptureSetPolicy.SCHEMA_VERSION)
        put("captureSetId", identity.captureSetId)
        put("deviceToken", identity.deviceToken)
        put("nameToken", identity.nameToken)
    }

    fun readIdentity(root: JSONObject?): CaptureSetIdentity {
        val block = root?.optJSONObject("captureSet") ?: return CaptureSetIdentity.UNKNOWN
        return CaptureSetIdentity(
            captureSetId = block.optString("captureSetId").orEmptyTrimmed(),
            deviceToken = block.optString("deviceToken").orEmptyTrimmed(),
            nameToken = block.optString("nameToken").orEmptyTrimmed(),
        )
    }

    // ─── GPS provenance ──────────────────────────────────────────────────────

    fun gpsJson(gps: GpsProvenance): JSONObject = JSONObject().apply {
        put("status", gps.status.name)
        put("source", gps.source.name)
        put("provider", gps.provider)
        gps.latitude?.let { put("lat", it) }
        gps.longitude?.let { put("lng", it) }
        gps.accuracyM?.let { put("accuracyM", it.toDouble()) }
        gps.fixTimeMillis?.let {
            put("fixTimestamp", isoFormat.format(Date(it)))
            put("fixEpochMs", it)
        }
        gps.ageMs?.let { put("ageMs", it) }
    }

    fun readGps(root: JSONObject?): GpsProvenance {
        if (root == null) return GpsProvenance.UNKNOWN
        val block = root.optJSONObject("gps")
        if (block == null) {
            // Legacy sidecar: bare lat/lng, no provenance recorded anywhere. Keep the coordinate,
            // refuse to claim it was fresh.
            val lat = root.optDoubleOrNull("lat")
            val lng = root.optDoubleOrNull("lng")
            return if (lat == null || lng == null) GpsProvenance.UNKNOWN
            else GpsProvenance(
                status = GpsStatus.UNKNOWN,
                latitude = lat,
                longitude = lng,
                source = GpsSource.LEGACY_SIDECAR,
            )
        }
        val status = GpsStatus.fromPersisted(block.optString("status"))
        return GpsProvenance(
            status = status,
            latitude = block.optDoubleOrNull("lat") ?: root.optDoubleOrNull("lat"),
            longitude = block.optDoubleOrNull("lng") ?: root.optDoubleOrNull("lng"),
            accuracyM = block.optDoubleOrNull("accuracyM")?.toFloat(),
            fixTimeMillis = block.optLongOrNull("fixEpochMs"),
            ageMs = block.optLongOrNull("ageMs"),
            provider = block.optString("provider").orEmptyTrimmed(),
            source = GpsSource.fromPersisted(block.optString("source")),
        )
    }

    // ─── Operator ────────────────────────────────────────────────────────────

    /** Never an empty string in output: an unlabelled package says UNKNOWN out loud. */
    fun operatorForOutput(operatorName: String?): String =
        operatorName?.trim()?.takeIf { it.isNotEmpty() } ?: UNKNOWN_OPERATOR

    /** Inverse of [operatorForOutput]: UNKNOWN/blank read back as "no operator recorded". */
    fun readOperator(root: JSONObject?): String {
        val raw = root?.optString("operator")?.trim().orEmpty()
        return if (raw.isEmpty() || raw.equals(UNKNOWN_OPERATOR, ignoreCase = true)) "" else raw
    }

    fun readCapturedAtMillis(root: JSONObject?): Long? {
        val timestamp = root?.optString("timestamp")?.trim().orEmpty()
        if (timestamp.isEmpty()) return null
        return runCatching { isoFormat.parse(timestamp)?.time }.getOrNull()
    }

    fun readCaptureDate(root: JSONObject?): String {
        val explicit = root?.optString("captureDate")?.trim().orEmpty()
        if (explicit.isNotEmpty()) return explicit
        // Legacy packages recorded only the commit `timestamp`, and it is written in UTC while
        // captureDate is the device-LOCAL capture day. Slicing the string would date any capture
        // made before 08:00 WITA (UTC+8, the collection site) to the previous day, so the instant
        // is parsed and reformatted in the local zone instead.
        val ts = root?.optString("timestamp")?.trim().orEmpty()
        if (ts.length < 10 || ts[4] != '-' || ts[7] != '-') return ""
        val parsed = runCatching { isoFormat.parse(ts) }.getOrNull()
        return if (parsed != null) dateOnlyFormat.format(parsed) else ts.substring(0, 10)
    }

    // ─── org.json helpers ────────────────────────────────────────────────────
    //
    // optDouble/optLong return sentinel defaults for a missing key, which would turn "no fix"
    // into coordinate 0,0 — the null-island bug. These return null instead.

    private fun JSONObject.optDoubleOrNull(key: String): Double? =
        if (!has(key) || isNull(key)) null else optDouble(key).takeIf { !it.isNaN() }

    private fun JSONObject.optLongOrNull(key: String): Long? =
        if (!has(key) || isNull(key)) null else optLong(key, Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE }

    private fun String?.orEmptyTrimmed(): String = this?.trim().orEmpty()
}
