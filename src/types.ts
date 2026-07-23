export interface ChatMessage {
  id: string;
  sender: "user" | "model";
  text: string;
  timestamp: string;
}

export interface ChatSession {
  id: string;
  title: string;
  createdAt: string;
  messages: ChatMessage[];
}

export interface VoiceSettings {
  voiceName: "Zephyr" | "Puck" | "Charon" | "Kore" | "Fenrir";
  /** Extra operator rules appended on top of the server order-taker prompt + catalog. */
  extraRules: string;
}

export type ConnectionStatus = "disconnected" | "connecting" | "connected" | "error";
