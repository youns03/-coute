package com.example.ui.screens

import com.example.audio.PlaybackStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderFollowModeTest {
    @Test
    fun `playback start enables following`() {
        val state = ReaderFollowState().onPlaybackSessionStarted()

        assertTrue(state.isFollowingAudio)
        assertFalse(state.detachedByUser)
        assertTrue(state.canAutoScroll(PlaybackStatus.BUFFERING))
        assertTrue(state.canAutoScroll(PlaybackStatus.PLAYING))
    }

    @Test
    fun `manual scroll detaches without stopping playback`() {
        val state = ReaderFollowState()
            .onPlaybackSessionStarted()
            .onManualScroll()

        assertFalse(state.isFollowingAudio)
        assertTrue(state.detachedByUser)
        assertFalse(state.canAutoScroll(PlaybackStatus.PLAYING))
    }

    @Test
    fun `return action reattaches without restarting playback`() {
        val state = ReaderFollowState()
            .onPlaybackSessionStarted()
            .onManualScroll()
            .onReturnToPlayback()

        assertTrue(state.isFollowingAudio)
        assertFalse(state.detachedByUser)
        assertTrue(state.canAutoScroll(PlaybackStatus.PLAYING))
    }

    @Test
    fun `paused and idle playback never auto scrolls`() {
        val state = ReaderFollowState().onPlaybackSessionStarted()

        assertFalse(state.canAutoScroll(PlaybackStatus.PAUSED))
        assertFalse(state.canAutoScroll(PlaybackStatus.IDLE))
        assertFalse(state.canAutoScroll(PlaybackStatus.ERROR))
    }

    @Test
    fun `stopping resets detached state for the next session`() {
        val state = ReaderFollowState()
            .onPlaybackSessionStarted()
            .onManualScroll()
            .onPlaybackSessionStopped()

        assertFalse(state.isFollowingAudio)
        assertFalse(state.detachedByUser)
    }
}
