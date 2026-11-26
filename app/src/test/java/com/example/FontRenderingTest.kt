package com.example

import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder

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
    }

    @Test
    fun `All bundled custom font resources resolve and have valid OpenType tables`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val fontIds = listOf(
            "readex_pro" to R.font.readex_pro,
            "newsreader" to R.font.newsreader,
            "cormorant_garamond" to R.font.cormorant_garamond,
            "jetbrains_mono" to R.font.jetbrains_mono
        )
        fontIds.forEach { (name, resId) ->
            requireNotNull(ResourcesCompat.getFont(context, resId)) {
                "Font $name failed ResourcesCompat.getFont"
            }
            val bytes = context.resources.openRawResource(resId).use { it.readBytes() }
            assertTrue("Font $name too small", bytes.size > 1024)
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            val sfntVersion = bb.int
            assertTrue(
                "Font $name invalid SFNT header: $sfntVersion",
                sfntVersion == 0x00010000 || sfntVersion == 0x4F54544F
            )
            val numTables = bb.short.toInt() and 0xFFFF
            assertTrue("Font $name invalid table count: $numTables", numTables in 5..64)
            for (i in 0 until numTables) {
                val recordOffset = 12 + i * 16
                val offset = bb.getInt(recordOffset + 8).toLong() and 0xFFFFFFFFL
                val length = bb.getInt(recordOffset + 12).toLong() and 0xFFFFFFFFL
                assertTrue(
                    "Font $name table #$i exceeds file bounds ($offset + $length > ${bytes.size})",
                    offset + length <= bytes.size.toLong()
                )
            }
        }
    }
}
