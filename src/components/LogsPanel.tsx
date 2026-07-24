import React, { useEffect, useMemo, useState } from "react";
import { FileText, RefreshCw, ScrollText, X } from "lucide-react";

type LogFileInfo = {
  id: string;
  name: string;
  kind: "day" | "session";
  sizeBytes: number;
  mtime: string;
};

type LogEntry = {
  ts?: string;
  level?: string;
  scope?: string;
  event?: string;
  data?: unknown;
  raw?: string;
};

type Props = {
  open: boolean;
  onClose: () => void;
};

function levelClass(level?: string) {
  switch ((level || "").toLowerCase()) {
    case "error":
      return "border-rose-200 bg-rose-50 text-rose-800";
    case "warn":
      return "border-amber-200 bg-amber-50 text-amber-900";
    case "debug":
      return "border-slate-200 bg-slate-50 text-slate-600";
    default:
      return "border-[#013BAA]/10 bg-[#F7F9FC] text-[#0B2C6E]";
  }
}

function formatBytes(n: number) {
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
  return `${(n / (1024 * 1024)).toFixed(1)} MB`;
}

function formatTime(ts?: string) {
  if (!ts) return "";
  try {
    return new Date(ts).toLocaleTimeString();
  } catch {
    return ts;
  }
}

export default function LogsPanel({ open, onClose }: Props) {
  const [tab, setTab] = useState<"session" | "day">("session");
  const [sessions, setSessions] = useState<LogFileInfo[]>([]);
  const [days, setDays] = useState<LogFileInfo[]>([]);
  const [selectedId, setSelectedId] = useState<string>("");
  const [entries, setEntries] = useState<LogEntry[]>([]);
  const [filter, setFilter] = useState("");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [autoRefresh, setAutoRefresh] = useState(true);

  const loadIndex = async () => {
    setError(null);
    try {
      const res = await fetch("/api/logs");
      const json = await res.json();
      if (!res.ok || !json?.ok) throw new Error(json?.error || "Failed to load logs");
      const nextSessions: LogFileInfo[] = Array.isArray(json.sessions)
        ? json.sessions
        : [];
      const nextDays: LogFileInfo[] = Array.isArray(json.days) ? json.days : [];
      setSessions(nextSessions);
      setDays(nextDays);
      setSelectedId((prev) => {
        if (prev) return prev;
        if (tab === "session") return nextSessions[0]?.id || "";
        return nextDays[0]?.id || "";
      });
    } catch (err: any) {
      setError(err?.message || "Failed to load log index");
    }
  };

  const loadEntries = async (id = selectedId, kind = tab) => {
    if (!id) {
      setEntries([]);
      return;
    }
    setLoading(true);
    setError(null);
    try {
      const path =
        kind === "session"
          ? `/api/logs/session/${encodeURIComponent(id)}?tail=400`
          : `/api/logs/day/${encodeURIComponent(id)}?tail=400`;
      const res = await fetch(path);
      const json = await res.json();
      if (!res.ok || !json?.ok) throw new Error(json?.error || "Failed to load log");
      setEntries(Array.isArray(json.entries) ? json.entries : []);
    } catch (err: any) {
      setError(err?.message || "Failed to load log entries");
    } finally {
      setLoading(false);
    }
  };

  const refreshAll = async () => {
    await loadIndex();
    await loadEntries();
  };

  useEffect(() => {
    if (!open) return;
    void refreshAll();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  useEffect(() => {
    if (!open) return;
    const list = tab === "session" ? sessions : days;
    if (!selectedId && list[0]?.id) {
      setSelectedId(list[0].id);
      return;
    }
    if (selectedId) void loadEntries(selectedId, tab);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tab, selectedId]);

  useEffect(() => {
    if (!open || !autoRefresh) return;
    const id = window.setInterval(() => {
      void loadEntries(selectedId, tab);
      void loadIndex();
    }, 5000);
    return () => window.clearInterval(id);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, autoRefresh, selectedId, tab]);

  const files = tab === "session" ? sessions : days;

  const visible = useMemo(() => {
    const q = filter.trim().toLowerCase();
    if (!q) return entries;
    return entries.filter((e) => {
      const hay = `${e.event || ""} ${e.scope || ""} ${e.level || ""} ${
        e.data ? JSON.stringify(e.data) : ""
      } ${e.raw || ""}`.toLowerCase();
      return hay.includes(q);
    });
  }, [entries, filter]);

  if (!open) return null;

  return (
    <div className="fixed inset-0 z-50 flex justify-end bg-[#013BAA]/25 backdrop-blur-sm">
      <div className="flex h-full w-full max-w-3xl flex-col bg-[#FEFEFE] shadow-2xl">
        <div className="flex items-center justify-between border-b border-[#013BAA]/10 px-5 py-4">
          <div>
            <h2 className="text-lg font-bold text-[#013BAA]">Live logs</h2>
            <p className="text-xs text-[#013BAA]/55">
              Call communication from the server log files
            </p>
          </div>
          <div className="flex items-center gap-2">
            <label className="mr-1 flex items-center gap-1.5 text-[11px] font-semibold text-[#013BAA]/65">
              <input
                type="checkbox"
                checked={autoRefresh}
                onChange={(e) => setAutoRefresh(e.target.checked)}
              />
              Auto
            </label>
            <button
              type="button"
              onClick={() => void refreshAll()}
              className="rounded-xl border border-[#013BAA]/15 p-2 text-[#013BAA]/70 hover:bg-[#E6F1FE]"
              title="Refresh"
            >
              <RefreshCw className={`h-4 w-4 ${loading ? "animate-spin" : ""}`} />
            </button>
            <button
              type="button"
              onClick={onClose}
              className="rounded-xl border border-[#013BAA]/15 p-2 text-[#013BAA]/70 hover:bg-[#E6F1FE]"
            >
              <X className="h-4 w-4" />
            </button>
          </div>
        </div>

        <div className="flex gap-2 border-b border-[#013BAA]/10 px-5 py-3">
          <button
            type="button"
            onClick={() => {
              setTab("session");
              setSelectedId(sessions[0]?.id || "");
            }}
            className={`inline-flex items-center gap-1.5 rounded-full px-3 py-1.5 text-xs font-semibold ${
              tab === "session"
                ? "bg-[#013BAA] text-white"
                : "border border-[#013BAA]/15 text-[#013BAA]/70"
            }`}
          >
            <ScrollText className="h-3.5 w-3.5" />
            Sessions
          </button>
          <button
            type="button"
            onClick={() => {
              setTab("day");
              setSelectedId(days[0]?.id || "");
            }}
            className={`inline-flex items-center gap-1.5 rounded-full px-3 py-1.5 text-xs font-semibold ${
              tab === "day"
                ? "bg-[#013BAA] text-white"
                : "border border-[#013BAA]/15 text-[#013BAA]/70"
            }`}
          >
            <FileText className="h-3.5 w-3.5" />
            Daily
          </button>
          <input
            value={filter}
            onChange={(e) => setFilter(e.target.value)}
            placeholder="Filter events…"
            className="ml-auto min-w-0 flex-1 rounded-xl border border-[#013BAA]/15 px-3 py-1.5 text-xs outline-none focus:border-[#013BAA]/40"
          />
        </div>

        <div className="flex min-h-0 flex-1">
          <div className="w-44 shrink-0 overflow-y-auto border-r border-[#013BAA]/10 p-2 sm:w-56">
            {files.length === 0 ? (
              <p className="px-2 py-3 text-[11px] text-[#013BAA]/45">
                No log files yet. Make a call first.
              </p>
            ) : (
              files.map((f) => (
                <button
                  key={f.id}
                  type="button"
                  onClick={() => setSelectedId(f.id)}
                  className={`mb-1 w-full rounded-xl px-2.5 py-2 text-left ${
                    selectedId === f.id
                      ? "bg-[#013BAA] text-white"
                      : "hover:bg-[#E6F1FE] text-[#013BAA]"
                  }`}
                >
                  <div className="truncate text-[11px] font-semibold">{f.id}</div>
                  <div
                    className={`mt-0.5 text-[10px] ${
                      selectedId === f.id ? "text-white/70" : "text-[#013BAA]/45"
                    }`}
                  >
                    {formatBytes(f.sizeBytes)}
                  </div>
                </button>
              ))
            )}
          </div>

          <div className="min-w-0 flex-1 overflow-y-auto p-3 space-y-2">
            {error && (
              <p className="rounded-xl bg-rose-50 px-3 py-2 text-xs font-medium text-rose-600">
                {error}
              </p>
            )}
            {loading && entries.length === 0 ? (
              <p className="text-xs text-[#013BAA]/45">Loading…</p>
            ) : visible.length === 0 ? (
              <p className="text-xs text-[#013BAA]/45">No matching log lines.</p>
            ) : (
              visible.map((e, i) => (
                <div
                  key={`${e.ts || i}-${e.event || "e"}-${i}`}
                  className={`rounded-xl border px-3 py-2 ${levelClass(e.level)}`}
                >
                  <div className="flex flex-wrap items-center gap-2 text-[10px] font-semibold uppercase tracking-wide opacity-70">
                    <span>{formatTime(e.ts)}</span>
                    <span>{e.level || "info"}</span>
                    {e.scope && <span className="normal-case opacity-90">{e.scope}</span>}
                  </div>
                  <div className="mt-1 text-xs font-semibold">{e.event || e.raw || "event"}</div>
                  {e.data !== undefined && (
                    <pre className="mt-1 max-h-40 overflow-auto whitespace-pre-wrap break-words text-[11px] opacity-90">
                      {typeof e.data === "string"
                        ? e.data
                        : JSON.stringify(e.data, null, 2)}
                    </pre>
                  )}
                </div>
              ))
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
