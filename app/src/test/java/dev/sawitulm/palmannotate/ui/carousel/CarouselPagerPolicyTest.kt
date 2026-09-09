package dev.sawitulm.palmannotate.ui.carousel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarouselPagerPolicyTest {
    @Test
    fun `partial page offset settles after canvas gesture ends`() {
        assertTrue(needsPagerSettle(canvasGestureActive = false, pageOffsetFraction = 0.09f))
        assertFalse(needsPagerSettle(canvasGestureActive = true, pageOffsetFraction = 0.09f))
        assertFalse(needsPagerSettle(canvasGestureActive = false, pageOffsetFraction = 0f))
    }
}
