import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useCooldown } from './cooldown';

beforeEach(() => {
  vi.useFakeTimers();
  vi.setSystemTime(new Date('2026-10-14T12:00:00Z'));
});
afterEach(() => vi.useRealTimers());

describe('useCooldown', () => {
  it('starts at zero', () => {
    const { result } = renderHook(() => useCooldown());
    expect(result.current.secondsLeft).toBe(0);
  });

  it('counts down to zero and stops', () => {
    const { result } = renderHook(() => useCooldown());
    act(() => result.current.start(5));
    expect(result.current.secondsLeft).toBe(5);

    act(() => { vi.advanceTimersByTime(2_000); });
    expect(result.current.secondsLeft).toBe(3);

    act(() => { vi.advanceTimersByTime(3_000); });
    expect(result.current.secondsLeft).toBe(0);
    act(() => { vi.advanceTimersByTime(10_000); });
    expect(result.current.secondsLeft).toBe(0);
  });

  it('can be started again, and a new wait replaces the old one', () => {
    const { result } = renderHook(() => useCooldown());
    act(() => result.current.start(3));
    act(() => { vi.advanceTimersByTime(3_000); });
    expect(result.current.secondsLeft).toBe(0);

    act(() => result.current.start(10));
    expect(result.current.secondsLeft).toBe(10);
    act(() => result.current.start(2));
    expect(result.current.secondsLeft).toBe(2);
  });

  it('rounds partial seconds up, so the button never re-enables early', () => {
    const { result } = renderHook(() => useCooldown());
    act(() => result.current.start(2));
    act(() => { vi.advanceTimersByTime(1_100); });
    expect(result.current.secondsLeft).toBe(1);
  });
});
