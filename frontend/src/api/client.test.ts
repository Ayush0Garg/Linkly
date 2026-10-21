import { beforeEach, describe, expect, it, vi } from 'vitest';
import { api, apiBlob, ApiError, authEvents, SIGNED_OUT } from './client';

const json = (body: unknown, status = 200, contentType = 'application/json') =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': contentType } });
const empty = (status: number) => new Response(null, { status });

let fetchMock: ReturnType<typeof vi.fn>;
const callsTo = (path: string) => fetchMock.mock.calls.filter(([p]) => p === path);
const headerOf = (call: unknown[], name: string) => (call[1] as RequestInit).headers as Record<string, string> | undefined && ((call[1] as RequestInit).headers as Record<string, string>)[name];

beforeEach(() => {
  document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/';
  fetchMock = vi.fn();
  vi.stubGlobal('fetch', fetchMock);
});

describe('requests', () => {
  it('GET sends no CSRF header and never asks for a token', async () => {
    fetchMock.mockResolvedValueOnce(json({ ok: true }));
    await expect(api('/api/links')).resolves.toEqual({ ok: true });
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(headerOf(fetchMock.mock.calls[0]!, 'X-XSRF-TOKEN')).toBeUndefined();
  });

  it('non-GET sends the XSRF-TOKEN cookie value as the header, with a JSON body and cookies', async () => {
    document.cookie = 'XSRF-TOKEN=abc-123; path=/';
    fetchMock.mockResolvedValueOnce(json({ id: 1 }, 201));
    await api('/api/links', { method: 'POST', body: { longUrl: 'https://example.com' } });

    const [, init] = fetchMock.mock.calls[0]! as [string, RequestInit];
    expect(init.method).toBe('POST');
    expect(init.credentials).toBe('same-origin');
    expect((init.headers as Record<string, string>)['X-XSRF-TOKEN']).toBe('abc-123');
    expect((init.headers as Record<string, string>)['Content-Type']).toBe('application/json');
    expect(init.body).toBe('{"longUrl":"https://example.com"}');
  });

  it('fetches a CSRF token first when the cookie is missing', async () => {
    fetchMock.mockImplementation(async (path: string) => {
      if (path === '/api/auth/csrf') {
        document.cookie = 'XSRF-TOKEN=fresh-token; path=/';
        return empty(204);
      }
      return json({ ok: true });
    });
    await api('/api/links/1', { method: 'DELETE' });

    expect(fetchMock.mock.calls.map(([p]) => p)).toEqual(['/api/auth/csrf', '/api/links/1']);
    expect(headerOf(fetchMock.mock.calls[1]!, 'X-XSRF-TOKEN')).toBe('fresh-token');
  });

  it('returns undefined for 204 responses', async () => {
    document.cookie = 'XSRF-TOKEN=t; path=/';
    fetchMock.mockResolvedValueOnce(empty(204));
    await expect(api('/api/links/1', { method: 'DELETE' })).resolves.toBeUndefined();
  });
});

describe('session renewal', () => {
  beforeEach(() => {
    document.cookie = 'XSRF-TOKEN=t; path=/';
  });

  it('on 401 renews the session once and retries the original request', async () => {
    fetchMock
      .mockResolvedValueOnce(empty(401))        // original
      .mockResolvedValueOnce(empty(200))        // POST /api/auth/refresh
      .mockResolvedValueOnce(json({ items: [] })); // retry
    await expect(api('/api/links')).resolves.toEqual({ items: [] });
    expect(fetchMock.mock.calls.map(([p]) => p)).toEqual(['/api/links', '/api/auth/refresh', '/api/links']);
  });

  it('shares one refresh between concurrent requests', async () => {
    let refreshes = 0;
    const seen = new Set<string>();
    fetchMock.mockImplementation(async (path: string) => {
      if (path === '/api/auth/refresh') {
        refreshes++;
        await new Promise((r) => setTimeout(r, 10));
        return empty(200);
      }
      // each endpoint answers 401 the first time, 200 afterwards
      if (!seen.has(path)) {
        seen.add(path);
        return empty(401);
      }
      return json({ path });
    });
    const results = await Promise.all([api('/api/links'), api('/api/stats/overview?days=30'), api('/api/auth/me')]);
    expect(results).toHaveLength(3);
    expect(refreshes).toBe(1);
  });

  it('signs out when the refresh is refused', async () => {
    const listener = vi.fn();
    authEvents.addEventListener(SIGNED_OUT, listener);
    fetchMock.mockResolvedValueOnce(empty(401)).mockResolvedValueOnce(empty(401));
    await expect(api('/api/links')).rejects.toMatchObject({ status: 401 });
    expect(listener).toHaveBeenCalledTimes(1);
    expect(callsTo('/api/links')).toHaveLength(1); // not retried
    authEvents.removeEventListener(SIGNED_OUT, listener);
  });

  it('does not try to refresh after a failed sign-in (it would loop)', async () => {
    fetchMock.mockResolvedValueOnce(json({ detail: 'Invalid email or password' }, 401));
    await expect(api('/api/auth/login', { method: 'POST', body: { email: 'a', password: 'b' } }))
      .rejects.toMatchObject({ status: 401, message: 'Invalid email or password' });
    expect(callsTo('/api/auth/refresh')).toHaveLength(0);
  });

  it('retries once with a fresh CSRF token after a 403 on a write', async () => {
    fetchMock
      .mockResolvedValueOnce(empty(403))
      .mockResolvedValueOnce(empty(204)) // GET /api/auth/csrf
      .mockResolvedValueOnce(json({ id: 9 }, 201));
    await expect(api('/api/links', { method: 'POST', body: {} })).resolves.toEqual({ id: 9 });
    expect(fetchMock.mock.calls.map(([p]) => p)).toEqual(['/api/links', '/api/auth/csrf', '/api/links']);
  });

  it('does not loop on a persistent 403', async () => {
    fetchMock.mockImplementation(async (path: string) => (path === '/api/auth/csrf' ? empty(204) : empty(403)));
    await expect(api('/api/links', { method: 'POST', body: {} })).rejects.toMatchObject({ status: 403 });
    expect(callsTo('/api/links')).toHaveLength(2);
  });
});

