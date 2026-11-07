import { recordUiState } from './api'

/**
 * Onboarding O3 — sidebar "not opened yet" dots. The sidebar route → its page key in the backend whitelist
 * (UserUiStateService.PAGES). A visit sends POST /me/ui-state {pageSeen} once per page per page load, fire-and-forget
 * (never blocks or fails navigation); keys sent this page load count as seen at once (no dot flashing back while
 * GET /me catches up).
 */
const PAGE_KEYS: [string, string][] = [
  ['/overview', 'overview'], ['/exceptions', 'exceptions'], ['/orders', 'orders'], ['/fulfill', 'fulfill'],
  ['/pickups', 'pickups'], ['/inventory', 'inventory'], ['/receiving', 'receiving'], ['/put-away', 'put_away'],
  ['/transfers', 'transfers'], ['/stock-take', 'stock_take'], ['/exchanges', 'exchanges'], ['/returns', 'returns'],
  ['/settings', 'settings'], ['/analytics', 'analytics'],
]

/** The page key of a route (its sidebar item, by path prefix), or null. */
export function pageKeyFor(pathname: string): string | null {
  for (const [path, key] of PAGE_KEYS) if (pathname === path || pathname.startsWith(path + '/')) return key
  return null
}

const sent = new Set<string>()

/** This page was opened: POST once (per page load), never awaited, never throws. */
export function markPageSeen(key: string) {
  if (sent.has(key)) return
  sent.add(key)
  recordUiState({ pageSeen: key }).catch(() => { /* a dot coming back next session is harmless */ })
}

export function sentThisLoad(key: string): boolean {
  return sent.has(key)
}

/** Tests only. */
export function forgetPagesSeen() {
  sent.clear()
}
