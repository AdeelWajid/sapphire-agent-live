type Props = {
  speaking?: boolean;
  listening?: boolean;
  size?: "compact" | "hero";
  getAnalyser?: () => AnalyserNode | null;
};

export default function AgentModel({ size = "hero" }: Props) {
  return (
    <div className={`call-avatar call-avatar--${size}`} aria-label="Sapphire Agent" role="img">
      <div className="call-avatar__circle">
        <span className="call-avatar__initials">SB</span>
      </div>
    </div>
  );
}
