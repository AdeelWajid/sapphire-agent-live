# Plano Agent

Voice assistant for **clothing** product info and **complaints**. Cloned from the Plano Agent live app, then simplified:

- No PostgreSQL — products and complaints use local JSON files
- Hardcoded clothing catalog in `data/products.json`
- Complaints are written/read from `data/complaints.json`
- Customers are identified by **phone number** when registering or fetching complaints

## Run

```bash
cp .env.example .env
# add GEMINI_API_KEY
npm install
npm run dev
```

Open http://localhost:3000

## Logs

Communication logs are written under `logs/`:

- `logs/agent-YYYY-MM-DD.log` — all sessions for the day (JSON lines)
- `logs/sessions/<sessionId>.log` — one file per Live call

Raw audio is not stored (only chunk counts). Tool calls, transcripts, and client events are logged.

## API

- `GET /api/products` — clothing catalog
- `GET /api/complaints?phone=03XXXXXXXXX` — list complaints for a phone
- `POST /api/complaints` — create a complaint (`phone`, `customer_name`, `address`, `complaint_type`, `description`, optional `order_number` / `invoice_number`, or `purchase_channel` + dates, optional `product_sku`)
- `WS /api/live` — Gemini Live voice session

## Voice tools

- `search_products` / `list_products` / `show_product_picker`
- `submit_complaint`, `get_complaints_by_phone`, `get_complaint_status`, `end_call`

## Android native

```bash
cd android-native
# Emulator uses ws://10.0.2.2:3000 by default — start the server first
./gradlew :app:installDebug
```
