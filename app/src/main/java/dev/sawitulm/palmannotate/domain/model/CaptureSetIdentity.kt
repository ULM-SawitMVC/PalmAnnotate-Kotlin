package dev.sawitulm.palmannotate.domain.model

/**
 * WS-12 — cross-device capture-set identity.
 *
 * ## The failure this closes
 * `treeName` is derived from variety+block+counter, and every device starts that counter at 1.
 * Two tablets collecting the same block therefore both produce `DAMIMAS_A21B_0001…_0042` with
 * different photos inside. Each ZIP is internally consistent, but extracting both into one
 * folder overwrites 168 samples with no warning (field report 27 Jul 2026, §3.1).
 *
 * ## The identity
 *  - [installId]   a UUID minted once per app install. Private; never leaves the device.
 *  - [deviceToken] a short, opaque token DERIVED from [installId]. This is the public handle:
 *                  it is stable for the life of the install and leaks no hardware serial,
 *                  IMEI or Android ID.
 *  - [captureSetId] a UUID minted once per run (variety+block). This is what makes two ZIPs
 *                  distinguishable even if they came from the same device on different days.
 *  - [nameToken]   OPT-IN. When non-blank it is spliced into `treeName`, which is the only
 *                  change that makes two devices' packages merge-safe by filename alone.
 *                  Blank keeps the exact legacy naming — existing 42- and 90-tree packages and
 *                  the folder-resume path parse unchanged.
 */
data class CaptureSetIdentity(
    val captureSetId: String,
    val deviceToken: String,
    val nameToken: String = "",
) {
    val isKnown: Boolean get() = captureSetId.isNotBlank()

    companion object {
        /** Identity of a package captured before WS-12, or of one whose sidecar has no identity. */
        val UNKNOWN = CaptureSetIdentity(captureSetId = "", deviceToken = "", nameToken = "")
    }
}

/**
 * Pure derivation + validation rules for [CaptureSetIdentity]. No Android, no I/O — every rule
 * here is a decision that has to be reproducible off-device by whoever merges the datasets.
 */
object CaptureSetPolicy {

    /** Version of the identity block written into metadata/manifest/descriptor JSON. */
    const val SCHEMA_VERSION = 1

    /** Token length. 6 Crockford base32 chars ≈ 30 bits ≈ 1e9 combinations. */
    const val TOKEN_LENGTH = 6

    /**
     * Crockford base32 minus I, L, O and U: nothing in the alphabet can be misread as another
     * character when an operator reads a token off a tablet and types it into a filename.
     */
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    private val tokenPattern = Regex("^[0-9A-HJKMNP-TV-Z]{$TOKEN_LENGTH}$")

    /**
     * Filename marker that keeps a bunch-weight sample out of the multiside name namespace.
     *
     * `treeName` is a FLAT namespace: `images/`, `labels/`, `depth/`, `manifests/`, `metadata/`,
     * `Output JSON/` and the revision journal are all keyed by it alone. Separating the two
     * modules' RUNS was therefore not enough: both counters start at 1, so the same
     * variety+block produced `DAMIMAS_QA0905_0001` in each module and the second module's commit
     * was rejected by the "already exists" guard.
     *
     * The marker sits after the block and before the optional naming token, so all three
     * existing derivations still hold: variety = leading token, block = segment 1, tree number =
     * trailing digits. [DatasetType.MULTISIDE] adds nothing, so every already-collected multiside
     * name stays byte-identical.
     */
    const val WEIGHT_NAME_MARKER = "BW"

    /**
     * null when [block] may be used, else why it was rejected.
     *
     * The marker is a reserved block token in BOTH modules. Without the reservation the marker is
     * only positionally distinct, not textually: a bunch-weight run with no block writes
     * `DAMIMAS_BW_0001`, and so would a multiside run whose block really is `BW`. Reserving one
     * token is a far smaller price than a name format ugly enough to be unambiguous, and it also
     * makes the resume-side block derivation exact: `parts[1] == BW` on a bunch-weight name can
     * then only mean "this sample has no block".
     */
    fun blockError(block: String): String? = when {
        block.isBlank() -> "Block is required"
        sanitizeBlock(block) == WEIGHT_NAME_MARKER ->
            "$WEIGHT_NAME_MARKER is reserved for bunch-weight sample names"
        else -> null
    }

