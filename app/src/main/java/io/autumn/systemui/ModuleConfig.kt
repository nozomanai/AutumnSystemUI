package io.autumn.systemui

object ModuleConfig {
    const val PREF_GROUP = "statusbar"
    const val KEY_HIDE_VOLTE_COLLAPSED = "hide_volte_collapsed"
    const val KEY_DUAL_SIGNAL = "dual_signal"
    const val SYSTEM_UI = "com.android.systemui"
    const val REPORT_AUTHORITY = "io.autumn.systemui.hookreport"
    const val REPORT_PREFS = "hook_report"
    const val REPORT_KEY = "text"

    val scopeCandidates = listOf(
        SYSTEM_UI,
        "miui.systemui.plugin",
        "com.miui.systemui.plugin",
    )
}
