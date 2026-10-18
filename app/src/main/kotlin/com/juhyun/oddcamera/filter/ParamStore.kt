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

    /** Stores only values that differ from their defaults, so a later change of a default reaches every user. */
    suspend fun save(filterId: String, values: ParamValues) {
        val map = values.toStringMap()
        val defaults = values.defaults().toStringMap()
        dataStore.edit { prefs ->
            map.forEach { (k, v) -> if (v == defaults[k]) prefs.remove(key(filterId, k)) else prefs[key(filterId, k)] = v }
        }
    }

    private fun key(filterId: String, paramKey: String) = stringPreferencesKey("$filterId.$paramKey")
}
