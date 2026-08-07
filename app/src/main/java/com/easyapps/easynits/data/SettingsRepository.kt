package com.easyapps.easynits.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository private constructor(context: Context) {
    private val appContext = context.applicationContext

    companion object {
        @Volatile
        private var INSTANCE: SettingsRepository? = null

        fun getInstance(context: Context): SettingsRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SettingsRepository(context).also { INSTANCE = it }
            }
        }
    }

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val listType = Types.newParameterizedType(List::class.java, ScheduledOffset::class.java)
    private val adapter = moshi.adapter<List<ScheduledOffset>>(listType)

    private object PreferencesKeys {
        val BRIGHTNESS_OFFSET = intPreferencesKey("brightness_offset")
        val IS_SERVICE_ACTIVE = booleanPreferencesKey("is_service_active")
        val RUN_IN_BACKGROUND = booleanPreferencesKey("run_in_background")
        val AUTO_START = booleanPreferencesKey("auto_start")
        val THEME_MODE = intPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val CUSTOM_PRIMARY_COLOR = longPreferencesKey("custom_primary_color")
        val CUSTOM_BACKGROUND_COLOR = longPreferencesKey("custom_background_color")
        val CUSTOM_TEXT_COLOR = longPreferencesKey("custom_text_color")
        val APP_ICON = stringPreferencesKey("app_icon")
        val LANGUAGE = stringPreferencesKey("language")
        val SCHEDULED_OFFSETS = stringPreferencesKey("scheduled_offsets")
    }

    val settingsFlow: Flow<UserSettings> = appContext.dataStore.data.map { preferences ->
        val scheduledJson = preferences[PreferencesKeys.SCHEDULED_OFFSETS]
        val scheduledList = if (scheduledJson != null) {
            try { adapter.fromJson(scheduledJson) ?: emptyList() } catch (e: Exception) { emptyList() }
        } else emptyList()

        UserSettings(
            brightnessOffset = preferences[PreferencesKeys.BRIGHTNESS_OFFSET] ?: 0,
            isServiceActive = preferences[PreferencesKeys.IS_SERVICE_ACTIVE] ?: false,
            runInBackground = preferences[PreferencesKeys.RUN_IN_BACKGROUND] ?: true,
            autoStart = preferences[PreferencesKeys.AUTO_START] ?: true,
            themeMode = preferences[PreferencesKeys.THEME_MODE] ?: 0,
            useDynamicColor = preferences[PreferencesKeys.DYNAMIC_COLOR] ?: true,
            customPrimaryColor = preferences[PreferencesKeys.CUSTOM_PRIMARY_COLOR],
            customBackgroundColor = preferences[PreferencesKeys.CUSTOM_BACKGROUND_COLOR],
            customTextColor = preferences[PreferencesKeys.CUSTOM_TEXT_COLOR],
            appIcon = preferences[PreferencesKeys.APP_ICON] ?: "v1",
            language = preferences[PreferencesKeys.LANGUAGE] ?: "system",
            scheduledOffsets = scheduledList
        )
    }

    suspend fun updateThemeMode(mode: Int) {
        appContext.dataStore.edit { it[PreferencesKeys.THEME_MODE] = mode }
    }

    suspend fun updateUseDynamicColor(use: Boolean) {
        appContext.dataStore.edit { it[PreferencesKeys.DYNAMIC_COLOR] = use }
    }

    suspend fun updateCustomPrimaryColor(color: Long?) {
        appContext.dataStore.edit { preferences ->
            if (color == null) preferences.remove(PreferencesKeys.CUSTOM_PRIMARY_COLOR)
            else preferences[PreferencesKeys.CUSTOM_PRIMARY_COLOR] = color
        }
    }

    suspend fun updateCustomBackgroundColor(color: Long?) {
        appContext.dataStore.edit { preferences ->
            if (color == null) preferences.remove(PreferencesKeys.CUSTOM_BACKGROUND_COLOR)
            else preferences[PreferencesKeys.CUSTOM_BACKGROUND_COLOR] = color
        }
    }

    suspend fun updateCustomTextColor(color: Long?) {
        appContext.dataStore.edit { preferences ->
            if (color == null) preferences.remove(PreferencesKeys.CUSTOM_TEXT_COLOR)
            else preferences[PreferencesKeys.CUSTOM_TEXT_COLOR] = color
        }
    }

    suspend fun updateAppIcon(icon: String) {
        appContext.dataStore.edit { it[PreferencesKeys.APP_ICON] = icon }
    }

    suspend fun updateLanguage(language: String) {
        appContext.dataStore.edit { it[PreferencesKeys.LANGUAGE] = language }
    }

    suspend fun updateBrightnessOffset(offset: Int) {
        appContext.dataStore.edit { preferences ->
            preferences[PreferencesKeys.BRIGHTNESS_OFFSET] = offset.coerceIn(-255, 255)
        }
    }

    suspend fun updateServiceActive(isActive: Boolean) {
        appContext.dataStore.edit { preferences ->
            preferences[PreferencesKeys.IS_SERVICE_ACTIVE] = isActive
        }
    }

    suspend fun updateRunInBackground(runInBackground: Boolean) {
        appContext.dataStore.edit { preferences ->
            preferences[PreferencesKeys.RUN_IN_BACKGROUND] = runInBackground
        }
    }

    suspend fun updateAutoStart(autoStart: Boolean) {
        appContext.dataStore.edit { preferences ->
            preferences[PreferencesKeys.AUTO_START] = autoStart
        }
    }

    suspend fun updateScheduledOffsets(offsets: List<ScheduledOffset>) {
        appContext.dataStore.edit { preferences ->
            preferences[PreferencesKeys.SCHEDULED_OFFSETS] = adapter.toJson(offsets)
        }
    }
}

@JsonClass(generateAdapter = true)
data class ScheduledOffset(
    val id: String = java.util.UUID.randomUUID().toString(),
    val startHour: Int,
    val startMinute: Int,
    val endHour: Int,
    val endMinute: Int,
    val offset: Int,
    val days: List<Int> = listOf(1, 2, 3, 4, 5, 6, 7)
)

data class UserSettings(
    val brightnessOffset: Int,
    val isServiceActive: Boolean,
    val runInBackground: Boolean,
    val autoStart: Boolean,
    val themeMode: Int,
    val useDynamicColor: Boolean,
    val customPrimaryColor: Long?,
    val customBackgroundColor: Long?,
    val customTextColor: Long?,
    val appIcon: String,
    val language: String,
    val scheduledOffsets: List<ScheduledOffset> = emptyList()
)
