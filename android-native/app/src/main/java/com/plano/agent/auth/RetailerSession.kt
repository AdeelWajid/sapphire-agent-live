package com.plano.agent.auth

import android.content.Context
import org.json.JSONObject

/**
 * Persists logged-in retailer offline until logout.
 */
object RetailerSession {
    private const val PREFS = "retailer_session"
    private const val KEY_RETAILER = "retailer_json"

    fun isLoggedIn(context: Context): Boolean = get(context) != null

    fun get(context: Context): RetailerProfile? {
        val raw = prefs(context).getString(KEY_RETAILER, null) ?: return null
        return try {
            RetailerProfile.fromJson(JSONObject(raw))
        } catch (_: Exception) {
            null
        }
    }

    fun save(context: Context, retailer: RetailerProfile) {
        prefs(context)
            .edit()
            .putString(KEY_RETAILER, retailer.toSessionJson().toString())
            .apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_RETAILER).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
