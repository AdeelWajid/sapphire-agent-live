package com.sapphire.agent.products

data class PickerProduct(
    val skuCode: String,
    val name: String,
    val brand: String? = null,
    val sizeMl: Int? = null,
    val pricePerCaseRs: Int,
    val unitsPerCase: Int? = null
)

data class SelectedProductLine(
    val skuCode: String,
    val name: String,
    val quantityCases: Int,
    val pricePerCaseRs: Int
)
