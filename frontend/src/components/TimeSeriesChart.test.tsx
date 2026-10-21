import { fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { DailyPoint } from '../api/types';
import { TimeSeriesChart } from './TimeSeriesChart';

const points: DailyPoint[] = Array.from({ length: 10 }, (_, i) => ({
  date: `2026-10-${String(i + 5).padStart(2, '0')}`,
  clicks: [5, 8, 3, 12, 9, 0, 4, 20, 11, 7][i]!,
}));

beforeEach(() => {
  // jsdom has no layout: give the SVG a 720px-wide box starting at x=0
  vi.spyOn(Element.prototype, 'getBoundingClientRect').mockReturnValue({
    x: 0, y: 0, left: 0, top: 0, right: 720, bottom: 264, width: 720, height: 264, toJSON: () => ({}),
  });
});
afterEach(() => vi.restoreAllMocks());

describe('TimeSeriesChart', () => {
  it('describes itself for screen readers with the total and the peak', () => {
    render(<TimeSeriesChart points={points} />);
    const label = screen.getByRole('img').getAttribute('aria-label')!;
    expect(label).toContain('over 10 days');
    expect(label).toContain('79 in total');
    expect(label).toContain('peaking at 20 on 12 Oct');
  });

  it('draws one line and one area, plus a marker on the latest day', () => {
    const { container } = render(<TimeSeriesChart points={points} />);
    expect(container.querySelectorAll('.chart__line')).toHaveLength(1);
    expect(container.querySelectorAll('.chart__area')).toHaveLength(1);
    expect(container.querySelectorAll('.chart__dot')).toHaveLength(1);
  });

  it('shows no tooltip until the chart is used', () => {
    const { container } = render(<TimeSeriesChart points={points} />);
    expect(container.querySelector('.tip')).toBeNull();
  });

  it('follows the pointer, snapping to the nearest day', () => {
    const { container } = render(<TimeSeriesChart points={points} />);
    // plot spans x=42..706; the point nearest 40% of the way across is index 4 -> 9 clicks on 9 Oct
    // (a MouseEvent of type "pointermove": jsdom's PointerEvent does not carry coordinates)
    fireEvent(screen.getByRole('img'), new MouseEvent('pointermove', { bubbles: true, clientX: 42 + 0.45 * (706 - 42) }));
    const tip = container.querySelector('.tip')!;
    expect(tip).toHaveTextContent('9 Oct');
    expect(tip).toHaveTextContent('9 clicks');
    // React derives onPointerLeave from a pointerout whose target is outside the element
    fireEvent(screen.getByRole('img'), new MouseEvent('pointerout', { bubbles: true, relatedTarget: document.body }));
    expect(container.querySelector('.tip')).toBeNull();
  });

  it('works from the keyboard: focus lands on the latest day, arrows move, Escape clears', () => {
    const { container } = render(<TimeSeriesChart points={points} />);
    const chart = screen.getByRole('img');
    fireEvent.focus(chart);
    expect(container.querySelector('.tip')).toHaveTextContent('14 Oct');

    fireEvent.keyDown(chart, { key: 'ArrowLeft' });
    expect(container.querySelector('.tip')).toHaveTextContent('13 Oct');
    expect(container.querySelector('.tip')).toHaveTextContent('11 clicks');

    fireEvent.keyDown(chart, { key: 'Home' });
    expect(container.querySelector('.tip')).toHaveTextContent('5 Oct');
    fireEvent.keyDown(chart, { key: 'ArrowLeft' }); // already at the start: stays put
    expect(container.querySelector('.tip')).toHaveTextContent('5 Oct');

    fireEvent.keyDown(chart, { key: 'End' });
    fireEvent.keyDown(chart, { key: 'ArrowRight' }); // already at the end
    expect(container.querySelector('.tip')).toHaveTextContent('14 Oct');

    fireEvent.keyDown(chart, { key: 'Escape' });
    expect(container.querySelector('.tip')).toBeNull();
  });

  it('announces the active day through a live region, so the tooltip is never the only way to read a value', () => {
    const { container } = render(<TimeSeriesChart points={points} />);
    fireEvent.focus(screen.getByRole('img'));
    expect(container.querySelector('[aria-live="polite"]')).toHaveTextContent('14 Oct: 7 clicks');
  });

  it('copes with an all-zero series, a single day and no days at all', () => {
    const zeros = points.map((p) => ({ ...p, clicks: 0 }));
    const { container, rerender } = render(<TimeSeriesChart points={zeros} />);
    expect(container.querySelector('.chart__line')).toBeInTheDocument();

    rerender(<TimeSeriesChart points={[{ date: '2026-10-14', clicks: 3 }]} />);
    expect(container.querySelector('.chart__line')).toBeNull(); // a line needs two points...
    expect(container.querySelector('.chart__dot')).toBeInTheDocument(); // ...but the day is still shown

    rerender(<TimeSeriesChart points={[]} />);
    expect(container.querySelector('.chart__dot')).toBeNull();
  });
});
