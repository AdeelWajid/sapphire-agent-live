import fs from "fs";
import path from "path";
import { fileURLToPath } from "url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const DATA_DIR = path.join(__dirname, "data");
const PRODUCTS_PATH = path.join(DATA_DIR, "products.json");
const COMPLAINTS_PATH = path.join(DATA_DIR, "complaints.json");

export type Product = {
  sku_code: string;
  name: string;
  category: string;
  brand: string;
  sizes: string[];
  color: string;
  price_pkr: number;
  description: string;
};

export type PurchaseChannel = "online" | "shop";

export type Complaint = {
  id: string;
  complaint_number: string;
  phone: string;
  customer_name: string;
  address: string;
  order_number: string | null;
  invoice_number: string | null;
  purchase_channel: PurchaseChannel | null;
  purchase_date: string | null;
  received_date: string | null;
  complaint_type: string;
  description: string;
  product_sku: string | null;
  product_name: string | null;
  status: "Open" | "In Progress" | "Resolved" | "Closed";
  created_at: string;
  updated_at: string;
};

const COMPLAINT_TYPES = [
  "Size Issue",
  "Defective Fabric",
  "Wrong Item",
  "Delivery Delay",
  "Return Request",
  "Other",
] as const;

function ensureDataFiles() {
  if (!fs.existsSync(DATA_DIR)) fs.mkdirSync(DATA_DIR, { recursive: true });
  if (!fs.existsSync(PRODUCTS_PATH)) {
    throw new Error(`Missing products file: ${PRODUCTS_PATH}`);
  }
  if (!fs.existsSync(COMPLAINTS_PATH)) {
    fs.writeFileSync(COMPLAINTS_PATH, "[]\n", "utf8");
  }
}

function readJsonFile<T>(filePath: string, fallback: T): T {
  ensureDataFiles();
  try {
    const raw = fs.readFileSync(filePath, "utf8");
    return JSON.parse(raw) as T;
  } catch {
    return fallback;
  }
}

function writeComplaints(complaints: Complaint[]) {
  ensureDataFiles();
  // Write atomically. Vite/chokidar is configured to ignore data/** so this
  // does not reload the dev server mid-call.
  const tmp = `${COMPLAINTS_PATH}.tmp`;
  fs.writeFileSync(tmp, `${JSON.stringify(complaints, null, 2)}\n`, "utf8");
  fs.renameSync(tmp, COMPLAINTS_PATH);
}

export function normalizePhone(raw: unknown): string {
  const digits = String(raw || "").replace(/\D/g, "");
  if (!digits) return "";
  // Keep last 10 digits for local PK mobiles (03xxxxxxxxx → 3xxxxxxxxx last 10)
  if (digits.length >= 10) return digits.slice(-10);
  return digits;
}

export function formatPrice(n: number): number {
  return Math.round(Number(n) || 0);
}

export function listProducts(limit = 100): Product[] {
  const products = readJsonFile<Product[]>(PRODUCTS_PATH, []);
  return products.slice(0, Math.max(1, limit));
}

export function searchProducts(query: string): Product[] {
  const q = String(query || "").trim().toLowerCase();
  const products = listProducts(200);
  if (!q) return products.slice(0, 20);
  return products
    .filter((p) =>
      `${p.sku_code} ${p.name} ${p.category} ${p.brand} ${p.color} ${p.description}`
        .toLowerCase()
        .includes(q)
    )
    .slice(0, 20);
}

export function getProductByCode(sku: string): Product | null {
  const code = String(sku || "").trim().toUpperCase();
  if (!code) return null;
  return (
    listProducts(200).find((p) => p.sku_code.toUpperCase() === code) || null
  );
}

export function buildCatalogText(limit = 20): string {
  const products = listProducts(limit);
  if (!products.length) return "No clothing products available.";
  const lines = products.map(
    (p) =>
      `- ${p.sku_code}: ${p.name} (${p.category}, ${p.color}) sizes ${p.sizes.join("/")}; Rs ${formatPrice(p.price_pkr)}`
  );
  return `CLOTHING CATALOG (hardcoded JSON):\n${lines.join("\n")}`;
}

export function normalizeComplaintType(raw: unknown): string {
  const v = String(raw || "").trim().toLowerCase();
  if (!v) return "Other";
  if (/size|fit|chhota|bara/.test(v)) return "Size Issue";
  if (/defect|torn|stain|fabric|quality|kharab/.test(v)) return "Defective Fabric";
  if (/wrong|galat|mistake|swap/.test(v)) return "Wrong Item";
  if (/deliver|late|delay|courier/.test(v)) return "Delivery Delay";
  if (/return|exchange|wapis/.test(v)) return "Return Request";
  const exact = COMPLAINT_TYPES.find((t) => t.toLowerCase() === v);
  return exact || "Other";
}

