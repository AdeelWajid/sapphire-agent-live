package com.sapphire.agent.products

import com.sapphire.agent.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object ProductCatalogApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun fetchProducts(): List<PickerProduct> {
        val request = Request.Builder()
            .url("${BuildConfig.API_BASE_URL}/api/products")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("Products HTTP ${response.code}")
            }
            val json = JSONObject(body)
            return parseProducts(json.optJSONArray("products"))
        }
    }

    fun parseProducts(array: JSONArray?): List<PickerProduct> {
        if (array == null) return emptyList()
        val out = ArrayList<PickerProduct>(array.length())
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val sku = o.optString("sku_code").ifBlank { o.optString("skuCode") }
            val name = o.optString("name")
            if (sku.isBlank() || name.isBlank()) continue
            out.add(
                PickerProduct(
                    skuCode = sku,
                    name = name,
                    brand = o.optString("brand").takeIf { it.isNotBlank() },
                    sizeMl = o.optInt("size_ml").takeIf { o.has("size_ml") && it > 0 }
                        ?: o.optInt("sizeMl").takeIf { o.has("sizeMl") && it > 0 },
                    pricePerCaseRs = when {
                        o.has("price_pkr") -> o.optDouble("price_pkr", 0.0).toInt()
                        o.has("pricePkr") -> o.optDouble("pricePkr", 0.0).toInt()
                        o.has("price_per_case_rs") -> o.optDouble("price_per_case_rs", 0.0).toInt()
                        o.has("pricePerCaseRs") -> o.optDouble("pricePerCaseRs", 0.0).toInt()
                        else -> 0
                    },
                    unitsPerCase = o.optInt("units_per_case").takeIf { o.has("units_per_case") && it > 0 }
                        ?: o.optInt("unitsPerCase").takeIf { o.has("unitsPerCase") && it > 0 }
                )
            )
        }
        return out
    }

    fun buildSelectionMessage(items: List<SelectedProductLine>): String {
        val lines = items.map {
            "- ${it.skuCode} | ${it.name} | qty ${it.quantityCases} @ Rs. ${it.pricePerCaseRs}"
        }
        return buildString {
            appendLine("I selected these clothing products on screen:")
            lines.forEach { appendLine(it) }
            append("Help me register a complaint about one of them if needed.")
        }
    }
}
