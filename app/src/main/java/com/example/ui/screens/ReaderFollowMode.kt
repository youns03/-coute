package com.example.ui.screens

import com.example.audio.PlaybackStatus

/**
 * UI-only state for following the sentence currently being read.
 *
 * A manual scroll detaches the reader without affecting playback. The user can
 * explicitly reattach it with the return-to-playback action.
 */
data class ReaderFollowState(
    val isFollowingAudio: Boolean = false,
    val detachedByUser: Boolean = false
) {
    fun onPlaybackSessionStarted(): ReaderFollowState = copy(
        isFollowingAudio = true,
        detachedByUser = false
    )

    fun onManualScroll(): ReaderFollowState = copy(
        isFollowingAudio = false,
        detachedByUser = true
    )

    fun onReturnToPlayback(): ReaderFollowState = copy(
        isFollowingAudio = true,
        detachedByUser = false
    )

    fun onPlaybackSessionStopped(): ReaderFollowState = ReaderFollowState()

    fun canAutoScroll(status: PlaybackStatus): Boolean =
        isFollowingAudio && status == PlaybackStatus.PLAYING ||
            isFollowingAudio && status == PlaybackStatus.BUFFERING
}
