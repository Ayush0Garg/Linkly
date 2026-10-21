import type { ReactNode } from 'react';
import { formatDay, formatExact } from '../lib/format';
import type { DailyPoint, Totals } from '../api/types';

/** The big number and the small ledger beside it: the one hero per view, with the figures that explain it. */
export function Hero({ days, totals, timeseries }: { days: number; totals: Totals; timeseries: DailyPoint[] }) {
  const best = timeseries.reduce<DailyPoint | undefined>((b, p) => (!b || p.clicks > b.clicks ? p : b), undefined);
  return (
    <div className="hero">
      <div>
        <p className="label">Clicks · last {days} days</p>
        <p className="hero__figure" aria-label={`${formatExact(totals.clicks)} clicks`}>{formatExact(totals.clicks)}</p>
      </div>
      <dl className="facts">
        <Fact name="Unique visitors" sub="counted per day">{formatExact(totals.uniqueVisitors)}</Fact>
        <Fact name="Best day" sub={best && best.clicks > 0 ? formatDay(best.date) : 'no clicks yet'}>
          {formatExact(best?.clicks ?? 0)}
        </Fact>
        <Fact name="Bot clicks" sub="kept out of every number here">{formatExact(totals.botClicks)}</Fact>
      </dl>
    </div>
  );
}

function Fact({ name, sub, children }: { name: string; sub: string; children: ReactNode }) {
  return (
    <div className="facts__row">
      <dt className="facts__name">
        {name}
        <span className="facts__sub">{sub}</span>
      </dt>
      <dd className="facts__value num">{children}</dd>
    </div>
  );
}
