import { Type } from "@google/genai";
import {
  createComplaintVerified,
  formatPrice,
  getComplaintStatus,
  getComplaintsByPhone,
  getProductByCode,
  listProducts,
  normalizePhone,
  searchProducts,
  type Product,
} from "./dataStore";

function mapProduct(p: Product) {
  return {
    sku_code: p.sku_code,
    name: p.name,
    category: p.category,
    brand: p.brand,
    color: p.color,
    sizes: p.sizes,
    price_pkr: formatPrice(p.price_pkr),
    description: p.description,
  };
}

export const COMPLAINT_TOOLS = [
  {
    functionDeclarations: [
      {
        name: "search_products",
        description:
          "Search clothing products from the local JSON catalog by name, category, color, brand, or SKU.",
        parameters: {
          type: Type.OBJECT,
          properties: {
            query: {
              type: Type.STRING,
              description: "Product search text from the customer",
            },
          },
          required: ["query"],
        },
      },
      {
        name: "list_products",
        description: "List clothing products from the hardcoded JSON catalog.",
        parameters: {
          type: Type.OBJECT,
          properties: {
            limit: {
              type: Type.NUMBER,
              description: "Max products to return (default 20)",
            },
          },
        },
      },
      {
        name: "show_product_picker",
        description:
          "Open the on-screen clothing catalog picker. Call only when the user wants to see products on screen.",
        parameters: {
          type: Type.OBJECT,
          properties: {},
        },
      },
      {
        name: "submit_complaint",
        description:
          "Register a clothing complaint into complaints.json ONLY after the customer has verbally confirmed. Requires phone, customer_name, address, complaint_type, description. Also require either order_number/invoice_number OR purchase_channel + dates (online: purchase_date + received_date; shop: purchase_date only). Confirm ONLY when ok:true and verified:true. After success keep the call open and ask if they need anything else.",
        parameters: {
          type: Type.OBJECT,
          properties: {
            phone: {
              type: Type.STRING,
              description: "Customer phone number used to store and later fetch complaints",
            },
            customer_name: {
              type: Type.STRING,
              description: "Customer full name",
            },
            address: {
              type: Type.STRING,
              description:
                "Complete customer address including house/street, area, and city",
            },
            order_number: {
              type: Type.STRING,
              description: "Order number if the customer has one",
            },
            invoice_number: {
              type: Type.STRING,
              description: "Invoice number if the customer has one",
            },
            purchase_channel: {
              type: Type.STRING,
              description:
                "Required when no order/invoice: 'online' or 'shop'",
            },
            purchase_date: {
              type: Type.STRING,
              description:
                "Date of purchase (required when no order/invoice). Accept spoken dates as text.",
            },
            received_date: {
              type: Type.STRING,
              description:
                "Date the parcel/order was received. Required for online purchases when no order/invoice.",
            },
            complaint_type: {
              type: Type.STRING,
              description:
                "One of: Size Issue, Defective Fabric, Wrong Item, Delivery Delay, Return Request, Other",
            },
            description: {
              type: Type.STRING,
              description: "Clear description of the complaint",
            },
            product_sku: {
              type: Type.STRING,
              description: "Optional clothing SKU related to the complaint",
            },
          },
          required: [
            "phone",
            "customer_name",
            "address",
            "complaint_type",
            "description",
          ],
        },
      },
      {
        name: "get_complaints_by_phone",
        description:
          "Fetch all complaints for a customer phone number from the local complaints JSON file.",
        parameters: {
          type: Type.OBJECT,
          properties: {
            phone: {
              type: Type.STRING,
              description: "Customer phone number",
            },
          },
          required: ["phone"],
        },
      },
      {
        name: "get_complaint_status",
        description:
          "Look up one complaint by complaint number such as CMP-1 from the local JSON file.",
        parameters: {
          type: Type.OBJECT,
          properties: {
            complaint_number: {
              type: Type.STRING,
              description: "Official complaint reference, e.g. CMP-1",
            },
          },
          required: ["complaint_number"],
        },
      },
      {
        name: "end_call",
        description:
          "End the live voice call. Call this ONLY after (1) you asked the customer in Urdu if you should end the call, AND (2) they clearly said yes. Speak a short اللہ حافظ first or at the same turn, then call end_call so the app hangs up.",
        parameters: {
          type: Type.OBJECT,
          properties: {
            reason: {
              type: Type.STRING,
              description: "Why the call is ending, e.g. customer_confirmed",
            },
          },
        },
      },
    ],
  },
];

