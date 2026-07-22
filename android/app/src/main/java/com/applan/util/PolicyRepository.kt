package com.applan.util

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

class PolicyRepository(context: Context) {
    private val preferences = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        context.createDeviceProtectedStorageContext()
    } else {
        context
    }).getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun getProfiles(): List<TimeProfile> = try {
        val array = JSONArray(preferences.getString(KEY_PROFILES, "[]"))
        buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(
                    TimeProfile(
                        id = item.getString("id"),
                        weekdays = item.getJSONArray("weekdays").toIntSet(),
                        startMinute = item.getInt("startMinute"),
                        endMinute = item.getInt("endMinute"),
                        allowedPackages = item.getJSONArray("allowedPackages").toStringSet()
                    )
                )
            }
        }
    } catch (_: Exception) {
        emptyList()
    }

    fun saveProfiles(profiles: List<TimeProfile>) {
        val array = JSONArray()
        profiles.forEach { profile ->
            array.put(
                JSONObject().apply {
                    put("id", profile.id)
                    put("weekdays", JSONArray(profile.weekdays.toList()))
                    put("startMinute", profile.startMinute)
                    put("endMinute", profile.endMinute)
                    put("allowedPackages", JSONArray(profile.allowedPackages.toList()))
                }
            )
        }
        preferences.edit().putString(KEY_PROFILES, array.toString()).apply()
    }

    fun getPlan(): AiPlan? {
        val value = preferences.getString(KEY_PLAN, null) ?: return null
        return try {
            val item = JSONObject(value)
            AiPlan(
                allowedPackages = item.getJSONArray("allowedPackages").toStringSet(),
                purpose = item.getString("purpose"),
                expiresAt = item.getLong("expiresAt"),
                id = item.optString("id").ifBlank { java.util.UUID.randomUUID().toString() },
                startedAt = item.optLong("startedAt", System.currentTimeMillis())
            )
        } catch (_: Exception) {
            null
        }
    }

    fun savePlan(plan: AiPlan) {
        preferences.edit().putString(
            KEY_PLAN,
            JSONObject().apply {
                put("allowedPackages", JSONArray(plan.allowedPackages.toList()))
                put("purpose", plan.purpose)
                put("expiresAt", plan.expiresAt)
                put("id", plan.id)
                put("startedAt", plan.startedAt)
            }.toString()
        ).apply()
    }

    fun clearPlan() {
        preferences.edit().remove(KEY_PLAN).apply()
    }

    fun getTemporaryPass(): TemporaryPass? {
        val value = preferences.getString(KEY_TEMPORARY_PASS, null) ?: return null
        val pass = try {
            JSONObject(value).let { TemporaryPass(it.getString("packageName"), it.getLong("expiresAt")) }
        } catch (_: Exception) {
            null
        }
        if (pass == null || pass.expiresAt <= System.currentTimeMillis()) {
            clearTemporaryPass()
            return null
        }
        return pass
    }

    fun saveTemporaryPass(pass: TemporaryPass) {
        preferences.edit().putString(
            KEY_TEMPORARY_PASS,
            JSONObject().apply {
                put("packageName", pass.packageName)
                put("expiresAt", pass.expiresAt)
            }.toString()
        ).apply()
    }

    fun clearTemporaryPass() {
        preferences.edit().remove(KEY_TEMPORARY_PASS).apply()
    }

    fun evaluate(now: Calendar = Calendar.getInstance()): EffectivePolicy =
        PolicyEngine.evaluate(getProfiles(), getPlan(), now)

    private fun JSONArray.toIntSet(): Set<Int> = buildSet {
        for (index in 0 until length()) add(getInt(index))
    }

    private fun JSONArray.toStringSet(): Set<String> = buildSet {
        for (index in 0 until length()) add(getString(index))
    }

    private companion object {
        const val PREF_NAME = "app_config"
        const val KEY_PROFILES = "time_profiles"
        const val KEY_PLAN = "ai_plan"
        const val KEY_TEMPORARY_PASS = "temporary_pass"
    }
}
