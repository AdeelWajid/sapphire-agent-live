/** Classic dual-tone phone ring using Web Audio (no asset file needed). */
export function playCallingRingtone(
  durationMs = 2000
): { stop: () => void; done: Promise<void> } {
  const AudioCtx = window.AudioContext || (window as unknown as { webkitAudioContext: typeof AudioContext }).webkitAudioContext;
  const ctx = new AudioCtx();
  const master = ctx.createGain();
  master.gain.value = 0.12;
  master.connect(ctx.destination);

  let stopped = false;
  const oscillators: OscillatorNode[] = [];

  const ringBurst = (startAt: number, length = 0.38) => {
    // Approximate North-American ring cadence tones
    for (const freq of [440, 480]) {
      const osc = ctx.createOscillator();
      const gain = ctx.createGain();
      osc.type = "sine";
      osc.frequency.value = freq;
      gain.gain.setValueAtTime(0.0001, startAt);
      gain.gain.exponentialRampToValueAtTime(0.55, startAt + 0.03);
      gain.gain.setValueAtTime(0.55, startAt + length - 0.05);
      gain.gain.exponentialRampToValueAtTime(0.0001, startAt + length);
      osc.connect(gain);
      gain.connect(master);
      osc.start(startAt);
      osc.stop(startAt + length + 0.02);
      oscillators.push(osc);
    }
  };

  // Two ring bursts within ~2 seconds (ring-ring… pause… ring-ring)
  const now = ctx.currentTime;
  ringBurst(now + 0.05, 0.4);
  ringBurst(now + 0.5, 0.4);
  ringBurst(now + 1.15, 0.4);
  ringBurst(now + 1.6, 0.4);

  const stop = () => {
    if (stopped) return;
    stopped = true;
    try {
      master.gain.cancelScheduledValues(ctx.currentTime);
      master.gain.setValueAtTime(master.gain.value, ctx.currentTime);
      master.gain.exponentialRampToValueAtTime(0.0001, ctx.currentTime + 0.05);
    } catch {
      // ignore
    }
    window.setTimeout(() => {
      oscillators.forEach((osc) => {
        try {
          osc.stop();
        } catch {
          // already stopped
        }
      });
      void ctx.close();
    }, 80);
  };

  const done = new Promise<void>((resolve) => {
    window.setTimeout(() => {
      stop();
      resolve();
    }, durationMs);
  });

  return { stop, done };
}
