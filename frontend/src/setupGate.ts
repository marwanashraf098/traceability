import { useEffect, useState } from 'react'
import { getAccessToken } from './auth'
import { request, type SetupStep } from './api'

/**
 * Onboarding gate (V186) — the tenant's setupStep from GET /me ('shopify' | 'address' | null; the backend's
 * SetupStatus is the one rule, demo and review tenants are null there). One /me request per access token,
 * shared by every gated route; {@link refreshSetupStep} forgets it after a step is done. A failed /me reads
 * as "not gated" — the gate never locks anyone out on an error.
 */

/** Onboarding O2 — the post-gate screen an owner goes to once (GET /me setupIntro; the backend's
 *  OnboardingProgressService.setupIntro is the one rule): Flow-3 courier, then Flow-4 "You're connected". */
export type SetupIntro = 'courier' | 'ready' | null

export interface SetupState {
  step: SetupStep
  warehouseAddressNeeded: boolean
  intro: SetupIntro
  /** The signed-in user's name (the gate's "Welcome, …"). */
  name: string | null
}

const OPEN: SetupState = { step: null, warehouseAddressNeeded: false, intro: null, name: null }

let cache: { token: string; promise: Promise<SetupState> } | null = null

export function loadSetupState(): Promise<SetupState> {
  const token = getAccessToken()
  if (!token) return Promise.resolve(OPEN)
  if (cache?.token !== token) {
    const promise = request<{ setupStep?: unknown; warehouseAddressNeeded?: unknown; setupIntro?: unknown; name?: unknown } | null>('/me')
      .then(me => ({
        step: (me?.setupStep === 'shopify' || me?.setupStep === 'address') ? me.setupStep : null,
        warehouseAddressNeeded: me?.warehouseAddressNeeded === true,
        intro: (me?.setupIntro === 'courier' || me?.setupIntro === 'ready') ? me.setupIntro : null,
        name: typeof me?.name === 'string' && me.name.trim() ? me.name.trim() : null,
      }) as SetupState)
      .catch(() => OPEN)
    cache = { token, promise }
  }
  return cache.promise
}

/** Forget the cached answer (after saving the address, on log out, or on the gate's own reload) — and the
 *  "Shopify step seen" mark, which only matters until the address is saved. */
export function refreshSetupStep() {
  cache = null
  clearShopifyStepSeen()
}

// The intro redirect happens at most once per screen per page load, so a failed pref write can never
// send an owner round in circles (the gate never locks anyone out).
const introRedirected = new Set<string>()

/** True the first time this page load asks for `intro` — and then never again for it. */
export function takeIntroRedirect(intro: Exclude<SetupIntro, null>): boolean {
  if (introRedirected.has(intro)) return false
  introRedirected.add(intro)
  return true
}

/** Tests only: forget the once-per-load intro redirects. */
export function forgetIntroRedirects() {
  introRedirected.clear()
}

/** Set on this device while the owner sees the Shopify step, so the gate shows "Connected" once they're back
 *  (Flow-1). An App Store signup arrives connected and never saw that step: it starts at the address. */
const SHOPIFY_STEP_SEEN = 'traced.setup.shopifyStepSeen'

export function markShopifyStepSeen() {
  try { localStorage.setItem(SHOPIFY_STEP_SEEN, '1') } catch { /* private mode */ }
}

export function shopifyStepSeen(): boolean {
  try { return localStorage.getItem(SHOPIFY_STEP_SEEN) === '1' } catch { return false }
}

export function clearShopifyStepSeen() {
  try { localStorage.removeItem(SHOPIFY_STEP_SEEN) } catch { /* private mode */ }
}

/** undefined while loading. */
export function useSetupState(): SetupState | undefined {
  const [state, setState] = useState<SetupState | undefined>(undefined)
  useEffect(() => {
    let cancelled = false
    loadSetupState().then(s => { if (!cancelled) setState(s) })
    return () => { cancelled = true }
  }, [])
  return state
}
