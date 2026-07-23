package com.plano.agent.auth

import org.json.JSONObject

data class RetailerProfile(
    val id: String,
    val code: String,
    val name: String,
    val ownerName: String?,
    val phone: String?,
    val address: String?,
    val city: String?,
    val distributorId: String?,
    val latitude: Double?,
    val longitude: Double?,
) {
    fun displayName(): String = ownerName?.takeIf { it.isNotBlank() } ?: name

    fun toSessionJson(): JSONObject =
        JSONObject()
            .put("id", id)
            .put("code", code)
            .put("name", name)
            .put("owner_name", ownerName)
            .put("phone", phone)
            .put("address", address)
            .put("city", city)
            .put("distributor_id", distributorId)
            .put("latitude", latitude)
            .put("longitude", longitude)

    companion object {
        fun fromJson(o: JSONObject): RetailerProfile =
            RetailerProfile(
                id = o.optString("id"),
                code = o.optString("code"),
                name = o.optString("name"),
                ownerName = o.optString("owner_name").takeIf { it.isNotBlank() }
                    ?: o.optString("ownerName").takeIf { it.isNotBlank() },
                phone = o.optString("phone").takeIf { it.isNotBlank() },
                address = o.optString("address").takeIf { it.isNotBlank() },
                city = o.optString("city").takeIf { it.isNotBlank() },
                distributorId = o.optString("distributor_id").takeIf { it.isNotBlank() }
                    ?: o.optString("distributorId").takeIf { it.isNotBlank() },
                latitude = o.optDouble("latitude").takeIf { o.has("latitude") && !o.isNull("latitude") }
                    ?: o.optDouble("lat").takeIf { o.has("lat") && !o.isNull("lat") },
                longitude = o.optDouble("longitude").takeIf { o.has("longitude") && !o.isNull("longitude") }
                    ?: o.optDouble("long").takeIf { o.has("long") && !o.isNull("long") },
            )
    }
}
