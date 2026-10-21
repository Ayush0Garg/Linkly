import { useSearchParams } from 'react-router-dom';
import { CheckIcon } from './icons';

export const PERIODS = [7, 30, 90] as const;
export type Period = (typeof PERIODS)[number];

/** The period lives in the URL (?days=30): shareable, survives reload, and Back works. */
export function usePeriod(): [Period, (days: Period) => void] {
  const [params, setParams] = useSearchParams();
  const raw = Number(params.get('days'));
  const days = (PERIODS as readonly number[]).includes(raw) ? (raw as Period) : 30;
  const set = (next: Period) =>
    setParams((prev) => {
      const copy = new URLSearchParams(prev);
      copy.set('days', String(next));
      return copy;
    }, { replace: true });
  return [days, set];
}

/** One row above everything it scopes; every chart and figure below follows it. */
export function PeriodFilter({ days, onChange }: { days: Period; onChange: (d: Period) => void }) {
  return (
    <div className="filters" role="group" aria-label="Time period">
      <span className="label">Period</span>
      <div className="seg">
        {PERIODS.map((p) => (
          <button key={p} type="button" aria-pressed={p === days} onClick={() => onChange(p)}>
            {p === days && <CheckIcon />}
            {p} days
          </button>
        ))}
      </div>
    </div>
  );
}
