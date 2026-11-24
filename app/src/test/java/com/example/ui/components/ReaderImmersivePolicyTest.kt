package com.example.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderImmersivePolicyTest {

    @Test
    fun `app chrome is hidden only for immersive reader route`() {
        assertTrue(shouldHideAppChrome(isReaderDestination = true, isImmersive = true))
        assertFalse(shouldHideAppChrome(isReaderDestination = true, isImmersive = false))
        assertFalse(shouldHideAppChrome(isReaderDestination = false, isImmersive = true))
        assertFalse(shouldHideAppChrome(isReaderDestination = false, isImmersive = false))
    }
}
