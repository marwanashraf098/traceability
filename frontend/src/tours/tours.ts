/**
 * Onboarding O5 — the four first-time tours (design/onboarding/Traced_onboarding_Tour-Cards, Pickups-Tour). Copy lives in
 * the locale files under `tours.<id>.s1..s4` ({title, body, tip?}); it describes how Traced really works (checked
 * against the code — e.g. a pickup hands parcels over when it's CLOSED, not on each scan).
 *
 * `task` is the progress task (GET /onboarding/progress) that says the tenant has done this action at least once —
 * a tour auto-starts only while it hasn't. `roles` is who may see the tour at all.
 */
export type TourId = 'pickups' | 'scan_returns' | 'stock_take' | 'transfers'

export interface TourStep {
  /** Locale key prefix: tours.<id>.s<n> */
  key: string
  /** data-tour-anchor value of the element to ring, when it's on screen. */
  anchor?: string
  hasTip?: boolean
}

export interface TourDef {
  id: TourId
  task: 'pickup' | 'returns' | 'stock_take' | 'transfer'
  roles: ('owner' | 'manager' | 'worker')[]
  steps: TourStep[]
}

const ALL = ['owner', 'manager', 'worker'] as ('owner' | 'manager' | 'worker')[]
const MANAGERS = ['owner', 'manager'] as ('owner' | 'manager' | 'worker')[]

export const TOURS: Record<TourId, TourDef> = {
  pickups: {
    id: 'pickups', task: 'pickup', roles: ALL,
    steps: [
      { key: 'tours.pickups.s1', anchor: 'pickups-new' },
      { key: 'tours.pickups.s2', anchor: 'pickups-scan', hasTip: true },
      { key: 'tours.pickups.s3', anchor: 'pickups-list' },
      { key: 'tours.pickups.s4', anchor: 'pickups-close' },
    ],
  },
  scan_returns: {
    id: 'scan_returns', task: 'returns', roles: ALL,
    steps: [
      { key: 'tours.scan_returns.s1', anchor: 'returns-scan' },
      { key: 'tours.scan_returns.s2', anchor: 'returns-scan', hasTip: true },
      { key: 'tours.scan_returns.s3', hasTip: true },
      { key: 'tours.scan_returns.s4', anchor: 'returns-close' },
    ],
  },
  stock_take: {
    id: 'stock_take', task: 'stock_take', roles: MANAGERS,
    steps: [
      { key: 'tours.stock_take.s1', anchor: 'stocktake-new' },
      { key: 'tours.stock_take.s2', anchor: 'stocktake-scan' },
      { key: 'tours.stock_take.s3' },
      { key: 'tours.stock_take.s4', hasTip: true },
    ],
  },
  transfers: {
    id: 'transfers', task: 'transfer', roles: MANAGERS,
    steps: [
      { key: 'tours.transfers.s1', anchor: 'transfers-new' },
      { key: 'tours.transfers.s2', anchor: 'transfers-scan' },
      { key: 'tours.transfers.s3', hasTip: true },
      { key: 'tours.transfers.s4', anchor: 'transfers-reconcile' },
    ],
  },
}

export function canSeeTour(id: TourId, role: string | null): boolean {
  return !!role && (TOURS[id].roles as string[]).includes(role)
}
