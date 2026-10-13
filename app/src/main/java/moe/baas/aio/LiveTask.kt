package moe.baas.aio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Snapshot-state mirror of TaskRuntime callbacks (posted on the main thread), so composables
 * recompose exactly when the View code used to run its `changed` listeners.
 */
object LiveTask {
    var snapshot by mutableStateOf(TaskRuntime.snapshot)
        private set
    var logs by mutableStateOf("[]")
        private set
    /** Bumped by the screens' 2 s refresh, for values the original read on each poll. */
    var tick by mutableIntStateOf(0)
    private var started = false
    fun start() {
        if (started) return
        started = true
        TaskRuntime.observe { snapshot = it }
        TaskRuntime.observeLogs { logs = it }
    }
}
