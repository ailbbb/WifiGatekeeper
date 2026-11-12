package com.wifigatekeeper.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

/** Monitoring behaviour: enabled flag, poll interval, baseline marker. */
class SettingsRepository(private val context: Context) {

    data class MonitoringSettings(
        val monitoringEnabled: Boolean = true,
        val pollIntervalSeconds: Int = 60,
        val baselineDone: Boolean = false,
    )

    private val KEY_ENABLED = booleanPreferencesKey("monitoring_enabled")
    private val KEY_INTERVAL = intPreferencesKey("poll_interval_seconds")
    private val KEY_BASELINE = booleanPreferencesKey("baseline_done")

    val settings: Flow<MonitoringSettings> = context.dataStore.data.map { prefs ->
        MonitoringSettings(
            monitoringEnabled = prefs[KEY_ENABLED] ?: true,
            pollIntervalSeconds = prefs[KEY_INTERVAL] ?: 60,
            baselineDone = prefs[KEY_BASELINE] ?: false,
        )
    }

    /** One-shot read for background workers. */
    suspend fun snapshot(): MonitoringSettings =
        context.dataStore.data.map { prefs ->
            MonitoringSettings(
                monitoringEnabled = prefs[KEY_ENABLED] ?: true,
                pollIntervalSeconds = prefs[KEY_INTERVAL] ?: 60,
                baselineDone = prefs[KEY_BASELINE] ?: false,
            )
        }.first()

    suspend fun setMonitoringEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_ENABLED] = enabled }
    }

    suspend fun setPollIntervalSeconds(seconds: Int) {
        context.dataStore.edit { it[KEY_INTERVAL] = seconds.coerceIn(30, 600) }
    }

    suspend fun setBaselineDone(done: Boolean) {
        context.dataStore.edit { it[KEY_BASELINE] = done }
    }
}
