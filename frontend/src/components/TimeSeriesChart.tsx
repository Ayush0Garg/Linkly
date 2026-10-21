import { useEffect, useMemo, useRef, useState, type KeyboardEvent, type PointerEvent } from 'react';
import type { DailyPoint } from '../api/types';
import { labelIndexes, nearestIndex, niceTicks } from '../lib/chart';
import { formatCount, formatDay, formatExact } from '../lib/format';

const HEIGHT = 264;
const M = { top: 14, right: 14, bottom: 30, left: 42 };

/**
 * One series, drawn by hand in SVG: a 2px line over a ~10% wash, hairline solid grid, a ringed end marker.
 * Pointer and keyboard both drive a snapping crosshair; the tooltip is a convenience only, because every value is
 * also in the table view and announced through the live region.
 */
export function TimeSeriesChart({ points, unit = 'clicks' }: { points: DailyPoint[]; unit?: string }) {
  const box = useRef<HTMLDivElement>(null);
  const svg = useRef<SVGSVGElement>(null);
  const [width, setWidth] = useState(720);
  const [active, setActive] = useState<number | null>(null);

  useEffect(() => {
    const el = box.current;
    if (!el) return;
    if (el.clientWidth) setWidth(Math.max(280, Math.round(el.clientWidth)));
    const observer = new ResizeObserver(([entry]) => {
      if (entry) setWidth(Math.max(280, Math.round(entry.contentRect.width)));
    });
    observer.observe(el);
    return () => observer.disconnect();
  }, []);

  const model = useMemo(() => {
    const left = M.left;
    const right = width - M.right;
    const top = M.top;
    const bottom = HEIGHT - M.bottom;
    const n = points.length;
    const { ticks, max } = niceTicks(Math.max(0, ...points.map((p) => p.clicks)));
    const x = (i: number) => (n <= 1 ? (left + right) / 2 : left + (i * (right - left)) / (n - 1));
    const y = (v: number) => bottom - (v / max) * (bottom - top);
    const line = points.map((p, i) => `${i === 0 ? 'M' : 'L'}${x(i).toFixed(1)},${y(p.clicks).toFixed(1)}`).join(' ');
    const area = n > 1 ? `${line} L${x(n - 1).toFixed(1)},${bottom} L${x(0).toFixed(1)},${bottom} Z` : '';
    return { left, right, top, bottom, n, ticks, x, y, line, area, labels: labelIndexes(n, width) };
  }, [points, width]);

  const total = points.reduce((sum, p) => sum + p.clicks, 0);
  const peak = points.reduce<DailyPoint | undefined>((best, p) => (!best || p.clicks > best.clicks ? p : best), undefined);
  const summary = `${unit} per day over ${points.length} days: ${formatExact(total)} in total` +
    (peak && peak.clicks > 0 ? `, peaking at ${formatExact(peak.clicks)} on ${formatDay(peak.date)}.` : '.');

  function onPointerMove(e: PointerEvent<SVGSVGElement>) {
    const rect = svg.current?.getBoundingClientRect();
    if (!rect || rect.width === 0) return;
    const px = ((e.clientX - rect.left) / rect.width) * width;
    setActive(nearestIndex(px, model.n, model.left, model.right));
  }

  function onKeyDown(e: KeyboardEvent<SVGSVGElement>) {
    const last = model.n - 1;
    const at = active ?? last;
    const next: Record<string, number> = {
      ArrowLeft: Math.max(0, at - 1), ArrowRight: Math.min(last, at + 1), Home: 0, End: last,
    };
    if (e.key === 'Escape') return setActive(null);
    if (e.key in next) {
      e.preventDefault();
      setActive(next[e.key]!);
    }
  }

  const shown = active !== null ? points[active] : undefined;
  const endIndex = model.n - 1;
  const tipLeft = active !== null ? Math.min(Math.max(model.x(active), 70), width - 70) : 0;

  return (
    <div className="chart" ref={box}>
      <svg
        ref={svg}
        viewBox={`0 0 ${width} ${HEIGHT}`}
        role="img"
        aria-label={summary}
        tabIndex={0}
        onPointerMove={onPointerMove}
        onPointerLeave={() => setActive(null)}
        onFocus={() => setActive((a) => a ?? endIndex)}
        onBlur={() => setActive(null)}
        onKeyDown={onKeyDown}
      >
        {model.ticks.map((t) => {
          const yy = model.y(t);
          return (
            <g key={t}>
              <line className={t === 0 ? 'chart__baseline' : 'chart__grid'} x1={model.left} x2={model.right} y1={yy} y2={yy} />
              <text className="chart__tick" x={model.left - 8} y={yy + 4} textAnchor="end">{formatCount(t)}</text>
            </g>
          );
        })}
        {model.labels.map((i, k) => (
          <text
            key={i}
            className="chart__tick"
            x={model.x(i)}
            y={HEIGHT - 8}
            textAnchor={k === 0 && model.n > 1 ? 'start' : k === model.labels.length - 1 && model.n > 1 ? 'end' : 'middle'}
          >
            {formatDay(points[i]!.date)}
          </text>
        ))}
        {model.area && <path className="chart__area" d={model.area} />}
        {model.n > 1 && <path className="chart__line" d={model.line} />}
        {active !== null && (
          <line className="chart__cursor" x1={model.x(active)} x2={model.x(active)} y1={model.top} y2={model.bottom} />
        )}
        {model.n > 0 && (
          <circle
            className="chart__dot"
            cx={model.x(active ?? endIndex)}
            cy={model.y(points[active ?? endIndex]!.clicks)}
            r={5}
          />
        )}
      </svg>
      {shown && active !== null && (
        <div className="tip" style={{ left: tipLeft, top: model.y(shown.clicks) - 14 }} aria-hidden="true">
          <span className="tip__date">{formatDay(shown.date)}</span>
          <span className="tip__value">
            <i className="tip__key" />
            {formatExact(shown.clicks)} <span className="tip__unit">{unit}</span>
          </span>
        </div>
      )}
      <span className="sr-only" aria-live="polite">
        {shown ? `${formatDay(shown.date)}: ${formatExact(shown.clicks)} ${unit}` : ''}
      </span>
    </div>
  );
}
