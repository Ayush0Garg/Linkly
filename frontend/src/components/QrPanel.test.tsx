import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { links } from '../api/endpoints';
import { renderWithProviders } from '../test/render';
import { QrPanel } from './QrPanel';

vi.mock('../api/endpoints', () => ({ links: { qr: vi.fn() } }));
const qr = vi.mocked(links.qr);

const svg = new Blob(['<svg/>'], { type: 'image/svg+xml' });
const png = new Blob(['png-bytes'], { type: 'image/png' });
let created: Blob[];
let revoked: string[];
let downloads: { name: string; href: string }[];

beforeEach(() => {
  qr.mockReset();
  qr.mockImplementation(async (_id, format) => (format === 'svg' ? svg : png));
  created = [];
  revoked = [];
  downloads = [];
  vi.stubGlobal('URL', Object.assign(URL, {
    createObjectURL: vi.fn((blob: Blob) => { created.push(blob); return `blob:test/${created.length}`; }),
    revokeObjectURL: vi.fn((url: string) => { revoked.push(url); }),
  }));
  // capture "click on a temporary download link" without navigating anywhere
  vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function click(this: HTMLAnchorElement) {
    downloads.push({ name: this.download, href: this.href });
  });
});
afterEach(() => vi.restoreAllMocks());

const props = { id: 1000, code: 'linkly-KZrNBm', shortUrl: 'http://localhost:8080/linkly-KZrNBm' };

describe('QrPanel', () => {
  it('asks the server for the SVG and shows it, with an alt text saying where the code leads', async () => {
    renderWithProviders(<QrPanel {...props} />);
    const image = await screen.findByRole('img', { name: 'QR code that opens http://localhost:8080/linkly-KZrNBm' });
    expect(image).toHaveAttribute('src', 'blob:test/1');
    expect(qr).toHaveBeenCalledWith(1000, 'svg', 168);
  });

  it('downloads the SVG it already has, named after the code, without another request', async () => {
    renderWithProviders(<QrPanel {...props} />);
    await screen.findByRole('img');
    await userEvent.click(screen.getByRole('button', { name: 'Download SVG' }));

    expect(downloads).toHaveLength(1);
    expect(downloads[0]!.name).toBe('linkly-KZrNBm.svg');
    expect(qr).toHaveBeenCalledTimes(1);
  });

  it('fetches a large PNG on demand and saves it under the code\'s name', async () => {
    renderWithProviders(<QrPanel {...props} />);
    await screen.findByRole('img');
    await userEvent.click(screen.getByRole('button', { name: 'Download PNG' }));

    await waitFor(() => expect(downloads).toHaveLength(1));
    expect(downloads[0]!.name).toBe('linkly-KZrNBm.png');
    expect(qr).toHaveBeenCalledWith(1000, 'png', 1024);
    expect(created).toContain(png);
    expect(screen.getByRole('button', { name: 'Download PNG' })).toBeEnabled(); // ready for another go
  });

  it('keeps the SVG button disabled until there is something to save', async () => {
    qr.mockImplementation(() => new Promise(() => {})); // never resolves
    renderWithProviders(<QrPanel {...props} />);
    expect(screen.getByRole('button', { name: 'Download SVG' })).toBeDisabled();
    expect(screen.queryByRole('img')).not.toBeInTheDocument();
  });

  it('says so when the code cannot be loaded, and offers no broken image', async () => {
    qr.mockRejectedValue(new Error('boom'));
    renderWithProviders(<QrPanel {...props} />);
    expect(await screen.findByRole('alert')).toHaveTextContent('Could not load the code.');
    expect(screen.queryByRole('img')).not.toBeInTheDocument();
  });

  it('reports a failed PNG download and lets the user retry', async () => {
    renderWithProviders(<QrPanel {...props} />);
    await screen.findByRole('img');
    qr.mockRejectedValueOnce(new Error('network'));
    await userEvent.click(screen.getByRole('button', { name: 'Download PNG' }));
    expect(await screen.findByText('The PNG could not be prepared. Try again.')).toBeInTheDocument();
    expect(downloads).toHaveLength(0);

    await userEvent.click(screen.getByRole('button', { name: 'Download PNG' }));
    await waitFor(() => expect(downloads).toHaveLength(1));
    expect(screen.queryByText('The PNG could not be prepared. Try again.')).not.toBeInTheDocument();
  });

  it('releases the image URL when it goes away', async () => {
    const { unmount } = renderWithProviders(<QrPanel {...props} />);
    await screen.findByRole('img');
    unmount();
    expect(revoked).toContain('blob:test/1');
  });
});
