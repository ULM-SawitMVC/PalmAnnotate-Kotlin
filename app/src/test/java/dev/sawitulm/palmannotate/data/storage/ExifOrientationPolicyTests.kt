package dev.sawitulm.palmannotate.data.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExifOrientationPolicyTest {

    @Test fun `normal and undefined orientations do not transform pixels`() {
        assertTrue(ExifOrientationPolicy.transformFor(0).isIdentity)
        assertTrue(ExifOrientationPolicy.transformFor(1).isIdentity)
    }

    @Test fun `quarter-turn orientations swap the display axes`() {
        assertEquals(90, ExifOrientationPolicy.transformFor(6).clockwiseDegrees)
        assertEquals(270, ExifOrientationPolicy.transformFor(8).clockwiseDegrees)
        assertFalse(ExifOrientationPolicy.transformFor(6).flipHorizontal)
        assertFalse(ExifOrientationPolicy.transformFor(8).flipHorizontal)
    }

    @Test fun `half-turn orientation rotates without mirroring`() {
        val transform = ExifOrientationPolicy.transformFor(3)
        assertEquals(180, transform.clockwiseDegrees)
        assertFalse(transform.flipHorizontal)
    }

    @Test fun `all mirrored EXIF orientations retain their required rotation`() {
        assertEquals(ExifOrientationTransform(true, 0), ExifOrientationPolicy.transformFor(2))
        assertEquals(ExifOrientationTransform(true, 180), ExifOrientationPolicy.transformFor(4))
        assertEquals(ExifOrientationTransform(true, 270), ExifOrientationPolicy.transformFor(5))
        assertEquals(ExifOrientationTransform(true, 90), ExifOrientationPolicy.transformFor(7))
    }
}
