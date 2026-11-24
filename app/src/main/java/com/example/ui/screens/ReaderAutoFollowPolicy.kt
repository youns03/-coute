package com.example.ui.screens

import com.example.audio.PlaybackStatus

/**
 * Keeps playback-driven scrolling separate from the playback state itself.
 * Only active playback may move the reader automatically.
 */
internal fun shouldAutoFollowPlayback(
    status: PlaybackStatus,
    isAutoFollowEnabled: Boolean
): Boolean = status == PlaybackStatus.PLAYING && isAutoFollowEnabled

/**
 * Do not move the list when the active paragraph is already on screen.
 */
internal fun shouldScrollToParagraph(
    targetItemIndex: Int,
    visibleItemIndices: Set<Int>
): Boolean = targetItemIndex >= 0 && targetItemIndex !in visibleItemIndices
