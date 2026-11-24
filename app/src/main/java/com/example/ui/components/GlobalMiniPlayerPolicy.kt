package com.example.ui.components

import com.example.audio.PlaybackStatus

internal fun shouldShowGlobalMiniPlayer(
    status: PlaybackStatus,
    isReaderDestination: Boolean,
    isFullPlayerDestination: Boolean
): Boolean = !isReaderDestination &&
    !isFullPlayerDestination &&
    status != PlaybackStatus.IDLE &&
    status != PlaybackStatus.ERROR
