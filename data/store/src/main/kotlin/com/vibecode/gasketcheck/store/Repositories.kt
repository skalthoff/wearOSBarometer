package com.vibecode.gasketcheck.store

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.preferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object GasketStore {
    fun repository(context: Context): Repository {
        val appCtx = context.applicationContext
        val ds: DataStore<Preferences> = PreferenceDataStoreFactory.create(
            produceFile = { appCtx.preferencesDataStoreFile("gasket_prefs") }
        )
        return RepositoryImpl(ds)
    }

    interface Repository {
        val results: Flow<List<Result>>
        suspend fun addResult(result: Result)
        suspend fun clearResults()
        suspend fun getCalibration(): Calibration
        suspend fun setCalibration(calibration: Calibration)
    }

    private class RepositoryImpl(private val dataStore: DataStore<Preferences>) : Repository {
        private val KEY_RESULTS = preferencesKey<String>("results_json")
        private val KEY_CAL = preferencesKey<String>("calibration_json")
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        override val results: Flow<List<Result>> = dataStore.data.map { pref ->
            val raw = pref[KEY_RESULTS] ?: return@map emptyList()
            runCatching { json.decodeFromString<List<Result>>(raw) }.getOrElse { emptyList() }
        }

        override suspend fun addResult(result: Result) {
            dataStore.edit { pref ->
                val existing = pref[KEY_RESULTS]
                val list = if (existing != null) {
                    runCatching { json.decodeFromString<List<Result>>(existing) }.getOrElse { emptyList() }
                } else emptyList()
                val newList = (list + result).takeLast(50) // keep last 50
                pref[KEY_RESULTS] = json.encodeToString(newList)
            }
        }

        override suspend fun clearResults() {
            dataStore.edit { pref -> pref.remove(KEY_RESULTS) }
        }

        override suspend fun getCalibration(): Calibration {
            val prefs = dataStore.data.first()
            val raw = prefs[KEY_CAL] ?: return Calibration()
            return runCatching { json.decodeFromString<Calibration>(raw) }.getOrElse { Calibration() }
        }

        override suspend fun setCalibration(calibration: Calibration) {
            dataStore.edit { pref ->
                pref[KEY_CAL] = json.encodeToString(calibration)
            }
        }
    }
}
