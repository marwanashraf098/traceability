import { isReadyPage, type AnalyticsPageId } from '../../analytics/flag'
import type { AlertKey } from '../../analyticsApi'

/**
 * A link to an analytics page carrying the shared period state, or undefined while that page
 * isn't built yet (so nothing links to a dead route). `extra` is the page's own query.
 */
export function analyticsLink(page: AnalyticsPageId, search: string, extra?: Record<string, string>): string | undefined {
  if (!isReadyPage(page)) return undefined
  const sp = new URLSearchParams(search.startsWith('?') ? search.slice(1) : search)
  for (const [k, v] of Object.entries(extra ?? {})) sp.set(k, v)
  const s = sp.toString()
  return `/analytics/${page}${s ? `?${s}` : ''}`
}

/**
 * Where each alert opens. The frontend owns this mapping (the backend's `link` is ignored), so a
 * route can change without a backend change.
 */
export const ALERT_ROUTE: Record<AlertKey, { page: AnalyticsPageId; query: Record<string, string> }> = {
  stuck_with_bosta:          { page: 'money',    query: { view: 'stuck', kind: 'stuck_with_bosta' } },
  never_picked_up:           { page: 'money',    query: { view: 'stuck', kind: 'never_picked_up' } },
  delivered_not_paid:        { page: 'money',    query: { view: 'stuck', kind: 'delivered_not_paid' } },
  low_success_governorates:  { page: 'delivery', query: { by: 'governorate' } },
  sells_out_soon:            { page: 'stock',    query: { filter: 'running_low' } },
}

export function alertLink(key: string, search: string): string | undefined {
  const r = ALERT_ROUTE[key as AlertKey]
  return r ? analyticsLink(r.page, search, r.query) : undefined
}
