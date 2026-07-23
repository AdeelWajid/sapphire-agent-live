package com.plano.agent.products

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.Window
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.plano.agent.R
import com.plano.agent.databinding.DialogProductPickerBinding
import com.plano.agent.databinding.ItemProductBinding

class ProductPickerDialog(
    context: Context,
    private val products: List<PickerProduct>,
    private val onSubmit: (List<SelectedProductLine>) -> Unit
) : Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen) {

    private lateinit var binding: DialogProductPickerBinding
    private val qtyBySku = mutableMapOf<String, Int>()
    private var filtered: List<PickerProduct> = products
    private lateinit var adapter: ProductAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        binding = DialogProductPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        window?.setBackgroundDrawableResource(android.R.color.transparent)
        binding.root.setBackgroundColor(ContextCompat.getColor(context, R.color.plano_sky))

        adapter = ProductAdapter(
            onMinus = { sku -> setQty(sku, (qtyBySku[sku] ?: 0) - 1) },
            onPlus = { sku -> setQty(sku, (qtyBySku[sku] ?: 0) + 1) }
        )
        binding.productList.layoutManager = LinearLayoutManager(context)
        binding.productList.adapter = adapter

        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                applyFilter(s?.toString().orEmpty())
            }
        })

        binding.btnClosePicker.setOnClickListener { dismiss() }
        binding.btnCancel.setOnClickListener { dismiss() }
        binding.btnSubmit.setOnClickListener {
            val selected = selectedLines()
            if (selected.isEmpty()) return@setOnClickListener
            onSubmit(selected)
            dismiss()
        }

        applyFilter("")
        refreshSummary()
    }

    private fun applyFilter(query: String) {
        val q = query.trim().lowercase()
        filtered = if (q.isEmpty()) {
            products
        } else {
            products.filter {
                "${it.skuCode} ${it.name} ${it.brand.orEmpty()}".lowercase().contains(q)
            }
        }
        adapter.submit(filtered, qtyBySku)
        binding.emptyText.visibility =
            if (filtered.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        binding.emptyText.setText(
            if (products.isEmpty()) R.string.product_empty else R.string.product_empty
        )
        binding.productList.visibility =
            if (filtered.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
    }

    private fun setQty(sku: String, next: Int) {
        val value = next.coerceIn(0, 999)
        if (value <= 0) qtyBySku.remove(sku) else qtyBySku[sku] = value
        adapter.submit(filtered, qtyBySku)
        refreshSummary()
    }

    private fun selectedLines(): List<SelectedProductLine> =
        products.mapNotNull { p ->
            val qty = qtyBySku[p.skuCode] ?: 0
            if (qty <= 0) null
            else SelectedProductLine(p.skuCode, p.name, qty, p.pricePerCaseRs)
        }

    private fun refreshSummary() {
        val selected = selectedLines()
        val cases = selected.sumOf { it.quantityCases }
        val amount = selected.sumOf { it.quantityCases * it.pricePerCaseRs }
        binding.summaryText.text =
            "${selected.size} items · $cases selected · Rs. $amount"
        binding.btnSubmit.alpha = if (selected.isEmpty()) 0.4f else 1f
        binding.btnSubmit.isEnabled = selected.isNotEmpty()
    }

    private class ProductAdapter(
        private val onMinus: (String) -> Unit,
        private val onPlus: (String) -> Unit
    ) : RecyclerView.Adapter<ProductAdapter.VH>() {

        private var items: List<PickerProduct> = emptyList()
        private var qty: Map<String, Int> = emptyMap()

        fun submit(list: List<PickerProduct>, quantities: Map<String, Int>) {
            items = list
            qty = quantities.toMap()
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val binding = ItemProductBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return VH(binding)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(items[position], qty[items[position].skuCode] ?: 0)
        }

        inner class VH(private val binding: ItemProductBinding) :
            RecyclerView.ViewHolder(binding.root) {

            fun bind(product: PickerProduct, quantity: Int) {
                binding.productName.text = product.name
                val meta = buildString {
                    append(product.skuCode)
                    product.sizeMl?.let { append(" · ${it}ml") }
                    product.unitsPerCase?.let { append(" · $it") }
                }
                binding.productMeta.text = meta
                binding.productPrice.text = "Rs. ${product.pricePerCaseRs}"
                binding.qtyText.text = quantity.toString()
                binding.productRow.setBackgroundResource(
                    if (quantity > 0) R.drawable.bg_product_row_selected
                    else R.drawable.bg_product_row
                )
                binding.btnMinus.setOnClickListener { onMinus(product.skuCode) }
                binding.btnPlus.setOnClickListener { onPlus(product.skuCode) }
            }
        }
    }
}
