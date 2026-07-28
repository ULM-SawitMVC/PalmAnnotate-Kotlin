package dev.sawitulm.palmannotate

import dev.sawitulm.palmannotate.data.build.SigningIdentityPolicy
import dev.sawitulm.palmannotate.data.build.SigningIdentityPolicy.Resolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// ════════════════════════════════════════════════════════════════════════════════
// WS-21 — signing config resolution.
//
// The consequence of getting this wrong is not a broken build, it is a Release that installs
// only after `pm uninstall`, which deletes the operator's collected dataset. So every branch of
// the resolution — including the ones that look like trivial null checks — is pinned here.
// ════════════════════════════════════════════════════════════════════════════════

class SigningIdentityResolutionTest {

    private fun allSecrets(): MutableMap<String, String?> = mutableMapOf(
        "PALMANNOTATE_KEYSTORE_BASE64" to "MIIB...",
        "PALMANNOTATE_KEYSTORE_PASSWORD" to "store-pw",
        "PALMANNOTATE_KEY_ALIAS" to "palmannotate",
        "PALMANNOTATE_KEY_PASSWORD" to "key-pw",
    )

    @Test fun `every secret present resolves to the persistent identity`() {
        val resolution = SigningIdentityPolicy.resolve(allSecrets())
        assertTrue(resolution is Resolution.Persistent)
        assertEquals(SigningIdentityPolicy.PERSISTENT, SigningIdentityPolicy.identityOf(resolution))
    }

    @Test fun `a keystore path substitutes for the base64 blob`() {
        val secrets = allSecrets().apply { remove("PALMANNOTATE_KEYSTORE_BASE64") }
        secrets[SigningIdentityPolicy.KEYSTORE_PATH_SECRET] = "C:/keys/palmannotate.jks"
        assertTrue(SigningIdentityPolicy.resolve(secrets) is Resolution.Persistent)
    }

    @Test fun `no secrets at all is ephemeral and names every missing one`() {
        val resolution = SigningIdentityPolicy.resolve(emptyMap())
        assertTrue(resolution is Resolution.Ephemeral)
        assertEquals(
            SigningIdentityPolicy.REQUIRED_SECRETS,
            (resolution as Resolution.Ephemeral).missing,
        )
        assertEquals(
            SigningIdentityPolicy.EPHEMERAL_DEBUG,
            SigningIdentityPolicy.identityOf(resolution),
        )
    }

    @Test fun `each individually missing secret degrades the whole resolution`() {
        for (name in SigningIdentityPolicy.REQUIRED_SECRETS) {
            val secrets = allSecrets().apply { remove(name) }
            val resolution = SigningIdentityPolicy.resolve(secrets)
            assertTrue("missing $name must not resolve", resolution is Resolution.Ephemeral)
            assertEquals(listOf(name), (resolution as Resolution.Ephemeral).missing)
        }
    }

    // An empty repository secret is the realistic failure: the secret exists, so a
    // presence-only check would pass, and Gradle would silently sign with the debug key.
    @Test fun `blank and whitespace-only secrets count as absent`() {
        for (blank in listOf("", "   ", "\n\t")) {
            val secrets = allSecrets().apply { put("PALMANNOTATE_KEY_ALIAS", blank) }
            val resolution = SigningIdentityPolicy.resolve(secrets)
            assertTrue("'$blank' must not count as configured", resolution is Resolution.Ephemeral)
            assertEquals(
                listOf("PALMANNOTATE_KEY_ALIAS"),
                (resolution as Resolution.Ephemeral).missing,
            )
        }
    }

    @Test fun `a blank keystore path does not stand in for a missing blob`() {
        val secrets = allSecrets().apply {
            put("PALMANNOTATE_KEYSTORE_BASE64", "")
            put(SigningIdentityPolicy.KEYSTORE_PATH_SECRET, "  ")
        }
        val resolution = SigningIdentityPolicy.resolve(secrets)
        assertTrue(resolution is Resolution.Ephemeral)
        assertEquals(
            listOf("PALMANNOTATE_KEYSTORE_BASE64"),
            (resolution as Resolution.Ephemeral).missing,
        )
    }
}

class SigningIdentityDistributionTest {

