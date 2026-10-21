import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { renderWithProviders } from '../test/render';
import { PeriodFilter, usePeriod } from './PeriodFilter';

function Harness() {
  const [days, setDays] = usePeriod();
  return <PeriodFilter days={days} onChange={setDays} />;
}

describe('PeriodFilter', () => {
  it('defaults to 30 days and marks it as the selected choice', () => {
    renderWithProviders(<Harness />);
    expect(screen.getByRole('button', { name: '30 days' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('button', { name: '7 days' })).toHaveAttribute('aria-pressed', 'false');
  });

  it('keeps the choice in the URL', async () => {
    renderWithProviders(<Harness />);
    await userEvent.click(screen.getByRole('button', { name: '90 days' }));
    expect(screen.getByTestId('location')).toHaveTextContent('/?days=90');
    expect(screen.getByRole('button', { name: '90 days' })).toHaveAttribute('aria-pressed', 'true');
  });

  it('reads the choice from the URL and ignores values it does not offer', () => {
    const { unmount } = renderWithProviders(<Harness />, { route: '/?days=7' });
    expect(screen.getByRole('button', { name: '7 days' })).toHaveAttribute('aria-pressed', 'true');
    unmount();
    renderWithProviders(<Harness />, { route: '/?days=12345' });
    expect(screen.getByRole('button', { name: '30 days' })).toHaveAttribute('aria-pressed', 'true');
  });
});
