package com.example.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import com.example.audio.PlaybackStatus
import com.example.audio.PlaybackUiState
import com.example.data.BookEntity
import com.example.data.ChapterEntity
import com.example.data.SentenceEntity
import com.example.data.UserSettings
import com.example.translation.DirectionPackState
import com.example.translation.TranslationDirection
import com.example.translation.TranslationManagerUiState
import com.example.translation.TranslationPackStatus
import com.example.ui.components.EcouteAtelierBackdrop
import com.example.ui.theme.EcouteTheme
import com.example.ui.viewmodel.ReaderUiState

private object ReaderPreviewFixtures {
    val sampleBook = BookEntity(
        id = "preview_book_en",
        title = "The Joy of Calm Reading",
        author = "Écoute Press",
        sourceType = "EPUB",
        sourceLanguage = "en",
        totalChapters = 3,
        totalSentences = 18
    )

    val sampleChapters = listOf(
        ChapterEntity(id = 1, bookId = sampleBook.id, chapterIndex = 0, title = "Chapter 1: Morning Quiet", sentenceCount = 6),
        ChapterEntity(id = 2, bookId = sampleBook.id, chapterIndex = 1, title = "Chapter 2: The Art of Listening", sentenceCount = 6),
        ChapterEntity(id = 3, bookId = sampleBook.id, chapterIndex = 2, title = "Chapter 3: Evening Reflection", sentenceCount = 6)
    )

    val sampleSentences = listOf(
        SentenceEntity(
            id = 101L,
            bookId = sampleBook.id,
            chapterIndex = 0,
            sentenceIndex = 0,
            paragraphIndex = 0,
            originalText = "A quiet room changes how we hear each phrase.",
            spokenText = "A quiet room changes how we hear each phrase.",
            cachedArabicTranslation = "تُغيّر الغرفة الهادئة طريقتنا في سماع كل عبارة."
        ),
        SentenceEntity(
            id = 102L,
            bookId = sampleBook.id,
            chapterIndex = 0,
            sentenceIndex = 1,
            paragraphIndex = 0,
            originalText = "When the voice slows down, meaning settles into memory naturally.",
            spokenText = "When the voice slows down, meaning settles into memory naturally.",
            cachedArabicTranslation = "وعندما يتباطأ الصوت، يستقر المعنى في الذاكرة بصورة طبيعية."
        ),
        SentenceEntity(
            id = 103L,
            bookId = sampleBook.id,
            chapterIndex = 0,
            sentenceIndex = 2,
            paragraphIndex = 1,
            originalText = "Reading alongside native speech turns difficult passages into familiar companions.",
            spokenText = "Reading alongside native speech turns difficult passages into familiar companions.",
            cachedArabicTranslation = "القراءة بالتزامن مع النطق السليم تحوّل المقاطع الصعبة إلى نصوص مألوفة."
        ),
        SentenceEntity(
            id = 104L,
            bookId = sampleBook.id,
            chapterIndex = 0,
            sentenceIndex = 3,
            paragraphIndex = 1,
            originalText = "Even brief daily sessions build lasting confidence across chapters.",
            spokenText = "Even brief daily sessions build lasting confidence across chapters.",
            cachedArabicTranslation = "وحتى الجلسات اليومية القصيرة تبني ثقة راسخة عبر الفصول."
        )
    )

    val shortTranslationMap = mapOf(
        0 to "تُغيّر الغرفة الهادئة طريقتنا في سماع كل عبارة. وعندما يتباطأ الصوت، يستقر المعنى في الذاكرة بصورة طبيعية.",
        1 to "القراءة بالتزامن مع النطق السليم تحوّل المقاطع الصعبة إلى نصوص مألوفة، وحتى الجلسات اليومية القصيرة تبني ثقة راسخة عبر الفصول."
    )

    val longTranslationMap = mapOf(
        0 to "تُغيّر الغرفة الهادئة طريقتنا في استقبال كل جملة ومراقبة إيقاعها الدقيق؛ فعندما يتباطأ الصوت المتزن وتبتعد الفوضى البصرية عن الصفحة، تصبح القراءة رحلة متصلة تتدرج فيها الألفاظ الجديدة إلى معرفة مألوفة.\n\nومع تكرار الاستماع المتزامن مع تتبع الكلمات، يتشكّل في الذهن ربط مباشر بين النغمة الصوتية والبنية المكتوبة دون إجهاد أو تشتت.",
        1 to "القراءة بالتزامن مع النطق السليم تحوّل المقاطع الصعبة إلى رفاق مألوفين، حتى في النصوص الأدبية الطويلة التي تتطلب صبراً وتركيزاً هادئاً."
    )

    val readyTranslationState = TranslationManagerUiState(
        enToAr = DirectionPackState(
            direction = TranslationDirection.EN_TO_AR,
            status = TranslationPackStatus.READY
        )
    )

