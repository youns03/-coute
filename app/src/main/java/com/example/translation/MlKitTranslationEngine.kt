package com.example.translation

import com.example.contracts.TranslationEngine
import com.example.domain.TranslationDirection
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Adapter implementing [TranslationEngine] via Google ML Kit On-Device Neural Translation.
 * Supports offline FR -> AR and EN -> AR once language packs (~30 MB each) are downloaded.
 */
class MlKitTranslationEngine(
    override val direction: TranslationDirection
) : TranslationEngine {

    override val engineName: String = "ML Kit On-Device NMT (${direction.shortBadge})"

    private val mutex = Mutex()
    private val remoteModelManager: RemoteModelManager by lazy { RemoteModelManager.getInstance() }

    private val sourceLangCode: String = when (direction) {
        TranslationDirection.FR_TO_AR -> TranslateLanguage.FRENCH
        TranslationDirection.EN_TO_AR -> TranslateLanguage.ENGLISH
    }

    private val sourceRemoteModel by lazy {
        TranslateRemoteModel.Builder(sourceLangCode).build()
    }
    private val arabicRemoteModel by lazy {
        TranslateRemoteModel.Builder(TranslateLanguage.ARABIC).build()
    }

    private var translator: Translator? = null

    override suspend fun isModelReady(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val srcReady = remoteModelManager.isModelDownloaded(sourceRemoteModel).await()
            val arReady = remoteModelManager.isModelDownloaded(arabicRemoteModel).await()
            srcReady && arReady
        }.getOrDefault(false)
    }

    override suspend fun downloadModel(): Result<Unit> = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                val client = getOrCreateTranslator()
                val conditions = DownloadConditions.Builder().build()
                client.downloadModelIfNeeded(conditions).await()
                Unit
            }
        }
    }

    override suspend fun deleteSourcePack(deleteSharedArabicTarget: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                translator?.close()
                translator = null
                remoteModelManager.deleteDownloadedModel(sourceRemoteModel).await()
                if (deleteSharedArabicTarget) {
                    remoteModelManager.deleteDownloadedModel(arabicRemoteModel).await()
                }
                Unit
            }
        }
    }

    override suspend fun translateText(text: String): Result<String> = withContext(Dispatchers.IO) {
        val clean = text.trim()
        if (clean.isEmpty()) return@withContext Result.success("")
        mutex.withLock {
            runCatching {
                val srcReady = remoteModelManager.isModelDownloaded(sourceRemoteModel).await()
                val arReady = remoteModelManager.isModelDownloaded(arabicRemoteModel).await()
                if (!srcReady || !arReady) {
                    error("حزمة الترجمة (${direction.shortBadge}) غير مثبّتة. نزّلها أولاً للعمل دون إنترنت.")
                }
                val client = getOrCreateTranslator()
                client.translate(clean).await().trim()
            }
        }
    }

    private fun getOrCreateTranslator(): Translator {
        return translator ?: Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(sourceLangCode)
                .setTargetLanguage(TranslateLanguage.ARABIC)
                .build()
        ).also { translator = it }
    }

    override fun close() {
        runCatching { translator?.close() }
        translator = null
    }
}
