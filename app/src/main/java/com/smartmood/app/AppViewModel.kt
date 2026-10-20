package com.smartmood.app

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.smartmood.app.data.MoodRepository
import com.smartmood.app.domain.Creature
import com.smartmood.app.domain.Creatures
import com.smartmood.app.domain.Mood
import com.smartmood.app.domain.MoodEntry
import com.smartmood.app.domain.Progress
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MoodRepository(application)

    var entries by mutableStateOf<List<MoodEntry>>(emptyList())
        private set

    var selectedCreatureId by mutableStateOf<String?>(null)
        private set

    var loaded by mutableStateOf(false)
        private set

    /** 打卡后新解锁的小精灵，界面显示完后调用 [consumeUnlocked] 清除。 */
    var justUnlocked by mutableStateOf<List<Creature>>(emptyList())
        private set

    init {
        viewModelScope.launch {
            val data = withContext(Dispatchers.IO) { repository.all() to repository.selectedCreatureId }
            entries = data.first
            selectedCreatureId = data.second
            loaded = true
        }
    }

    val unlockedIds: Set<String>
        get() = Progress.unlockedIds(entries)

    val today: LocalDate
        get() = LocalDate.now()

    fun todayEntry(): MoodEntry? = entries.firstOrNull { it.date == today }

    fun selectedCreature(): Creature {
        val unlocked = unlockedIds
        return Creatures.byId(selectedCreatureId)?.takeIf { it.id in unlocked }
            ?: Creatures.all.firstOrNull { it.id in unlocked }
            ?: Creatures.all.first()
    }

    fun checkIn(mood: Mood, note: String) {
        val before = unlockedIds
        val entry = MoodEntry(today, mood, note.trim().take(NOTE_LIMIT))
        entries = (entries.filterNot { it.date == entry.date } + entry).sortedBy { it.date }
        justUnlocked = Progress.newlyUnlocked(before, unlockedIds)
        viewModelScope.launch(Dispatchers.IO) { repository.upsert(entry) }
    }

    fun consumeUnlocked() {
        justUnlocked = emptyList()
    }

    fun selectCreature(id: String) {
        selectedCreatureId = id
        viewModelScope.launch(Dispatchers.IO) { repository.selectedCreatureId = id }
    }

    fun clearAll() {
        entries = emptyList()
        selectedCreatureId = null
        justUnlocked = emptyList()
        viewModelScope.launch(Dispatchers.IO) { repository.clearAll() }
    }

    fun exportText(): String = buildString {
        appendLine("SmartMood 心情记录")
        entries.forEach { entry ->
            val note = entry.note.replace('\n', ' ')
            appendLine("${entry.date} ${entry.mood.label} $note".trimEnd())
        }
    }

    companion object {
        const val NOTE_LIMIT = 80
    }
}
