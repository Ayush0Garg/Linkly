import { describe, expect, it } from 'vitest';
import {
  countryName, formatCount, formatDate, formatDay, formatExact, formatPercent, hostOf, linkStatus, localInputToIso, splitCode,
} from './format';

describe('numbers', () => {
  it('shows exact figures below ten thousand and compacts above', () => {
    expect(formatCount(0)).toBe('0');
    expect(formatCount(1284)).toBe('1,284');
    expect(formatCount(9999)).toBe('9,999');
    expect(formatCount(12_400)).toBe('12.4K');
    expect(formatCount(3_100_000)).toBe('3.1M');
    expect(formatExact(1_234_567)).toBe('1,234,567');
  });

  it('formats shares, never rounding a real share down to 0%', () => {
    expect(formatPercent(1, 4)).toBe('25%');
    expect(formatPercent(1, 1000)).toBe('<1%');
    expect(formatPercent(0, 10)).toBe('0%');
    expect(formatPercent(5, 0)).toBe('0%');
  });
});

describe('dates', () => {
  it('formats a day label from its parts with a fixed month table (September is always "Sep")', () => {
    expect(formatDay('2026-10-14')).toBe('14 Oct');
    expect(formatDay('2026-09-03')).toBe('3 Sep');
    expect(formatDay('2026-01-31')).toBe('31 Jan');
  });

  it('formats a full date in local time', () => {
    expect(formatDate(new Date(2026, 8, 26, 12, 0).toISOString())).toBe('26 Sep 2026');
  });
});

describe('linkStatus', () => {
  const now = new Date('2026-10-14T12:00:00Z');
  it('is deactivated whatever the expiry says', () => {
    expect(linkStatus({ active: false, expiresAt: null }, now)).toBe('deactivated');
    expect(linkStatus({ active: false, expiresAt: '2030-01-01T00:00:00Z' }, now)).toBe('deactivated');
  });
  it('is expired once the expiry has passed (including the exact instant)', () => {
    expect(linkStatus({ active: true, expiresAt: '2026-10-14T11:59:59Z' }, now)).toBe('expired');
    expect(linkStatus({ active: true, expiresAt: '2026-10-14T12:00:00Z' }, now)).toBe('expired');
  });
  it('is active otherwise', () => {
    expect(linkStatus({ active: true, expiresAt: null }, now)).toBe('active');
    expect(linkStatus({ active: true, expiresAt: '2026-10-14T12:00:01Z' }, now)).toBe('active');
  });
});

describe('small helpers', () => {
  it('splits the service prefix off a code', () => {
    expect(splitCode('linkly-KZrNBm')).toEqual({ prefix: 'linkly-', rest: 'KZrNBm' });
    expect(splitCode('show-hn')).toEqual({ prefix: '', rest: 'show-hn' });
  });

  it('extracts a host, and falls back to the input when it is not a URL', () => {
    expect(hostOf('https://developer.mozilla.org/en-US/docs')).toBe('developer.mozilla.org');
    expect(hostOf('not a url')).toBe('not a url');
  });

  it('turns a datetime-local value into an ISO instant, or nothing', () => {
    expect(localInputToIso('')).toBeUndefined();
    expect(localInputToIso('garbage')).toBeUndefined();
    expect(localInputToIso('2026-10-14T18:30')).toBe(new Date('2026-10-14T18:30').toISOString());
  });

  it('names countries and passes everything else through', () => {
    expect(countryName('IN')).toBe('India');
    expect(countryName('US')).toBe('United States');
    expect(countryName('Unknown')).toBe('Unknown');
    expect(countryName('ZZZ')).toBe('ZZZ');
  });
});
