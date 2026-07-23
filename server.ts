import dotenv from "dotenv";
dotenv.config();

import { GoogleGenAI, Modality } from "@google/genai";
import express from "express";
import http from "http";
import path from "path";
import { WebSocket, WebSocketServer } from "ws";
import {
  buildCatalogText,
  createComplaint,
  getComplaintsByPhone,
  listProducts,
  normalizePhone,
  pingDataStore,
} from "./dataStore";
import { buildComplaintSystemPrompt } from "./complaintPrompt";
import { COMPLAINT_TOOLS, handleComplaintToolCall } from "./complaintTools";

const GEMINI_TEXT_MODEL =
  process.env.GEMINI_TEXT_MODEL?.trim() || "gemini-3.1-flash-lite";
const GEMINI_LIVE_MODEL =
  process.env.GEMINI_LIVE_MODEL?.trim() || "gemini-3.1-flash-live-preview";
const GEMINI_LIVE_TRANSCRIPTS =
  (process.env.GEMINI_LIVE_TRANSCRIPTS || "false").trim().toLowerCase() ===
  "true";

console.log(
  `[models] text=${GEMINI_TEXT_MODEL} | live=${GEMINI_LIVE_MODEL} | transcripts=${GEMINI_LIVE_TRANSCRIPTS}`
);

function transliterateGurmukhi(text: string): string {
  if (!/[\u0A00-\u0A7F]/.test(text)) return text;
  const vowels: Record<string, string> = {
    "ਅ": "a", "ਆ": "aa", "ਇ": "i", "ਈ": "ee", "ਉ": "u", "ਊ": "oo",
    "ਏ": "e", "ਐ": "ai", "ਓ": "o", "ਔ": "au",
  };
  const consonants: Record<string, string> = {
    "ਕ": "k", "ਖ": "kh", "ਗ": "g", "ਘ": "gh", "ਙ": "ng",
    "ਚ": "ch", "ਛ": "chh", "ਜ": "j", "ਝ": "jh", "ਞ": "ny",
    "ਟ": "t", "ਠ": "th", "ਡ": "d", "ਢ": "dh", "ਣ": "n",
    "ਤ": "t", "ਥ": "th", "ਦ": "d", "ਧ": "dh", "ਨ": "n",
    "ਪ": "p", "ਫ": "ph", "ਬ": "b", "ਭ": "bh", "ਮ": "m",
    "ਯ": "y", "ਰ": "r", "ਲ": "l", "ਵ": "v", "ੜ": "r",
    "ਸ਼": "sh", "ਸ": "s", "ਹ": "h", "ਖ਼": "kh", "ਗ਼": "gh",
    "ਜ਼": "z", "ਫ਼": "f", "ਲ਼": "l",
  };
  const matras: Record<string, string> = {
    "ਾ": "aa", "ਿ": "i", "ੀ": "ee", "ੁ": "u", "ੂ": "oo",
    "ੇ": "e", "ੈ": "ai", "ੋ": "o", "ੌ": "au",
  };
  const chars = Array.from(text.normalize("NFC"));
  let result = "";
  for (let i = 0; i < chars.length; i += 1) {
    const char = chars[i];
    if (vowels[char]) result += vowels[char];
    else if (consonants[char]) {
      result += consonants[char];
      const next = chars[i + 1];
      if (next && (consonants[next] || next === "ਂ" || next === "ੰ")) result += "a";
    } else if (matras[char]) result += matras[char];
    else if (char === "ਂ" || char === "ੰ") result += "n";
    else if (char === "ੱ") {
      const next = chars[i + 1];
      if (next && consonants[next]) result += consonants[next];
    } else if (!/[\u0A00-\u0A7F]/.test(char)) result += char;
  }
  return result;
}

function normalizePakistaniTranscript(text: string): string {
  return transliterateGurmukhi(String(text || ""))
    .replace(/\bkripa\b/gi, "meherbani")
    .replace(/\bkripya\b/gi, "meherbani karke");
}

