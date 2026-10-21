/** Saves a Blob as a file through a temporary link: the usual way to download something fetched with a session. */
export function downloadBlob(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = filename;
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  // Revoke on the next turn so the browser has started the download first.
  setTimeout(() => URL.revokeObjectURL(url), 0);
}
