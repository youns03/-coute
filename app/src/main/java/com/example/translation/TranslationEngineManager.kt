package com.example.translation

import com.example.contracts.SentenceTranslationCacheStore
import com.example.contracts.TranslationEngine
import com.example.contracts.TranslationGateway
import com.example.contracts.VocabularyLookupStore
import com.example.domain.DirectionPackState
import com.example.domain.Sentence
import com.example.domain.SourceLanguage
import com.example.domain.TranslationDirection
import com.example.domain.TranslationManagerUiState
import com.example.domain.TranslationPackStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/**
 * Single shared manager for all Phase 1 offline translation engines (FR -> AR and EN -> AR).
 * Shared identically by both the Reader and the Background File Translator so models are never duplicated.
 */
class TranslationEngineManager(
    private val sentenceCacheStore: SentenceTranslationCacheStore,
    private val vocabularyLookupStore: VocabularyLookupStore,
    private val engines: Map<TranslationDirection, TranslationEngine> = mapOf(
        TranslationDirection.FR_TO_AR to MlKitTranslationEngine(TranslationDirection.FR_TO_AR),
        TranslationDirection.EN_TO_AR to MlKitTranslationEngine(TranslationDirection.EN_TO_AR)
    )
) : TranslationGateway {

    private val _state = MutableStateFlow(TranslationManagerUiState())
    override val state: StateFlow<TranslationManagerUiState> = _state.asStateFlow()

    override fun getEngine(direction: TranslationDirection): TranslationEngine =
        engines.getValue(direction)

    override suspend fun refreshAllStatuses() = withContext(Dispatchers.IO) {
        TranslationDirection.entries.forEach { dir ->
            val engine = engines.getValue(dir)
            val readyResult = runCatching { engine.isModelReady() }
            val newState = readyResult.fold(
                onSuccess = { ready ->
                    if (ready) {
                        DirectionPackState(
                            direction = dir,
                            status = TranslationPackStatus.READY,
                            statusMessage = "حزمة ${dir.displayNameAr} مثبّتة وجاهزة دون إنترنت."
                        )
                    } else {
                        DirectionPackState(
                            direction = dir,
                            status = TranslationPackStatus.NOT_DOWNLOADED,
                            statusMessage = "حزمة ${dir.displayNameAr} غير مثبّتة (~30 MB). نزّلها مرة واحدة للقارئ ومترجم الملفات."
                        )
                    }
                },
                onFailure = { err ->
                    DirectionPackState(
                        direction = dir,
                        status = TranslationPackStatus.ERROR,
                        statusMessage = err.message ?: "تعذر فحص حزمة ${dir.shortBadge}"
                    )
                }
            )
            updateDirectionState(dir, newState)
        }
    }

    override suspend fun downloadPack(direction: TranslationDirection): Result<Unit> {
        updateDirectionState(
            direction,
            DirectionPackState(
                direction = direction,
                status = TranslationPackStatus.DOWNLOADING,
                statusMessage = "جاري تنزيل حزمة ${direction.displayNameAr}..."
            )
        )
        val result = engines.getValue(direction).downloadModel()
        result.onSuccess {
            updateDirectionState(
                direction,
                DirectionPackState(
                    direction = direction,
                    status = TranslationPackStatus.READY,
                    statusMessage = "تم تثبيت حزمة ${direction.displayNameAr} بنجاح وتعمل دون إنترنت."
                )
            )
        }.onFailure { err ->
            updateDirectionState(
                direction,
                DirectionPackState(
                    direction = direction,
                    status = TranslationPackStatus.ERROR,
                    statusMessage = "تعذر تنزيل ${direction.shortBadge}: ${err.message ?: "خطأ في الاتصال"}"
                )
            )
        }
        return result
    }

    override suspend fun deletePack(direction: TranslationDirection): Result<Unit> {
        val otherDirection = if (direction == TranslationDirection.FR_TO_AR) {
            TranslationDirection.EN_TO_AR
        } else {
            TranslationDirection.FR_TO_AR
        }
        val otherReady = runCatching { engines.getValue(otherDirection).isModelReady() }.getOrDefault(false)
        val res = engines.getValue(direction).deleteSourcePack(deleteSharedArabicTarget = !otherReady)
        res.onSuccess {
            updateDirectionState(
                direction,
                DirectionPackState(
                    direction = direction,
                    status = TranslationPackStatus.NOT_DOWNLOADED,
                    statusMessage = "تم حذف حزمة ${direction.displayNameAr}."
                )
            )
        }
        return res
    }

    override suspend fun isDirectionReady(direction: TranslationDirection): Boolean =
        engines.getValue(direction).isModelReady()

    override suspend fun translateRaw(direction: TranslationDirection, text: String): Result<String> =
        engines.getValue(direction).translateText(text)

    override suspend fun translateSentence(
        sentenceId: Long,
        sourceLanguage: SourceLanguage,
        originalText: String,
        cachedTranslation: String?
    ): Result<String> = withContext(Dispatchers.IO) {
        if (!cachedTranslation.isNullOrBlank()) {
            return@withContext Result.success(cachedTranslation)
        }
        val direction = TranslationDirection.forSource(sourceLanguage)
        engines.getValue(direction).translateText(originalText).onSuccess { arabic ->
            if (sentenceId > 0L && arabic.isNotBlank()) {
                sentenceCacheStore.updateSentenceTranslation(sentenceId, arabic)
            }
        }
    }

    override suspend fun translateParagraph(
        sentences: List<Sentence>,
        sourceLanguage: SourceLanguage
    ): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val direction = TranslationDirection.forSource(sourceLanguage)
            val engine = engines.getValue(direction)
            val pending = sentences.filter { it.cachedArabicTranslation.isNullOrBlank() }
            if (pending.isEmpty()) return@runCatching 0

            var translatedCount = 0
            for (s in pending) {
                val arabic = engine.translateText(s.originalText).getOrThrow()
                if (s.id > 0L && arabic.isNotBlank()) {
                    sentenceCacheStore.updateSentenceTranslation(s.id, arabic)
                }
                translatedCount++
            }
            translatedCount
        }
    }

    override suspend fun translateWord(sourceLanguage: SourceLanguage, rawWord: String): Result<String> =
        withContext(Dispatchers.IO) {
            val clean = rawWord.trim()
            if (clean.isEmpty()) return@withContext Result.failure(IllegalArgumentException("كلمة فارغة"))
            val cached = vocabularyLookupStore.findArabicTranslation(clean)
            if (!cached.isNullOrBlank()) {
                return@withContext Result.success(cached)
            }
            val direction = TranslationDirection.forSource(sourceLanguage)
            engines.getValue(direction).translateText(clean)
        }

    private fun updateDirectionState(direction: TranslationDirection, newState: DirectionPackState) {
        _state.update { current ->
            when (direction) {
                TranslationDirection.FR_TO_AR -> current.copy(frToAr = newState)
                TranslationDirection.EN_TO_AR -> current.copy(enToAr = newState)
            }
        }
    }

    override fun closeAll() {
        engines.values.forEach { it.close() }
    }
}