    /**
     * Derive the public device token from the private install id.
     *
     * Deterministic (same install id → same token, so the token survives a process restart with
     * no extra stored state) and one-way (a token cannot be turned back into the install id).
     */
    fun deviceTokenFrom(installId: String): String {
        require(installId.isNotBlank()) { "installId must not be blank" }
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(installId.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(TOKEN_LENGTH)
        for (i in 0 until TOKEN_LENGTH) {
            sb.append(ALPHABET[(digest[i].toInt() and 0xFF) % ALPHABET.length])
        }
        return sb.toString()
    }

    /** null when [token] is a well-formed naming token, else why it was rejected. */
    fun tokenError(token: String): String? = when {
        token.isEmpty() -> null // blank = legacy naming, explicitly allowed
        !tokenPattern.matches(token) ->
            "capture-set token must be $TOKEN_LENGTH characters from $ALPHABET"
        else -> null
    }

    /**
     * The tree name for [treeId] in a run.
     *
     * With a blank [nameToken] this is byte-identical to the pre-WS-12 name, which is what keeps
     * the existing packages, `FolderResumeImporter` and every downstream parser working:
     *   `DAMIMAS_A21B_0001`
     * With a token it becomes
     *   `DAMIMAS_A21B_K7Q2M1_0001`
     * The token is inserted BEFORE the sequence so that all three existing derivations still
     * hold: variety = leading letters, block = segment 1, tree number = trailing digits.
     * A [DatasetType.BUNCH_WEIGHT] sample additionally carries [WEIGHT_NAME_MARKER] before the
     * token, which is what keeps the two modules out of each other's artifact namespace:
     *   `DAMIMAS_A21B_BW_0001`
     * The marker is only positionally distinct from a block segment, so [blockError] reserves it
     * as a block token; that is what makes the separation total rather than near-total.
     */
    fun treeName(
        variety: String,
        block: String,
        nameToken: String,
        treeId: Int,
        datasetType: DatasetType = DatasetType.MULTISIDE,
    ): String {
        val v = sanitizeVariety(variety)
        val b = sanitizeBlock(block)
        val t = sanitizeToken(nameToken)
        val seq = "%04d".format(treeId)
        return buildString {
            append(v)
            if (b.isNotEmpty()) { append('_'); append(b) }
            if (datasetType == DatasetType.BUNCH_WEIGHT) { append('_'); append(WEIGHT_NAME_MARKER) }
            if (t.isNotEmpty()) { append('_'); append(t) }
            append('_'); append(seq)
        }
    }

    /**
     * The naming token a run will actually use, given what it already has.
     *
     * One rule, used by BOTH the Start Session dialog's preview and the repository that folds a
     * new request into an existing run — so the name the operator is shown is the name that gets
     * written. There is no third possibility hidden in either implementation.
     *
     *  - a run that already has a token keeps it: the token is baked into every filename it has
     *    written and cannot be revised;
     *  - a run that has STARTED (a committed tree, or an in-flight capture draft) keeps the
     *    legacy tokenless naming, because switching format mid-run would produce a run whose
     *    files are half `_0001` and half `_TOKEN_0043` while the already-delivered packages still
     *    collide — worse than the collision itself;
     *  - only a run that has written nothing may adopt [requestedToken].
     *
     * The cross-device protection for a run that cannot adopt a token is not lost: `captureSetId`
     * and `deviceToken` still reach the sidecar, the manifest, Output JSON, `capture_set.json`
     * and the ZIP filename, so a merge tool can still separate two devices' identical names.
     */
    fun resolveNameToken(
        existingToken: String,
        runHasStarted: Boolean,
        requestedToken: String,
    ): String = when {
        existingToken.isNotBlank() -> existingToken
        runHasStarted -> ""
        else -> requestedToken
    }

    /**
     * The logical (device-independent) name of a tree: the name it WOULD have had without a
     * naming token. Two packages collide logically when these match, which is what the merge
     * policy reports even though the physical filenames differ.
     */
    fun logicalTreeName(treeName: String, nameToken: String): String {
        val t = sanitizeToken(nameToken)
        if (t.isEmpty()) return treeName
        val marker = "_${t}_"
        val at = treeName.lastIndexOf(marker)
        return if (at < 0) treeName
        else treeName.substring(0, at) + "_" + treeName.substring(at + marker.length)
    }

    // The two sanitizers below reproduce CaptureFlowScreen's historical `safe`/`safeBlock`
    // character-for-character. That is deliberate: with a blank nameToken, treeName() must
    // return the byte-identical name every already-collected package uses.
    private fun sanitizeVariety(raw: String): String =
        raw.uppercase().replace(Regex("[^A-Z0-9_]+"), "_").trim('_').ifBlank { "TREE" }

    private fun sanitizeBlock(raw: String): String =
        raw.uppercase().replace(Regex("[^A-Z0-9]"), "")

    private fun sanitizeToken(raw: String): String =
        raw.uppercase().replace(Regex("[^A-Z0-9]"), "")
}
