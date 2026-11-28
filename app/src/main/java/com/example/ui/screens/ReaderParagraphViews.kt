package com.example.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.audio.PlaybackStatus
import com.example.data.SentenceEntity
import com.example.data.importengine.LanguageDetector
import com.example.ui.components.AtelierCard
import com.example.ui.theme.ArabicSansFamily
import com.example.ui.theme.FrenchSerifFamily
import com.example.ui.theme.LocalEcouteAtelierColors
import com.example.ui.theme.UiSansFamily
import com.example.ui.theme.withTabularNumbers

enum class ParagraphPresentationMode {
    CURRENT_CARD,
    FLAT_A,
    ACTIVE_HIGHLIGHT_B
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ReaderParagraphItem(
    paragraphIndex: Int,
    paragraphSentences: List<SentenceEntity>,
    isArabicSource: Boolean,
    isActiveParagraph: Boolean,
    isSelectedParagraph: Boolean,
    isLastParagraph: Boolean,
    activeSentenceIdx: Int,
    activeWordStartChar: Int,
    activeWordEndChar: Int,
    playbackStatus: PlaybackStatus,
    isAutoFollowEnabled: Boolean,
    returnToAudioRequest: Int,
    showInlineTranslations: Boolean,
    isParagraphTranslating: Boolean,
    cachedParagraphTranslation: String?,
    revealedSentenceIds: Set<Long>,
    readerFontSizeSp: Float,
    readerLineHeightMultiplier: Float,
    presentationMode: ParagraphPresentationMode,
    onSelectParagraph: (Int) -> Unit,
    onTranslateOrToggleParagraph: (Int, List<SentenceEntity>) -> Unit,
    onWordTapped: (String, SentenceEntity) -> Unit,
    onPlayFromSentence: (SentenceEntity) -> Unit,
    onSaveParagraphVocab: (List<SentenceEntity>, String) -> Unit,
    onSaveSentenceVocab: (SentenceEntity, String) -> Unit,
    modifier: Modifier = Modifier
) {
    val atelier = LocalEcouteAtelierColors.current
    val allParagraphRevealed = paragraphSentences.all {
        showInlineTranslations || it.id in revealedSentenceIds
    }
    val showParagraphTranslation = !isArabicSource && (
        showInlineTranslations || paragraphSentences.any { it.id in revealedSentenceIds }
    )
    val currentSentence = paragraphSentences.firstOrNull { it.sentenceIndex == activeSentenceIdx }
    val effectiveOriginalFontSp = readerFontSizeSp.coerceIn(18f, 30f)
    val effectiveLineMultiplier = readerLineHeightMultiplier.coerceIn(1.3f, 2.2f)
    val translationFontSp = (effectiveOriginalFontSp - 3f).coerceIn(15f, 26f)

    val innerBody: @Composable () -> Unit = {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Aligned paragraph header row: single Arabic number + 48dp icon-only translate button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (isActiveParagraph && presentationMode != ParagraphPresentationMode.CURRENT_CARD) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(atelier.readerSageAccent)
                        )
                    }
                    Text(
                        text = stringResource(R.string.reader_paragraph_number, paragraphIndex + 1),
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontFamily = UiSansFamily,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        ).withTabularNumbers(),
                        color = if (isActiveParagraph) atelier.readerSageAccent else atelier.readerMetaText
                    )
                }

                if (!isArabicSource) {
                    ParagraphTranslateIconButton(
                        isTranslating = isParagraphTranslating,
                        hasTranslation = !cachedParagraphTranslation.isNullOrBlank(),
                        isTranslationVisible = allParagraphRevealed,
                        autoInlineEnabled = showInlineTranslations,
                        onClick = { onTranslateOrToggleParagraph(paragraphIndex, paragraphSentences) },
                        modifier = Modifier.testTag("translate_paragraph_$paragraphIndex")
                    )
                }
            }

            Spacer(Modifier.height(6.dp))

            val isArabicParagraph = remember(isArabicSource, paragraphSentences) {
                isArabicSource || LanguageDetector.isArabicText(
                    paragraphSentences.joinToString(" ") { it.originalText }
                )
            }
            val paragraphBringIntoViewRequester = remember(paragraphIndex) { BringIntoViewRequester() }
            var paragraphTextLayout by remember(paragraphIndex) { mutableStateOf<TextLayoutResult?>(null) }

            CompositionLocalProvider(
                LocalLayoutDirection provides if (isArabicParagraph) LayoutDirection.Rtl else LayoutDirection.Ltr
            ) {
                val wordHighlightColor = atelier.readerSageAccent
                val wordHighlightBg = atelier.wordHighlightBg
                val sentenceHighlightBg = atelier.readerSentenceHighlightBg
                val annotated = remember(
                    paragraphSentences,
                    activeSentenceIdx,
                    activeWordStartChar,
                    activeWordEndChar,
                    wordHighlightColor,
                    wordHighlightBg,
                    sentenceHighlightBg
                ) {
                    buildParagraphAnnotatedString(
                        sentences = paragraphSentences,
                        activeSentenceIndex = activeSentenceIdx,
                        activeWordStart = activeWordStartChar,
                        activeWordEnd = activeWordEndChar,
                        highlightColor = wordHighlightColor,
                        wordHighlightBackground = wordHighlightBg,
                        sentenceHighlightBackground = sentenceHighlightBg
                    )
                }

                LaunchedEffect(
                    activeSentenceIdx,
                    playbackStatus,
                    isAutoFollowEnabled,
                    returnToAudioRequest,
                    paragraphTextLayout,
                    annotated
                ) {
                    val shouldRevealCurrentSentence = currentSentence != null &&
                        isAutoFollowEnabled &&
                        (playbackStatus == PlaybackStatus.PLAYING || returnToAudioRequest > 0)
                    val layout = paragraphTextLayout
                    if (shouldRevealCurrentSentence && layout != null && layout.size.width > 0) {
                        val sentenceRange = annotated.getStringAnnotations(
                            tag = "SENTENCE",
                            start = 0,
                            end = annotated.length
                        ).firstOrNull { it.item == currentSentence.id.toString() }
                        if (sentenceRange != null && sentenceRange.end > sentenceRange.start) {
                            val firstLine = layout.getLineForOffset(sentenceRange.start)
                            val lastLine = layout.getLineForOffset(sentenceRange.end - 1)
                            paragraphBringIntoViewRequester.bringIntoView(
                                Rect(
                                    left = 0f,
                                    top = layout.getLineTop(firstLine),
                                    right = layout.size.width.toFloat(),
                                    bottom = layout.getLineBottom(lastLine)
                                )
                            )
                        }
                    }
                }

                Text(
                    text = annotated,
                    onTextLayout = { paragraphTextLayout = it },
                    style = TextStyle(
                        fontFamily = if (isArabicParagraph) ArabicSansFamily else FrenchSerifFamily,
                        fontWeight = FontWeight.Normal,
                        fontSize = effectiveOriginalFontSp.sp,
                        lineHeight = (effectiveOriginalFontSp * effectiveLineMultiplier).sp,
                        color = MaterialTheme.colorScheme.onBackground,
                        textAlign = TextAlign.Start
                    ),
                    modifier = Modifier
                        .bringIntoViewRequester(paragraphBringIntoViewRequester)
                        .fillMaxWidth()
                        .pointerInput(annotated, paragraphSentences, currentSentence, isArabicParagraph) {
                            detectTapGestures { pos ->
                                onSelectParagraph(paragraphIndex)
                                val layout = paragraphTextLayout ?: return@detectTapGestures
                                val offset = layout.getOffsetForPosition(pos)
                                val word = annotated.getStringAnnotations("WORD", offset, offset).firstOrNull()
                                val sentenceTag = annotated.getStringAnnotations("SENTENCE", offset, offset).firstOrNull()
                                val sentence = paragraphSentences.firstOrNull {
                                    it.id.toString() == sentenceTag?.item
                                }
                                if (sentence != null) {
                                    if (word != null && !isArabicParagraph) {
                                        onWordTapped(word.item, sentence)
                                    } else {
                                        onPlayFromSentence(sentence)
                                    }
                                } else if (currentSentence != null) {
                                    onPlayFromSentence(currentSentence)
                                }
                            }
                        }
                        .testTag("reader_paragraph_text_$paragraphIndex")
                )
            }

            if (showParagraphTranslation) {
                Spacer(Modifier.height(14.dp))
                ParagraphTranslationSection(
                    paragraphIndex = paragraphIndex,
                    paragraphSentences = paragraphSentences,
                    isParagraphTranslating = isParagraphTranslating,
                    cachedParagraphTranslation = cachedParagraphTranslation,
                    translationFontSp = translationFontSp,
                    onSaveParagraphVocab = onSaveParagraphVocab,
                    onSaveSentenceVocab = onSaveSentenceVocab
                )
            }
        }
    }

    when (presentationMode) {
        ParagraphPresentationMode.CURRENT_CARD -> {
            AtelierCard(
                containerColor = if (isActiveParagraph) {
                    MaterialTheme.colorScheme.surface
                } else {
                    MaterialTheme.colorScheme.surface.copy(alpha = 0.88f)
                },
                borderColor = if (isActiveParagraph) {
                    atelier.activeParagraphStripe.copy(alpha = 0.45f)
                } else {
                    atelier.cardBorder
                },
                elevation = if (isActiveParagraph) 1.dp else 0.dp,
                shape = RoundedCornerShape(20.dp),
                modifier = modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                Row(modifier = Modifier.height(IntrinsicSize.Min)) {
                    if (isActiveParagraph) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .fillMaxHeight()
                                .background(atelier.activeParagraphStripe)
                        )
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        innerBody()
                    }
                }
            }
        }

        ParagraphPresentationMode.FLAT_A -> {
            Column(
                modifier = modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
            ) {
                innerBody()
                if (!isLastParagraph) {
                    Spacer(Modifier.height(20.dp))
                    HorizontalDivider(
                        thickness = 0.8.dp,
                        color = atelier.readerDivider.copy(alpha = 0.6f)
                    )
                }
            }
        }

        ParagraphPresentationMode.ACTIVE_HIGHLIGHT_B -> {
            val targetBg = when {
                isActiveParagraph -> atelier.readerActiveParagraphBg
                isSelectedParagraph -> atelier.readerSelectedParagraphBg
                else -> Color.Transparent
            }
            val animatedBg by animateColorAsState(
                targetValue = targetBg,
                animationSpec = tween(durationMillis = 220),
                label = "active_paragraph_bg"
            )
            val isHighlighted = isActiveParagraph || isSelectedParagraph
            Surface(
                color = animatedBg,
                shape = RoundedCornerShape(20.dp),
                modifier = modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
            ) {
                Box(
                    modifier = Modifier.padding(
                        horizontal = 12.dp,
                        vertical = if (isHighlighted) 10.dp else 2.dp
                    )
                ) {
                    innerBody()
                }
            }
        }
    }
}