    fun state(
        showTranslation: Boolean,
        longTranslation: Boolean = false
    ): ReaderUiState = ReaderUiState(
        book = sampleBook,
        chapters = sampleChapters,
        activeChapterIndex = 0,
        sentences = sampleSentences,
        paragraphTranslations = if (longTranslation) longTranslationMap else shortTranslationMap,
        revealedSentenceIds = if (showTranslation) setOf(101L, 102L, 103L, 104L) else emptySet()
    )

    fun playback(isPlaying: Boolean): PlaybackUiState = if (isPlaying) {
        PlaybackUiState(
            status = PlaybackStatus.PLAYING,
            bookId = sampleBook.id,
            bookTitle = sampleBook.title,
            chapterIndex = 0,
            chapterTitle = sampleChapters.first().title,
            sentenceIndex = 0,
            paragraphIndex = 0,
            totalSentencesInChapter = 4,
            currentSentenceText = sampleSentences.first().originalText,
            activeWordStartChar = 15,
            activeWordEndChar = 22,
            activeLanguageCode = "en"
        )
    } else {
        PlaybackUiState(status = PlaybackStatus.IDLE)
    }
}

@Composable
private fun ReaderPreviewHost(
    darkTheme: Boolean,
    state: ReaderUiState,
    playback: PlaybackUiState,
    fontSizeSp: Float = 22f,
    presentationMode: ParagraphPresentationMode = ParagraphPresentationMode.CURRENT_CARD,
    initialAutoFollowEnabled: Boolean = true
) {
    EcouteTheme(darkTheme = darkTheme) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            EcouteAtelierBackdrop {
                Box(modifier = Modifier.fillMaxSize()) {
                    ReaderScreen(
                        state = state,
                        settings = UserSettings(
                            nightMode = darkTheme,
                            readerFontSizeSp = fontSizeSp,
                            readerLineHeightMultiplier = 1.55f,
                            playbackSpeed = 1.0f
                        ),
                        playbackState = playback,
                        translationManagerState = ReaderPreviewFixtures.readyTranslationState,
                        wordLookupSheet = null,
                        onSelectChapter = {},
                        onToggleBookLanguage = {},
                        onPlayFromSentence = {},
                        onTogglePlayChapter = {},
                        onNextSentence = {},
                        onPrevSentence = {},
                        onSpeedSelected = {},
                        onToggleInlineTranslations = {},
                        onTranslateOrToggleSentence = {},
                        onTranslateOrToggleParagraph = { _, _ -> },
                        onSaveSentenceVocab = { _, _ -> },
                        onWordTapped = { _, _ -> },
                        onPronounceWord = {},
                        onSaveWordVocab = {},
                        onDismissWordSheet = {},
                        onToggleSearch = {},
                        onSearchQueryChange = {},
                        onJumpToSearchResult = {},
                        onFontSizeChange = {},
                        onLineHeightChange = {},
                        onNightModeChange = {},
                        onDownloadTranslationPack = {},
                        onDismissTranslationError = {},
                        onOpenFullPlayer = {},
                        isImmersive = false,
                        onToggleImmersive = {},
                        paragraphPresentationMode = presentationMode,
                        initialAutoFollowEnabled = initialAutoFollowEnabled
                    )
                }
            }
        }
    }
}

// =========================================================================
// Section 5 Comparison Previews: Current Card vs Design A (Flat) vs Design B (Active Tinted)
// =========================================================================

@Preview(name = "Compare Current Card (Dark 393dp)", showBackground = true, widthDp = 393, heightDp = 780)
@Composable
private fun PreviewParagraphComparison_CurrentCard_Dark() {
    ReaderPreviewHost(
        darkTheme = true,
        state = ReaderPreviewFixtures.state(showTranslation = true),
        playback = ReaderPreviewFixtures.playback(isPlaying = true),
        presentationMode = ParagraphPresentationMode.CURRENT_CARD
    )
}

@Preview(name = "Compare Design A Flat (Dark 393dp)", showBackground = true, widthDp = 393, heightDp = 780)
@Composable
private fun PreviewParagraphComparison_DesignA_Flat_Dark() {
    ReaderPreviewHost(
        darkTheme = true,
        state = ReaderPreviewFixtures.state(showTranslation = true),
        playback = ReaderPreviewFixtures.playback(isPlaying = true),
        presentationMode = ParagraphPresentationMode.FLAT_A
    )
}

@Preview(name = "Compare Design B Active Tint (Dark 393dp)", showBackground = true, widthDp = 393, heightDp = 780)
@Composable
private fun PreviewParagraphComparison_DesignB_Active_Dark() {
    ReaderPreviewHost(
        darkTheme = true,
        state = ReaderPreviewFixtures.state(showTranslation = true),
        playback = ReaderPreviewFixtures.playback(isPlaying = true),
        presentationMode = ParagraphPresentationMode.ACTIVE_HIGHLIGHT_B
    )
}