function nextComplaintNumber(complaints: Complaint[]): string {
  let max = 0;
  for (const c of complaints) {
    const m = String(c.complaint_number || "").match(/^CMP-(\d+)$/i);
    if (m) max = Math.max(max, Number(m[1]) || 0);
  }
  return `CMP-${max + 1}`;
}

export function listComplaints(): Complaint[] {
  return readJsonFile<Complaint[]>(COMPLAINTS_PATH, []);
}

export function getComplaintsByPhone(phone: string): Complaint[] {
  const normalized = normalizePhone(phone);
  if (!normalized) return [];
  return listComplaints()
    .filter((c) => normalizePhone(c.phone) === normalized)
    .sort((a, b) => String(b.created_at).localeCompare(String(a.created_at)));
}

export function getComplaintStatus(complaintNumber: string): Complaint | null {
  const normalized = String(complaintNumber || "").trim().toUpperCase();
  if (!normalized) return null;
  return (
    listComplaints().find(
      (c) => String(c.complaint_number).toUpperCase() === normalized
    ) || null
  );
}

export function normalizePurchaseChannel(
  raw: unknown
): PurchaseChannel | null {
  const v = String(raw || "")
    .trim()
    .toLowerCase();
  if (!v) return null;
  if (
    /online|website|web|app|ecommerce|e-?commerce|daraz|amazon|instagram|facebook/.test(
      v
    )
  ) {
    return "online";
  }
  if (/shop|store|outlet|retail|physical|branch|mall/.test(v)) {
    return "shop";
  }
  if (v === "online") return "online";
  if (v === "shop") return "shop";
  return null;
}

function normalizeDateLabel(raw: unknown): string {
  return String(raw || "").trim();
}

export function createComplaint(input: {
  phone: string;
  customer_name: string;
  address: string;
  complaint_type: string;
  description: string;
  product_sku?: string | null;
  order_number?: string | null;
  invoice_number?: string | null;
  purchase_channel?: string | null;
  purchase_date?: string | null;
  received_date?: string | null;
}): Complaint {
  const phone = normalizePhone(input.phone);
  const customerName = String(input.customer_name || "").trim();
  const address = String(input.address || "").trim();
  const description = String(input.description || "").trim();
  const complaintType = normalizeComplaintType(input.complaint_type);
  const orderNumber = String(input.order_number || "").trim() || null;
  const invoiceNumber = String(input.invoice_number || "").trim() || null;
  const purchaseChannel = normalizePurchaseChannel(input.purchase_channel);
  const purchaseDate = normalizeDateLabel(input.purchase_date) || null;
  const receivedDate = normalizeDateLabel(input.received_date) || null;

  if (!phone) throw new Error("phone is required for a complaint.");
  if (!customerName) throw new Error("customer_name is required for a complaint.");
  if (!address) throw new Error("address is required for a complaint.");
  if (address.length < 8) {
    throw new Error("Please provide a complete address (house/street, area, city).");
  }
  if (!description) throw new Error("description is required for a complaint.");

  const hasOrderOrInvoice = !!(orderNumber || invoiceNumber);
  if (!hasOrderOrInvoice) {
    if (!purchaseChannel) {
      throw new Error(
        "No order/invoice number. Ask if they bought online or from a shop."
      );
    }
    if (!purchaseDate) {
      throw new Error("purchase_date is required when there is no order/invoice number.");
    }
    if (purchaseChannel === "online" && !receivedDate) {
      throw new Error(
        "received_date is required for online purchases when there is no order/invoice number."
      );
    }
  }

  const product = input.product_sku
    ? getProductByCode(input.product_sku)
    : null;

  const complaints = listComplaints();
  const now = new Date().toISOString();
  const complaint: Complaint = {
    id: `cmp_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`,
    complaint_number: nextComplaintNumber(complaints),
    phone,
    customer_name: customerName,
    address,
    order_number: orderNumber,
    invoice_number: invoiceNumber,
    purchase_channel: purchaseChannel,
    purchase_date: purchaseDate,
    received_date:
      !hasOrderOrInvoice && purchaseChannel === "shop" ? null : receivedDate,
    complaint_type: complaintType,
    description,
    product_sku: product?.sku_code || (input.product_sku || null),
    product_name: product?.name || null,
    status: "Open",
    created_at: now,
    updated_at: now,
  };

  complaints.push(complaint);
  writeComplaints(complaints);
  return complaint;
}

export function createComplaintVerified(input: {
  phone: string;
  customer_name: string;
  address: string;
  complaint_type: string;
  description: string;
  product_sku?: string | null;
  order_number?: string | null;
  invoice_number?: string | null;
  purchase_channel?: string | null;
  purchase_date?: string | null;
  received_date?: string | null;
}): Complaint & { verified: boolean } {
  const created = createComplaint(input);
  const verified = getComplaintStatus(created.complaint_number);
  if (!verified) {
    throw new Error("Complaint was written but could not be re-read from file.");
  }
  return { ...verified, verified: true };
}

export function pingDataStore(): boolean {
  ensureDataFiles();
  listProducts(1);
  listComplaints();
  return true;
}
