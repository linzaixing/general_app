package com.generalapp.joinery.data

import android.content.Context
import android.content.SharedPreferences
import com.generalapp.joinery.domain.ProgressSnapshot

class GameRepository(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("joinery_progress", Context.MODE_PRIVATE)

    fun load(): ProgressSnapshot {
        val raw = prefs.getString(KEY_STARS, "").orEmpty()
        val stars = raw.split(';')
            .mapNotNull { entry ->
                val parts = entry.split(':')
                val id = parts.getOrNull(0)?.toIntOrNull()
                val value = parts.getOrNull(1)?.toIntOrNull()
                if (id != null && value != null) id to value else null
            }
            .toMap()
        return ProgressSnapshot(stars)
    }

    fun save(snapshot: ProgressSnapshot) {
        val raw = snapshot.stars.entries.joinToString(";") { "${it.key}:${it.value}" }
        prefs.edit().putString(KEY_STARS, raw).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_STARS = "stars"
    }
}
