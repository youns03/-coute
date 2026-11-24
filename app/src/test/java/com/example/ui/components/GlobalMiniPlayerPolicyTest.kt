package com.example.ui.components

import com.example.audio.PlaybackStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalMiniPlayerPolicyTest {

    @Test
    fun `mini player appears on other screens during active playback`() {
        assertTrue(shouldShowGlobalMiniPlayer(PlaybackStatus.PLAYING, false, false))
        assertTrue(shouldShowGlobalMiniPlayer(PlaybackStatus.PAUSED, false, false))
        assertTrue(shouldShowGlobalMiniPlayer(PlaybackStatus.BUFFERING, false, false))
    }

    @Test
    fun `mini player is hidden on reader and full player screens`() {
        assertFalse(shouldShowGlobalMiniPlayer(PlaybackStatus.PLAYING, true, false))
        assertFalse(shouldShowGlobalMiniPlayer(PlaybackStatus.PLAYING, false, true))
    }

    @Test
    fun `mini player is hidden when playback is idle or failed`() {
        assertFalse(shouldShowGlobalMiniPlayer(PlaybackStatus.IDLE, false, false))
        assertFalse(shouldShowGlobalMiniPlayer(PlaybackStatus.ERROR, false, false))
    }
}
