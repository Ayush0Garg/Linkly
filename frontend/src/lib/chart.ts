/** Geometry helpers for the time-series chart, kept pure so they can be tested without a DOM. */

export interface Ticks {
  ticks: number[];
  max: number;
}

/**
 * Round y-axis ticks: 0 and a few "nice" steps (1, 2, 5 x 10^n). Counts are integers, so the step never drops
 * below 1, and an empty series still gets a readable 0-4 axis instead of a collapsed one.
 */
export function niceTicks(dataMax: number, target = 4): Ticks {
  if (!Number.isFinite(dataMax) || dataMax <= 0) return { ticks: [0, 1, 2, 3, 4], max: 4 };
  if (dataMax <= target) {
    const max = Math.ceil(dataMax);
    return { ticks: Array.from({ length: max + 1 }, (_, i) => i), max };
  }
  const rough = dataMax / target;
  const magnitude = 10 ** Math.floor(Math.log10(rough));
  const residual = rough / magnitude;
  const step = (residual <= 1 ? 1 : residual <= 2 ? 2 : residual <= 5 ? 5 : 10) * magnitude;
  const max = Math.ceil(dataMax / step) * step;
  const ticks: number[] = [];
  for (let v = 0; v <= max + step / 2; v += step) ticks.push(Math.round(v));
  return { ticks, max };
}

/** Index of the point nearest to x, for points spread evenly between left and right. */
export function nearestIndex(x: number, count: number, left: number, right: number): number {
  if (count <= 1) return 0;
  if (!Number.isFinite(x)) return count - 1; // no usable pointer position: fall back to the latest day
  const t = (x - left) / (right - left);
  return Math.min(count - 1, Math.max(0, Math.round(t * (count - 1))));
}

/**
 * Which points get an x-axis label. A whole-number step, counted back from the most recent day, so the labels are
 * evenly spaced (every 3rd day, say) instead of drifting by rounding, and the latest day is always labelled.
 */
export function labelIndexes(count: number, width: number, minGap = 84): number[] {
  if (count <= 0) return [];
  if (count === 1) return [0];
  const slots = Math.max(2, Math.floor(width / minGap));
  if (count <= slots) return Array.from({ length: count }, (_, i) => i);
  const step = Math.ceil((count - 1) / (slots - 1));
  const picked: number[] = [];
  for (let i = count - 1; i >= 0; i -= step) picked.push(i);
  return picked.reverse();
}
