import { useEffect, useRef, useState } from 'react';

interface GoogleIdentity {
  accounts: {
    id: {
      initialize(config: { client_id: string; callback: (response: { credential: string }) => void }): void;
      renderButton(parent: HTMLElement, options: Record<string, unknown>): void;
    };
  };
}

declare global {
  interface Window {
    google?: GoogleIdentity;
  }
}

let scriptPromise: Promise<void> | null = null;

function loadGoogleScript(): Promise<void> {
  scriptPromise ??= new Promise<void>((resolve, reject) => {
    const script = document.createElement('script');
    script.src = 'https://accounts.google.com/gsi/client';
    script.async = true;
    script.onload = () => resolve();
    script.onerror = () => {
      scriptPromise = null; // allow a retry on the next mount
      reject(new Error('Could not load Google sign-in'));
    };
    document.head.appendChild(script);
  });
  return scriptPromise;
}

/** Google's own button; we only receive an ID token, which the backend verifies against Google's keys. */
export function GoogleSignIn({ clientId, onCredential }: { clientId: string; onCredential: (idToken: string) => void }) {
  const slot = useRef<HTMLDivElement>(null);
  const handler = useRef(onCredential);
  handler.current = onCredential;
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let cancelled = false;
    loadGoogleScript()
      .then(() => {
        if (cancelled || !slot.current || !window.google) return;
        window.google.accounts.id.initialize({ client_id: clientId, callback: (r) => handler.current(r.credential) });
        window.google.accounts.id.renderButton(slot.current, {
          theme: 'outline', size: 'large', text: 'continue_with', shape: 'rectangular', width: 360, logo_alignment: 'left',
        });
      })
      .catch(() => !cancelled && setFailed(true));
    return () => {
      cancelled = true;
    };
  }, [clientId]);

  if (failed) return <p className="field__hint">Google sign-in could not be loaded. Use email and password instead.</p>;
  return <div className="google-slot" ref={slot} />;
}
