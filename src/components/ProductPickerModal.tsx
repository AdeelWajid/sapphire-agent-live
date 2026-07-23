import React, { useEffect, useMemo, useState } from "react";
import { X, Package, Minus, Plus } from "lucide-react";

export type PickerProduct = {
  sku_code: string;
  name: string;
  brand?: string | null;
  size_ml?: number | null;
  price_per_case_rs: number;
  units_per_case?: number | null;
};

export type SelectedProductLine = {
  sku_code: string;
  name: string;
  quantity_cases: number;
  price_per_case_rs: number;
};

type Props = {
  open: boolean;
  products: PickerProduct[];
  loading?: boolean;
  onClose: () => void;
  onSubmit: (items: SelectedProductLine[]) => void;
};

export default function ProductPickerModal({
  open,
  products,
  loading = false,
  onClose,
  onSubmit,
}: Props) {
  const [qtyBySku, setQtyBySku] = useState<Record<string, number>>({});
  const [query, setQuery] = useState("");

  useEffect(() => {
    if (open) {
      setQtyBySku({});
      setQuery("");
    }
  }, [open]);

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (!q) return products;
    return products.filter((p) =>
      `${p.sku_code} ${p.name} ${p.brand || ""}`.toLowerCase().includes(q)
    );
  }, [products, query]);

  const selected = useMemo(() => {
    return products
      .filter((p) => (qtyBySku[p.sku_code] || 0) > 0)
      .map((p) => ({
        sku_code: p.sku_code,
        name: p.name,
        quantity_cases: qtyBySku[p.sku_code],
        price_per_case_rs: p.price_per_case_rs,
      }));
  }, [products, qtyBySku]);

  const totalCases = selected.reduce((s, i) => s + i.quantity_cases, 0);
  const totalAmount = selected.reduce(
    (s, i) => s + i.quantity_cases * i.price_per_case_rs,
    0
  );

  if (!open) return null;

  const setQty = (sku: string, next: number) => {
    setQtyBySku((prev) => {
      const value = Math.max(0, Math.min(999, Math.trunc(next)));
      const copy = { ...prev };
      if (value <= 0) delete copy[sku];
      else copy[sku] = value;
      return copy;
    });
  };

  return (
    <div className="fixed inset-0 z-[80] flex items-end sm:items-center justify-center bg-[#013BAA]/25 p-0 sm:p-4 backdrop-blur-sm">
      {/* Fixed height on mobile so the footer cannot be pushed off-screen */}
      <div className="w-full sm:max-w-2xl h-[min(92dvh,100%)] sm:h-auto sm:max-h-[min(92vh,880px)] bg-[#FEFEFE] border border-[#013BAA]/10 rounded-t-3xl sm:rounded-3xl shadow-[0_28px_80px_rgba(1,59,170,0.22)] flex flex-col overflow-hidden min-h-0">
        <div className="shrink-0 px-5 py-4 border-b border-[#013BAA]/10 flex items-center justify-between bg-[#E6F1FE]/55">
          <div className="flex items-center gap-2 min-w-0">
            <Package className="w-4 h-4 text-[#013BAA] shrink-0" />
            <div className="min-w-0">
              <h3 className="text-sm font-bold text-[#013BAA]">Clothing catalog</h3>
              <p className="text-[11px] text-[#013BAA]/55 truncate">
                Select items to mention in a complaint, then submit to the assistant.
              </p>
            </div>
          </div>
          <button
            type="button"
            onClick={onClose}
            className="p-2 rounded-xl text-[#013BAA]/55 hover:text-[#013BAA] hover:bg-[#FEFEFE] shrink-0"
            aria-label="Close"
          >
            <X className="w-4 h-4" />
          </button>
        </div>

        <div className="shrink-0 px-5 py-3 border-b border-[#013BAA]/10">
          <input
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="Search brand, name, or SKU…"
            className="w-full bg-[#E6F1FE]/65 border border-[#013BAA]/10 rounded-2xl px-4 py-3 text-xs text-[#013BAA] placeholder:text-[#013BAA]/40 focus:outline-none focus:border-[#013BAA]/40"
          />
        </div>

        <div className="plano-scrollbar min-h-0 flex-1 overflow-y-auto overscroll-contain px-5 py-4 space-y-2">
          {loading && (
            <p className="text-xs text-[#013BAA]/50 py-8 text-center">Loading products…</p>
          )}
          {!loading && filtered.length === 0 && (
            <p className="text-xs text-[#013BAA]/50 py-8 text-center">No products found.</p>
          )}
          {!loading &&
            filtered.map((p) => {
              const qty = qtyBySku[p.sku_code] || 0;
              return (
                <div
                  key={p.sku_code}
                  className={`border rounded-xl p-3 flex items-center gap-3 transition-colors ${
                    qty > 0
                      ? "border-[#013BAA]/35 bg-[#E6F1FE]"
                      : "border-[#013BAA]/10 bg-[#FEFEFE]"
                  }`}
                >
                  <div className="flex-1 min-w-0">
                    <p className="text-xs font-semibold text-[#013BAA] truncate">{p.name}</p>
                    <p className="text-[10px] text-[#013BAA]/45 font-mono mt-0.5">
                      {p.sku_code}
                      {p.size_ml ? ` · ${p.size_ml}ml` : ""}
                      {p.units_per_case ? ` · ${p.units_per_case}/case` : ""}
                    </p>
                    <p className="text-[11px] text-[#013BAA] font-semibold mt-1">
                      Rs. {p.price_per_case_rs}
                    </p>
                  </div>
                  <div className="flex items-center gap-2 shrink-0">
                    <button
                      type="button"
                      onClick={() => setQty(p.sku_code, qty - 1)}
                      className="w-8 h-8 rounded-xl border border-[#013BAA]/10 text-[#013BAA] hover:bg-[#E6F1FE] flex items-center justify-center"
                    >
                      <Minus className="w-3.5 h-3.5" />
                    </button>
                    <input
                      type="number"
                      min={0}
                      max={999}
                      value={qty}
                      onChange={(e) => setQty(p.sku_code, Number(e.target.value) || 0)}
                      className="w-12 text-center bg-[#E6F1FE]/60 border border-[#013BAA]/10 rounded-xl py-1.5 text-xs text-[#013BAA]"
                    />
                    <button
                      type="button"
                      onClick={() => setQty(p.sku_code, qty + 1)}
                      className="w-8 h-8 rounded-xl border border-[#013BAA]/10 text-[#013BAA] hover:bg-[#E6F1FE] flex items-center justify-center"
                    >
                      <Plus className="w-3.5 h-3.5" />
                    </button>
                  </div>
                </div>
              );
            })}
        </div>

        {/* Always pinned — stacked on mobile so Submit never clips off */}
        <div className="shrink-0 px-5 pt-3 pb-[max(1rem,env(safe-area-inset-bottom))] border-t border-[#013BAA]/10 bg-[#E6F1FE]/55 flex flex-col gap-3">
          <div className="text-[11px] text-[#013BAA]/60">
            <span className="text-[#013BAA] font-semibold">{selected.length}</span> items ·{" "}
            <span className="text-[#013BAA] font-semibold">{totalCases}</span> selected ·{" "}
            <span className="text-[#013BAA] font-bold">Rs. {totalAmount}</span>
          </div>
          <div className="flex flex-col-reverse sm:flex-row gap-2 sm:justify-end">
            <button
              type="button"
              onClick={onClose}
              className="w-full sm:w-auto px-3 py-3 sm:py-2 rounded-xl text-sm sm:text-xs border border-[#013BAA]/15 text-[#013BAA] hover:bg-[#FEFEFE]"
            >
              Cancel
            </button>
            <button
              type="button"
              disabled={selected.length === 0}
              onClick={() => onSubmit(selected)}
              className={`w-full sm:w-auto px-4 py-3 sm:py-2 rounded-xl text-sm sm:text-xs font-semibold ${
                selected.length === 0
                  ? "bg-[#013BAA]/10 text-[#013BAA]/40 cursor-not-allowed"
                  : "bg-[#013BAA] hover:bg-[#013BAA]/90 text-white shadow-[0_8px_18px_rgba(1,59,170,0.18)]"
              }`}
            >
              Submit to Assistant
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
