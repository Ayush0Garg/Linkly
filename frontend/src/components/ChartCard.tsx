import { useState, type ReactNode } from 'react';

export interface TableSpec {
  caption: string;
  columns: string[];
  rows: (string | number)[][];
}

interface Props {
  title: string;
  note?: string;
  table: TableSpec;
  busy?: boolean;
  full?: boolean;
  children: ReactNode;
}

/** A titled section whose figures are always available as a plain table (the accessible twin of every chart). */
export function ChartCard({ title, note, table, busy, full, children }: Props) {
  const [view, setView] = useState<'chart' | 'table'>('chart');
  return (
    <section className={`section${full ? ' section--full' : ''}`} aria-label={title}>
      <div className="section__head">
        <h2 className="section__title">{title}</h2>
        {note && <span className="section__note">{note}</span>}
        <div className="seg seg--small" role="group" aria-label={`${title}: view`}>
          <button type="button" aria-pressed={view === 'chart'} onClick={() => setView('chart')}>Chart</button>
          <button type="button" aria-pressed={view === 'table'} onClick={() => setView('table')}>Table</button>
        </div>
      </div>
      <div className={busy ? 'busy' : undefined}>
        {view === 'chart' ? (
          children
        ) : (
          <div className="table-wrap">
            <table className="ledger">
              <caption className="sr-only">{table.caption}</caption>
              <thead>
                <tr>
                  {table.columns.map((c, i) => (
                    <th key={c} scope="col" className={i > 0 ? 'num' : undefined}>{c}</th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {table.rows.map((row, r) => (
                  <tr key={r}>
                    {row.map((cell, i) => (
                      <td key={i} className={i > 0 ? 'num' : undefined}>{cell}</td>
                    ))}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </section>
  );
}
