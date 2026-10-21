import type { SVGProps } from 'react';

// Hand-drawn 16px glyphs, 1.75 stroke, square caps. No icon library: the set is tiny and this keeps one look.
const base: SVGProps<SVGSVGElement> = {
  viewBox: '0 0 16 16', fill: 'none', stroke: 'currentColor', strokeWidth: 1.75,
  strokeLinecap: 'square', strokeLinejoin: 'miter', 'aria-hidden': true, focusable: false,
};

export const CheckIcon = (p: SVGProps<SVGSVGElement>) => (
  <svg {...base} {...p}><path d="M3 8.5 6.5 12 13 4.5" /></svg>
);
export const ClockIcon = (p: SVGProps<SVGSVGElement>) => (
  <svg {...base} {...p}><circle cx="8" cy="8" r="5.75" /><path d="M8 4.5V8l2.5 1.5" /></svg>
);
export const MinusIcon = (p: SVGProps<SVGSVGElement>) => (
  <svg {...base} {...p}><circle cx="8" cy="8" r="5.75" /><path d="M5 8h6" /></svg>
);
