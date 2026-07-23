package com.plano.agent.auth

import com.plano.agent.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object RetailerLoginApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    fun loginByPhone(phone: String): RetailerProfile {
        val body = JSONObject().put("phone", phone.trim()).toString()
        val request = Request.Builder()
            .url("${BuildConfig.API_BASE_URL}/api/retailer/login")
            .post(body.toRequestBody(jsonMedia))
            .build()

        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            val json = try {
                JSONObject(raw)
            } catch (_: Exception) {
                throw IllegalStateException("Invalid server response")
            }

            if (response.code == 404 || json.optBoolean("found") == false) {
                throw IllegalStateException(
                    json.optString("message").ifBlank {
                        "No retailer found for this phone number"
                    }
                )
            }
            if (!response.isSuccessful || !json.optBoolean("ok", false)) {
                throw IllegalStateException(
                    json.optString("error").ifBlank { "Login failed (HTTP ${response.code})" }
                )
            }

            val retailer = json.optJSONObject("retailer")
                ?: throw IllegalStateException("Retailer data missing")
            return RetailerProfile.fromJson(retailer)
        }
    }
}