function isFarewellTranscript(text: string): boolean {
  const raw = String(text || "");
  if (/اللہ\s*حافظ/.test(raw) || /خدا\s*حافظ/.test(raw)) return true;
  const t = raw
    .toLowerCase()
    .replace(/[^a-z0-9\u0600-\u06ff\s]/gi, " ")
    .replace(/\s+/g, " ")
    .trim();
  return (
    /allah\s*ha+fi[zs]/.test(t) ||
    /alla+h\s*ha+fi[zs]/.test(t) ||
    /khuda\s*ha+fi[zs]/.test(t) ||
    /allahhafiz/.test(t) ||
    /khudahafiz/.test(t) ||
    /good\s*bye/.test(t) ||
    /goodbye/.test(t) ||
    /bye\s*bye/.test(t) ||
    // Avoid bare "bye" alone — too easy to false-trigger mid-call.
    /\b(please )?end (the )?call\b/.test(t) ||
    /\bcall end\b/.test(t) ||
    /\bcall band\b/.test(t)
  );
}

async function startServer() {
  const app = express();
  const server = http.createServer(app);
  const wss = new WebSocketServer({ noServer: true });
  const PORT = Number(process.env.PORT) || 3000;

  app.use(express.json({ limit: "100kb" }));

  app.get("/api/health", (_req, res) => {
    let dataOk = false;
    try {
      dataOk = pingDataStore();
    } catch (err) {
      console.error("data store health check failed:", err);
    }
    res.json({
      status: "ok",
      app: "sapphire_bot",
      hasApiKey: !!process.env.GEMINI_API_KEY,
      dataStoreReady: dataOk,
      storage: "json-files",
    });
  });

  app.get("/api/products", (_req, res) => {
    try {
      const products = listProducts(100);
      res.json({
        products: products.map((p) => ({
          sku_code: p.sku_code,
          name: p.name,
          brand: p.brand,
          category: p.category,
          color: p.color,
          sizes: p.sizes,
          price_pkr: p.price_pkr,
          price_per_case_rs: p.price_pkr,
          description: p.description,
        })),
      });
    } catch (err: any) {
      console.error("products endpoint failed:", err);
      res.status(500).json({ error: err?.message || "Failed to load products" });
    }
  });

  app.get("/api/complaints", (req, res) => {
    try {
      const phone = normalizePhone(req.query.phone);
      if (!phone) {
        return res.status(400).json({ ok: false, error: "phone query is required" });
      }
      const complaints = getComplaintsByPhone(phone);
      res.json({ ok: true, phone, count: complaints.length, complaints });
    } catch (err: any) {
      console.error("complaints list failed:", err);
      res.status(500).json({ ok: false, error: err?.message || "Failed to load complaints" });
    }
  });

  app.post("/api/complaints", (req, res) => {
    try {
      const complaint = createComplaint({
        phone: String(req.body?.phone || ""),
        customer_name: String(req.body?.customer_name || ""),
        address: String(req.body?.address || ""),
        complaint_type: String(req.body?.complaint_type || "Other"),
        description: String(req.body?.description || ""),
        product_sku: req.body?.product_sku ? String(req.body.product_sku) : null,
        order_number: req.body?.order_number
          ? String(req.body.order_number)
          : null,
        invoice_number: req.body?.invoice_number
          ? String(req.body.invoice_number)
          : null,
        purchase_channel: req.body?.purchase_channel
          ? String(req.body.purchase_channel)
          : null,
        purchase_date: req.body?.purchase_date
          ? String(req.body.purchase_date)
          : null,
        received_date: req.body?.received_date
          ? String(req.body.received_date)
          : null,
      });
      res.json({ ok: true, verified: true, complaint });
    } catch (err: any) {
      console.error("create complaint failed:", err);
      res.status(400).json({ ok: false, error: err?.message || "Failed to create complaint" });
    }
  });

  app.post("/api/optimize-rules", async (req, res) => {
    try {
      const apiKey = process.env.GEMINI_API_KEY;
      if (!apiKey) {
        res.status(500).json({ error: "GEMINI_API_KEY is not configured" });
        return;
      }
      const rawRules =
        typeof req.body?.rules === "string" ? req.body.rules.trim() : "";
      if (!rawRules) {
        res.status(400).json({ error: "Please provide some rules to optimize." });
        return;
      }
      const ai = new GoogleGenAI({ apiKey });
      const response = await ai.models.generateContent({
        model: GEMINI_TEXT_MODEL,
        contents: `Optimize custom operator rules for Plano Agent, a clothing complaints voice assistant that speaks ONLY Pakistani Urdu.
Rewrite into a clean bullet list. Keep intent. One rule per line starting with "- ".
Do not invent policies. Remind that spoken replies must stay in Urdu. Output ONLY the bullet list.

Operator draft rules:
${rawRules}`,
      });
      const optimized = (response.text || "").trim();
      if (!optimized) {
        res.status(502).json({ error: "Model returned an empty result." });
        return;
      }
      res.json({ optimized });
    } catch (err: any) {
      console.error("optimize-rules failed:", err);
      res.status(500).json({ error: err?.message || "Failed to optimize rules" });
    }
  });

  server.on("upgrade", (request, socket, head) => {
    const urlObj = new URL(request.url || "", `http://${request.headers.host}`);
    if (urlObj.pathname === "/api/live") {
      wss.handleUpgrade(request, socket, head, (ws) => {
        wss.emit("connection", ws, request);
      });
    } else {
      socket.destroy();
    }
  });

  wss.on("connection", async (ws: WebSocket, req) => {
    console.log("Client WebSocket connected");

    const reqUrl = new URL(req.url || "", `http://${req.headers.host}`);
    let voiceName = reqUrl.searchParams.get("voice") || "Charon";

    const apiKey = process.env.GEMINI_API_KEY;
    if (!apiKey) {
      ws.send(
        JSON.stringify({
          type: "error",
          message:
            "GEMINI_API_KEY is not configured on the server. Please add it to your .env file.",
        })
      );
      ws.close();
      return;
    }

    let session: Awaited<ReturnType<GoogleGenAI["live"]["connect"]>> | null =
      null;
    let aiSessionReady = false;

    type LanguageMode = "auto" | "english" | "roman_urdu" | "urdu" | "punjabi";
    const supportedLanguageModes = new Set<LanguageMode>([
      "auto",
      "english",
      "roman_urdu",
      "urdu",
      "punjabi",
    ]);

    const waitForSessionConfig = (): Promise<{
      extraRules: string;
      languageMode: LanguageMode;
    }> =>
      new Promise((resolve) => {
        let settled = false;
        const finish = (value: {
          extraRules: string;
          languageMode: LanguageMode;
        }) => {
          if (settled) return;
          settled = true;
          cleanup();
          resolve(value);
        };

        const timeout = setTimeout(() => {
          finish({ extraRules: "", languageMode: "urdu" });
        }, 2500);

        const onMessage = (raw: WebSocket.RawData) => {
          try {
            const msg = JSON.parse(raw.toString());
            if (msg.type === "session_config") {
              if (typeof msg.voice === "string" && msg.voice.trim()) {
                voiceName = msg.voice.trim();
              }
              const extra =
                typeof msg.extraRules === "string"
                  ? msg.extraRules
                  : typeof msg.systemInstruction === "string"
                    ? msg.systemInstruction
                    : "";
              const requestedMode =
                typeof msg.languageMode === "string"
                  ? (msg.languageMode as LanguageMode)
                  : "auto";
              const languageMode = supportedLanguageModes.has(requestedMode)
                ? requestedMode
                : "auto";
              finish({ extraRules: extra, languageMode });
            }
          } catch {
            // ignore
          }
        };

        const cleanup = () => {
          clearTimeout(timeout);
          ws.off("message", onMessage);
        };

        ws.on("message", onMessage);
        ws.send(JSON.stringify({ type: "status", status: "awaiting_config" }));
      });

    try {
      const { extraRules } = await waitForSessionConfig();
      // Plano Agent is Urdu-only — ignore client language switches.
      const languageMode: LanguageMode = "urdu";
      const catalog = buildCatalogText(20);
      const baseSystemInstruction = buildComplaintSystemPrompt(
        catalog,
        extraRules
      );
      const systemInstruction = `${baseSystemInstruction}

## ACTIVE LANGUAGE MODE
URDU ONLY (NON-OVERRIDABLE): You must speak Pakistani Urdu on every response.
Understand the customer if they speak English, Roman Urdu, or Punjabi, but ALWAYS reply in Urdu.
Never reply in English, Roman Urdu, Punjabi, Hindi, or Devanagari/Gurmukhi.`;

      const ai = new GoogleGenAI({
        apiKey,
        httpOptions: { headers: { "User-Agent": "aistudio-build" } },
      });

      console.log(
        `Connecting Plano Agent voice=${voiceName} model=${GEMINI_LIVE_MODEL} lang=${languageMode}`
      );

      let farewellHangupSent = false;
      let recentUserTranscript = "";
      let greetingSent = false;
      let clientClosed = false;
      // Gemini Live drops the session if realtime audio/activity arrives
      // while a tool call is pending (WebSocket 1008 policy violation).
      let toolCallPending = false;

      const sendJson = (payload: Record<string, unknown>) => {
        if (clientClosed || ws.readyState !== ws.OPEN) return;
        ws.send(JSON.stringify(payload));
      };

      const sanitizeToolResponse = (
        value: Record<string, unknown>
      ): Record<string, unknown> => {
        // Keep tool payloads small/primitive — large or nested objects
        // are a common cause of Live session drops after sendToolResponse.
        const out: Record<string, unknown> = {};
        for (const [key, raw] of Object.entries(value || {})) {
          if (raw == null) {
            out[key] = null;
            continue;
          }
          if (
            typeof raw === "string" ||
            typeof raw === "number" ||
            typeof raw === "boolean"
          ) {
            out[key] =
              typeof raw === "string" && raw.length > 500
                ? `${raw.slice(0, 500)}…`
                : raw;
            continue;
          }
          if (Array.isArray(raw)) {
            // Products list for picker — allow capped array of plain objects
            if (key === "products" || key === "complaints") {
              out[key] = raw.slice(0, 30).map((item) => {
                if (!item || typeof item !== "object") return item;
                const row: Record<string, unknown> = {};
                for (const [k, v] of Object.entries(
                  item as Record<string, unknown>
                )) {
                  if (
                    v == null ||
                    typeof v === "string" ||
                    typeof v === "number" ||
                    typeof v === "boolean"
                  ) {
                    row[k] = v;
                  } else if (Array.isArray(v)) {
                    row[k] = v
                      .filter(
                        (x) =>
                          typeof x === "string" ||
                          typeof x === "number" ||
                          typeof x === "boolean"
                      )
                      .slice(0, 12);
                  }
                }
                return row;
              });
            } else {
              out[key] = raw.length;
            }
            continue;
          }
          // Skip nested objects except we already handled arrays
        }
        return out;
      };

      session = await ai.live.connect({
        model: GEMINI_LIVE_MODEL,
        config: {
          responseModalities: [Modality.AUDIO],
          speechConfig: {
            voiceConfig: { prebuiltVoiceConfig: { voiceName } },
          },
          thinkingConfig: { thinkingLevel: "minimal" as any },
          realtimeInputConfig: {
            automaticActivityDetection: { disabled: true },
          },
          systemInstruction,
          tools: COMPLAINT_TOOLS,
          ...(GEMINI_LIVE_TRANSCRIPTS
            ? {
                outputAudioTranscription: {},
                inputAudioTranscription: {},
              }
            : {}),
        },
        callbacks: {
          onmessage: async (message: any) => {
            if (!session || clientClosed) return;

            if (message.toolCall?.functionCalls?.length) {
              toolCallPending = true;
              sendJson({ type: "tool_pending", pending: true });
              const functionResponses = [];
              try {
                for (const fc of message.toolCall.functionCalls) {
                  console.log(`Tool call: ${fc.name}`, fc.args);
                  const result = await handleComplaintToolCall(
                    fc.name,
                    (fc.args || {}) as Record<string, unknown>
                  );
                  const safeResult = sanitizeToolResponse(result);
                  functionResponses.push({
                    id: fc.id,
                    name: fc.name,
                    response: safeResult,
                  });
                  sendJson({
                    type: "tool",
                    name: fc.name,
                    args: fc.args,
                    result: safeResult,
                  });
                  if (
                    fc.name === "show_product_picker" &&
                    result.ui_action === "open_product_picker"
                  ) {
                    sendJson({
                      type: "show_products",
                      products: result.products || [],
                    });
                  }
                  if (fc.name === "end_call" && result.end_call) {
                    sendJson({
                      type: "end_call",
                      reason: result.reason || "customer_confirmed",
                    });
                  }
                }
                if (!clientClosed && session) {
                  session.sendToolResponse({ functionResponses });
                }
              } catch (err) {
                console.error("Tool call handling failed:", err);
                sendJson({
                  type: "error",
                  message:
                    err instanceof Error
                      ? err.message
                      : "Tool call failed",
                });
              } finally {
                toolCallPending = false;
                sendJson({ type: "tool_pending", pending: false });
              }
              return;
            }

            const audio =
              message.serverContent?.modelTurn?.parts?.[0]?.inlineData?.data;
            if (audio) {
              sendJson({ type: "audio", data: audio });
            }

            if (message.serverContent?.interrupted) {
              sendJson({ type: "interrupted" });
            }

            if (message.serverContent?.modelTurn?.parts) {
              for (const part of message.serverContent.modelTurn.parts) {
                if (part.text) {
                  sendJson({
                    type: "model-text",
                    text: normalizePakistaniTranscript(part.text),
                  });
                }
              }
            }

            const outputTx = message.serverContent?.outputTranscription?.text;
            if (outputTx) {
              sendJson({
                type: "model-text",
                text: normalizePakistaniTranscript(outputTx),
              });
            }

            const emitUserText = (text: string) => {
              const normalized = normalizePakistaniTranscript(text);
              if (!normalized.trim()) return;
              sendJson({ type: "user-text", text: normalized });
              recentUserTranscript = `${recentUserTranscript} ${normalized}`
                .replace(/\s+/g, " ")
                .trim()
                .slice(-240);
              if (
                !farewellHangupSent &&
                isFarewellTranscript(recentUserTranscript)
              ) {
                farewellHangupSent = true;
                sendJson({
                  type: "farewell_hangup",
                  text: recentUserTranscript,
                });
              }
            };

            if (message.serverContent?.userTurn?.parts) {
              for (const part of message.serverContent.userTurn.parts) {
                if (part.text) emitUserText(part.text);
              }
            }

            const inputTx = message.serverContent?.inputTranscription?.text;
            if (inputTx) emitUserText(inputTx);
          },
          onclose: (ev?: any) => {
            console.log(
              "Plano Agent session closed",
              ev?.code || ev?.reason || ""
            );
            toolCallPending = false;
            sendJson({ type: "status", status: "closed" });
          },
          onerror: (err: any) => {
            console.error("Plano Agent session error:", err);
            sendJson({
              type: "error",
              message:
                err.message || "Plano Agent experienced an internal error.",
            });
          },
        },
      });

      aiSessionReady = true;
      console.log("Plano Agent session established");
      sendJson({ type: "status", status: "established" });

      const greetingCue =
        "Customer joined. Give a SHORT Pakistani Urdu greeting as Plano Agent. Speak ONLY Urdu. Offer clothing product info OR complaint help. Ask ONLY one short question at the end (e.g. how can I help). Do not ask for phone/name/address yet. Do not list multiple questions. Speak only — do not wait. Do not use English or Roman Urdu.";

      const sendGreetingOnce = () => {
        if (greetingSent || !session || !aiSessionReady || clientClosed) return;
        if (ws.readyState !== ws.OPEN) return;
        greetingSent = true;
        try {
          session.sendClientContent({
            turns: [
              {
                role: "user",
                parts: [{ text: greetingCue }],
              },
            ],
            turnComplete: true,
          });
        } catch (err) {
          greetingSent = false;
          console.error("Failed to trigger opening greeting:", err);
        }
      };

      const greetingTimer = setTimeout(sendGreetingOnce, 2000);

      const onClientMessage = async (data: WebSocket.RawData) => {
        if (clientClosed) return;
        try {
          const message = JSON.parse(data.toString());
          if (message.type === "session_config") return;

          // Block ALL realtime input while a tool is running — required by Live API.
          if (toolCallPending) {
            if (
              message.audio ||
              message.type === "activity_start" ||
              message.type === "activity_end"
            ) {
              return;
            }
          }

          if (message.audio && session && aiSessionReady) {
            session.sendRealtimeInput({
              audio: {
                data: message.audio,
                mimeType: "audio/pcm;rate=16000",
              },
            });
          }

          if (message.type === "activity_start" && session && aiSessionReady) {
            session.sendRealtimeInput({ activityStart: {} });
          }

          if (message.type === "activity_end" && session && aiSessionReady) {
            session.sendRealtimeInput({ activityEnd: {} });
          }

          if (
            (message.type === "user_text" || message.type === "client_text") &&
            typeof message.text === "string" &&
            message.text.trim() &&
            session &&
            aiSessionReady
          ) {
            session.sendClientContent({
              turns: [
                {
                  role: "user",
                  parts: [{ text: message.text.trim() }],
                },
              ],
              turnComplete: true,
            });
          }
        } catch (err) {
          console.error("Error processing client message:", err);
        }
      };

      ws.on("message", onClientMessage);

      ws.on("close", () => {
        clientClosed = true;
        clearTimeout(greetingTimer);
        ws.off("message", onClientMessage);
        console.log("Client WebSocket connection closed");
        try {
          session?.close();
        } catch (err) {
          console.error("Error closing Plano Agent session:", err);
        }
        session = null;
        aiSessionReady = false;
      });
    } catch (err: any) {
      console.error("Failed to initialize Plano Agent:", err);
      ws.send(
        JSON.stringify({
          type: "error",
          message:
            "Failed to establish Plano Agent connection: " +
            (err.message || String(err)),
        })
      );
      ws.close();
    }
  });

  if (process.env.NODE_ENV !== "production") {
    const { createServer: createViteServer } = await import("vite");
    const vite = await createViteServer({
      server: {
        middlewareMode: true,
        // Complaint writes touch data/*.json — must not trigger Vite reload / WS drop.
        watch: {
          ignored: [
            "**/data/**",
            "**/data/complaints.json",
            "**/data/*.json.tmp",
            "**/*.json.tmp",
          ],
        },
      },
      appType: "spa",
    });
    app.use(vite.middlewares);
  } else {
    const distPath = path.join(process.cwd(), "dist");
    app.use(express.static(distPath));
    app.get("*", (_req, res) => {
      res.sendFile(path.join(distPath, "index.html"));
    });
  }

  server.listen(PORT, "0.0.0.0", () => {
    console.log(`Plano Agent listening on http://0.0.0.0:${PORT}`);
  });
}

startServer().catch((err) => {
  console.error("Failed to start server:", err);
  process.exit(1);
});
