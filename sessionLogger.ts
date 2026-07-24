import fs from "fs";
import path from "path";

export type LogLevel = "info" | "warn" | "error" | "debug";

const LOG_DIR = path.resolve(
  process.env.LOG_DIR?.trim() || path.join(process.cwd(), "logs")
);
const SESSIONS_DIR = path.join(LOG_DIR, "sessions");

function ensureDirs() {
  fs.mkdirSync(SESSIONS_DIR, { recursive: true });
}

function stamp() {
  return new Date().toISOString();
}

function dayKey(d = new Date()) {
  return d.toISOString().slice(0, 10);
}

function safeJson(value: unknown): string {
  try {
    return JSON.stringify(value);
  } catch {
    return JSON.stringify({ error: "unserializable" });
  }
}

/** Strip huge audio blobs; keep size for debugging. */
export function summarizeForLog(payload: Record<string, unknown>): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  for (const [key, raw] of Object.entries(payload || {})) {
    if (key === "audio" || key === "data") {
      if (typeof raw === "string") {
        out[key] = `[base64 ${raw.length} chars]`;
      } else {
        out[key] = "[omitted]";
      }
      continue;
    }
    if (key === "products" && Array.isArray(raw)) {
      out[key] = { count: raw.length };
      continue;
    }
    if (key === "result" && raw && typeof raw === "object") {
      const r = raw as Record<string, unknown>;
      if (Array.isArray(r.products)) {
        out[key] = { ...r, products: { count: r.products.length } };
        continue;
      }
    }
    out[key] = raw;
  }
  return out;
}

function appendLine(filePath: string, line: string) {
  try {
    ensureDirs();
    fs.appendFileSync(filePath, line + "\n", "utf8");
  } catch (err) {
    console.error("[sessionLogger] write failed:", err);
  }
}

function write(
  level: LogLevel,
  scope: string,
  event: string,
  data?: unknown,
  sessionFile?: string
) {
  const entry = {
    ts: stamp(),
    level,
    scope,
    event,
    ...(data !== undefined ? { data } : {}),
  };
  const line = safeJson(entry);
  appendLine(path.join(LOG_DIR, `agent-${dayKey()}.log`), line);
  if (sessionFile) appendLine(sessionFile, line);

  const consoleLine = `[${entry.ts}] ${level.toUpperCase()} ${scope} ${event}${
    data !== undefined ? " " + safeJson(data) : ""
  }`;
  if (level === "error") console.error(consoleLine);
  else if (level === "warn") console.warn(consoleLine);
  else console.log(consoleLine);
}

let bootLogged = false;

export function logBoot(appName: string, extra?: Record<string, unknown>) {
  if (bootLogged) return;
  bootLogged = true;
  ensureDirs();
  write("info", "server", "boot", { app: appName, logDir: LOG_DIR, ...extra });
}

export type SessionLogger = {
  sessionId: string;
  info: (event: string, data?: unknown) => void;
  warn: (event: string, data?: unknown) => void;
  error: (event: string, data?: unknown) => void;
  debug: (event: string, data?: unknown) => void;
  clientIn: (event: string, data?: unknown) => void;
  clientOut: (event: string, data?: unknown) => void;
  agent: (event: string, data?: unknown) => void;
};

export function createSessionLogger(
  appName: string,
  meta?: Record<string, unknown>
): SessionLogger {
  ensureDirs();
  const sessionId = `${dayKey().replace(/-/g, "")}-${Date.now().toString(36)}-${Math.random()
    .toString(36)
    .slice(2, 8)}`;
  const sessionFile = path.join(SESSIONS_DIR, `${sessionId}.log`);
  const scope = `session:${sessionId}`;

  const log = (level: LogLevel, event: string, data?: unknown) =>
    write(level, scope, event, data, sessionFile);

  log("info", "session_open", { app: appName, ...meta });

  return {
    sessionId,
    info: (event, data) => log("info", event, data),
    warn: (event, data) => log("warn", event, data),
    error: (event, data) => log("error", event, data),
    debug: (event, data) => log("debug", event, data),
    clientIn: (event, data) => log("info", `client_in:${event}`, data),
    clientOut: (event, data) => log("info", `client_out:${event}`, data),
    agent: (event, data) => log("info", `agent:${event}`, data),
  };
}

export type LogEntry = {
  ts?: string;
  level?: string;
  scope?: string;
  event?: string;
  data?: unknown;
  raw?: string;
};

export type LogFileInfo = {
  id: string;
  name: string;
  kind: "day" | "session";
  sizeBytes: number;
  mtimeMs: number;
  mtime: string;
};

function safeTailLines(filePath: string, maxLines: number): string[] {
  if (!fs.existsSync(filePath)) return [];
  const text = fs.readFileSync(filePath, "utf8");
  const lines = text.split(/\r?\n/).filter((l) => l.trim().length > 0);
  if (lines.length <= maxLines) return lines;
  return lines.slice(-maxLines);
}

function parseLogLines(lines: string[]): LogEntry[] {
  return lines.map((line) => {
    try {
      const parsed = JSON.parse(line) as LogEntry;
      return parsed;
    } catch {
      return { raw: line, level: "info", event: "raw" };
    }
  });
}

export function listDayLogFiles(): LogFileInfo[] {
  ensureDirs();
  return fs
    .readdirSync(LOG_DIR)
    .filter((name) => /^agent-\d{4}-\d{2}-\d{2}\.log$/.test(name))
    .map((name) => {
      const full = path.join(LOG_DIR, name);
      const st = fs.statSync(full);
      return {
        id: name.slice(6, 16),
        name,
        kind: "day" as const,
        sizeBytes: st.size,
        mtimeMs: st.mtimeMs,
        mtime: st.mtime.toISOString(),
      };
    })
    .sort((a, b) => b.mtimeMs - a.mtimeMs);
}

export function listSessionLogFiles(limit = 80): LogFileInfo[] {
  ensureDirs();
  return fs
    .readdirSync(SESSIONS_DIR)
    .filter((name) => /^[\w-]+\.log$/.test(name))
    .map((name) => {
      const full = path.join(SESSIONS_DIR, name);
      const st = fs.statSync(full);
      return {
        id: name.replace(/\.log$/, ""),
        name,
        kind: "session" as const,
        sizeBytes: st.size,
        mtimeMs: st.mtimeMs,
        mtime: st.mtime.toISOString(),
      };
    })
    .sort((a, b) => b.mtimeMs - a.mtimeMs)
    .slice(0, Math.max(1, Math.min(limit, 200)));
}

export function readDayLog(day: string, tailLines = 300): LogEntry[] {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(day)) {
    throw new Error("Invalid day id");
  }
  const filePath = path.join(LOG_DIR, `agent-${day}.log`);
  return parseLogLines(safeTailLines(filePath, Math.max(20, Math.min(tailLines, 2000))));
}

export function readSessionLog(sessionId: string, tailLines = 500): LogEntry[] {
  if (!/^[\w-]+$/.test(sessionId)) {
    throw new Error("Invalid session id");
  }
  const filePath = path.join(SESSIONS_DIR, `${sessionId}.log`);
  return parseLogLines(safeTailLines(filePath, Math.max(20, Math.min(tailLines, 2000))));
}

export function getLogDirPath() {
  return LOG_DIR;
}
