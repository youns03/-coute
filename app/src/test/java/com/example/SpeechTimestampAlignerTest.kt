package com.example

import com.example.domain.SpeechTimestampAligner
import com.example.domain.WordTimestampRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechTimestampAlignerTest {

    @Test
    fun `expanded French abbreviation and number map back to original tokens`() {
        val original = "M. Dupont a 12 ans."
        val spoken = "Monsieur Dupont a douze ans."
        val timestamps = spokenWords(spoken)

        val aligned = SpeechTimestampAligner.align(original, spoken, timestamps)

        assertEquals(listOf("M", "Dupont", "a", "12", "ans"), aligned.map { it.word })
        assertEquals(original.indexOf("M."), aligned[0].startChar)
        assertEquals(original.indexOf("12"), aligned[3].startChar)
        assertEquals(original.indexOf("12") + 2, aligned[3].endChar)
        aligned.forEach { timestamp ->
            assertTrue(timestamp.startChar >= 0)
            assertTrue(timestamp.endChar <= original.length)
            assertTrue(timestamp.endChar > timestamp.startChar)
        }
    }

    @Test
    fun `English expansion keeps later words synchronized`() {
        val original = "Dr. Smith has 12 books."
        val spoken = "Doctor Smith has twelve books."
        val timestamps = spokenWords(spoken)

        val aligned = SpeechTimestampAligner.align(original, spoken, timestamps)

        assertEquals(listOf("Dr", "Smith", "has", "12", "books"), aligned.map { it.word })
        assertEquals(original.indexOf("books"), aligned.last().startChar)
        assertEquals(original.indexOf("12"), aligned[3].startChar)
    }

    @Test
    fun `unchanged text preserves exact source ranges`() {
        val text = "Bonjour tout le monde."
        val timestamps = spokenWords(text)

        val aligned = SpeechTimestampAligner.align(text, text, timestamps)

        assertEquals(timestamps.map { it.startChar }, aligned.map { it.startChar })
        assertEquals(timestamps.map { it.endChar }, aligned.map { it.endChar })
    }

    private fun spokenWords(text: String): List<WordTimestampRange> {
        val regex = Regex("""[\p{L}\p{N}]+(?:['’\-][\p{L}\p{N}]+)*""")
        return regex.findAll(text).mapIndexed { index, match ->
            WordTimestampRange(
                word = match.value,
                startChar = match.range.first,
                endChar = match.range.last + 1,
                startSec = index.toFloat(),
                endSec = index + 0.5f
            )
        }.toList()
    }
}
