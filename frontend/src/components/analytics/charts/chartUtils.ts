// Shared bits for the hand-rolled SVG charts (no chart library — see the s9a diagnosis).

/** Series colours, matching the mockup and the app's tokens. */
export const CHART = {
  booked: '#86b6ef',
  realized: '#16a34a',
  blue: '#2a78d6',
  amber: '#f59e0b',
  red: '#e5484d',
  grey: '#b4b8c0',
  grid: '#E5E7EB',
  base: '#D1D5DB',
  label: '#5B6675',
} as const

/** A "nice" axis maximum at or above m: 1, 1.2, 1.5, 2, 2.5, 3, 4, 5, 6, 8 × 10ⁿ. */
export function niceMax(m: number): number {
  if (!(m > 0)) return 1
  const p = Math.pow(10, Math.floor(Math.log10(m)))
  for (const s of [1, 1.2, 1.5, 2, 2.5, 3, 4, 5, 6, 8, 10]) if (s * p >= m) return s * p
  return 10 * p
}

/** Maps a left-to-right x to the reading direction: in RTL the time axis runs right to left. */
export function mirrorX(rtl: boolean, width: number) {
  return (x: number) => (rtl ? width - x : x)
}
