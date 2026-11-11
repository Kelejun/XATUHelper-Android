package com.xatune.app.data

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Decode cache fields explicitly so erased list signatures cannot produce map entries in logs. */
internal object UpdateStateCodec {
    private val gson = Gson()

    fun encode(state: AppUpdateState): String = gson.toJson(
        state.copy(logs = state.logs.take(2), checking = false, message = null, offer = null)
    )

    fun decode(json: String?): AppUpdateState {
        val root = runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull()
            ?: return AppUpdateState()
        val defaults = UpdateSettings()
        val settingsJson = root.get("settings")?.takeIf { it.isJsonObject }?.asJsonObject
        val settings = UpdateSettings(
            channel = settingsJson?.string("channel")?.let { name -> UpdateChannel.entries.firstOrNull { it.name == name } }
                ?: defaults.channel,
            server = settingsJson?.string("server")?.let { name -> UpdateServer.entries.firstOrNull { it.name == name } }
                ?: defaults.server,
            repositoryUrl = settingsJson?.string("repositoryUrl") ?: defaults.repositoryUrl,
            automatic = settingsJson?.boolean("automatic") ?: defaults.automatic
        )
        val logs = root.get("logs")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { entry ->
            // Old or damaged entries are discarded individually, preserving usable settings and logs.
            runCatching {
                val log = entry.asJsonObject
                UpdateLog(
                    at = requireNotNull(log.get("at")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong),
                    automatic = requireNotNull(log.boolean("automatic")),
                    channel = UpdateChannel.valueOf(requireNotNull(log.string("channel"))),
                    success = requireNotNull(log.boolean("success")),
                    detail = requireNotNull(log.string("detail"))
                )
            }.getOrNull()
        }?.take(2).orEmpty()
        return AppUpdateState(settings = settings, logs = logs)
    }

    private fun JsonObject.string(name: String): String? = get(name)
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun JsonObject.boolean(name: String): Boolean? = get(name)
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
}
