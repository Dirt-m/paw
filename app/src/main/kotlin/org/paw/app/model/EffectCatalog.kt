package org.paw.app.model

import org.json.JSONArray

data class PluginPort(
    val name: String,
    val def: Float,
    val min: Float,
    val max: Float,
    val logarithmic: Boolean,
    val integer: Boolean,
    val toggled: Boolean,
)

data class PluginInfo(
    val id: String,
    val name: String,
    val stereo: Boolean,
    val ports: List<PluginPort>,
) {
    val isBuiltin: Boolean get() = id.startsWith("builtin:")
}

fun parseEffectCatalog(json: String): List<PluginInfo> {
    val arr = JSONArray(json)
    return (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        val ports = o.getJSONArray("ports")
        PluginInfo(
            id = o.getString("id"),
            name = o.getString("name"),
            stereo = o.getBoolean("stereo"),
            ports = (0 until ports.length()).map { p ->
                val port = ports.getJSONObject(p)
                PluginPort(
                    name = port.getString("name"),
                    def = port.getDouble("def").toFloat(),
                    min = port.getDouble("min").toFloat(),
                    max = port.getDouble("max").toFloat(),
                    logarithmic = port.getBoolean("log"),
                    integer = port.getBoolean("int"),
                    toggled = port.getBoolean("toggle"),
                )
            },
        )
    }
}
