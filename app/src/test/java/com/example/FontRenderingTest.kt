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
    fun `All Readex Pro weights embed Arabic glyphs harakat French accents and GSUB shaping`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val requiredChars =
            "ءآأؤإئابةتثجحخدذرزسشصضطظعغـفقكلمنهويى" +
                "ًٌٍَُِّْ" +
                "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz" +
                "ÀÂÆÇÉÈÊËÎÏÔŒÙÛÜŸàâæçéèêëîïôœùûüÿ0123456789"
        val requiredFeatures = setOf("init", "medi", "fina", "rlig")

        val readexWeights = listOf(
            "readex_pro" to R.font.readex_pro,
            "readex_pro_medium" to R.font.readex_pro_medium,
            "readex_pro_semibold" to R.font.readex_pro_semibold,
            "readex_pro_bold" to R.font.readex_pro_bold
        )

        readexWeights.forEach { (name, resId) ->
            val bytes = context.resources.openRawResource(resId).use { it.readBytes() }
            val cmap = extractFormat4CodePoints(bytes)
            val missingChars = requiredChars.filter { it.code !in cmap }
            assertTrue(
                "Font $name is missing required codepoints (e.g. Arabic/French): ${missingChars.take(10)}",
                missingChars.isEmpty()
            )
            val arabicFeatures = extractArabicGsubFeatures(bytes)
            val missingFeatures = requiredFeatures - arabicFeatures
            assertTrue(
                "Font $name is missing Arabic GSUB shaping features: $missingFeatures",
                missingFeatures.isEmpty()
            )
        }
    }

    @Test
    fun `Newsreader weights cover complete French character set including Y diaeresis`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val requiredFrench =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz" +
                "ÀÂÆÇÉÈÊËÎÏÔŒÙÛÜŸàâæçéèêëîïôœùûüÿ0123456789"
        val newsreaderWeights = listOf(
            "newsreader" to R.font.newsreader,
            "newsreader_medium" to R.font.newsreader_medium,
            "newsreader_semibold" to R.font.newsreader_semibold
        )
        newsreaderWeights.forEach { (name, resId) ->
            val bytes = context.resources.openRawResource(resId).use { it.readBytes() }
            val cmap = extractFormat4CodePoints(bytes)
            val missing = requiredFrench.filter { it.code !in cmap }
            assertTrue("Font $name missing French glyphs: $missing", missing.isEmpty())
        }
    }

    @Test
    fun `All bundled custom font resources resolve and have valid OpenType tables`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val fontIds = listOf(
            "readex_pro" to R.font.readex_pro,
            "readex_pro_medium" to R.font.readex_pro_medium,
            "readex_pro_semibold" to R.font.readex_pro_semibold,
            "readex_pro_bold" to R.font.readex_pro_bold,
            "newsreader" to R.font.newsreader,
            "newsreader_medium" to R.font.newsreader_medium,
            "newsreader_semibold" to R.font.newsreader_semibold,
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

    private fun findSfntTable(bytes: ByteArray, tag: String): Pair<Int, Int>? {
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val numTables = bb.getShort(4).toInt() and 0xFFFF
        for (i in 0 until numTables) {
            val rec = 12 + i * 16
            val t = String(bytes, rec, 4, Charsets.US_ASCII)
            if (t == tag) {
                val offset = bb.getInt(rec + 8)
                val length = bb.getInt(rec + 12)
                return offset to length
            }
        }
        return null
    }

    private fun extractFormat4CodePoints(bytes: ByteArray): Set<Int> {
        val (cmapOffset, _) = findSfntTable(bytes, "cmap") ?: return emptySet()
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val numSubtables = bb.getShort(cmapOffset + 2).toInt() and 0xFFFF
        val cps = HashSet<Int>(1024)
        for (i in 0 until numSubtables) {
            val subOffset = cmapOffset + bb.getInt(cmapOffset + 4 + i * 8 + 4)
            val format = bb.getShort(subOffset).toInt() and 0xFFFF
            if (format == 4) {
                val segCountX2 = bb.getShort(subOffset + 6).toInt() and 0xFFFF
                val segCount = segCountX2 / 2
                val endArrayOff = subOffset + 14
                val startArrayOff = endArrayOff + 2 * segCount + 2
                for (s in 0 until segCount) {
                    val start = bb.getShort(startArrayOff + s * 2).toInt() and 0xFFFF
                    val end = bb.getShort(endArrayOff + s * 2).toInt() and 0xFFFF
                    if (start != 0xFFFF && end >= start) {
                        for (cp in start..end) cps.add(cp)
                    }
                }
            }
        }
        return cps
    }

    private fun extractArabicGsubFeatures(bytes: ByteArray): Set<String> {
        val (gsubOffset, _) = findSfntTable(bytes, "GSUB") ?: return emptySet()
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val scriptListOffset = gsubOffset + (bb.getShort(gsubOffset + 4).toInt() and 0xFFFF)
        val featureListOffset = gsubOffset + (bb.getShort(gsubOffset + 6).toInt() and 0xFFFF)
        val scriptCount = bb.getShort(scriptListOffset).toInt() and 0xFFFF
        val featureCount = bb.getShort(featureListOffset).toInt() and 0xFFFF
        val featureTags = (0 until featureCount).map { idx ->
            String(bytes, featureListOffset + 2 + idx * 6, 4, Charsets.US_ASCII)
        }
        val activeFeatures = mutableSetOf<String>()
        for (i in 0 until scriptCount) {
            val recOff = scriptListOffset + 2 + i * 6
            val tag = String(bytes, recOff, 4, Charsets.US_ASCII)
            if (tag == "arab") {
                val arabScriptOff = scriptListOffset + (bb.getShort(recOff + 4).toInt() and 0xFFFF)
                val defLangRel = bb.getShort(arabScriptOff).toInt() and 0xFFFF
                val langSysOffsets = mutableListOf<Int>()
                if (defLangRel != 0) langSysOffsets.add(arabScriptOff + defLangRel)
                val langSysCount = bb.getShort(arabScriptOff + 2).toInt() and 0xFFFF
                for (j in 0 until langSysCount) {
                    val lsRel = bb.getShort(arabScriptOff + 4 + j * 6 + 4).toInt() and 0xFFFF
                    langSysOffsets.add(arabScriptOff + lsRel)
                }
                for (lsOff in langSysOffsets) {
                    val reqIdx = bb.getShort(lsOff + 2).toInt() and 0xFFFF
                    if (reqIdx != 0xFFFF && reqIdx < featureTags.size) {
                        activeFeatures.add(featureTags[reqIdx])
                    }
                    val fCount = bb.getShort(lsOff + 4).toInt() and 0xFFFF
                    for (k in 0 until fCount) {
                        val fIdx = bb.getShort(lsOff + 6 + k * 2).toInt() and 0xFFFF
                        if (fIdx < featureTags.size) {
                            activeFeatures.add(featureTags[fIdx])
                        }
                    }
                }
            }
        }
        return activeFeatures
    }
}
