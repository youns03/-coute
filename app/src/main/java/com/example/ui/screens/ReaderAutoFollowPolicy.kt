package com.example.ui.screens

import com.example.audio.PlaybackStatus
import kotlin.math.roundToInt

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

/**
 * Smoothly clamps the collapsible top-bar translation offset without shifting LazyColumn layout.
 */
internal fun computeCollapsedTopBarOffsetPx(
    currentOffsetPx: Float,
    scrollDeltaY: Float,
    maxCollapsePx: Float,
    isPinnedOpen: Boolean
): Float {
    if (maxCollapsePx <= 0f || isPinnedOpen) return 0f
    return (currentOffsetPx + scrollDeltaY).coerceIn(-maxCollapsePx, 0f)
}

/**
 * Computes scroll adjustment (in px) to keep an already-visible active paragraph in the upper third
 * of the viewport without vibrating on minor sentence advances inside the same paragraph.
 */
internal fun computeUpperThirdScrollAdjustmentPx(
    itemOffsetPx: Int,
    viewportHeightPx: Int,
    topReservedPx: Int
): Int {
    if (viewportHeightPx <= 0) return 0
    val desiredTopPx = (viewportHeightPx * 0.16f).roundToInt().coerceAtLeast(topReservedPx)
    val upperComfortBoundPx = (viewportHeightPx * 0.45f).roundToInt().coerceAtLeast(desiredTopPx + 1)
    return when {
        itemOffsetPx < topReservedPx -> itemOffsetPx - desiredTopPx
        itemOffsetPx > upperComfortBoundPx -> itemOffsetPx - desiredTopPx
        else -> 0
    }
}

/**
 * Returns true when the active paragraph is clearly visible within the viewport bounds
 * so a suspended auto-follow pill can hide once the user scrolls back manually.
 */
internal fun isParagraphComfortablyVisible(
    activeParagraphIndex: Int,
    visibleItemsOffsetsAndSizes: List<Triple<Int, Int, Int>>,
    viewportHeightPx: Int,
    topReservedPx: Int
): Boolean {
    if (activeParagraphIndex < 0 || viewportHeightPx <= 0) return false
    val match = visibleItemsOffsetsAndSizes.firstOrNull { it.first == activeParagraphIndex } ?: return false
    val top = match.second
    val bottom = top + match.third
    return bottom > topReservedPx && top < (viewportHeightPx * 0.85f)
}
