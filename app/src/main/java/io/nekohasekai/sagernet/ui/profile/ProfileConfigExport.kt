package app.qthrone.mobile.ui.profile

import app.qthrone.mobile.bg.proto.CoreConfigs
import app.qthrone.mobile.database.ProxyEntity
import app.qthrone.mobile.ktx.toStringPretty
import org.json.JSONObject

/** "Export configuration" of the profile share menu: the core configs the profile would start with, as pretty JSON. */
object ProfileConfigExport {

    /**
     * The config text and a file name for it: sing-box, then the Xray config and every Xray full config instance.
     * All come from one build, because the sing-box <-> Xray bridge ports and credentials are generated per build.
     * Reads the database: call it off the main thread.
     */
    fun export(entity: ProxyEntity): Pair<String, String> {
        val result = CoreConfigs.buildMain(entity)

        val xrayConfigs = ArrayList<String>()
        result.xrayConfig?.takeIf { it.isNotBlank() }?.let(xrayConfigs::add)
        xrayConfigs.addAll(result.xrayFullConfigs.filter { it.isNotBlank() })

        val text = StringBuilder(JSONObject(result.coreConfig).toStringPretty())
        xrayConfigs.forEachIndexed { i, xray ->
            val label = if (xrayConfigs.size == 1) "xray" else "xray ${i + 1}"
            text.append("\n\n// ").append(label).append('\n').append(JSONObject(xray).toStringPretty())
        }
        val name = if (xrayConfigs.isEmpty()) "${entity.displayName()}.json" else "${entity.displayName()}.txt"
        return text.toString() to name
    }
}
