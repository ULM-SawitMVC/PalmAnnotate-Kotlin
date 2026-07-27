package dev.sawitulm.palmannotate

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Test

class OrbbecVendorPatchTests {
    @Test
    fun vendoredAarContainsReviewedAndroid14ReceiverPatch() {
        val aar = File("libs/obsensor_v2.0.6_2026031801_release.aar")
        val digest = MessageDigest.getInstance("SHA-256")
        aar.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { "%02X".format(it) }
        assertEquals(
            "F20073409C3A63011E5158E59829EA8522A68374D59547A6AFFD7614E79330B1",
            actual,
        )
    }
}
