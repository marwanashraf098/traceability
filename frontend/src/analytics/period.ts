import { useCallback, useMemo } from 'react'
import { useSearchParams } from 'react-router-dom'
import type { PeriodParams } from '../analyticsApi'

// The analytics URL state, shared by every page and carried by the nav links:
//   ?period=today|yesterday|7d|30d|month   (default 30d)  or  ?from=YYYY-MM-DD&to=YYYY-MM-DD
//   &compare=1      ask for the previous period past 92 days (the backend skips it otherwise)
//   &mode=booked    revenue basis (default realized)
// The backend takes today|7d|30d or from/to; "yesterday" and "this month" are resolved here to
// from/to in Cairo days. A custom range is capped at 366 days, like the backend.

export type PeriodKey = 'today' | 'yesterday' | '7d' | '30d' | 'month' | 'custom'
export type RevenueMode = 'booked' | 'realized'

export const PERIOD_KEYS: PeriodKey[] = ['today', 'yesterday', '7d', '30d', 'month', 'custom']
export const MAX_RANGE_DAYS = 366
/** Past this many days the backend sends no previous period unless compare=true. */
export const COMPARE_THRESHOLD_DAYS = 92

const ISO = /^\d{4}-\d{2}-\d{2}$/

/** Today's date in Cairo, as YYYY-MM-DD. */
export function cairoToday(now: Date = new Date()): string {
  // en-CA formats as YYYY-MM-DD.
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'Africa/Cairo', year: 'numeric', month: '2-digit', day: '2-digit' })
    .format(now)
}

export function addDays(iso: string, days: number): string {
  const d = new Date(`${iso}T00:00:00Z`)
  d.setUTCDate(d.getUTCDate() + days)
  return d.toISOString().slice(0, 10)
}

export function daysBetween(from: string, to: string): number {
  return Math.round((Date.parse(`${to}T00:00:00Z`) - Date.parse(`${from}T00:00:00Z`)) / 86_400_000) + 1
}

export interface AnalyticsState {
  periodKey: PeriodKey
  /** What the API takes. */
  params: PeriodParams
  /** The inclusive Cairo days the period covers (for labels and the compare rule). */
  from: string
  to: string
  days: number
  compare: boolean
  mode: RevenueMode
}

export function resolveState(sp: URLSearchParams, today: string = cairoToday()): AnalyticsState {
  const compare = sp.get('compare') === '1'
  const mode: RevenueMode = sp.get('mode') === 'booked' ? 'booked' : 'realized'
  const from = sp.get('from'), to = sp.get('to')
  if (from && to && ISO.test(from) && ISO.test(to) && from <= to && daysBetween(from, to) <= MAX_RANGE_DAYS) {
    return { periodKey: 'custom', params: { from, to }, from, to, days: daysBetween(from, to), compare, mode }
  }
  const p = sp.get('period')
  switch (p) {
    case 'today':
      return { periodKey: 'today', params: { period: 'today' }, from: today, to: today, days: 1, compare, mode }
    case '7d':
      return { periodKey: '7d', params: { period: '7d' }, from: addDays(today, -6), to: today, days: 7, compare, mode }
    case 'yesterday': {
      const y = addDays(today, -1)
      return { periodKey: 'yesterday', params: { from: y, to: y }, from: y, to: y, days: 1, compare, mode }
    }
    case 'month': {
      const first = `${today.slice(0, 8)}01`
      return { periodKey: 'month', params: { from: first, to: today }, from: first, to: today,
        days: daysBetween(first, today), compare, mode }
    }
    default:
      return { periodKey: '30d', params: { period: '30d' }, from: addDays(today, -29), to: today, days: 30, compare, mode }
  }
}

/** The query keys the analytics pages share (and nothing else of the URL). */
const SHARED_KEYS = ['period', 'from', 'to', 'compare', 'mode']

/** "?period=7d&mode=booked" — the shared state, for links between analytics pages. */
export function sharedSearch(sp: URLSearchParams): string {
  const out = new URLSearchParams()
  for (const k of SHARED_KEYS) { const v = sp.get(k); if (v) out.set(k, v) }
  const s = out.toString()
  return s ? `?${s}` : ''
}

export function useAnalyticsState() {
  const [sp, setSp] = useSearchParams()
  const state = useMemo(() => resolveState(sp), [sp])

  const update = useCallback((mut: (next: URLSearchParams) => void) => {
    setSp(prev => { const next = new URLSearchParams(prev); mut(next); return next }, { replace: true })
  }, [setSp])

  const setPeriod = useCallback((key: Exclude<PeriodKey, 'custom'>) => update(n => {
    n.delete('from'); n.delete('to')
    if (key === '30d') n.delete('period'); else n.set('period', key)
  }), [update])

  const setCustom = useCallback((from: string, to: string) => update(n => {
    n.delete('period'); n.set('from', from); n.set('to', to)
  }), [update])

  const setCompare = useCallback((on: boolean) => update(n => { if (on) n.set('compare', '1'); else n.delete('compare') }), [update])
  const setMode = useCallback((m: RevenueMode) => update(n => { if (m === 'booked') n.set('mode', 'booked'); else n.delete('mode') }), [update])

  return { ...state, setPeriod, setCustom, setCompare, setMode, search: sharedSearch(sp) }
}
