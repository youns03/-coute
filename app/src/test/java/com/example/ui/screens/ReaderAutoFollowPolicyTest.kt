package com.example.ui.screens

import com.example.audio.PlaybackStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
