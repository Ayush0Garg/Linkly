/**
 * Thin fetch wrapper that hides three cookie-auth chores from the rest of the app:
 *  1. CSRF: every non-GET request carries the XSRF-TOKEN cookie's value in the X-XSRF-TOKEN header.
 *  2. Silent renewal: a 401 triggers one POST /api/auth/refresh (shared by all concurrent requests), then a retry.
 *  3. Errors: problem+json bodies become ApiError with a readable message and per-field messages.
 */

export class ApiError extends Error {
  readonly status: number;
  readonly fieldErrors: Record<string, string>;
  /** For 429 responses: how long until trying again can work. */
  readonly retryAfterSeconds?: number;

  constructor(status: number, message: string, fieldErrors: Record<string, string> = {}, retryAfterSeconds?: number) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.fieldErrors = fieldErrors;
    this.retryAfterSeconds = retryAfterSeconds;
  }
}

/** Fired when the session cannot be renewed, so the UI can drop to the signed-out state. */
export const authEvents = new EventTarget();
export const SIGNED_OUT = 'signed-out';

/** These endpoints handle their own credentials; a 401 from them must not trigger a refresh loop. */
const NO_REFRESH = new Set([
  '/api/auth/login',
  '/api/auth/register',
  '/api/auth/google',
  '/api/auth/refresh',
  '/api/auth/logout',
  '/api/auth/csrf',
]);

function readCookie(name: string): string | null {
  const match = document.cookie.split('; ').find((c) => c.startsWith(`${name}=`));
  return match ? decodeURIComponent(match.slice(name.length + 1)) : null;
}

async function csrfToken(): Promise<string> {
  let token = readCookie('XSRF-TOKEN');
  if (!token) {
    await fetch('/api/auth/csrf', { credentials: 'same-origin' });
    token = readCookie('XSRF-TOKEN');
  }
  return token ?? '';
}

let refreshInFlight: Promise<boolean> | null = null;

function refreshSession(): Promise<boolean> {
  refreshInFlight ??= (async () => {
    try {
      const res = await fetch('/api/auth/refresh', {
        method: 'POST',
        credentials: 'same-origin',
        headers: { 'X-XSRF-TOKEN': await csrfToken() },
      });
      return res.ok;
    } catch {
      return false;
    } finally {
      refreshInFlight = null;
    }
  })();
  return refreshInFlight;
}

interface RequestOptions {
  method?: 'GET' | 'POST' | 'DELETE';
  body?: unknown;
}

async function send(path: string, { method = 'GET', body }: RequestOptions): Promise<Response> {
  const headers: Record<string, string> = { Accept: 'application/json' };
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (method !== 'GET') headers['X-XSRF-TOKEN'] = await csrfToken();
  return fetch(path, {
    method,
    headers,
    credentials: 'same-origin',
    body: body === undefined ? undefined : JSON.stringify(body),
  });
}

function sentence(text: string): string {
  const t = text.trim();
  return t ? `${t.charAt(0).toUpperCase()}${t.slice(1)}${/[.!?]$/.test(t) ? '' : '.'}` : t;
}

async function toError(res: Response): Promise<ApiError> {
  let message = `Request failed (${res.status})`;
  let fieldErrors: Record<string, string> = {};
  let retryAfter = positiveSeconds(res.headers.get('Retry-After'));
  try {
    const problem = (await res.json()) as { detail?: string; errors?: Record<string, string>; retryAfterSeconds?: number };
    retryAfter ??= positiveSeconds(problem.retryAfterSeconds);
    if (problem.detail) message = problem.detail;
    if (problem.errors) {
      // Bean Validation messages are lower-case fragments ("must not be blank"); present them as sentences.
      fieldErrors = Object.fromEntries(
        Object.entries(problem.errors).map(([field, msg]) => [field, sentence(msg)]),
      );
    }
  } catch {
    /* not JSON: keep the generic message */
  }
  return new ApiError(res.status, message, fieldErrors, retryAfter);
}

function positiveSeconds(value: string | number | null | undefined): number | undefined {
  const n = typeof value === 'string' ? Number(value) : value;
  return typeof n === 'number' && Number.isFinite(n) && n > 0 ? Math.ceil(n) : undefined;
}

/** The full request cycle (CSRF, silent renewal, error mapping). Resolves only for a 2xx response. */
async function request(path: string, options: RequestOptions): Promise<Response> {
  let res = await send(path, options);

  if (res.status === 401 && !NO_REFRESH.has(path)) {
    if (await refreshSession()) {
      res = await send(path, options);
    } else {
      authEvents.dispatchEvent(new Event(SIGNED_OUT));
    }
  } else if (res.status === 403 && options.method && options.method !== 'GET') {
    // Most likely a missing or stale CSRF token: fetch a fresh one and try once more.
    await fetch('/api/auth/csrf', { credentials: 'same-origin' });
    res = await send(path, options);
  }

  if (!res.ok) throw await toError(res);
  return res;
}

export async function api<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const res = await request(path, options);
  if (res.status === 204) return undefined as T;
  return (await res.json()) as T;
}

/** For images and files. Goes through the same session handling, which a plain <img src> could not. */
export async function apiBlob(path: string): Promise<Blob> {
  return (await request(path, {})).blob();
}
