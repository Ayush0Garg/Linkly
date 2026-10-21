import type { LinkItem } from '../api/types';

const integer = new Intl.NumberFormat('en');
const compact = new Intl.NumberFormat('en', { notation: 'compact', maximumFractionDigits: 1 });

/** 1,284 below ten thousand, then 12.4K / 3.1M, so big numbers stay short without losing precision for small ones. */
export function formatCount(n: number): string {
  return Math.abs(n) < 10_000 ? integer.format(n) : compact.format(n);
}

/** Always the full number (tables, tooltips, accessible names). */
export function formatExact(n: number): string {
  return integer.format(n);
}

export function formatPercent(part: number, whole: number): string {
  if (whole <= 0) return '0%';
  const p = (part / whole) * 100;
  return p > 0 && p < 1 ? '<1%' : `${Math.round(p)}%`;
}

// A fixed table, not Intl: browsers disagree on the short form of September ("Sep" vs "Sept"), and a chart axis
// that mixes "Sept" and "Oct" looks careless.
const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

/** "14 Oct" for a YYYY-MM-DD day label. Built from the parts, so the viewer's time zone cannot shift the day. */
export function formatDay(day: string): string {
  const [, m, d] = day.split('-').map(Number);
  return `${d} ${MONTHS[m! - 1]}`;
}

/** "14 Oct 2026" in the viewer's local time. */
export function formatDate(iso: string): string {
  const date = new Date(iso);
  return `${date.getDate()} ${MONTHS[date.getMonth()]} ${date.getFullYear()}`;
}

export function formatDateTime(iso: string): string {
  const time = new Intl.DateTimeFormat('en-GB', { hour: '2-digit', minute: '2-digit' }).format(new Date(iso));
  return `${formatDate(iso)}, ${time}`;
}

export type LinkStatus = 'active' | 'expired' | 'deactivated';

export function linkStatus(link: Pick<LinkItem, 'active' | 'expiresAt'>, now = new Date()): LinkStatus {
  if (!link.active) return 'deactivated';
  if (link.expiresAt && new Date(link.expiresAt) <= now) return 'expired';
  return 'active';
}

/** Splits "linkly-KZrNBm" so the service prefix can be shown quietly and the unique part prominently. */
export function splitCode(code: string): { prefix: string; rest: string } {
  const prefix = 'linkly-';
  return code.startsWith(prefix) ? { prefix, rest: code.slice(prefix.length) } : { prefix: '', rest: code };
}

export function hostOf(url: string): string {
  try {
    return new URL(url).host;
  } catch {
    return url;
  }
}

/** The value of a datetime-local input is local wall-clock time without a zone; the API wants an instant. */
export function localInputToIso(value: string): string | undefined {
  if (!value) return undefined;
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? undefined : date.toISOString();
}

const regions = (() => {
  try {
    return new Intl.DisplayNames(['en'], { type: 'region' });
  } catch {
    return null;
  }
})();

/** ISO 3166 code -> "India". Anything else ("Unknown") passes through unchanged. */
export function countryName(code: string): string {
  if (!/^[A-Z]{2}$/.test(code)) return code;
  try {
    return regions?.of(code) ?? code;
  } catch {
    return code;
  }
}