describe('rate limiting (429)', () => {
  it('reads how long to wait from the Retry-After header', async () => {
    document.cookie = 'XSRF-TOKEN=t; path=/';
    fetchMock.mockResolvedValueOnce(new Response(JSON.stringify({ detail: 'Too many attempts. Try again in 42 seconds.' }),
      { status: 429, headers: { 'Content-Type': 'application/problem+json', 'Retry-After': '42' } }));
    const error = await api('/api/auth/login', { method: 'POST', body: {} }).catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).status).toBe(429);
    expect((error as ApiError).retryAfterSeconds).toBe(42);
    expect((error as ApiError).message).toBe('Too many attempts. Try again in 42 seconds.');
  });

  it('falls back to the body when the header is missing, and rounds up', async () => {
    document.cookie = 'XSRF-TOKEN=t; path=/';
    fetchMock.mockResolvedValueOnce(json({ detail: 'Too many requests.', retryAfterSeconds: 7.2 }, 429, 'application/problem+json'));
    await expect(api('/api/links', { method: 'POST', body: {} })).rejects.toMatchObject({ status: 429, retryAfterSeconds: 8 });
  });

  it('leaves it undefined when the server gave no usable number', async () => {
    fetchMock.mockResolvedValueOnce(new Response('slow down', { status: 429, headers: { 'Retry-After': 'Wed, 21 Oct 2026 07:28:00 GMT' } }));
    const error = (await api('/api/links').catch((e: unknown) => e)) as ApiError;
    expect(error.status).toBe(429);
    expect(error.retryAfterSeconds).toBeUndefined();
  });

  it('is surfaced as it is: no session refresh, no CSRF retry, no second request', async () => {
    document.cookie = 'XSRF-TOKEN=t; path=/';
    fetchMock.mockResolvedValueOnce(new Response(null, { status: 429, headers: { 'Retry-After': '5' } }));
    await expect(api('/api/links', { method: 'POST', body: {} })).rejects.toMatchObject({ status: 429 });
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
});

describe('binary downloads', () => {
  it('returns the response body as a Blob', async () => {
    fetchMock.mockResolvedValueOnce(new Response('PNGDATA', { headers: { 'Content-Type': 'image/png' } }));
    const blob = await apiBlob('/api/links/1/qr?format=png');
    expect(blob.type).toBe('image/png');
    expect(await blob.text()).toBe('PNGDATA');
  });

  it('renews an expired session just like JSON calls do (an <img src> could not)', async () => {
    document.cookie = 'XSRF-TOKEN=t; path=/';
    fetchMock
      .mockResolvedValueOnce(empty(401))
      .mockResolvedValueOnce(empty(200))
      .mockResolvedValueOnce(new Response('SVG', { headers: { 'Content-Type': 'image/svg+xml' } }));
    expect(await (await apiBlob('/api/links/1/qr?format=svg')).text()).toBe('SVG');
    expect(fetchMock.mock.calls.map(([p]) => p)).toEqual(['/api/links/1/qr?format=svg', '/api/auth/refresh', '/api/links/1/qr?format=svg']);
  });

  it('turns an error status into an ApiError, not a corrupt file', async () => {
    fetchMock.mockResolvedValueOnce(json({ detail: 'Link not found' }, 404, 'application/problem+json'));
    await expect(apiBlob('/api/links/9/qr')).rejects.toMatchObject({ status: 404, message: 'Link not found' });
  });
});

describe('errors', () => {
  it('turns problem+json into a message and per-field sentences', async () => {
    fetchMock.mockResolvedValueOnce(json(
      { detail: 'Please correct the highlighted fields.', errors: { email: 'must be a well-formed email address', password: 'Size must be between 10 and 128.' } },
      400, 'application/problem+json'));
    const error = await api('/api/auth/register').catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).message).toBe('Please correct the highlighted fields.');
    expect((error as ApiError).fieldErrors).toEqual({
      email: 'Must be a well-formed email address.',
      password: 'Size must be between 10 and 128.',
    });
  });

  it('falls back to a generic message when the body is not JSON', async () => {
    fetchMock.mockResolvedValueOnce(new Response('<html>Bad gateway</html>', { status: 502 }));
    await expect(api('/api/links')).rejects.toMatchObject({ status: 502, message: 'Request failed (502)' });
  });
});
