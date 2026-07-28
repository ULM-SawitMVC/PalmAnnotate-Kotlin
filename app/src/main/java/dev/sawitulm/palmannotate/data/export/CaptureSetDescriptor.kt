package dev.sawitulm.palmannotate.data.export

import dev.sawitulm.palmannotate.domain.model.CaptureSetIdentity
import dev.sawitulm.palmannotate.domain.model.CaptureSetPolicy
import org.json.JSONArray
import org.json.JSONObject

/**
 * WS-12 — the root `capture_set.json` a dataset ZIP carries, and the rule for merging two of
 * them.
 *
 * ## Why a descriptor rather than a rename
 * The existing packages (`images/`, `labels/`, `json/`, `depth/`, `metadata/`, `manifests/`,
 * flat `{tree}_{n}` names) are already in the ML engineer's hands. Renaming them would break
 * every consumer, so identity is carried in an ADDITIONAL root file. A tool that does not know
 * about `capture_set.json` reads the ZIP exactly as before; a tool that does can refuse an
 * unsafe merge before a single byte is overwritten.
 *
 * ## The merge rule
 * Two entries collide when they occupy the same path — that is, share a `treeName`. The verdict
 * depends on identity, never on file timestamps:
 *  - same tree, same capture set, same content digest  → the same package twice (safe, idempotent)
 *  - same tree, same capture set, different digest     → CONFLICT (re-annotated or corrupted)
 *  - same tree, different capture set                  → CONFLICT (two devices / two runs)
 *  - same tree, at least one identity unknown          → CONFLICT (legacy package: cannot prove
 *                                                        they are the same tree, so refuse)
 * Fail-closed in every ambiguous case: silently overwriting 168 samples is what this exists to
 * stop.
 */
object CaptureSetMergePolicy {

    /** One tree as advertised by a package descriptor. */
    data class Entry(
        val treeName: String,
        val captureSetId: String,
        val deviceToken: String,
        /** Stable digest of the tree's committed content (its package manifest's revision). */
        val contentDigest: String,
    ) {
        val hasIdentity: Boolean get() = captureSetId.isNotBlank()
    }

    data class Collision(
        val treeName: String,
        val reason: String,
        val captureSetIds: List<String>,
    ) {
        fun describe(): String =
            "$treeName: $reason (capture sets: ${captureSetIds.joinToString(", ") { it.ifBlank { "UNKNOWN" } }})"
    }

    sealed class Verdict {
        object Mergeable : Verdict()
        data class Conflict(val collisions: List<Collision>) : Verdict()
    }

    /**
     * Evaluate merging [packages] (each the entry list of one ZIP/folder) into one directory.
     * A single package is also checked, which catches a descriptor that contradicts itself.
     */
    fun evaluate(packages: List<List<Entry>>): Verdict {
        val byTree = LinkedHashMap<String, MutableList<Entry>>()
        for (pkg in packages) {
            for (entry in pkg) byTree.getOrPut(entry.treeName) { ArrayList() }.add(entry)
        }
        val collisions = ArrayList<Collision>()
        for ((treeName, entries) in byTree) {
            if (entries.size < 2) continue
            val reason = collisionReason(entries) ?: continue
            collisions.add(Collision(treeName, reason, entries.map { it.captureSetId }.distinct()))
        }
        return if (collisions.isEmpty()) Verdict.Mergeable else Verdict.Conflict(collisions)
    }

    private fun collisionReason(entries: List<Entry>): String? {
        if (entries.any { !it.hasIdentity }) {
            return "a package with no capture-set identity shares this tree name; " +
                "merge manually after prefixing the legacy package"
        }
        val captureSets = entries.map { it.captureSetId }.distinct()
        if (captureSets.size > 1) {
            val devices = entries.map { it.deviceToken }.distinct()
            return if (devices.size > 1) {
                "same tree name captured by ${devices.size} different devices"
            } else {
                "same tree name captured in ${captureSets.size} different runs on one device"
            }
        }
        val digests = entries.map { it.contentDigest }.distinct()
        if (digests.size > 1) {
            return "same capture set but ${digests.size} different contents for this tree"
        }
        return null // identical package listed twice — extracting it twice is a no-op
    }
}

/**
 * Build/parse the root `capture_set.json`.
 *
 * Kept I/O-free so both the exporter and the JVM tests use the identical bytes.
 */
internal object CaptureSetDescriptor {

    const val FILE_NAME = "capture_set.json"

    /**
     * @param appVersion versionName of the build that produced the ZIP, for provenance only.
     * @param entries    one per tree in the archive.
     */
    fun build(
        identity: CaptureSetIdentity,
        appVersion: String,
        generatedAt: String,
        entries: List<CaptureSetMergePolicy.Entry>,
    ): JSONObject = JSONObject().apply {
        put("schema", CaptureSetPolicy.SCHEMA_VERSION)
        put("captureSetId", identity.captureSetId)
        put("deviceToken", identity.deviceToken)
        put("nameToken", identity.nameToken)
        put("appVersion", appVersion)
        put("generatedAt", generatedAt)
        put("treeCount", entries.size)
        put(
            "mergeRule",
            "Two packages may be extracted into one directory only when no treeName appears in " +
                "both with a different captureSetId or a different contentDigest. Compare this " +
                "file, not file timestamps.",
        )
        put("trees", JSONArray().apply {
            for (entry in entries.sortedBy { it.treeName }) {
                put(JSONObject().apply {
                    put("treeName", entry.treeName)
                    put("captureSetId", entry.captureSetId)
                    put("deviceToken", entry.deviceToken)
                    put("contentDigest", entry.contentDigest)
                })
            }
        })
    }

    /** Entries advertised by a descriptor; empty when the JSON is unusable. */
    fun entriesOf(descriptor: JSONObject): List<CaptureSetMergePolicy.Entry> {
        val trees = descriptor.optJSONArray("trees") ?: return emptyList()
        val fallbackSet = descriptor.optString("captureSetId").trim()
        val fallbackDevice = descriptor.optString("deviceToken").trim()
        return (0 until trees.length()).mapNotNull { i ->
            val node = trees.optJSONObject(i) ?: return@mapNotNull null
            val name = node.optString("treeName").trim()
            if (name.isEmpty()) return@mapNotNull null
            CaptureSetMergePolicy.Entry(
                treeName = name,
                captureSetId = node.optString("captureSetId").trim().ifEmpty { fallbackSet },
                deviceToken = node.optString("deviceToken").trim().ifEmpty { fallbackDevice },
                contentDigest = node.optString("contentDigest").trim(),
            )
        }
    }
}