@Preview(name = "Compare Current Card (Light 393dp)", showBackground = true, widthDp = 393, heightDp = 780)
@Composable
private fun PreviewParagraphComparison_CurrentCard_Light() {
    ReaderPreviewHost(
        darkTheme = false,
        state = ReaderPreviewFixtures.state(showTranslation = true),
        playback = ReaderPreviewFixtures.playback(isPlaying = true),
        presentationMode = ParagraphPresentationMode.CURRENT_CARD
    )
}

@Preview(name = "Compare Design A Flat (Light 393dp)", showBackground = true, widthDp = 393, heightDp = 780)
@Composable
private fun PreviewParagraphComparison_DesignA_Flat_Light() {
    ReaderPreviewHost(
        darkTheme = false,
        state = ReaderPreviewFixtures.state(showTranslation = true),
        playback = ReaderPreviewFixtures.playback(isPlaying = true),
        presentationMode = ParagraphPresentationMode.FLAT_A
    )
}

@Preview(name = "Compare Design B Active Tint (Light 393dp)", showBackground = true, widthDp = 393, heightDp = 780)
@Composable
private fun PreviewParagraphComparison_DesignB_Active_Light() {
    ReaderPreviewHost(
        darkTheme = false,
        state = ReaderPreviewFixtures.state(showTranslation = true),
        playback = ReaderPreviewFixtures.playback(isPlaying = true),
        presentationMode = ParagraphPresentationMode.ACTIVE_HIGHLIGHT_B
    )
}

// =========================================================================
// Section 11 Required Scenarios Matrix
// =========================================================================

@Preview(name = "1. Stopped + Translation Hidden (Dark 393dp)", showBackground = true, widthDp = 393, heightDp = 780)
@Composable
private fun PreviewReader_Stopped_Hidden_Dark() {
    ReaderPreviewHost(
        darkTheme = true,
        state = ReaderPreviewFixtures.state(showTranslation = false),
        playback = ReaderPreviewFixtures.playback(isPlaying = false)
    )
}

@Preview(name = "2. Stopped + Translation Hidden (Light 393dp)", showBackground = true, widthDp = 393, heightDp = 780)
@Composable
private fun PreviewReader_Stopped_Hidden_Light() {
    ReaderPreviewHost(
        darkTheme = false,
        state = ReaderPreviewFixtures.state(showTranslation = false),
        playback = ReaderPreviewFixtures.playback(isPlaying = false)
    )
}

@Preview(name = "3. Playing + Floating Return Pill (Dark 393dp)", showBackground = true, widthDp = 393, heightDp = 780)
@Composable
private fun PreviewReader_Playing_ReturnPill_Dark() {
    ReaderPreviewHost(
        darkTheme = true,
        state = ReaderPreviewFixtures.state(showTranslation = false),
        playback = ReaderPreviewFixtures.playback(isPlaying = true),
        initialAutoFollowEnabled = false
    )
}

@Preview(name = "4. Long Arabic Translation (Dark 393dp)", showBackground = true, widthDp = 393, heightDp = 780)
@Composable
private fun PreviewReader_LongTranslation_Dark() {
    ReaderPreviewHost(
        darkTheme = true,
        state = ReaderPreviewFixtures.state(showTranslation = true, longTranslation = true),
        playback = ReaderPreviewFixtures.playback(isPlaying = true)
    )
}

@Preview(name = "5. Long Arabic Translation (Light 393dp)", showBackground = true, widthDp = 393, heightDp = 780)
@Composable
private fun PreviewReader_LongTranslation_Light() {
    ReaderPreviewHost(
        darkTheme = false,
        state = ReaderPreviewFixtures.state(showTranslation = true, longTranslation = true),
        playback = ReaderPreviewFixtures.playback(isPlaying = true)
    )
}

@Preview(name = "6. Narrow 360dp + fontScale 1.3 (Dark)", showBackground = true, widthDp = 360, heightDp = 760, fontScale = 1.3f)
@Composable
private fun PreviewReader_360dp_FontScale13_Dark() {
    ReaderPreviewHost(
        darkTheme = true,
        state = ReaderPreviewFixtures.state(showTranslation = true),
        playback = ReaderPreviewFixtures.playback(isPlaying = true)
    )
}

@Preview(name = "7. Narrow 360dp + fontScale 1.3 (Light)", showBackground = true, widthDp = 360, heightDp = 760, fontScale = 1.3f)
@Composable
private fun PreviewReader_360dp_FontScale13_Light() {
    ReaderPreviewHost(
        darkTheme = false,
        state = ReaderPreviewFixtures.state(showTranslation = true),
        playback = ReaderPreviewFixtures.playback(isPlaying = false)
    )
}

@Preview(name = "8. Wide 432dp Playing (Dark)", showBackground = true, widthDp = 432, heightDp = 820)
@Composable
private fun PreviewReader_432dp_Dark() {
    ReaderPreviewHost(
        darkTheme = true,
        state = ReaderPreviewFixtures.state(showTranslation = true),
        playback = ReaderPreviewFixtures.playback(isPlaying = true),
        presentationMode = ParagraphPresentationMode.ACTIVE_HIGHLIGHT_B
    )
}
