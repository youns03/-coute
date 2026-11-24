package com.example

import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import androidx.test.core.app.ApplicationProvider
import com.example.R
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FontRenderingTest {

    @Test
    fun `Readex Pro resolves connected Arabic words and mixed text`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val typeface = requireNotNull(ResourcesCompat.getFont(context, R.font.readex_pro))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.typeface = Typeface.create(typeface, Typeface.NORMAL)
            textSize = 40f
        }

        assertTrue("Connected Arabic text should produce visible output", paint.measureText("سلام") > 0f)
        assertTrue("Mixed Arabic/French text should produce visible glyph widths", paint.measureText("سلام français") > 0f)

        val widths = FloatArray("سلام".length)
        paint.getTextWidths("سلام", widths)
        assertTrue("Arabic shaping must produce non-zero measured output", widths.sum() > 0f)
    }
}
