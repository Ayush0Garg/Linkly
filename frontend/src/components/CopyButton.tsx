import { useEffect, useRef, useState } from 'react';

async function writeClipboard(text: string): Promise<boolean> {
  try {
    await navigator.clipboard.writeText(text);
    return true;
  } catch {
    // Insecure origins and some embedded browsers block the async API; fall back to a throwaway textarea.
    const area = document.createElement('textarea');
    area.value = text;
    area.setAttribute('readonly', '');
    area.style.position = 'fixed';
    area.style.opacity = '0';
    document.body.appendChild(area);
    area.select();
    const ok = document.execCommand('copy');
    area.remove();
    return ok;
  }
}

export function CopyButton({ text, label = 'Copy' }: { text: string; label?: string }) {
  const [copied, setCopied] = useState(false);
  const timer = useRef<number | undefined>(undefined);
  useEffect(() => () => window.clearTimeout(timer.current), []);

  async function copy() {
    if (await writeClipboard(text)) {
      setCopied(true);
      window.clearTimeout(timer.current);
      timer.current = window.setTimeout(() => setCopied(false), 1600);
    }
  }

  return (
    <>
      <button type="button" className="btn btn--text copy" onClick={copy} aria-label={`${label} ${text}`}>
        {copied ? 'Copied' : label}
      </button>
      <span className="sr-only" role="status">{copied ? 'Copied to clipboard' : ''}</span>
    </>
  );
}
