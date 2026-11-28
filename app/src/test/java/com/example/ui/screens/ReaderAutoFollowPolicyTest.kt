package com.example.ui.screens

import com.example.audio.PlaybackStatus
import com.example.ui.theme.InkBackgroundDark
import com.example.ui.theme.InkSurfaceDark
import com.example.ui.theme.InkSurfaceVariantDark
import com.example.ui.theme.OnInkVariantDark
import com.example.ui.theme.OnPaperVariantLight
import com.example.ui.theme.OnReaderSageAccentDark
import com.example.ui.theme.OnReaderSageAccentLight
import com.example.ui.theme.PaperBackgroundLight
import com.example.ui.theme.PaperSurfaceLight
import com.example.ui.theme.ReaderSageAccentDark
import com.example.ui.theme.ReaderSageAccentLight
import com.example.ui.theme.ReaderTranslationTextDark
import com.example.ui.theme.ReaderTranslationTextLight
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

class ReaderAutoFollowPolicyTest {

    @Test
    fun `auto follow is enabled only while playing and following is enabled`() {
        assertTrue(shouldAutoFollowPlayback(PlaybackStatus.PLAYING, true))
        assertFalse(shouldAutoFollowPlayback(PlaybackStatus.PAUSED, true))
        assertFalse(shouldAutoFollowPlayback(PlaybackStatus.BUFFERING, true))
        assertFalse(shouldAutoFollowPlayback(PlaybackStatus.IDLE, true))
        assertFalse(shouldAutoFollowPlayback(PlaybackStatus.PLAYING, false))
    }

    @Test
    fun `visible active paragraph does not trigger a scroll`() {
        assertFalse(shouldScrollToParagraph(3, setOf(1, 2, 3, 4)))
    }

    @Test
    fun `off screen active paragraph triggers a scroll`() {
        assertTrue(shouldScrollToParagraph(8, setOf(1, 2, 3, 4)))
    }

    @Test
    fun `invalid paragraph index never triggers a scroll`() {
        assertFalse(shouldScrollToParagraph(-1, setOf(0, 1)))
    }

    @Test
    fun `collapsible top bar clamps smoothly between hidden and visible and pins open when needed`() {
        assertEquals(-40f, computeCollapsedTopBarOffsetPx(0f, -40f, 114f, isPinnedOpen = false), 0.01f)
        assertEquals(-114f, computeCollapsedTopBarOffsetPx(-90f, -50f, 114f, isPinnedOpen = false), 0.01f)
        assertEquals(0f, computeCollapsedTopBarOffsetPx(-30f, 80f, 114f, isPinnedOpen = false), 0.01f)
        assertEquals(0f, computeCollapsedTopBarOffsetPx(-80f, -20f, 114f, isPinnedOpen = true), 0.01f)
    }

    @Test
    fun `upper third adjustment does not jitter when paragraph is already in upper comfort band`() {
        val adjustment = computeUpperThirdScrollAdjustmentPx(
            itemOffsetPx = 220,
            viewportHeightPx = 1000,
            topReservedPx = 120
        )
        assertEquals(0, adjustment)

        val lowerAdjustment = computeUpperThirdScrollAdjustmentPx(
            itemOffsetPx = 680,
            viewportHeightPx = 1000,
            topReservedPx = 120
        )
        assertTrue("Paragraph below upper comfort zone should scroll upward", lowerAdjustment > 0)
    }

    @Test
    fun `paragraph visibility helper detects when user scrolls back to active item`() {
        val visible = listOf(Triple(2, 150, 260), Triple(3, 430, 280))
        assertTrue(isParagraphComfortablyVisible(2, visible, viewportHeightPx = 1000, topReservedPx = 120))
        assertFalse(isParagraphComfortablyVisible(5, visible, viewportHeightPx = 1000, topReservedPx = 120))
    }

    @Test
    fun `reader theme tokens satisfy minimum WCAG 4_5 to 1 contrast`() {
        assertTrue(contrastRatio(OnInkVariantDark, InkSurfaceDark) >= 4.5)
        assertTrue(contrastRatio(OnInkVariantDark, InkSurfaceVariantDark) >= 4.5)
        assertTrue(contrastRatio(ReaderTranslationTextDark, InkBackgroundDark) >= 4.5)
        assertTrue(contrastRatio(ReaderSageAccentDark, OnReaderSageAccentDark) >= 4.5)
        assertTrue(contrastRatio(ReaderSageAccentDark, InkSurfaceDark) >= 4.5)

        assertTrue(contrastRatio(OnPaperVariantLight, PaperSurfaceLight) >= 4.5)
        assertTrue(contrastRatio(ReaderTranslationTextLight, PaperBackgroundLight) >= 4.5)
        assertTrue(contrastRatio(ReaderSageAccentLight, OnReaderSageAccentLight) >= 4.5)
    }

    private fun contrastRatio(first: Color, second: Color): Double {
        val l1 = first.luminance().toDouble()
        val l2 = second.luminance().toDouble()
        return (max(l1, l2) + 0.05) / (min(l1, l2) + 0.05)
    }
}
