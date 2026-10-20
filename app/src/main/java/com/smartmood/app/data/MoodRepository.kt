package com.smartmood.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.smartmood.app.domain.Mood
import com.smartmood.app.domain.MoodEntry
import java.time.LocalDate

class MoodRepository(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    private val prefs = context.getSharedPreferences("smartmood_prefs", Context.MODE_PRIVATE)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE entries (" +
                "date TEXT PRIMARY KEY NOT NULL, " +
                "mood TEXT NOT NULL, " +
                "note TEXT NOT NULL, " +
                "created_at INTEGER NOT NULL)",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun all(): List<MoodEntry> {
        val result = mutableListOf<MoodEntry>()
        readableDatabase.query("entries", arrayOf("date", "mood", "note"), null, null, null, null, "date ASC")
            .use { cursor ->
                while (cursor.moveToNext()) {
                    result += MoodEntry(
                        date = LocalDate.parse(cursor.getString(0)),
                        mood = Mood.fromId(cursor.getString(1)),
                        note = cursor.getString(2),
                    )
                }
            }
        return result
    }

    fun upsert(entry: MoodEntry) {
        val values = ContentValues().apply {
            put("date", entry.date.toString())
            put("mood", entry.mood.id)
            put("note", entry.note)
            put("created_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("entries", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun clearAll() {
        writableDatabase.delete("entries", null, null)
        prefs.edit().clear().apply()
    }

    var selectedCreatureId: String?
        get() = prefs.getString(KEY_SELECTED, null)
        set(value) {
            prefs.edit().putString(KEY_SELECTED, value).apply()
        }

    private companion object {
        const val DB_NAME = "smartmood.db"
        const val DB_VERSION = 1
        const val KEY_SELECTED = "selected_creature"
    }
}
