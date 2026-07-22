package com.applan.util

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class PolicyEventStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun record(type: String, packageName: String? = null, durationMinutes: Int = 0) {
        val events = getAll().takeLast(MAX_EVENTS - 1).toMutableList()
        events.add(PolicyEvent(UUID.randomUUID().toString(), type, System.currentTimeMillis(), packageName, durationMinutes))
        val encoded = JSONArray()
        events.forEach { event ->
            encoded.put(JSONObject().apply {
                put("id", event.id)
                put("type", event.type)
                put("occurredAt", event.occurredAt)
                put("packageName", event.packageName)
                put("durationMinutes", event.durationMinutes)
            })
        }
        preferences.edit().putString(KEY_EVENTS, encoded.toString()).apply()
    }

    fun getAll(): List<PolicyEvent> = try {
        val array = JSONArray(preferences.getString(KEY_EVENTS, "[]"))
        buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(PolicyEvent(item.getString("id"), item.getString("type"), item.getLong("occurredAt"), item.optString("packageName").ifBlank { null }, item.optInt("durationMinutes")))
            }
        }
    } catch (_: Exception) {
        emptyList()
    }

    private companion object {
        const val PREF_NAME = "policy_events"
        const val KEY_EVENTS = "events"
        const val MAX_EVENTS = 1_000
    }
}
