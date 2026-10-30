import { useEffect, useState } from 'react'
import { getAccessToken } from '../../auth'
import { getPutAwaySummary } from '../../api'
import { loadCapabilities } from '../../capabilities'

/**
 * Warehouse places S2 — how many pieces are waiting to be put away (every location that has a shelf or box), for the
 * sidebar badge and the WorkerHome tile. null until known, and always null for a tenant without places (no request
 * is made). One request per access token per 30 s, shared by every caller; a failure reads as null.
 */
let cache: { token: string; at: number; promise: Promise<number | null> } | null = null
const TTL_MS = 30_000

export function loadPutAwayWaiting(): Promise<number | null> {
  const token = getAccessToken()
  if (!token) return Promise.resolve(null)
  if (!cache || cache.token !== token || Date.now() - cache.at > TTL_MS) {
    const promise = loadCapabilities()
      .then(c => (c.places ? getPutAwaySummary().then(s => s.waiting) : null))
      .catch(() => null)
    cache = { token, at: Date.now(), promise }
  }
  return cache.promise
}

/** Tests only — and after a put-away session, so the badge shows the new count. */
export function resetPutAwayWaitingCache() {
  cache = null
}

export function usePutAwayWaiting(): number | null {
  const [n, setN] = useState<number | null>(null)
  useEffect(() => {
    let cancelled = false
    loadPutAwayWaiting().then(v => { if (!cancelled) setN(v) })
    return () => { cancelled = true }
  }, [])
  return n
}