export async function handleComplaintToolCall(
  name: string,
  args: Record<string, unknown>
): Promise<Record<string, unknown>> {
  try {
    switch (name) {
      case "search_products": {
        const products = searchProducts(String(args.query || ""));
        return {
          ok: true,
          count: products.length,
          products: products.map(mapProduct),
        };
      }
      case "list_products": {
        const limit = Number(args.limit) || 20;
        const products = listProducts(limit);
        return {
          ok: true,
          count: products.length,
          products: products.map(mapProduct),
        };
      }
      case "show_product_picker": {
        const products = listProducts(60);
        return {
          ok: true,
          ui_action: "open_product_picker",
          products: products.map((p) => ({
            sku_code: p.sku_code,
            name: p.name,
            brand: p.brand,
            category: p.category,
            color: p.color,
            sizes: p.sizes,
            price_pkr: formatPrice(p.price_pkr),
            // Keep legacy picker fields for UI compatibility
            price_per_case_rs: formatPrice(p.price_pkr),
            size_ml: null,
            units_per_case: 1,
          })),
        };
      }
      case "submit_complaint": {
        const phone = normalizePhone(args.phone);
        const customerName = String(args.customer_name || "").trim();
        const address = String(args.address || "").trim();
        const complaintType = String(args.complaint_type || "").trim();
        const description = String(args.description || "").trim();
        const productSku = String(args.product_sku || "").trim() || null;
        const orderNumber = String(args.order_number || "").trim() || null;
        const invoiceNumber = String(args.invoice_number || "").trim() || null;
        const purchaseChannel = String(args.purchase_channel || "").trim() || null;
        const purchaseDate = String(args.purchase_date || "").trim() || null;
        const receivedDate = String(args.received_date || "").trim() || null;

        if (
          !phone ||
          !customerName ||
          !address ||
          !complaintType ||
          !description
        ) {
          return {
            ok: false,
            error:
              "phone, customer_name, address, complaint_type, and description are required",
          };
        }

        if (address.length < 8) {
          return {
            ok: false,
            error:
              "Address is too short. Ask for a complete address with house/street, area, and city.",
          };
        }

        if (!orderNumber && !invoiceNumber) {
          if (!purchaseChannel) {
            return {
              ok: false,
              error:
                "No order/invoice number. Ask if they bought online or from a shop, then collect the required dates.",
            };
          }
          if (!purchaseDate) {
            return {
              ok: false,
              error: "Ask for the purchase date.",
            };
          }
          const channel = purchaseChannel.toLowerCase();
          if (
            (channel === "online" || /online|website|app/.test(channel)) &&
            !receivedDate
          ) {
            return {
              ok: false,
              error:
                "Online purchase: ask for both purchase date and receiving/delivery date.",
            };
          }
        }

        if (productSku && !getProductByCode(productSku)) {
          return {
            ok: false,
            error: `Unknown product_sku ${productSku}. Search products first or omit product_sku.`,
          };
        }

        const complaint = createComplaintVerified({
          phone,
          customer_name: customerName,
          address,
          complaint_type: complaintType,
          description,
          product_sku: productSku,
          order_number: orderNumber,
          invoice_number: invoiceNumber,
          purchase_channel: purchaseChannel,
          purchase_date: purchaseDate,
          received_date: receivedDate,
        });

        return {
          ok: true,
          verified: true,
          complaint_number: complaint.complaint_number,
          status: complaint.status,
          phone: complaint.phone,
          customer_name: complaint.customer_name,
          keep_call_open: true,
          message:
            "Complaint saved and verified. Speak this exact complaint_number to the customer in Urdu. KEEP THE CALL OPEN. Then ask if they need anything else (کیا اور کوئی مدد چاہیے؟). Do not end the call.",
        };
      }
      case "get_complaints_by_phone": {
        const phone = normalizePhone(args.phone);
        if (!phone) {
          return { ok: false, error: "phone is required" };
        }
        const complaints = getComplaintsByPhone(phone);
        return {
          ok: true,
          phone,
          count: complaints.length,
          complaints,
          message:
            complaints.length === 0
              ? "No complaints found for this phone number."
              : undefined,
        };
      }
      case "get_complaint_status": {
        const complaintNumber = String(args.complaint_number || "").trim();
        const status = getComplaintStatus(complaintNumber);
        if (!status) {
          return {
            ok: false,
            found: false,
            complaint_number: complaintNumber,
            error: "Complaint not found",
          };
        }
        return { ok: true, found: true, ...status };
      }
      case "end_call": {
        return {
          ok: true,
          end_call: true,
          reason: String(args.reason || "customer_confirmed"),
          message:
            "Hang up now. The app will end the call after your farewell audio finishes.",
        };
      }
      default:
        return { ok: false, error: `Unknown tool: ${name}` };
    }
  } catch (err: any) {
    console.error(`Tool ${name} failed:`, err);
    return {
      ok: false,
      error: err?.message || `Failed to run ${name}`,
    };
  }
}
