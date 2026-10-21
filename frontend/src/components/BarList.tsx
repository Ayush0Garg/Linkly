import type { CSSProperties } from 'react';
import { Link } from 'react-router-dom';
import { formatExact, formatPercent } from '../lib/format';
import { Code } from './Code';

export interface BarRow {
  key: string;
  label: string;
  /** Small mono text beside the label (a country code) or under it (a link's destination host). */
  detail?: string;
  /** Show the label as a short code (service prefix set back quietly). */
  code?: boolean;
  count: number;
  href?: string;
}

interface Props {
  rows: BarRow[];
  /** Denominator for the share shown beside each value. */
  total: number;
  unit?: string;
  emptyText?: string;
}

/**
 * Horizontal bars for nominal categories: one colour (they have no order, so no ramp), at most 20px of row and 12px
 * of bar, square at the baseline and rounded at the data end, value at the tip. The bar is the mark, so the whole row
 * is the hover/focus target.
 */
export function BarList({ rows, total, unit = 'clicks', emptyText = 'Nothing to show for this period.' }: Props) {
  if (rows.length === 0) return <p className="muted">{emptyText}</p>;
  const max = Math.max(...rows.map((r) => r.count), 1);

  return (
    <ul className="bars">
      {rows.map((row) => {
        const summary = `${row.label}: ${formatExact(row.count)} ${unit}, ${formatPercent(row.count, total)}`;
        const name = (
          <>
            {row.code ? <Code code={row.label} /> : <span>{row.label}</span>}
            {row.detail && <span className="bars__code">{row.detail}</span>}
          </>
        );
        return (
          <li
            key={row.key}
            className="bars__row"
            title={summary}
            tabIndex={row.href ? undefined : 0}
            aria-label={row.href ? undefined : summary}
            style={{ '--pct': String(row.count / max) } as CSSProperties}
          >
            <div className={`bars__name${row.href ? ' bars__name--stacked' : ''}`}>
              {row.href ? <Link to={row.href} aria-label={summary}>{name}</Link> : name}
            </div>
            <div className="bars__track" aria-hidden="true">
              <div className="bars__bar" />
              <span className="bars__value">
                {formatExact(row.count)}
                <span className="bars__share">{formatPercent(row.count, total)}</span>
              </span>
            </div>
          </li>
        );
      })}
    </ul>
  );
}
