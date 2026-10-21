import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { links } from '../api/endpoints';
import { ApiError } from '../api/client';
import type { Bucket } from '../api/types';
import { BarList, type BarRow } from '../components/BarList';
import { ChartCard } from '../components/ChartCard';
import { Code } from '../components/Code';
import { ConfirmDialog } from '../components/ConfirmDialog';
import { CopyButton } from '../components/CopyButton';
import { Hero } from '../components/Facts';
import { PeriodFilter, usePeriod } from '../components/PeriodFilter';
import { QrPanel } from '../components/QrPanel';
import { QueryError } from '../components/QueryState';
import { StatusBadge } from '../components/StatusBadge';
import { TimeSeriesChart } from '../components/TimeSeriesChart';
import { countryName, formatDate, formatDateTime, formatDay, formatExact, formatPercent, linkStatus } from '../lib/format';
import { useLink, useLinkStats } from '../lib/queries';
import { NotFound } from './NotFound';

export function LinkDetail() {
  const id = Number(useParams().id);
  const [days, setDays] = usePeriod();
  const link = useLink(id);
  const stats = useLinkStats(id, days);
  const qc = useQueryClient();
  const [confirming, setConfirming] = useState(false);

  const deactivate = useMutation({
    mutationFn: () => links.deactivate(id),
    onSuccess: () => {
      setConfirming(false);
      void qc.invalidateQueries({ queryKey: ['link', id] });
      void qc.invalidateQueries({ queryKey: ['links'] });
      void qc.invalidateQueries({ queryKey: ['overview'] });
    },
  });

  if (!Number.isInteger(id) || (link.error instanceof ApiError && link.error.status === 404)) return <NotFound inApp />;
  if (link.isPending) return <p className="page muted" role="status">Loading…</p>;
  if (link.isError) return <div className="page"><QueryError error={link.error} onRetry={() => void link.refetch()} /></div>;

  const l = link.data;
  const data = stats.data;
  const busy = stats.isPlaceholderData;
  const total = data?.totals.clicks ?? 0;

  const rowsOf = (buckets: Bucket[], label: (key: string) => string = (k) => k, detail?: (key: string) => string | undefined): BarRow[] =>
    buckets.map((b) => ({ key: b.key, label: label(b.key), detail: detail?.(b.key), count: b.count }));
  const tableOf = (name: string, buckets: Bucket[], label: (key: string) => string = (k) => k) => ({
    caption: `${name} by clicks`,
    columns: [name, 'Clicks', 'Share'],
    rows: buckets.map((b) => [label(b.key), formatExact(b.count), formatPercent(b.count, total)]),
  });
  const isCode = (k: string) => (/^[A-Z]{2}$/.test(k) ? k : undefined);

  return (
    <div className="page">
      <p className="label"><Link to="/links">← All links</Link></p>

      <div className="detail-top">
        <header className="detail-head" style={{ marginTop: 14 }}>
          <h1><Code code={l.code} large /></h1>
          <p className="detail-head__dest">
            → <a href={l.longUrl} target="_blank" rel="noopener noreferrer">{l.longUrl}</a>
          </p>
          <div className="detail-head__meta">
            <StatusBadge link={l} />
            <span>Created {formatDate(l.createdAt)}</span>
            {l.expiresAt && <span>Expires {formatDateTime(l.expiresAt)}</span>}
            <CopyButton text={l.shortUrl} label="Copy link" />
            {linkStatus(l) !== 'deactivated' && (
              <button type="button" className="btn btn--text btn--danger copy" onClick={() => setConfirming(true)}>Deactivate</button>
            )}
          </div>
        </header>
        <QrPanel id={l.id} code={l.code} shortUrl={l.shortUrl} />
      </div>

      <PeriodFilter days={days} onChange={setDays} />

      {stats.isPending && <p className="muted" role="status" style={{ marginTop: 32 }}>Loading…</p>}
      {stats.isError && !data && <div style={{ marginTop: 32 }}><QueryError error={stats.error} onRetry={() => void stats.refetch()} /></div>}

      {data && (
        <div style={{ marginTop: 32 }}>
          <div className={busy ? 'busy' : undefined}>
            <Hero days={data.days} totals={data.totals} timeseries={data.timeseries} />
          </div>

          <ChartCard
            title="Clicks per day"
            note="UTC days"
            busy={busy}
            table={{
              caption: `Clicks per day for the last ${data.days} days`,
              columns: ['Day', 'Clicks'],
              rows: data.timeseries.map((p) => [formatDay(p.date), formatExact(p.clicks)]),
            }}
          >
            <TimeSeriesChart points={data.timeseries} />
          </ChartCard>

          {/* Two columns of sections: the long lists (countries, referrers) on one side, the short ones on the other. */}
          <div className="breakdowns">
            <div className="breakdowns__col">
              <ChartCard title="Countries" busy={busy} table={tableOf('Country', data.countries, countryName)}>
                <BarList total={total} rows={rowsOf(data.countries, countryName, isCode)} />
              </ChartCard>
              <ChartCard title="Referrers" note="the site that sent the visitor" busy={busy} table={tableOf('Referrer', data.referrers)}>
                <BarList total={total} rows={rowsOf(data.referrers)} />
              </ChartCard>
            </div>
            <div className="breakdowns__col">
              <ChartCard title="Devices" busy={busy} table={tableOf('Device', data.devices)}>
                <BarList total={total} rows={rowsOf(data.devices)} />
              </ChartCard>
              <ChartCard title="Browsers" busy={busy} table={tableOf('Browser', data.browsers)}>
                <BarList total={total} rows={rowsOf(data.browsers)} />
              </ChartCard>
              <ChartCard title="Operating systems" busy={busy} table={tableOf('System', data.operatingSystems)}>
                <BarList total={total} rows={rowsOf(data.operatingSystems)} />
              </ChartCard>
            </div>
          </div>
        </div>
      )}

      <ConfirmDialog
        open={confirming}
        title="Deactivate this link?"
        body={`${l.shortUrl} will stop redirecting and visitors will see "no longer available". The code is never reused. This cannot be undone.`}
        confirmLabel="Deactivate"
        busy={deactivate.isPending}
        onConfirm={() => deactivate.mutate()}
        onCancel={() => setConfirming(false)}
      />
    </div>
  );
}
