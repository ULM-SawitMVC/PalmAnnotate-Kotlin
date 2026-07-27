package dev.sawitulm.palmannotate.data.location

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GpsProviderTests {
    @Test
    fun lastKnownFixMustBeRecentAndNotFromFuture() {
        val now = 1_000_000L
        assertTrue(GpsProvider.isFresh(now - 60_000L, now))
        assertFalse(GpsProvider.isFresh(now - 60_001L, now))
        assertFalse(GpsProvider.isFresh(now + 1L, now))
    }
}
