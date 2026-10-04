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
export interface Capabilities { scanHelpers: boolean; demoMode: boolean }

const NONE: Capabilities = { scanHelpers: false, demoMode: false }

let cache: { token: string; promise: Promise<Capabilities> } | null = null

export function loadCapabilities(): Promise<Capabilities> {
  const token = getAccessToken()
  if (!token) return Promise.resolve(NONE)
  if (cache?.token !== token) {
    const promise = request<{ scanHelpers?: unknown; demoMode?: unknown } | null>('/me')
      .then(me => ({ scanHelpers: me?.scanHelpers === true, demoMode: me?.demoMode === true }))
      .catch(() => NONE)
    cache = { token, promise }
  }
  return cache.promise
}

/** Tests only — forget the cached /me answer. */
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