@Composable
internal fun ParagraphTranslateIconButton(
    isTranslating: Boolean,
    hasTranslation: Boolean,
    isTranslationVisible: Boolean,
    autoInlineEnabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val atelier = LocalEcouteAtelierColors.current
    val enabled = !isTranslating && !(autoInlineEnabled && hasTranslation)
    val contentDesc = when {
        isTranslating -> stringResource(R.string.reader_paragraph_translating)
        hasTranslation && autoInlineEnabled -> stringResource(R.string.reader_translation_complete)
        hasTranslation && isTranslationVisible -> stringResource(R.string.reader_hide_translation)
        hasTranslation -> stringResource(R.string.reader_show_translation)
        else -> stringResource(R.string.reader_translate_paragraph)
    }

    val isShownState = hasTranslation && isTranslationVisible
    val bgColor = when {
        isShownState -> atelier.readerSageAccent.copy(alpha = 0.14f)
        else -> Color.Transparent
    }
    val borderStroke = if (isShownState) {
        Modifier.border(
            width = 1.dp,
            color = atelier.readerSageAccent.copy(alpha = 0.35f),
            shape = RoundedCornerShape(12.dp)
        )
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .size(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .then(borderStroke)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics {
                role = Role.Button
                contentDescription = contentDesc
            },
        contentAlignment = Alignment.Center
    ) {
        when {
            isTranslating -> CircularProgressIndicator(
                modifier = Modifier.size(19.dp),
                strokeWidth = 2.dp,
                color = atelier.readerSageAccent
            )
            hasTranslation && autoInlineEnabled -> Icon(
                imageVector = Icons.Rounded.CheckCircleOutline,
                contentDescription = null,
                tint = atelier.readerSageAccent,
                modifier = Modifier.size(22.dp)
            )
            isShownState -> Icon(
                imageVector = Icons.Rounded.VisibilityOff,
                contentDescription = null,
                tint = atelier.readerSageAccent,
                modifier = Modifier.size(22.dp)
            )
            else -> {
                Icon(
                    imageVector = Icons.Rounded.Translate,
                    contentDescription = null,
                    tint = if (hasTranslation) atelier.readerSageAccent else atelier.readerMetaText,
                    modifier = Modifier.size(22.dp)
                )
                if (hasTranslation) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(atelier.readerSageAccent)
                    )
                }
            }
        }
    }
}

