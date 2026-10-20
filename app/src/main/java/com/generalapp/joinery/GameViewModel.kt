package com.generalapp.joinery

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.generalapp.joinery.data.GameRepository
import com.generalapp.joinery.domain.Level
import com.generalapp.joinery.domain.Levels
import com.generalapp.joinery.domain.ProgressSnapshot
import com.generalapp.joinery.domain.V3
import com.generalapp.joinery.domain.Voxels

data class LevelResult(
    val level: Level,
    val stars: Int,
    val actions: Int,
    val isNewBest: Boolean,
)

class GameViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = GameRepository(app)

    var progress by mutableStateOf(repo.load())
        private set

    var level by mutableStateOf<Level?>(null)
        private set

    var voxels by mutableStateOf<Set<V3>>(emptySet())
        private set

    var actions by mutableStateOf(0)
        private set

    val canUndo: Boolean get() = history.isNotEmpty()

    var result by mutableStateOf<LevelResult?>(null)
        private set

    private var history: List<Set<V3>> = emptyList()

    fun open(target: Level) {
        if (!progress.isUnlocked(target.id)) return
        level = target
        voxels = target.initial
        actions = 0
        history = emptyList()
        result = null
    }

    fun closeLevel() {
        level = null
        result = null
        history = emptyList()
    }

    fun dismissResult() {
        result = null
    }

    fun chisel(target: V3) {
        val current = level ?: return
        if (result != null) return
        val next = Voxels.removeOne(voxels, target)
        if (next == voxels) return
        commit(next)
    }

    fun cut(axis: Int, k: Int, removeGreater: Boolean) {
        if (result != null) return
        val next = Voxels.cut(voxels, axis, k, removeGreater)
        if (next == voxels) return
        commit(next)
    }

    fun undo() {
        val previous = history.lastOrNull() ?: return
        history = history.dropLast(1)
        voxels = previous
        if (actions > 0) actions -= 1
        result = null
    }

    fun reset() {
        val current = level ?: return
        if (voxels == current.initial) return
        voxels = current.initial
        actions = 0
        history = emptyList()
        result = null
    }

    fun nextLevel(): Level? {
        val current = level ?: return null
        return Levels.byId(current.id + 1)?.takeIf { progress.isUnlocked(it.id) }
    }

    fun restart() {
        reset()
    }

    private fun commit(next: Set<V3>) {
        val current = level ?: return
        history = (history + listOf(voxels)).takeLast(40)
        voxels = next
        actions += 1
        if (Voxels.matches(next, current.target)) {
            val stars = Levels.starsFor(current.par, actions)
            val old = progress.starsOf(current.id)
            val best = maxOf(old, stars)
            val updated = ProgressSnapshot(progress.stars + (current.id to best))
            progress = updated
            repo.save(updated)
            result = LevelResult(current, stars, actions, best > old)
        }
    }
}
