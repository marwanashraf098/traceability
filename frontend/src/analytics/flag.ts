// Analytics ships behind VITE_ANALYTICS_ENABLED (default off): the nav group and the /analytics
// routes render only when it is exactly "true". Read at call time (not a module constant) so
// tests can flip it with vi.stubEnv.
export function analyticsEnabled(): boolean {
  return import.meta.env.VITE_ANALYTICS_ENABLED === 'true'
}

/** The analytics pages, in nav order. `ready` = built and reachable; the rest stay hidden. */
export const ANALYTICS_PAGES = [
  { id: 'summary',   ready: true },
  { id: 'revenue',   ready: true },
  { id: 'orders',    ready: true },
  { id: 'money',     ready: true },
  { id: 'products',  ready: true },
  { id: 'stock',     ready: true },
  { id: 'delivery',  ready: true },
  { id: 'customers', ready: true },
] as const

export type AnalyticsPageId = typeof ANALYTICS_PAGES[number]['id']

export function isReadyPage(id: string): id is AnalyticsPageId {
  return ANALYTICS_PAGES.some(p => p.id === id && p.ready)
}