    @Test fun `only a persistent identity may be distributed`() {
        assertTrue(SigningIdentityPolicy.isDistributable(SigningIdentityPolicy.PERSISTENT, "field"))
        assertTrue(SigningIdentityPolicy.isDistributable(SigningIdentityPolicy.PERSISTENT, "trace"))
        assertFalse(
            SigningIdentityPolicy.isDistributable(SigningIdentityPolicy.EPHEMERAL_DEBUG, "field"),
        )
    }

    @Test fun `the developer debug variant is never a distribution artifact`() {
        assertFalse(SigningIdentityPolicy.isDistributable(SigningIdentityPolicy.PERSISTENT, "debug"))
        assertFalse(
            SigningIdentityPolicy.isDistributable(SigningIdentityPolicy.PERSISTENT, "release"),
        )
    }

    @Test fun `the advisory names the app and the data-loss consequence`() {
        val advisory = SigningIdentityPolicy.advisory(
            SigningIdentityPolicy.EPHEMERAL_DEBUG,
            "dev.sawitulm.palmannotate.field",
        )
        assertNotNull(advisory)
        assertTrue(advisory!!.contains("dev.sawitulm.palmannotate.field"))
        assertTrue(advisory.contains("dataset"))
    }

    @Test fun `a persistent build produces no advisory noise`() {
        assertNull(
            SigningIdentityPolicy.advisory(
                SigningIdentityPolicy.PERSISTENT,
                "dev.sawitulm.palmannotate.field",
            ),
        )
    }

    @Test fun `the build stamps one of the two known identities into BuildConfig`() {
        assertTrue(
            "unexpected SIGNING_IDENTITY '${BuildConfig.SIGNING_IDENTITY}'",
            BuildConfig.SIGNING_IDENTITY == SigningIdentityPolicy.PERSISTENT ||
                BuildConfig.SIGNING_IDENTITY == SigningIdentityPolicy.EPHEMERAL_DEBUG,
        )
    }
}

/**
 * Gradle cannot import this policy object, so the secret names exist in two places. This test is
 * what keeps them from drifting: rename a secret in one file and the build fails here rather
 * than at 06:00 on a collection day.
 */
class SigningGradleContractTest {

    private val script by lazy { repoFile("app/build.gradle.kts").readText() }

    @Test fun `the build script reads exactly the documented secret names`() {
        for (name in SigningIdentityPolicy.REQUIRED_SECRETS + SigningIdentityPolicy.KEYSTORE_PATH_SECRET) {
            assertTrue("app/build.gradle.kts must read $name", script.contains(name))
        }
    }

    @Test fun `the build script stamps the same identity constants`() {
        assertTrue(script.contains("SIGNING_IDENTITY"))
        assertTrue(script.contains(SigningIdentityPolicy.PERSISTENT))
        assertTrue(script.contains(SigningIdentityPolicy.EPHEMERAL_DEBUG))
    }

    @Test fun `an unsigned distributable APK is renamed so it cannot pass as shippable`() {
        assertTrue("the -NOKEY marker is the loud fallback", script.contains("-NOKEY"))
        for (variant in SigningIdentityPolicy.DISTRIBUTABLE_VARIANTS) {
            assertTrue("variant $variant must be in distributableVariants", script.contains("\"$variant\""))
        }
    }

    @Test fun `R8 stays disabled in every build type`() {
        // Re-asserted here because the signing work touches the same buildTypes block, and an
        // accidental minify flip freezes the Orbbec live preview (CLAUDE.md).
        assertFalse(script.contains("isMinifyEnabled = true"))
        assertFalse(script.contains("isShrinkResources = true"))
    }

    @Test fun `the release workflow refuses to publish without the signing secrets`() {
        val release = repoFile(".github/workflows/release.yml").readText()
        for (name in SigningIdentityPolicy.REQUIRED_SECRETS) {
            assertTrue("release.yml must require $name", release.contains(name))
        }
        assertTrue("release.yml must still guard the tag/version match", release.contains("does not match built APK version"))
        assertTrue("release.yml must reject a -NOKEY asset", release.contains("-NOKEY"))
        assertTrue("release.yml must reject a non-monotonic versionCode", release.contains("not greater than the published"))
        assertTrue("release.yml must record the certificate digest", release.contains("apksigner"))
        assertTrue("full history is required for versionCode", release.contains("fetch-depth: 0"))
    }
}
