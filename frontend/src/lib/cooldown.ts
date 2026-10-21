import { useCallback, useEffect, useState } from 'react';

/**
 * A countdown for "too many requests": start(seconds) when the server says to wait, and secondsLeft ticks down to 0.
 * Based on a deadline rather than counting ticks, so a throttled background tab still ends at the right moment.
 */
export function useCooldown(): { secondsLeft: number; start: (seconds: number) => void } {
  const [until, setUntil] = useState(0);
  const [now, setNow] = useState(() => Date.now());

  useEffect(() => {
    if (until <= Date.now()) return;
    setNow(Date.now());
    const timer = window.setInterval(() => {
      const current = Date.now();
      setNow(current);
      if (current >= until) window.clearInterval(timer);
    }, 250);
    return () => window.clearInterval(timer);
  }, [until]);

  const start = useCallback((seconds: number) => {
    setNow(Date.now());
    setUntil(Date.now() + seconds * 1000);
  }, []);

  return { secondsLeft: Math.max(0, Math.ceil((until - now) / 1000)), start };
}
