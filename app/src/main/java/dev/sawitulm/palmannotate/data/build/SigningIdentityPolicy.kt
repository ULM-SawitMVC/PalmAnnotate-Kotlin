package dev.sawitulm.palmannotate.data.build

/**
 * WS-21 — how an APK's *update identity* is resolved, and what it means for the operator.
 *
 * Android refuses `install -r` when the replacement APK is signed by a different certificate.
 * The only way past that is `pm uninstall`, which deletes the package's app-private storage —
 * i.e. the collected dataset under `getExternalFilesDir`. So the signer is not a packaging
 * detail here; it is the difference between "update the app" and "lose the dataset".
 *
 * This object is the single source of truth for the secret names and the resolution rule.
 * `app/build.gradle.kts` implements the same rule at configuration time and stamps the result
 * into `BuildConfig.SIGNING_IDENTITY`; `SigningIdentityPolicyTests` reads the Gradle script and
 * fails if the two ever drift apart.
 */
object SigningIdentityPolicy {

    /** A durable keystore was configured — this APK can update a previous one in place. */
    const val PERSISTENT = "PERSISTENT"

    /** No keystore configured; the build fell back to a throwaway debug signer. */
    const val EPHEMERAL_DEBUG = "EPHEMERAL_DEBUG"

    /**
     * Secrets (GitHub Actions) or Gradle properties (local) that must ALL resolve to a non-blank
     * value before a distribution signing config is created.
     */
    val REQUIRED_SECRETS: List<String> = listOf(
        "PALMANNOTATE_KEYSTORE_BASE64",
        "PALMANNOTATE_KEYSTORE_PASSWORD",
        "PALMANNOTATE_KEY_ALIAS",
        "PALMANNOTATE_KEY_PASSWORD",
    )

    /** Local alternative to [REQUIRED_SECRETS]`[0]`: a path instead of an embedded blob. */
    const val KEYSTORE_PATH_SECRET = "PALMANNOTATE_KEYSTORE_PATH"

    /**
     * SHA-256 of the certificate a published Release must be signed by.
     *
     * Not needed to BUILD (hence absent from [REQUIRED_SECRETS]) but required to PUBLISH. Without
     * it, `release.yml`'s only mandatory certificate comparison is field-vs-trace, and those two
     * share one signing config by construction — so the check can never fail and a swapped or
     * regenerated keystore would ship silently, orphaning every installed app.
     */
    const val CERT_PIN_SECRET = "PALMANNOTATE_SIGNING_CERT_SHA256"

    /** What `release.yml` must have before it is allowed to publish anything. */
    val RELEASE_REQUIRED_SECRETS: List<String> = REQUIRED_SECRETS + CERT_PIN_SECRET

    /** Build variants that are handed to an operator and therefore need a stable identity. */
    val DISTRIBUTABLE_VARIANTS: Set<String> = setOf("field", "trace")

    sealed class Resolution {
        /** Every secret resolved; the build signs with the durable distribution key. */
        object Persistent : Resolution()

        /** [missing] secrets were absent/blank; the build falls back to the debug signer. */
        data class Ephemeral(val missing: List<String>) : Resolution()
    }

    /**
     * Resolve the signing identity from a secret name → value map.
     *
     * Either [REQUIRED_SECRETS]`[0]` (base64 blob) or [KEYSTORE_PATH_SECRET] supplies the
     * keystore; the remaining three secrets are mandatory in both cases. Blank values count as
     * absent — an empty repository secret is the most common way this silently degrades.
     */
    fun resolve(secrets: Map<String, String?>): Resolution {
        fun present(name: String) = !secrets[name]?.trim().isNullOrEmpty()
        val keystoreSecret = REQUIRED_SECRETS[0]
        val missing = ArrayList<String>()
        if (!present(keystoreSecret) && !present(KEYSTORE_PATH_SECRET)) missing.add(keystoreSecret)
        REQUIRED_SECRETS.drop(1).filterNotTo(missing) { present(it) }
        return if (missing.isEmpty()) Resolution.Persistent else Resolution.Ephemeral(missing)
    }

    /** The `BuildConfig.SIGNING_IDENTITY` string a [Resolution] produces. */
    fun identityOf(resolution: Resolution): String = when (resolution) {
        is Resolution.Persistent -> PERSISTENT
        is Resolution.Ephemeral -> EPHEMERAL_DEBUG
    }

    /**
     * Whether an APK of [variantName] with [identity] may be handed out as an updatable build.
     * Non-distributable variants (`debug`) are always false: they are developer builds, not
     * something an operator installs over a dataset-bearing package.
     */
    fun isDistributable(identity: String, variantName: String): Boolean =
        identity == PERSISTENT && variantName in DISTRIBUTABLE_VARIANTS

    /**
     * Operator-facing warning, or null when the build carries a durable identity.
     * Deliberately spells out the consequence (uninstall = dataset loss) rather than the cause.
     */
    fun advisory(identity: String, applicationId: String): String? =
        if (identity == PERSISTENT) null
        else "$applicationId was built without the distribution signing key. It CANNOT be " +
            "updated in place: installing a later build would require uninstalling this one, " +
            "which deletes the collected dataset. Export a ZIP before replacing this app."
}
