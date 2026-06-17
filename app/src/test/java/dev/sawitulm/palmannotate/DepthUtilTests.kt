package dev.sawitulm.palmannotate

import dev.sawitulm.palmannotate.domain.util.DepthUtil
import org.junit.Assert.*
import org.junit.Test

// ════════════════════════════════════════════════════════════════════════════════
// DepthUtil — uint16 decode, robust P2–P98 range, jet colormap. Pure math ported from
// the web depth viewer; a regression here silently miscolors / misreads depth.
// ════════════════════════════════════════════════════════════════════════════════

class DepthToUint16Test {
    @Test fun `decodes little-endian uint16`() {
        // 0x2710 = 10000, stored low byte first.
        val out = DepthUtil.toUint16(byteArrayOf(0x10, 0x27))
        assertArrayEquals(intArrayOf(10000), out)
    }

    @Test fun `decodes multiple values and high bit`() {
        // [0x01,0x00]=1 ; [0x00,0x01]=256 ; [0xFF,0xFF]=65535
        val out = DepthUtil.toUint16(byteArrayOf(0x01, 0x00, 0x00, 0x01, 0xFF.toByte(), 0xFF.toByte()))
        assertArrayEquals(intArrayOf(1, 256, 65535), out)
    }

    @Test fun `odd trailing byte is ignored (no crash)`() {
        val out = DepthUtil.toUint16(byteArrayOf(0x01, 0x00, 0x05))
        assertArrayEquals(intArrayOf(1), out)
    }
}

class DepthRangeTest {
    @Test fun `empty input yields zero range with display floor and ceiling`() {
        val r = DepthUtil.range(intArrayOf())
        assertEquals(0, r.minMm); assertEquals(0, r.maxMm); assertEquals(0, r.valid)
        assertEquals(250, r.displayFloorMm); assertEquals(7000, r.displayCeilingMm)
    }

    @Test fun `out-of-range values are filtered`() {
        // 100 < 250 floor, 8000 > 7000 ceiling → only 300 and 5000 are valid.
        val r = DepthUtil.range(intArrayOf(100, 300, 5000, 8000))
        assertEquals(2, r.valid)
    }

    @Test fun `P2-P98 percentile range over a uniform ramp`() {
        // 100 values 1000,1010,…,1990 (all in 250..7000). sorted size 100:
        // p2Idx = round(2)-1 = 1 → 1010 ; p98Idx = round(98)-1 = 97 → 1970.
        val depths = IntArray(100) { 1000 + it * 10 }
        val r = DepthUtil.range(depths)
        assertEquals(100, r.valid)
        assertEquals(1010, r.minMm)
        assertEquals(1970, r.maxMm)
        assertTrue("max strictly above min", r.maxMm >= r.minMm + 1)
    }

    @Test fun `degenerate single value keeps max at least one above min`() {
        val r = DepthUtil.range(IntArray(10) { 1500 })
        assertTrue(r.maxMm >= r.minMm + 1)
    }
}

class DepthColorTest {
    @Test fun `invalid and out-of-range map to black`() {
        assertEquals(Triple(0, 0, 0), DepthUtil.depthColor(0, 1000, 2000))
        assertEquals(Triple(0, 0, 0), DepthUtil.depthColor(500, 1000, 2000))   // below floor
        assertEquals(Triple(0, 0, 0), DepthUtil.depthColor(3000, 1000, 2000))  // above ceiling
    }

    @Test fun `near floor is blue dominant`() {
        val (r, g, b) = DepthUtil.depthColor(1000, 1000, 2000) // t = 0
        assertTrue("blue beats red", b > r)
        assertTrue("blue beats green", b > g)
    }

    @Test fun `mid range is green dominant`() {
        val (r, g, b) = DepthUtil.depthColor(1500, 1000, 2000) // t = 0.5
        assertTrue("green beats red", g > r)
        assertTrue("green beats blue", g > b)
    }

    @Test fun `near ceiling is red dominant`() {
        val (r, g, b) = DepthUtil.depthColor(2000, 1000, 2000) // t = 1
        assertTrue("red beats green", r > g)
        assertTrue("red beats blue", r > b)
    }
}
