import { Link } from 'react-router-dom';
import { ChartCard } from '../components/ChartCard';
import { BarList } from '../components/BarList';
import { Hero } from '../components/Facts';
import { PeriodFilter, usePeriod } from '../components/PeriodFilter';
import { QueryError } from '../components/QueryState';
import { TimeSeriesChart } from '../components/TimeSeriesChart';
import { formatDay, formatExact, hostOf, formatPercent } from '../lib/format';
import { useOverview } from '../lib/queries';

export function Overview() {
  const [days, setDays] = usePeriod();
  const q = useOverview(days);
  const data = q.data;
  const busy = q.isPlaceholderData;

  return (
    <div className="page">
      <header className="page__head">
        <p className="label">Overview</p>
        <h1 className="page__title">Everything you have shared</h1>
      </header>

      <PeriodFilter days={days} onChange={setDays} />

      {q.isPending && <p className="muted" role="status" style={{ marginTop: 32 }}>Loading…</p>}
      {q.isError && !data && <div style={{ marginTop: 32 }}><QueryError error={q.error} onRetry={() => void q.refetch()} /></div>}

      {data && (
        <div className={busy ? 'busy' : undefined} style={{ marginTop: 32 }}>
          <Hero days={data.days} totals={data.totals} timeseries={data.timeseries} />

          {data.totals.clicks === 0 && data.totals.botClicks === 0 && (
            <div className="empty">
              <p className="empty__title">No clicks in this period.</p>
              <p className="muted">Create a short link and share it; clicks show up here within a couple of seconds.</p>
              <Link className="btn" to="/links">Create a link</Link>
            </div>
          )}

          <ChartCard
            title="Clicks per day"
            note="UTC days"
            table={{
              caption: `Clicks per day for the last ${data.days} days`,
              columns: ['Day', 'Clicks'],
              rows: data.timeseries.map((p) => [formatDay(p.date), formatExact(p.clicks)]),
            }}
          >
            <TimeSeriesChart points={data.timeseries} />
          </ChartCard>

          <ChartCard
            title="Top links"
            note={`by clicks, last ${data.days} days`}
            table={{
              caption: 'Top links by clicks',
              columns: ['Link', 'Destination', 'Clicks', 'Share'],
              rows: data.topLinks.map((l) => [l.code, hostOf(l.longUrl), formatExact(l.clicks), formatPercent(l.clicks, data.totals.clicks)]),
            }}
          >
            <BarList
              total={data.totals.clicks}
              emptyText="No link has had a click in this period."
              rows={data.topLinks.map((l) => ({
                key: String(l.linkId), label: l.code, code: true, detail: hostOf(l.longUrl), count: l.clicks, href: `/links/${l.linkId}`,
              }))}
            />
          </ChartCard>
        </div>
      )}
    </div>
  );
}