@Composable
private fun ParagraphTranslationSection(
    paragraphIndex: Int,
    paragraphSentences: List<SentenceEntity>,
    isParagraphTranslating: Boolean,
    cachedParagraphTranslation: String?,
    translationFontSp: Float,
    onSaveParagraphVocab: (List<SentenceEntity>, String) -> Unit,
    onSaveSentenceVocab: (SentenceEntity, String) -> Unit
) {
    val atelier = LocalEcouteAtelierColors.current
    val resolvedTranslation = cachedParagraphTranslation?.takeIf(String::isNotBlank)
        ?: paragraphSentences.mapNotNull { it.cachedArabicTranslation?.takeIf(String::isNotBlank) }
            .joinToString("\n")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .width(2.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(1.dp))
                .background(atelier.readerSageAccent.copy(alpha = 0.45f))
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            when {
                isParagraphTranslating -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = atelier.readerSageAccent
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.reader_translating_paragraph_locally),
                            style = MaterialTheme.typography.bodySmall,
                            color = atelier.readerMetaText
                        )
                    }
                }
                resolvedTranslation.isNotBlank() -> {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                        Text(
                            text = resolvedTranslation,
                            style = TextStyle(
                                fontFamily = ArabicSansFamily,
                                fontWeight = FontWeight.Normal,
                                fontSize = translationFontSp.sp,
                                lineHeight = (translationFontSp * 1.7f).sp,
                                color = atelier.readerTranslationText,
                                textAlign = TextAlign.Start
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    val hasAnySentenceCached = paragraphSentences.any { !it.cachedArabicTranslation.isNullOrBlank() }
                    if (!cachedParagraphTranslation.isNullOrBlank() || hasAnySentenceCached) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (!cachedParagraphTranslation.isNullOrBlank()) {
                                TextButton(
                                    onClick = {
                                        onSaveParagraphVocab(paragraphSentences, cachedParagraphTranslation)
                                    },
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .testTag("save_paragraph_$paragraphIndex")
                                ) {
                                    Icon(
                                        Icons.Rounded.BookmarkAdd,
                                        contentDescription = stringResource(R.string.reader_save_paragraph_content_description),
                                        tint = atelier.readerSageAccent,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(5.dp))
                                    Text(
                                        stringResource(R.string.reader_save_paragraph_to_vocabulary),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = atelier.readerSageAccent
                                    )
                                }
                            } else {
                                Spacer(Modifier.width(1.dp))
                            }
                            Row(horizontalArrangement = Arrangement.End) {
                                paragraphSentences.forEach { sentence ->
                                    val translated = sentence.cachedArabicTranslation
                                    if (!translated.isNullOrBlank()) {
                                        IconButton(
                                            onClick = { onSaveSentenceVocab(sentence, translated) },
                                            modifier = Modifier
                                                .minimumInteractiveComponentSize()
                                                .testTag("save_sentence_${sentence.sentenceIndex}")
                                        ) {
                                            Icon(
                                                Icons.Rounded.BookmarkAdd,
                                                contentDescription = stringResource(
                                                    R.string.reader_save_sentence_translation_content_description
                                                ),
                                                tint = atelier.readerMetaText,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                else -> {
                    Text(
                        text = stringResource(R.string.reader_translate_paragraph_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = atelier.readerMetaText
                    )
                }
            }
        }
    }
}

internal fun buildParagraphAnnotatedString(
    sentences: List<SentenceEntity>,
    activeSentenceIndex: Int,
    activeWordStart: Int,
    activeWordEnd: Int,
    highlightColor: Color,
    wordHighlightBackground: Color,
    sentenceHighlightBackground: Color
): AnnotatedString = buildAnnotatedString {
    var paragraphOffset = 0
    val wordRegex = Regex("""[\p{L}\p{N}]+(?:['’\-][\p{L}\p{N}]+)*""")
    sentences.forEachIndexed { index, sentence ->
        val sentenceStart = paragraphOffset
        append(sentence.originalText)
        val sentenceEnd = sentenceStart + sentence.originalText.length
        if (sentenceEnd > sentenceStart) {
            addStringAnnotation("SENTENCE", sentence.id.toString(), sentenceStart, sentenceEnd)
        }
        wordRegex.findAll(sentence.originalText).forEach { match ->
            addStringAnnotation(
                "WORD",
                match.value,
                sentenceStart + match.range.first,
                sentenceStart + match.range.last + 1
            )
        }
        if (sentence.sentenceIndex == activeSentenceIndex && sentenceEnd > sentenceStart) {
            val hasWordRange = activeWordStart >= 0 &&
                activeWordEnd > activeWordStart &&
                activeWordEnd <= sentence.originalText.length
            addStyle(
                SpanStyle(
                    background = sentenceHighlightBackground,
                    textDecoration = if (hasWordRange) TextDecoration.None else TextDecoration.Underline
                ),
                start = sentenceStart,
                end = sentenceEnd
            )
            if (hasWordRange) {
                addStyle(
                    SpanStyle(
                        background = wordHighlightBackground,
                        color = highlightColor,
                        fontWeight = FontWeight.SemiBold,
                        textDecoration = TextDecoration.Underline
                    ),
                    start = sentenceStart + activeWordStart,
                    end = sentenceStart + activeWordEnd
                )
            }
        }
        paragraphOffset = sentenceEnd
        if (index < sentences.lastIndex) {
            append(" ")
            paragraphOffset++
        }
    }
}
