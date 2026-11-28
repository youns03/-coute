package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.contracts.SettingsGateway
import com.example.domain.AcceleratorMode
import com.example.domain.SpeedControlMode
import com.example.domain.UserSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "ecoute_settings")

class SettingsRepository(private val context: Context) : SettingsGateway {

    private object Keys {
        val ACTIVE_MODEL_ID = stringPreferencesKey("active_model_id")
        val ACTIVE_VOICE_ID = stringPreferencesKey("active_voice_id")
        val ACTIVE_ASR_MODEL_ID = stringPreferencesKey("active_asr_model_id")
        val ACCELERATOR_MODE = stringPreferencesKey("accelerator_mode")
        val PLAYBACK_SPEED = floatPreferencesKey("playback_speed")
        val SPEED_CONTROL_MODE = stringPreferencesKey("speed_control_mode")
        val READER_FONT_SIZE = floatPreferencesKey("reader_font_size_sp")
        val READER_LINE_HEIGHT = floatPreferencesKey("reader_line_height")
        val NIGHT_MODE = booleanPreferencesKey("night_mode")
        val SHOW_INLINE_TRANSLATIONS = booleanPreferencesKey("show_inline_translations")
        val ACTIVE_BOOK_ID = stringPreferencesKey("active_book_id")
        val LIBRARY_VIEW_MODE = stringPreferencesKey("library_view_mode")
        val LIBRARY_SORT_ORDER = stringPreferencesKey("library_sort_order")
        val LAST_ROUTE_NAME = stringPreferencesKey("last_route_name")
    }

    override val settingsFlow: Flow<UserSettings> = context.settingsDataStore.data.map { prefs ->
        val accelName = prefs[Keys.ACCELERATOR_MODE]
        val speedModeName = prefs[Keys.SPEED_CONTROL_MODE]
        UserSettings(
            activeModelId = prefs[Keys.ACTIVE_MODEL_ID] ?: "kokoro-82m-gpu-fp16",
            activeVoiceId = prefs[Keys.ACTIVE_VOICE_ID] ?: "",
            activeAsrModelId = prefs[Keys.ACTIVE_ASR_MODEL_ID] ?: "sherpa-asr-nemo-fast-conformer-fr-en",
            acceleratorMode = AcceleratorMode.entries.firstOrNull { it.name == accelName }
                ?: AcceleratorMode.AUTO_GPU_WITH_CPU_FALLBACK,
            playbackSpeed = prefs[Keys.PLAYBACK_SPEED] ?: 1.0f,
            speedControlMode = SpeedControlMode.entries.firstOrNull { it.name == speedModeName }
                ?: SpeedControlMode.AUDIO_TRACK_TIME_STRETCH,
            readerFontSizeSp = prefs[Keys.READER_FONT_SIZE] ?: 20f,
            readerLineHeightMultiplier = prefs[Keys.READER_LINE_HEIGHT] ?: 1.65f,
            nightMode = prefs[Keys.NIGHT_MODE] ?: true,
            showInlineTranslations = prefs[Keys.SHOW_INLINE_TRANSLATIONS] ?: false,
            activeBookId = prefs[Keys.ACTIVE_BOOK_ID],
            libraryViewMode = prefs[Keys.LIBRARY_VIEW_MODE] ?: "GRID",
            librarySortOrder = prefs[Keys.LIBRARY_SORT_ORDER] ?: "RECENT",
            lastRouteName = prefs[Keys.LAST_ROUTE_NAME] ?: "Home"
        )
    }

    override suspend fun setActiveModelId(modelId: String) {
        context.settingsDataStore.edit { it[Keys.ACTIVE_MODEL_ID] = modelId }
    }

    override suspend fun setActiveVoiceId(voiceId: String) {
        context.settingsDataStore.edit { it[Keys.ACTIVE_VOICE_ID] = voiceId }
    }

    override suspend fun setActiveAsrModelId(modelId: String) {
        context.settingsDataStore.edit { it[Keys.ACTIVE_ASR_MODEL_ID] = modelId }
    }

    override suspend fun setAcceleratorMode(mode: AcceleratorMode) {
        context.settingsDataStore.edit { it[Keys.ACCELERATOR_MODE] = mode.name }
    }

    override suspend fun setPlaybackSpeed(speed: Float) {
        context.settingsDataStore.edit { it[Keys.PLAYBACK_SPEED] = speed }
    }

    override suspend fun setSpeedControlMode(mode: SpeedControlMode) {
        context.settingsDataStore.edit { it[Keys.SPEED_CONTROL_MODE] = mode.name }
    }

    override suspend fun setReaderFontSize(sizeSp: Float) {
        context.settingsDataStore.edit { it[Keys.READER_FONT_SIZE] = sizeSp.coerceIn(15f, 30f) }
    }

    override suspend fun setReaderLineHeight(multiplier: Float) {
        context.settingsDataStore.edit { it[Keys.READER_LINE_HEIGHT] = multiplier.coerceIn(1.3f, 2.2f) }
    }

    override suspend fun setNightMode(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.NIGHT_MODE] = enabled }
    }

    override suspend fun setShowInlineTranslations(show: Boolean) {
        context.settingsDataStore.edit { it[Keys.SHOW_INLINE_TRANSLATIONS] = show }
    }

    override suspend fun setActiveBookId(bookId: String) {
        context.settingsDataStore.edit { it[Keys.ACTIVE_BOOK_ID] = bookId }
    }

    override suspend fun setLibraryViewMode(mode: String) {
        context.settingsDataStore.edit { it[Keys.LIBRARY_VIEW_MODE] = mode }
    }

    override suspend fun setLibrarySortOrder(order: String) {
        context.settingsDataStore.edit { it[Keys.LIBRARY_SORT_ORDER] = order }
    }

    override suspend fun setLastRouteName(routeName: String) {
        context.settingsDataStore.edit { it[Keys.LAST_ROUTE_NAME] = routeName }
    }
}
