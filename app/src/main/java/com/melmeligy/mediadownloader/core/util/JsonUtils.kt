package com.melmeligy.mediadownloader.core.util

import org.json.JSONObject

/** Minimal JSON helpers (backed by the platform org.json) for persisting header maps. */
object JsonUtils {

    fun mapToJson(map: Map<String, String>): String =
        if (map.isEmpty()) "{}" else JSONObject(map as Map<*, *>).toString()

    fun jsonToMap(json: String?): Map<String, String> {
        if (json.isNullOrBlank()) return emptyMap()
        return runCatching {
            val obj = JSONObject(json)
            buildMap {
                obj.keys().forEach { key -> put(key, obj.optString(key)) }
            }
        }.getOrDefault(emptyMap())
    }
}
