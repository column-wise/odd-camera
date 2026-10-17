package com.juhyun.oddcamera.filter

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first

/** Per-filter parameter persistence; keys "<filterId>.<paramKey>", values as strings. */
class ParamStore(private val dataStore: DataStore<Preferences>) {
    suspend fun load(filter: Filter): ParamValues {
        val prefs = dataStore.data.first()
        val saved = filter.params.mapNotNull { p -> prefs[key(filter.id, p.key)]?.let { p.key to it } }.toMap()
        return ParamValues(filter.params).apply { loadFrom(saved) }
    }

    suspend fun save(filterId: String, values: ParamValues) {
        val map = values.toStringMap()
        dataStore.edit { prefs -> map.forEach { (k, v) -> prefs[key(filterId, k)] = v } }
    }

    private fun key(filterId: String, paramKey: String) = stringPreferencesKey("$filterId.$paramKey")
}
