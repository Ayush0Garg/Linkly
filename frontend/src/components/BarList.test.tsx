import { screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { renderWithProviders } from '../test/render';
import { BarList } from './BarList';

const rows = [
  { key: 'IN', label: 'India', detail: 'IN', count: 405 },
  { key: 'US', label: 'United States', detail: 'US', count: 219 },
  { key: 'x', label: 'Unknown', count: 0 },
];

describe('BarList', () => {
  it('shows each value and its share of the total at the tip of the bar', () => {
    renderWithProviders(<BarList rows={rows} total={1038} />);
    expect(screen.getByText('405')).toBeInTheDocument();
    expect(screen.getByText('39%')).toBeInTheDocument();
    expect(screen.getByText('219')).toBeInTheDocument();
    expect(screen.getByText('21%')).toBeInTheDocument();
  });

  it('scales bars against the largest value, as a 0-1 ratio', () => {
    renderWithProviders(<BarList rows={rows} total={1038} />);
    const items = screen.getAllByRole('listitem');
    expect(items[0]!.style.getPropertyValue('--pct')).toBe('1');
    expect(Number(items[1]!.style.getPropertyValue('--pct'))).toBeCloseTo(219 / 405, 5);
    expect(items[2]!.style.getPropertyValue('--pct')).toBe('0');
  });

  it('makes every row a keyboard-focusable target that states its full meaning', () => {
    renderWithProviders(<BarList rows={rows} total={1038} />);
    const first = screen.getByRole('listitem', { name: 'India: 405 clicks, 39%' });
    expect(first).toHaveAttribute('tabindex', '0');
    expect(first).toHaveAttribute('title', 'India: 405 clicks, 39%');
  });

  it('links rows that point somewhere, without a second tab stop on the row', () => {
    renderWithProviders(<BarList rows={[{ key: '1', label: 'linkly-KZrNBm', code: true, detail: 'mozilla.org', count: 5, href: '/links/1000' }]} total={5} />);
    const link = screen.getByRole('link');
    expect(link).toHaveAttribute('href', '/links/1000');
    expect(screen.getByRole('listitem')).not.toHaveAttribute('tabindex');
  });

  it('says so when there is nothing to show', () => {
    renderWithProviders(<BarList rows={[]} total={0} emptyText="No referrers yet." />);
    expect(screen.getByText('No referrers yet.')).toBeInTheDocument();
    expect(screen.queryByRole('list')).not.toBeInTheDocument();
  });

  it('does not divide by zero when every count is zero', () => {
    renderWithProviders(<BarList rows={[{ key: 'a', label: 'A', count: 0 }]} total={0} />);
    expect(screen.getByRole('listitem').style.getPropertyValue('--pct')).toBe('0');
  });
});
