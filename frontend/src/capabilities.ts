import { useEffect, useState } from 'react'
import { getAccessToken } from './auth'
import { request } from './api'

/**
 * Review mode S7 — what this tenant's screens may offer beyond the real product, from GET /me
 * (backend ReviewCapabilities is the one rule):
 *   scanHelpers — click-to-scan chips (public demo + simulated-courier review tenant; never a real merchant)
 *   demoMode    — the public demo only (e.g. the station exit without a password)
 * One /me request per access token, shared by every screen; anything missing or failing reads as
 * a real merchant (both false).
 */
export interface Capabilities {
  scanHelpers: boolean
  demoMode: boolean
  /** Non-Bosta couriers G2 — the store ships with other couriers too (courier-neutral scan texts). */
  genericCourier: boolean
  /** Flexible labeling — blank labels / untagged stock (backend FlexibleLabeling; no screen reads it yet). */
  flexibleLabeling: boolean
  /**
   * Warehouse places — zones / racks / shelves / boxes, as features: the switch is on AND a location is set up (a
   * live shelf or box — backend Places.anySetUp; /me's placesDormant says it isn't yet). Every place feature reads
   * this, so a store that switched places on but hasn't set up a shelf or box sees today's screens.
   */
  places: boolean
  /** The Settings › Places switch itself ("Use places in this store", backend tenants.places_enabled). */
  placesEnabled: boolean
  /** Bazar POS (backend tenants.bazar_enabled) — the sidebar's Bazar item. */
  bazar: boolean
  /** Bazar POS B6 — the live bazar the caller's role may sell at (RootRoute sends the phone there), else null. */
  bazarLive: BazarLive | null
  /** Bazar B9b — the guided bazar setup is on (owner / manager; backend BAZAR_SIGNUP_ENABLED + a bazar signup). */
  bazarSetup: boolean
}

export interface BazarLive {
  eventId: string
  name: string
  /** Which day of the event today is (1-based; may fall outside 1..dayCount when started early / running late). */
  day: number
  dayCount: number
  /** Today's Africa/Cairo date, YYYY-MM-DD — "today's" sales are the ones with this business_day. */
  businessDay: string
  paymentMethods: string[]
}

function parseBazarLive(v: unknown): BazarLive | null {
  if (!v || typeof v !== 'object') return null
  const o = v as Record<string, unknown>
  if (typeof o.eventId !== 'string' || typeof o.name !== 'string') return null
  return {
    eventId: o.eventId, name: o.name,
    day: typeof o.day === 'number' ? o.day : 0,
    dayCount: typeof o.dayCount === 'number' ? o.dayCount : 0,
    businessDay: typeof o.businessDay === 'string' ? o.businessDay : '',
    paymentMethods: Array.isArray(o.paymentMethods) ? o.paymentMethods.filter((m): m is string => typeof m === 'string') : [],
  }
}

const NONE: Capabilities = { scanHelpers: false, demoMode: false, genericCourier: false, flexibleLabeling: false, places: false,
                             placesEnabled: false, bazar: false, bazarLive: null, bazarSetup: false }

let cache: { token: string; promise: Promise<Capabilities> } | null = null

export function loadCapabilities(): Promise<Capabilities> {
  const token = getAccessToken()
  if (!token) return Promise.resolve(NONE)
  if (cache?.token !== token) {
    const promise = request<{ scanHelpers?: unknown; demoMode?: unknown; genericCourier?: unknown;
                              flexibleLabeling?: unknown; places?: unknown; placesDormant?: unknown;
                              bazar?: unknown; bazarLive?: unknown; bazarSetup?: unknown } | null>('/me')
      .then(me => ({ scanHelpers: me?.scanHelpers === true, demoMode: me?.demoMode === true,
                     genericCourier: me?.genericCourier === true,
                     flexibleLabeling: me?.flexibleLabeling === true,
                     places: me?.places === true && me?.placesDormant !== true,
                     placesEnabled: me?.places === true,
                     bazar: me?.bazar === true,
                     bazarLive: me?.bazar === true ? parseBazarLive(me?.bazarLive) : null,
                     bazarSetup: me?.bazar === true && me?.bazarSetup === true }))
      .catch(() => NONE)
    cache = { token, promise }
  }
  return cache.promise
}

/** Forget the cached /me answer — tests, and the bazar screens after an event starts / ends (bazarLive changes). */
export function resetCapabilitiesCache() {
  cache = null
}

export function useCapabilities(): Capabilities {
  const [caps, setCaps] = useState<Capabilities>(NONE)
  useEffect(() => {
    let cancelled = false
    loadCapabilities().then(c => { if (!cancelled) setCaps(c) })
    return () => { cancelled = true }
  }, [])
  return caps
}
