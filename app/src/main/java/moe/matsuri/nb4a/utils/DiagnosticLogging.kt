package moe.matsuri.nb4a.utils

import app.rawline.mobile.database.DataStore

object DiagnosticLogging {
    /** Upgrade the former warning-only defaults once; subsequent user choices are respected. */
    fun prepare() {
        val store = DataStore.configurationStore
        if (store.getBoolean("diagnosticLogsConfigured", false)) return
        if (DataStore.logLevel in listOf("warn", "warning", "info")) DataStore.logLevel = "debug"
        if (DataStore.xrayLogLevel in listOf("warn", "warning", "info")) DataStore.xrayLogLevel = "debug"
        store.putBoolean("diagnosticLogsConfigured", true)
    }
}
