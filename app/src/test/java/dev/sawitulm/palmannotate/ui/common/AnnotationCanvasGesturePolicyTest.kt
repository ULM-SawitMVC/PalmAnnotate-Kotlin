package dev.sawitulm.palmannotate.ui.common

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnotationCanvasGesturePolicyTest {
    @Test
    fun `review leaves one finger pan to the parent pager`() {
        assertFalse(canPanViewport(CanvasTool.VIEW, touchOnBox = false))
    }

    @Test
    fun `edit pans only when the gesture starts outside a box`() {
        assertTrue(canPanViewport(CanvasTool.SELECT, touchOnBox = false))
        assertFalse(canPanViewport(CanvasTool.SELECT, touchOnBox = true))
    }
}
