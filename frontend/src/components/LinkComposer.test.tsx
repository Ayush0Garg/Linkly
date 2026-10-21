import { act, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../api/client';
import { links } from '../api/endpoints';
import { renderWithProviders } from '../test/render';
import { LinkComposer, validate } from './LinkComposer';

vi.mock('../api/endpoints', () => ({ links: { create: vi.fn() } }));
const create = vi.mocked(links.create);

const made = {
  id: 7, code: 'linkly-AbCdEf', shortUrl: 'http://localhost:8080/linkly-AbCdEf', longUrl: 'https://example.com/x',
  custom: false, active: true, expiresAt: null, createdAt: '2026-10-14T10:00:00Z', totalClicks: 0,
};

beforeEach(() => create.mockReset());

describe('validate', () => {
  const now = new Date('2026-10-14T12:00:00');
  it('accepts a normal submission', () => {
    expect(validate('https://example.com/a', '', '', now)).toEqual({});
    expect(validate('  http://example.com  ', 'my-launch', '2026-10-15T09:00', now)).toEqual({});
  });
  it('requires an http(s) address of a sane length', () => {
    expect(validate('', '', '', now).longUrl).toBeDefined();
    expect(validate('example.com', '', '', now).longUrl).toMatch(/http/);
    expect(validate('javascript:alert(1)', '', '', now).longUrl).toBeDefined();
    expect(validate(`https://e.com/${'a'.repeat(8200)}`, '', '', now).longUrl).toMatch(/8192/);
  });
  it('checks the alias shape and reserves the generated prefix, in any case', () => {
    expect(validate('https://e.com', 'ab', '', now).alias).toBeDefined();
    expect(validate('https://e.com', 'has space', '', now).alias).toBeDefined();
    expect(validate('https://e.com', 'x'.repeat(33), '', now).alias).toBeDefined();
    expect(validate('https://e.com', 'linkly-abc', '', now).alias).toMatch(/reserved/);
    expect(validate('https://e.com', 'LINKLY-abc', '', now).alias).toMatch(/reserved/);
    expect(validate('https://e.com', 'linkly', '', now).alias).toBeUndefined();
  });
  it('wants an expiry in the future', () => {
    expect(validate('https://e.com', '', '2026-10-14T11:00', now).expiresAt).toBeDefined();
    expect(validate('https://e.com', '', '2026-10-14T12:00', now).expiresAt).toBeDefined();
    expect(validate('https://e.com', '', '2026-10-14T12:01', now).expiresAt).toBeUndefined();
  });
});

describe('LinkComposer', () => {
  it('blocks an empty submission with a message beside the field, and does not call the server', async () => {
    renderWithProviders(<LinkComposer />);
    await userEvent.click(screen.getByRole('button', { name: 'Shorten' }));
    expect(screen.getByText('Paste the address you want to shorten.')).toBeInTheDocument();
    expect(screen.getByLabelText('Destination')).toHaveAttribute('aria-invalid', 'true');
    expect(create).not.toHaveBeenCalled();
  });

  it('sends trimmed values, then shows the new link with a copy action and clears the form', async () => {
    create.mockResolvedValueOnce(made);
    renderWithProviders(<LinkComposer />);
    await userEvent.type(screen.getByLabelText('Destination'), '  https://example.com/x  ');
    await userEvent.type(screen.getByLabelText(/Alias/), ' my-launch ');
    await userEvent.click(screen.getByRole('button', { name: 'Shorten' }));

    await waitFor(() => expect(create).toHaveBeenCalledTimes(1));
    expect(create.mock.calls[0]![0]).toEqual({ longUrl: 'https://example.com/x', alias: 'my-launch', expiresAt: undefined });
    const banner = (await screen.findByText('Created')).closest('.created')!;
    expect(banner).toHaveTextContent('linkly-AbCdEf');
    expect(screen.getByRole('link', { name: /Stats and QR code/ })).toHaveAttribute('href', '/links/7');
    expect(screen.getByLabelText('Destination')).toHaveValue('');
  });

  it('puts the server\'s per-field messages next to the right inputs', async () => {
    create.mockRejectedValueOnce(new ApiError(400, 'Please correct the highlighted fields.', { alias: 'Size must be between 3 and 32.' }));
    renderWithProviders(<LinkComposer />);
    await userEvent.type(screen.getByLabelText('Destination'), 'https://example.com');
    await userEvent.click(screen.getByRole('button', { name: 'Shorten' }));
    expect(await screen.findByText('Size must be between 3 and 32.')).toBeInTheDocument();
    expect(screen.getByLabelText(/Alias/)).toHaveAttribute('aria-invalid', 'true');
  });

  it('shows refusals that belong to no field (taken alias, blocked address) as an alert above the form', async () => {
    create.mockRejectedValueOnce(new ApiError(409, 'That alias is already taken'));
    renderWithProviders(<LinkComposer />);
    await userEvent.type(screen.getByLabelText('Destination'), 'https://example.com');
    await userEvent.click(screen.getByRole('button', { name: 'Shorten' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('That alias is already taken');
    expect(screen.getByLabelText('Destination')).toHaveValue('https://example.com'); // nothing the user typed is lost
  });

  it('on 429 explains and counts down instead of inviting retries, keeping what was typed', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    try {
      create.mockRejectedValueOnce(new ApiError(429, 'Too many requests. Try again in 20 seconds.', {}, 20));
      renderWithProviders(<LinkComposer />);
      await userEvent.type(screen.getByLabelText('Destination'), 'https://example.com');
      await userEvent.click(screen.getByRole('button', { name: 'Shorten' }));

      expect(await screen.findByRole('alert')).toHaveTextContent('Too many requests. Try again in 20 seconds.');
      expect(screen.getByRole('button', { name: /Wait \d+s/ })).toBeDisabled();
      expect(screen.getByLabelText('Destination')).toHaveValue('https://example.com');

      act(() => { vi.advanceTimersByTime(21_000); });
      expect(await screen.findByRole('button', { name: 'Shorten' })).toBeEnabled();
    } finally {
      vi.useRealTimers();
    }
  });

  it('says so when the server cannot be reached', async () => {
    create.mockRejectedValueOnce(new TypeError('Failed to fetch'));
    renderWithProviders(<LinkComposer />);
    await userEvent.type(screen.getByLabelText('Destination'), 'https://example.com');
    await userEvent.click(screen.getByRole('button', { name: 'Shorten' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(/Could not reach the server/);
  });
});
