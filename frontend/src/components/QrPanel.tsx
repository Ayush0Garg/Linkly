import { useEffect, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { links } from '../api/endpoints';
import { downloadBlob } from '../lib/download';

const SHOWN_PX = 168;
const PNG_PX = 1024; // large enough to print at a few centimetres

interface Props {
  id: number;
  code: string;
  shortUrl: string;
}

/** The link's QR code, drawn by the server (so it encodes exactly the short URL), with PNG and SVG downloads. */
export function QrPanel({ id, code, shortUrl }: Props) {
  // The SVG is what is shown (crisp at any density) and doubles as the SVG download.
  const svg = useQuery({ queryKey: ['qr', id, 'svg'], queryFn: () => links.qr(id, 'svg', SHOWN_PX), staleTime: Infinity });
  const [imageUrl, setImageUrl] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    if (!svg.data) return;
    const url = URL.createObjectURL(svg.data);
    setImageUrl(url);
    return () => URL.revokeObjectURL(url);
  }, [svg.data]);

  async function downloadPng() {
    setBusy(true);
    setFailed(false);
    try {
      downloadBlob(await links.qr(id, 'png', PNG_PX), `${code}.png`);
    } catch {
      setFailed(true);
    } finally {
      setBusy(false);
    }
  }

  return (
    <aside className="qr" aria-label="QR code">
      {/* A fixed box, so the page does not jump when the image arrives. Always dark on white: scanners need it. */}
      <div className="qr__tile" style={{ width: SHOWN_PX, height: SHOWN_PX }}>
        {imageUrl && <img src={imageUrl} width={SHOWN_PX} height={SHOWN_PX} alt={`QR code that opens ${shortUrl}`} />}
        {svg.isError && <p className="qr__error" role="alert">Could not load the code.</p>}
      </div>
      <div className="qr__side">
        <p className="label">QR code</p>
        <div className="qr__actions">
          <button type="button" className="btn btn--text copy" onClick={downloadPng} disabled={busy}>
            {busy ? 'Preparing…' : 'Download PNG'}
          </button>
          <button type="button" className="btn btn--text copy" disabled={!svg.data}
            onClick={() => svg.data && downloadBlob(svg.data, `${code}.svg`)}>
            Download SVG
          </button>
        </div>
        {failed && <p className="field__error" role="alert">The PNG could not be prepared. Try again.</p>}
        <p className="field__hint">SVG prints sharp at any size. Scan a test copy before printing.</p>
      </div>
    </aside>
  );
}
