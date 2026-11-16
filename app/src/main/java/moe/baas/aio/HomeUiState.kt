package moe.baas.aio

import androidx.annotation.StringRes

/** Transient UI state uses enum names; unknown or old saved values fall back to the default. */
enum class HomeTab(@StringRes val labelRes: Int) {
    HOME(R.string.tab_home),
    MANAGED(R.string.tab_managed),
    SCHEDULE(R.string.tab_schedule),
    LOGS(R.string.tab_logs),
    SETTINGS(R.string.tab_settings);

    companion object {
        fun restore(value: String?) = entries.firstOrNull { it.name == value } ?: HOME
    }
}

internal enum class TaskFilter(@StringRes val labelRes: Int) {
    ALL(R.string.filter_all),
    ENABLED(R.string.filter_enabled),
    DISABLED(R.string.filter_disabled);

    fun matches(enabled: Boolean) = when (this) {
        ALL -> true
        ENABLED -> enabled
        DISABLED -> !enabled
    }

    companion object {
        fun restore(value: String?) = entries.firstOrNull { it.name == value } ?: ALL
    }
}
