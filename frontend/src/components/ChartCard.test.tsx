import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { ChartCard } from './ChartCard';

const table = {
  caption: 'Clicks per day',
  columns: ['Day', 'Clicks'],
  rows: [['12 Oct', '139'], ['13 Oct', '104']],
};

describe('ChartCard', () => {
  it('shows the chart first, and the same figures as a table on request', async () => {
    render(<ChartCard title="Clicks per day" table={table}><p>the chart</p></ChartCard>);
    expect(screen.getByText('the chart')).toBeInTheDocument();
    expect(screen.queryByRole('table')).not.toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Table' }));
    expect(screen.queryByText('the chart')).not.toBeInTheDocument();
    const grid = screen.getByRole('table', { name: 'Clicks per day' });
    expect(within(grid).getAllByRole('columnheader').map((h) => h.textContent)).toEqual(['Day', 'Clicks']);
    expect(within(grid).getByRole('cell', { name: '139' })).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Chart' }));
    expect(screen.getByText('the chart')).toBeInTheDocument();
  });

  it('marks the active view for assistive tech and dims while refetching', async () => {
    const { container } = render(<ChartCard title="Countries" table={table} busy><p>x</p></ChartCard>);
    expect(screen.getByRole('button', { name: 'Chart' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('button', { name: 'Table' })).toHaveAttribute('aria-pressed', 'false');
    expect(container.querySelector('.busy')).toBeInTheDocument();
  });
});
