import { describe, expect, it } from 'vitest';
import { labelIndexes, nearestIndex, niceTicks } from './chart';

describe('niceTicks', () => {
  it('gives an empty series a readable 0-4 axis', () => {
    expect(niceTicks(0)).toEqual({ ticks: [0, 1, 2, 3, 4], max: 4 });
    expect(niceTicks(Number.NaN).max).toBe(4);
  });

  it('keeps integer ticks for tiny counts', () => {
    expect(niceTicks(3)).toEqual({ ticks: [0, 1, 2, 3], max: 3 });
  });

  it.each([
    [139, [0, 50, 100, 150]],
    [58, [0, 20, 40, 60]],
    [1038, [0, 500, 1000, 1500]],
    [47, [0, 20, 40, 60]],
  ])('rounds %i to clean steps', (max, ticks) => {
    expect(niceTicks(max).ticks).toEqual(ticks);
  });

  it('always covers the data and starts at zero', () => {
    for (const max of [5, 9, 17, 99, 101, 4_999, 123_456]) {
      const { ticks, max: top } = niceTicks(max);
      expect(ticks[0]).toBe(0);
      expect(top).toBeGreaterThanOrEqual(max);
      expect(ticks.at(-1)).toBe(top);
    }
  });
});

describe('nearestIndex', () => {
  it('snaps to the closest point and clamps at the edges', () => {
    expect(nearestIndex(0, 5, 10, 110)).toBe(0);
    expect(nearestIndex(60, 5, 10, 110)).toBe(2);
    expect(nearestIndex(1000, 5, 10, 110)).toBe(4);
    expect(nearestIndex(50, 1, 10, 110)).toBe(0);
    expect(nearestIndex(Number.NaN, 5, 10, 110)).toBe(4); // never an invalid index
  });
});

describe('labelIndexes', () => {
  it('labels every point when there is room', () => {
    expect(labelIndexes(5, 1000)).toEqual([0, 1, 2, 3, 4]);
    expect(labelIndexes(1, 1000)).toEqual([0]);
    expect(labelIndexes(0, 1000)).toEqual([]);
  });

  it('spaces labels evenly and always includes the latest day', () => {
    const picked = labelIndexes(30, 1000);
    expect(picked.at(-1)).toBe(29);
    const gaps = picked.slice(1).map((v, i) => v - picked[i]!);
    expect(new Set(gaps).size).toBe(1); // every gap identical
  });

  it('never crowds labels: fewer of them on narrow screens', () => {
    expect(labelIndexes(90, 320).length).toBeLessThan(labelIndexes(90, 1200).length);
    expect(labelIndexes(90, 320).length).toBeLessThanOrEqual(Math.floor(320 / 84) + 1);
  });
});
