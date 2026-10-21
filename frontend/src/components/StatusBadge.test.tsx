import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { StatusBadge } from './StatusBadge';

describe('StatusBadge', () => {
  it('always pairs the colour with a word', () => {
    const future = new Date(Date.now() + 5 * 864e5).toISOString();
    const past = new Date(Date.now() - 864e5).toISOString();
    const { rerender } = render(<StatusBadge link={{ active: true, expiresAt: null }} />);
    expect(screen.getByText('Active')).toBeInTheDocument();

    rerender(<StatusBadge link={{ active: true, expiresAt: future }} />);
    expect(screen.getByText('Active')).toBeInTheDocument();
    expect(screen.getByText(/until/)).toBeInTheDocument();

    rerender(<StatusBadge link={{ active: true, expiresAt: past }} />);
    expect(screen.getByText('Expired')).toBeInTheDocument();

    rerender(<StatusBadge link={{ active: false, expiresAt: null }} />);
    expect(screen.getByText('Deactivated')).toBeInTheDocument();
  });
});
