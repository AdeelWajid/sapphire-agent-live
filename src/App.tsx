import React, { useEffect, useRef, useState } from "react";
import {
  AlertCircle,
  Mic,
  MicOff,
  Phone,
  PhoneOff,
  Search,
  Settings,
  Sparkles,
  X,
} from "lucide-react";
import { ConnectionStatus, VoiceSettings } from "./types";
import ProductPickerModal, {
  PickerProduct,
  SelectedProductLine,
} from "./components/ProductPickerModal";
import AgentModel from "./components/AgentModel";
import { playCallingRingtone } from "./lib/callingRingtone";

type ComplaintRow = {
  complaint_number: string;
  phone: string;
  customer_name: string;
  address?: string;
  order_number?: string | null;
  invoice_number?: string | null;
  purchase_channel?: string | null;
  purchase_date?: string | null;
  received_date?: string | null;
  complaint_type: string;
  description: string;
  product_name?: string | null;
  status: string;
  created_at: string;
};

function formatDuration(totalSeconds: number) {
  const m = Math.floor(totalSeconds / 60);
  const s = totalSeconds % 60;
  return `${String(m).padStart(2, "0")}:${String(s).padStart(2, "0")}`;
}

export default function App() {
  const [voiceSettings, setVoiceSettings] = useState<VoiceSettings>(() => {
    try {
      const saved = localStorage.getItem("sapphire_bot_voice_settings");
      if (saved) return JSON.parse(saved);
    } catch {
      // ignore
    }
    return { voiceName: "Charon", extraRules: "" };
  });
  const [languageMode] = useState<"urdu">("urdu");
  const [isSettingsOpen, setIsSettingsOpen] = useState(false);
  const [status, setStatus] = useState<ConnectionStatus>("disconnected");
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [callDurationSeconds, setCallDurationSeconds] = useState(0);
  const [isRecording, setIsRecording] = useState(false);
  const [isMuted, setIsMuted] = useState(false);
  const [isAiSpeaking, setIsAiSpeaking] = useState(false);
  const [micMode, setMicMode] = useState<"open" | "ptt">(() => {
    return localStorage.getItem("sapphire_bot_mic_mode") === "open"
      ? "open"
      : "ptt";
  });

  const [lookupPhone, setLookupPhone] = useState("");
  const [complaints, setComplaints] = useState<ComplaintRow[]>([]);
  const [complaintsLoading, setComplaintsLoading] = useState(false);
  const [complaintsError, setComplaintsError] = useState<string | null>(null);

  const [isProductPickerOpen, setIsProductPickerOpen] = useState(false);
  const [pickerProducts, setPickerProducts] = useState<PickerProduct[]>([]);

  const wsRef = useRef<WebSocket | null>(null);
  const callSessionIdRef = useRef(0);
  const connectingRef = useRef(false);
  const isRecordingRef = useRef(false);
  const audioContextRef = useRef<AudioContext | null>(null);
  const processorRef = useRef<ScriptProcessorNode | null>(null);
  const streamRef = useRef<MediaStream | null>(null);
  const playbackCtxRef = useRef<AudioContext | null>(null);
  const nextPlayTimeRef = useRef(0);
  const activeSourcesRef = useRef<AudioBufferSourceNode[]>([]);
  const speakingTimerRef = useRef<number | null>(null);
  const ringStopRef = useRef<(() => void) | null>(null);
  const toolPendingRef = useRef(false);
  const farewellHangupTimerRef = useRef<number | null>(null);
  const farewellTriggeredRef = useRef(false);
  const farewellPhaseRef = useRef<
    "idle" | "wait_silence" | "wait_reply_start" | "wait_reply_end"
  >("idle");
  const recentUserSpeechRef = useRef("");
  const recentModelSpeechRef = useRef("");
  const awaitingEndConfirmRef = useRef(false);
  const endCallRef = useRef<() => void>(() => {});

  useEffect(() => {
    localStorage.setItem(
      "sapphire_bot_voice_settings",
      JSON.stringify(voiceSettings)
    );
  }, [voiceSettings]);

  useEffect(() => {
    localStorage.setItem("sapphire_bot_mic_mode", micMode);
  }, [micMode]);

  useEffect(() => {
    if (status !== "connected") {
      setCallDurationSeconds(0);
      return;
    }
    const t = window.setInterval(
      () => setCallDurationSeconds((s) => s + 1),
      1000
    );
    return () => clearInterval(t);
  }, [status]);

  useEffect(() => {
    return () => {
      // Prevent orphaned Live sessions after HMR / remount (sounds like multi-agent).
      connectingRef.current = false;
      ringStopRef.current?.();
      ringStopRef.current = null;
      try {
        activeSourcesRef.current.forEach((s) => {
          try {
            s.stop();
          } catch {
            // ignore
          }
        });
      } catch {
        // ignore
      }
      activeSourcesRef.current = [];
      try {
        wsRef.current?.close();
      } catch {
        // ignore
      }
      wsRef.current = null;
      streamRef.current?.getTracks().forEach((t) => t.stop());
    };
  }, []);

  const stopAudioPlayback = () => {
    activeSourcesRef.current.forEach((source) => {
      try {
        source.stop();
      } catch {
        // ignore
      }
    });
    activeSourcesRef.current = [];
    nextPlayTimeRef.current = 0;
    setIsAiSpeaking(false);
    if (speakingTimerRef.current) {
      window.clearTimeout(speakingTimerRef.current);
      speakingTimerRef.current = null;
    }
  };

  const clearFarewellWatch = () => {
    if (farewellHangupTimerRef.current != null) {
      window.clearInterval(farewellHangupTimerRef.current);
      farewellHangupTimerRef.current = null;
    }
    farewellTriggeredRef.current = false;
    farewellPhaseRef.current = "idle";
    recentUserSpeechRef.current = "";
  };

  const isEndCallOffer = (text: string) => {
    const raw = String(text || "");
    if (!raw.trim()) return false;
    if (/کال\s*ختم/.test(raw) || /کال\s*بند/.test(raw)) return true;
    const t = raw
      .toLowerCase()
      .replace(/[^a-z0-9\u0600-\u06ff\s]/gi, " ")
      .replace(/\s+/g, " ")
      .trim();
    return (
      /end (the )?call/.test(t) ||
      /call end/.test(t) ||
      /call band/.test(t) ||
      /khatam kar/.test(t) ||
      /band kar/.test(t) ||
      /hang\s*up/.test(t)
    );
  };

  const isAffirmative = (text: string) => {
    const raw = String(text || "");
    if (!raw.trim()) return false;
    if (/^(جی|ہاں|هان|درست|ٹھیک|کریں|کر دو|بند)\b/.test(raw.trim())) return true;
    const t = raw
      .toLowerCase()
      .replace(/[^a-z0-9\u0600-\u06ff\s]/gi, " ")
      .replace(/\s+/g, " ")
      .trim();
    return (
      /^(yes|yeah|yep|ok|okay|sure|haan|ji|jee|theek|sahi|bilkul)(\b|$)/.test(
        t
      ) ||
      /\b(yes|haan|ji|jee|theek hai|sahi hai|bilkul|end kar do|band kar do|khatam kar do)\b/.test(
        t
      )
    );
  };

  const isFarewellUtterance = (text: string) => {
    const raw = String(text || "");
    if (!raw.trim()) return false;
    if (/اللہ\s*حافظ/.test(raw) || /خدا\s*حافظ/.test(raw)) return true;
    const t = raw
      .toLowerCase()
      .replace(/[^a-z0-9\u0600-\u06ff\s]/gi, " ")
      .replace(/\s+/g, " ")
      .trim();
    if (!t) return false;
    return (
      /allah\s*ha+fi[zs]/.test(t) ||
      /alla+h\s*ha+fi[zs]/.test(t) ||
      /khuda\s*ha+fi[zs]/.test(t) ||
      /allahhafiz/.test(t) ||
      /khudahafiz/.test(t) ||
      /good\s*bye/.test(t) ||
      /goodbye/.test(t) ||
      /bye\s*bye/.test(t) ||
      /(^| )bye( |$)/.test(t) ||
      /end (the )?call/.test(t) ||
      /call end/.test(t) ||
      /call band/.test(t)
    );
  };

  const scheduleHangUpAfterAgentSpeaks = (label = "hangup") => {
    if (farewellTriggeredRef.current) return;
    farewellTriggeredRef.current = true;
    awaitingEndConfirmRef.current = false;
    farewellPhaseRef.current =
      activeSourcesRef.current.length > 0 ? "wait_reply_end" : "wait_reply_start";

    if (farewellHangupTimerRef.current != null) {
      window.clearInterval(farewellHangupTimerRef.current);
    }

    const startedAt = Date.now();
    let silenceStartedAt: number | null = null;
    console.log("Scheduling call hangup:", label);

    farewellHangupTimerRef.current = window.setInterval(() => {
      const speakingNow = activeSourcesRef.current.length > 0;
      const waitedMs = Date.now() - startedAt;
      const phase = farewellPhaseRef.current;

      if (phase === "wait_silence") {
        if (!speakingNow) farewellPhaseRef.current = "wait_reply_start";
        if (waitedMs >= 12000) {
          clearFarewellWatch();
          endCallRef.current();
        }
        return;
      }

      if (phase === "wait_reply_start") {
        silenceStartedAt = null;
        if (speakingNow) {
          farewellPhaseRef.current = "wait_reply_end";
          return;
        }
        // If agent already finished / won't speak more, hang up soon.
        if (waitedMs >= 1800) {
          clearFarewellWatch();
          endCallRef.current();
        }
        return;
      }

      if (phase === "wait_reply_end") {
        if (speakingNow) {
          silenceStartedAt = null;
          return;
        }
        if (silenceStartedAt == null) silenceStartedAt = Date.now();
        if (Date.now() - silenceStartedAt >= 600) {
          clearFarewellWatch();
          endCallRef.current();
        }
      }
    }, 150);
  };

  const maybeHangUpOnFarewell = (chunk: string) => {
    const piece = String(chunk || "");
    if (!piece.trim()) return;

    recentUserSpeechRef.current = `${recentUserSpeechRef.current} ${piece}`
      .replace(/\s+/g, " ")
      .trim()
      .slice(-240);
    if (farewellTriggeredRef.current) return;

    // User said yes after agent asked to end the call.
    if (awaitingEndConfirmRef.current && isAffirmative(piece)) {
      scheduleHangUpAfterAgentSpeaks("user_confirmed_end");
      return;
    }

    const haystack = recentUserSpeechRef.current;
    if (!isFarewellUtterance(piece) && !isFarewellUtterance(haystack)) return;
    scheduleHangUpAfterAgentSpeaks("user_farewell");
  };

  const noteModelSpeech = (chunk: string) => {
    const piece = String(chunk || "");
    if (!piece.trim()) return;
    recentModelSpeechRef.current = `${recentModelSpeechRef.current} ${piece}`
      .replace(/\s+/g, " ")
      .trim()
      .slice(-280);
    if (isEndCallOffer(piece) || isEndCallOffer(recentModelSpeechRef.current)) {
      awaitingEndConfirmRef.current = true;
    }
  };

  const stopMic = () => {
    isRecordingRef.current = false;
    setIsRecording(false);
    try {
      processorRef.current?.disconnect();
    } catch {
      // ignore
    }
    processorRef.current = null;
    streamRef.current?.getTracks().forEach((t) => t.stop());
    streamRef.current = null;
    if (audioContextRef.current) {
      void audioContextRef.current.close();
      audioContextRef.current = null;
    }
  };

  const startMic = async () => {
    if (!wsRef.current || wsRef.current.readyState !== WebSocket.OPEN) return;
    if (isRecordingRef.current) return;

    const stream = await navigator.mediaDevices.getUserMedia({
      audio: {
        echoCancellation: true,
        noiseSuppression: true,
        channelCount: 1,
      },
    });
    const audioContext = new AudioContext({ sampleRate: 16000 });
    const source = audioContext.createMediaStreamSource(stream);
    const processor = audioContext.createScriptProcessor(4096, 1, 1);

    processor.onaudioprocess = (event) => {
      if (!isRecordingRef.current || isMuted || toolPendingRef.current) return;
      if (!wsRef.current || wsRef.current.readyState !== WebSocket.OPEN) return;
      const input = event.inputBuffer.getChannelData(0);
      const pcm = new Int16Array(input.length);
      for (let i = 0; i < input.length; i += 1) {
        const s = Math.max(-1, Math.min(1, input[i]));
        pcm[i] = s < 0 ? s * 0x8000 : s * 0x7fff;
      }
      const bytes = new Uint8Array(pcm.buffer);
      let binary = "";
      for (let i = 0; i < bytes.length; i += 1) {
        binary += String.fromCharCode(bytes[i]);
      }
      wsRef.current.send(JSON.stringify({ audio: btoa(binary) }));
    };

    source.connect(processor);
    // ScriptProcessor only runs when connected into a playing graph.
    // Mute the tap so we don't hear ourselves / create echo.
    const silent = audioContext.createGain();
    silent.gain.value = 0;
    processor.connect(silent);
    silent.connect(audioContext.destination);

    streamRef.current = stream;
    audioContextRef.current = audioContext;
    processorRef.current = processor;
    isRecordingRef.current = true;
    setIsRecording(true);
    if (!toolPendingRef.current) {
      wsRef.current.send(JSON.stringify({ type: "activity_start" }));
    }
  };

  const playPcmBase64 = (base64: string) => {
    if (!playbackCtxRef.current) {
      playbackCtxRef.current = new AudioContext({ sampleRate: 24000 });
    }
    const ctx = playbackCtxRef.current;
    if (ctx.state === "suspended") void ctx.resume();

    const binary = atob(base64);
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i += 1) bytes[i] = binary.charCodeAt(i);
    const view = new DataView(bytes.buffer);
    const samples = new Float32Array(bytes.length / 2);
    for (let i = 0; i < samples.length; i += 1) {
      samples[i] = view.getInt16(i * 2, true) / 0x8000;
    }
    const buffer = ctx.createBuffer(1, samples.length, 24000);
    buffer.copyToChannel(samples, 0);
    const source = ctx.createBufferSource();
    source.buffer = buffer;
    source.connect(ctx.destination);

    const now = ctx.currentTime;
    if (nextPlayTimeRef.current < now) {
      nextPlayTimeRef.current = now + 0.03;
    }
    source.start(nextPlayTimeRef.current);
    nextPlayTimeRef.current += buffer.duration;
    activeSourcesRef.current.push(source);
    setIsAiSpeaking(true);

    source.onended = () => {
      activeSourcesRef.current = activeSourcesRef.current.filter((s) => s !== source);
      if (activeSourcesRef.current.length === 0) setIsAiSpeaking(false);
    };
  };

  const endCall = () => {
    connectingRef.current = false;
    callSessionIdRef.current += 1;
    toolPendingRef.current = false;
    clearFarewellWatch();
    ringStopRef.current?.();
    ringStopRef.current = null;
    stopMic();
    stopAudioPlayback();
    const ws = wsRef.current;
    wsRef.current = null;
    try {
      ws?.close();
    } catch {
      // ignore
    }
    setStatus("disconnected");
  };
  endCallRef.current = endCall;

  const startCall = async () => {
    if (
      connectingRef.current ||
      status === "connecting" ||
      status === "connected"
    ) {
      return;
    }
    connectingRef.current = true;

    // Kill any leftover socket/audio from a previous call or HMR.
    ringStopRef.current?.();
    stopMic();
    stopAudioPlayback();
    try {
      wsRef.current?.close();
    } catch {
      // ignore
    }
    wsRef.current = null;

    const sessionId = ++callSessionIdRef.current;
    clearFarewellWatch();
    toolPendingRef.current = false;
    awaitingEndConfirmRef.current = false;
    recentModelSpeechRef.current = "";
    setErrorMessage(null);
    setStatus("connecting");

    try {
      const ring = playCallingRingtone(1800);
      ringStopRef.current = ring.stop;
      await ring.done;
      ringStopRef.current = null;
    } catch {
      ringStopRef.current = null;
    }

    if (sessionId !== callSessionIdRef.current) {
      connectingRef.current = false;
      return;
    }

    try {
      const protocol = window.location.protocol === "https:" ? "wss" : "ws";
      const voiceQuery = encodeURIComponent(voiceSettings.voiceName);
      const ws = new WebSocket(
        `${protocol}://${window.location.host}/api/live?voice=${voiceQuery}`
      );
      wsRef.current = ws;

      const sendSessionConfig = () => {
        if (ws.readyState !== WebSocket.OPEN) return;
        if (sessionId !== callSessionIdRef.current) return;
        ws.send(
          JSON.stringify({
            type: "session_config",
            voice: voiceSettings.voiceName,
            extraRules: voiceSettings.extraRules || "",
            languageMode,
            source: "Web Agent",
          })
        );
      };

      ws.onopen = () => {
        sendSessionConfig();
      };

      ws.onmessage = (event) => {
        if (sessionId !== callSessionIdRef.current || wsRef.current !== ws) {
          return;
        }
        try {
          const msg = JSON.parse(event.data);
          if (msg.type === "status" && msg.status === "awaiting_config") {
            sendSessionConfig();
            return;
          }
          if (msg.type === "status") {
            if (msg.status === "established") {
              connectingRef.current = false;
              setStatus("connected");
              // Delay open-mic so it doesn't overlap the server greeting turn.
              if (micMode === "open") {
                window.setTimeout(() => {
                  if (
                    sessionId === callSessionIdRef.current &&
                    wsRef.current === ws
                  ) {
                    void startMic();
                  }
                }, 2800);
              }
            }
            if (msg.status === "closed") endCall();
          } else if (msg.type === "tool_pending") {
            toolPendingRef.current = !!msg.pending;
            // While a tool runs, end the open activity turn so Live doesn't
            // get realtime input during function calling.
            if (msg.pending && isRecordingRef.current) {
              try {
                ws.send(JSON.stringify({ type: "activity_end" }));
              } catch {
                // ignore
              }
            }
          } else if (msg.type === "audio" && msg.data) {
            playPcmBase64(msg.data);
          } else if (msg.type === "model-text") {
            noteModelSpeech(msg.text);
          } else if (msg.type === "user-text") {
            maybeHangUpOnFarewell(msg.text);
          } else if (msg.type === "show_products") {
            setPickerProducts(Array.isArray(msg.products) ? msg.products : []);
            setIsProductPickerOpen(true);
          } else if (msg.type === "end_call") {
            scheduleHangUpAfterAgentSpeaks("end_call_tool");
          } else if (msg.type === "farewell_hangup") {
            // Do NOT drop immediately — wait until agent speaks farewell / asks to end.
            maybeHangUpOnFarewell(msg.text || "goodbye");
          } else if (msg.type === "error") {
            setErrorMessage(msg.message || "Connection error");
            setStatus("error");
            endCall();
          } else if (msg.type === "interrupted") {
            stopAudioPlayback();
          }
        } catch (err) {
          console.error(err);
        }
      };

      ws.onerror = () => {
        if (sessionId !== callSessionIdRef.current) return;
        setErrorMessage("WebSocket connection failed");
        setStatus("error");
        connectingRef.current = false;
      };

      ws.onclose = () => {
        if (wsRef.current === ws) {
          wsRef.current = null;
        }
        if (sessionId !== callSessionIdRef.current) return;
        stopMic();
        stopAudioPlayback();
        connectingRef.current = false;
        setStatus((prev) => (prev === "error" ? prev : "disconnected"));
      };
    } catch (err: any) {
      connectingRef.current = false;
      setErrorMessage(err?.message || "Failed to start call");
      setStatus("error");
    }
  };

  const loadComplaints = async () => {
    const phone = lookupPhone.trim();
    if (!phone) {
      setComplaintsError("Enter a phone number");
      return;
    }
    setComplaintsLoading(true);
    setComplaintsError(null);
    try {
      const res = await fetch(
        `/api/complaints?phone=${encodeURIComponent(phone)}`
      );
      const json = await res.json();
      if (!res.ok || !json?.ok) {
        throw new Error(json?.error || "Failed to load complaints");
      }
      setComplaints(Array.isArray(json.complaints) ? json.complaints : []);
    } catch (err: any) {
      setComplaints([]);
      setComplaintsError(err?.message || "Failed to load complaints");
    } finally {
      setComplaintsLoading(false);
    }
  };

  const onPickerSubmit = (items: SelectedProductLine[]) => {
    setIsProductPickerOpen(false);
    if (!items.length || !wsRef.current) return;
    const summary = items
      .map((i) => `${i.name} (${i.sku_code})`)
      .join(", ");
    wsRef.current.send(
      JSON.stringify({
        type: "user_text",
        text: `I selected these clothing products on screen: ${summary}. Help me register a complaint about one of them if needed.`,
      })
    );
  };

  const pttDown = async () => {
    if (status !== "connected" || micMode !== "ptt") return;
    if (toolPendingRef.current) return;
    await startMic();
  };

  const pttUp = () => {
    if (status !== "connected" || micMode !== "ptt") return;
    if (isRecordingRef.current) {
      if (!toolPendingRef.current) {
        wsRef.current?.send(JSON.stringify({ type: "activity_end" }));
      }
      stopMic();
    }
  };

  return (
    <div className="min-h-screen bg-[radial-gradient(circle_at_top,_#e8f0ff,_#f7f9fc_45%,_#eef3fb)] text-[#0B2C6E]">
      <div className="mx-auto flex min-h-screen max-w-6xl flex-col gap-6 px-4 py-6 lg:flex-row lg:px-8">
        <section className="flex flex-1 flex-col rounded-[28px] border border-[#0B2C6E]/10 bg-white/80 p-6 shadow-[0_20px_60px_rgba(11,44,110,0.08)] backdrop-blur">
          <header className="flex items-start justify-between gap-4">
            <div className="flex items-center gap-3">
              <div className="flex h-11 w-11 items-center justify-center rounded-2xl bg-[#0B2C6E] text-white shadow-lg">
                <Sparkles className="h-5 w-5" />
              </div>
              <div>
                <h1 className="text-2xl font-bold tracking-tight">Plano Agent</h1>
                <p className="text-sm text-[#0B2C6E]/60">
                  Urdu clothing complaints assistant
                </p>
              </div>
            </div>
            <button
              type="button"
              onClick={() => setIsSettingsOpen(true)}
              className="rounded-xl border border-[#0B2C6E]/12 p-2 text-[#0B2C6E]/70 hover:bg-[#E8F0FF]"
              title="Settings"
            >
              <Settings className="h-4 w-4" />
            </button>
          </header>

          <div className="mt-8 flex flex-1 flex-col items-center justify-center text-center">
            <AgentModel speaking={isAiSpeaking} listening={isRecording} />
            <p className="mt-5 text-sm font-semibold uppercase tracking-[0.18em] text-[#0B2C6E]/45">
              {status === "connected"
                ? formatDuration(callDurationSeconds)
                : status === "connecting"
                  ? "Calling…"
                  : status === "error"
                    ? "Unavailable"
                    : "Ready"}
            </p>
            <p className="mt-2 max-w-md text-sm text-[#0B2C6E]/65">
              Register clothing complaints or check existing ones by phone number.
            </p>

            {errorMessage && (
              <div className="mt-4 flex max-w-md items-start gap-2 rounded-2xl border border-rose-200 bg-rose-50 px-3 py-2 text-left text-xs text-rose-700">
                <AlertCircle className="mt-0.5 h-4 w-4 shrink-0" />
                <span>{errorMessage}</span>
              </div>
            )}

            <div className="mt-8 flex flex-wrap items-center justify-center gap-3">
              {status !== "connected" ? (
                <button
                  type="button"
                  onClick={() => void startCall()}
                  disabled={status === "connecting"}
                  className="inline-flex items-center gap-2 rounded-full bg-[#0B2C6E] px-6 py-3 text-sm font-semibold text-white shadow-lg transition hover:bg-[#0B2C6E]/90 disabled:opacity-60"
                >
                  <Phone className="h-4 w-4" />
                  {status === "connecting" ? "Connecting…" : "Call Plano Agent"}
                </button>
              ) : (
                <>
                  {micMode === "ptt" ? (
                    <button
                      type="button"
                      onMouseDown={() => void pttDown()}
                      onMouseUp={pttUp}
                      onMouseLeave={pttUp}
                      onTouchStart={(e) => {
                        e.preventDefault();
                        void pttDown();
                      }}
                      onTouchEnd={(e) => {
                        e.preventDefault();
                        pttUp();
                      }}
                      className={`inline-flex items-center gap-2 rounded-full px-6 py-3 text-sm font-semibold text-white shadow-lg ${
                        isRecording ? "bg-emerald-600" : "bg-[#0B2C6E]"
                      }`}
                    >
                      {isRecording ? (
                        <Mic className="h-4 w-4" />
                      ) : (
                        <MicOff className="h-4 w-4" />
                      )}
                      Hold to talk
                    </button>
                  ) : (
                    <button
                      type="button"
                      onClick={() => setIsMuted((m) => !m)}
                      className="inline-flex items-center gap-2 rounded-full border border-[#0B2C6E]/15 bg-white px-5 py-3 text-sm font-semibold"
                    >
                      {isMuted ? <MicOff className="h-4 w-4" /> : <Mic className="h-4 w-4" />}
                      {isMuted ? "Unmute" : "Mute"}
                    </button>
                  )}
                  <button
                    type="button"
                    onClick={endCall}
                    className="inline-flex items-center gap-2 rounded-full bg-rose-600 px-6 py-3 text-sm font-semibold text-white shadow-lg"
                  >
                    <PhoneOff className="h-4 w-4" />
                    End call
                  </button>
                </>
              )}
            </div>

            <div className="mt-6 flex flex-wrap items-center justify-center gap-2">
              <span className="rounded-full bg-[#0B2C6E] px-3 py-1.5 text-xs font-semibold text-white">
                Urdu only
              </span>
            </div>
          </div>
        </section>

        <aside className="w-full space-y-4 lg:w-[360px]">
          <div className="rounded-[28px] border border-[#0B2C6E]/10 bg-white/80 p-5 shadow-[0_20px_60px_rgba(11,44,110,0.06)]">
            <h2 className="text-lg font-bold">Find complaints</h2>
            <p className="mt-1 text-xs text-[#0B2C6E]/55">
              Enter the customer phone number to read complaints from the JSON file.
            </p>
            <div className="mt-4 flex gap-2">
              <input
                value={lookupPhone}
                onChange={(e) => setLookupPhone(e.target.value)}
                placeholder="03XXXXXXXXX"
                className="min-w-0 flex-1 rounded-xl border border-[#0B2C6E]/15 bg-white px-3 py-2.5 text-sm outline-none focus:border-[#0B2C6E]/40"
              />
              <button
                type="button"
                onClick={() => void loadComplaints()}
                className="inline-flex items-center gap-1 rounded-xl bg-[#0B2C6E] px-3 py-2.5 text-sm font-semibold text-white"
              >
                <Search className="h-4 w-4" />
                Search
              </button>
            </div>
            {complaintsError && (
              <p className="mt-3 text-xs font-medium text-rose-600">{complaintsError}</p>
            )}
            <div className="mt-4 max-h-[420px] space-y-3 overflow-y-auto">
              {complaintsLoading ? (
                <p className="text-xs text-[#0B2C6E]/45">Loading…</p>
              ) : complaints.length === 0 ? (
                <p className="text-xs text-[#0B2C6E]/45">
                  No complaints loaded yet.
                </p>
              ) : (
                complaints.map((c) => (
                  <div
                    key={c.complaint_number}
                    className="rounded-2xl border border-[#0B2C6E]/10 bg-[#F7F9FC] p-3"
                  >
                    <div className="flex items-center justify-between gap-2">
                      <span className="text-sm font-bold">{c.complaint_number}</span>
                      <span className="rounded-full bg-white px-2 py-0.5 text-[10px] font-semibold uppercase tracking-wide text-[#0B2C6E]/70">
                        {c.status}
                      </span>
                    </div>
                    <p className="mt-1 text-xs font-semibold text-[#0B2C6E]/80">
                      {c.complaint_type}
                      {c.product_name ? ` · ${c.product_name}` : ""}
                    </p>
                    <p className="mt-1 text-xs text-[#0B2C6E]/65">{c.description}</p>
                    {c.address && (
                      <p className="mt-1 text-[11px] text-[#0B2C6E]/55">
                        Address: {c.address}
                      </p>
                    )}
                    {(c.order_number || c.invoice_number) && (
                      <p className="mt-1 text-[11px] text-[#0B2C6E]/55">
                        {c.order_number ? `Order: ${c.order_number}` : ""}
                        {c.order_number && c.invoice_number ? " · " : ""}
                        {c.invoice_number ? `Invoice: ${c.invoice_number}` : ""}
                      </p>
                    )}
                    {!c.order_number && !c.invoice_number && c.purchase_channel && (
                      <p className="mt-1 text-[11px] text-[#0B2C6E]/55">
                        Bought: {c.purchase_channel}
                        {c.purchase_date ? ` · Purchased: ${c.purchase_date}` : ""}
                        {c.received_date ? ` · Received: ${c.received_date}` : ""}
                      </p>
                    )}
                    <p className="mt-2 text-[10px] text-[#0B2C6E]/45">
                      {c.customer_name} · {c.phone}
                    </p>
                  </div>
                ))
              )}
            </div>
          </div>
        </aside>
      </div>

      <ProductPickerModal
        open={isProductPickerOpen}
        products={pickerProducts}
        onClose={() => setIsProductPickerOpen(false)}
        onSubmit={onPickerSubmit}
      />

      {isSettingsOpen && (
        <div className="fixed inset-0 z-50 flex justify-end bg-[#0B2C6E]/25 backdrop-blur-sm">
          <div className="h-full w-full max-w-md bg-white p-6 shadow-2xl">
            <div className="flex items-center justify-between">
              <h2 className="text-lg font-bold">Voice settings</h2>
              <button
                type="button"
                onClick={() => setIsSettingsOpen(false)}
                className="rounded-xl border border-[#0B2C6E]/12 p-2"
              >
                <X className="h-4 w-4" />
              </button>
            </div>
            <div className="mt-6 space-y-5">
              <div>
                <label className="text-xs font-bold uppercase tracking-wide text-[#0B2C6E]/50">
                  Voice
                </label>
                <div className="mt-2 flex flex-wrap gap-2">
                  {(["Zephyr", "Puck", "Charon", "Kore", "Fenrir"] as const).map(
                    (v) => (
                      <button
                        key={v}
                        type="button"
                        onClick={() =>
                          setVoiceSettings((prev) => ({ ...prev, voiceName: v }))
                        }
                        className={`rounded-full px-3 py-1.5 text-xs font-semibold ${
                          voiceSettings.voiceName === v
                            ? "bg-[#0B2C6E] text-white"
                            : "bg-[#E8F0FF] text-[#0B2C6E]/70"
                        }`}
                      >
                        {v}
                      </button>
                    )
                  )}
                </div>
              </div>
              <div>
                <label className="text-xs font-bold uppercase tracking-wide text-[#0B2C6E]/50">
                  Mic mode
                </label>
                <div className="mt-2 flex gap-2">
                  <button
                    type="button"
                    onClick={() => setMicMode("ptt")}
                    className={`rounded-full px-3 py-1.5 text-xs font-semibold ${
                      micMode === "ptt"
                        ? "bg-[#0B2C6E] text-white"
                        : "bg-[#E8F0FF] text-[#0B2C6E]/70"
                    }`}
                  >
                    Push to talk
                  </button>
                  <button
                    type="button"
                    onClick={() => setMicMode("open")}
                    className={`rounded-full px-3 py-1.5 text-xs font-semibold ${
                      micMode === "open"
                        ? "bg-[#0B2C6E] text-white"
                        : "bg-[#E8F0FF] text-[#0B2C6E]/70"
                    }`}
                  >
                    Open mic
                  </button>
                </div>
              </div>
              <div>
                <label className="text-xs font-bold uppercase tracking-wide text-[#0B2C6E]/50">
                  Extra rules
                </label>
                <textarea
                  value={voiceSettings.extraRules}
                  onChange={(e) =>
                    setVoiceSettings((prev) => ({
                      ...prev,
                      extraRules: e.target.value,
                    }))
                  }
                  rows={6}
                  className="mt-2 w-full rounded-2xl border border-[#0B2C6E]/15 p-3 text-sm outline-none focus:border-[#0B2C6E]/40"
                  placeholder="Optional operator rules…"
                />
              </div>
              <button
                type="button"
                onClick={() => setIsSettingsOpen(false)}
                className="w-full rounded-2xl bg-[#0B2C6E] py-3 text-sm font-semibold text-white"
              >
                Done
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
