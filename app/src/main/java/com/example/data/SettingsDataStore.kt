package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.domain.AcceleratorMode
import com.example.domain.SpeedControlMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "ecoute_settings")

data class UserSettings(
    val activeModelId: String = "kokoro-82m-gpu-fp16",
    val acceleratorMode: AcceleratorMode = AcceleratorMode.AUTO_GPU_WITH_CPU_FALLBACK,
    val playbackSpeed: Float = 1.0f,
    val speedControlMode: SpeedControlMode = SpeedControlMode.AUDIO_TRACK_TIME_STRETCH,
    val readerFontSizeSp: Float = 20f,
    val readerLineHeightMultiplier: Float = 1.65f,
    val nightMode: Boolean = false,
    val showInlineTranslations: Boolean = false,
    val activeBookId: String? = null
)

class SettingsRepository(private val context: Context) {

    private object Keys {
        val ACTIVE_MODEL_ID = stringPreferencesKey("active_model_id")
        val ACCELERATOR_MODE = stringPreferencesKey("accelerator_mode")
        val PLAYBACK_SPEED = floatPreferencesKey("playback_speed")
        val SPEED_CONTROL_MODE = stringPreferencesKey("speed_control_mode")
        val READER_FONT_SIZE = floatPreferencesKey("reader_font_size_sp")
        val READER_LINE_HEIGHT = floatPreferencesKey("reader_line_height")
        val NIGHT_MODE = booleanPreferencesKey("night_mode")
        val SHOW_INLINE_TRANSLATIONS = booleanPreferencesKey("show_inline_translations")
        val ACTIVE_BOOK_ID = stringPreferencesKey("active_book_id")
    }

    val settingsFlow: Flow<UserSettings> = context.settingsDataStore.data.map { prefs ->
        val accelName = prefs[Keys.ACCELERATOR_MODE]
        val speedModeName = prefs[Keys.SPEED_CONTROL_MODE]
        UserSettings(
            activeModelId = prefs[Keys.ACTIVE_MODEL_ID] ?: "kokoro-82m-gpu-fp16",
            acceleratorMode = AcceleratorMode.entries.firstOrNull { it.name == accelName }
                ?: AcceleratorMode.AUTO_GPU_WITH_CPU_FALLBACK,
            playbackSpeed = prefs[Keys.PLAYBACK_SPEED] ?: 1.0f,
            speedControlMode = SpeedControlMode.entries.firstOrNull { it.name == speedModeName }
                ?: SpeedControlMode.AUDIO_TRACK_TIME_STRETCH,
            readerFontSizeSp = prefs[Keys.READER_FONT_SIZE] ?: 20f,
            readerLineHeightMultiplier = prefs[Keys.READER_LINE_HEIGHT] ?: 1.65f,
            nightMode = prefs[Keys.NIGHT_MODE] ?: false,
            showInlineTranslations = prefs[Keys.SHOW_INLINE_TRANSLATIONS] ?: false,
            activeBookId = prefs[Keys.ACTIVE_BOOK_ID]
        )
    }

    suspend fun setActiveModelId(modelId: String) {
        context.settingsDataStore.edit { it[Keys.ACTIVE_MODEL_ID] = modelId }
    }

    suspend fun setAcceleratorMode(mode: AcceleratorMode) {
        context.settingsDataStore.edit { it[Keys.ACCELERATOR_MODE] = mode.name }
    }

    suspend fun setPlaybackSpeed(speed: Float) {
        context.settingsDataStore.edit { it[Keys.PLAYBACK_SPEED] = speed }
    }

    suspend fun setSpeedControlMode(mode: SpeedControlMode) {
        context.settingsDataStore.edit { it[Keys.SPEED_CONTROL_MODE] = mode.name }
    }

    suspend fun setReaderFontSize(sizeSp: Float) {
        context.settingsDataStore.edit { it[Keys.READER_FONT_SIZE] = sizeSp.coerceIn(15f, 30f) }
    }

    suspend fun setReaderLineHeight(multiplier: Float) {
        context.settingsDataStore.edit { it[Keys.READER_LINE_HEIGHT] = multiplier.coerceIn(1.3f, 2.2f) }
    }

    suspend fun setNightMode(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.NIGHT_MODE] = enabled }
    }

    suspend fun setShowInlineTranslations(show: Boolean) {
        context.settingsDataStore.edit { it[Keys.SHOW_INLINE_TRANSLATIONS] = show }
    }

    suspend fun setActiveBookId(bookId: String) {
        context.settingsDataStore.edit { it[Keys.ACTIVE_BOOK_ID] = bookId }
    }
}
