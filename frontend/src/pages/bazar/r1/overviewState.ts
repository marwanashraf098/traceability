import type { BazarEventRow } from '../../../bazar/api'

/**
 * Bazar R1 — which hero the overview shows, chosen ONLY from the already-loaded GET /bazar-events list:
 * live → upcoming draft → past only → first time. Pure, so the choice is unit-tested on its own.
 */
export type OverviewState =
  | { kind: 'live'; hero: BazarEventRow; alsoLive: BazarEventRow[]; upcoming: BazarEventRow[]; past: BazarEventRow[] }
  | { kind: 'upcoming'; hero: BazarEventRow; upcoming: BazarEventRow[]; past: BazarEventRow[]; lastEnded: BazarEventRow | null }
  | { kind: 'past'; hero: BazarEventRow; past: BazarEventRow[] }
  | { kind: 'empty' }

/** Earliest first by starts_on, then by created_at — a stable order for live / draft events. */
function byStart(a: BazarEventRow, b: BazarEventRow): number {
  return a.starts_on.localeCompare(b.starts_on) || a.created_at.localeCompare(b.created_at) || a.id.localeCompare(b.id)
}

/** Newest first by ends_on, then created_at — "the last bazar" is the first of these. */
function byEndDesc(a: BazarEventRow, b: BazarEventRow): number {
  return b.ends_on.localeCompare(a.ends_on) || b.created_at.localeCompare(a.created_at) || b.id.localeCompare(a.id)
}

export function pickOverview(rows: readonly BazarEventRow[]): OverviewState {
  const live = rows.filter(r => r.status === 'live').sort(byStart)
  const drafts = rows.filter(r => r.status === 'draft').sort(byStart)
  const past = rows.filter(r => r.status === 'ended').sort(byEndDesc)
  if (live.length > 0) return { kind: 'live', hero: live[0], alsoLive: live.slice(1), upcoming: drafts, past }
  if (drafts.length > 0) {
    return { kind: 'upcoming', hero: drafts[0], upcoming: drafts.slice(1), past, lastEnded: past[0] ?? null }
  }
  if (past.length > 0) return { kind: 'past', hero: past[0], past }
  return { kind: 'empty' }
}

/** Whole days from {@code today} to {@code ymd} (both YYYY-MM-DD; negative = in the past). */
export function daysFrom(today: string, ymd: string): number {
  const ms = (s: string) => Date.UTC(+s.slice(0, 4), +s.slice(5, 7) - 1, +s.slice(8, 10))
  return Math.round((ms(ymd) - ms(today)) / 86_400_000)
}

/** How many selling days an event has (inclusive). */
export function dayCount(startsOn: string, endsOn: string): number {
  return daysFrom(startsOn, endsOn) + 1
}

/** Today's date in Cairo as YYYY-MM-DD — the business day the backend uses. */
export function cairoToday(): string {
  return new Date().toLocaleDateString('en-CA', { timeZone: 'Africa/Cairo' })
}
