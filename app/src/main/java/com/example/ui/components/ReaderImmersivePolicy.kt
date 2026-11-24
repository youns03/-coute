package com.example.ui.components

internal fun shouldHideAppChrome(
    isReaderDestination: Boolean,
    isImmersive: Boolean
): Boolean = isReaderDestination && isImmersive
