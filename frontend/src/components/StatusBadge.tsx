import { formatDate, linkStatus } from '../lib/format';
import type { LinkItem } from '../api/types';
import { CheckIcon, ClockIcon, MinusIcon } from './icons';

const COPY = {
  active: { label: 'Active', Icon: CheckIcon },
  expired: { label: 'Expired', Icon: ClockIcon },
  deactivated: { label: 'Deactivated', Icon: MinusIcon },
} as const;

/** Shape + word + colour: status never relies on colour alone. */
export function StatusBadge({ link }: { link: Pick<LinkItem, 'active' | 'expiresAt'> }) {
  const status = linkStatus(link);
  const { label, Icon } = COPY[status];
  return (
    <span className={`status status--${status}`}>
      <Icon />
      {label}
      {status === 'active' && link.expiresAt && (
        <span className="muted" style={{ textTransform: 'none', letterSpacing: 0, fontWeight: 400 }}>
          until {formatDate(link.expiresAt)}
        </span>
      )}
    </span>
  );
}
