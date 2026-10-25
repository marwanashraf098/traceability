import { useEffect, useState } from 'react'
import { getAccessToken } from './auth'
import { request, type SetupStep } from './api'

/**
 * Onboarding gate (V185) — the tenant's setupStep from GET /me ('shopify' | 'address' | null; the backend's
 * SetupStatus is the one rule, demo and review tenants are null there). One /me request per access token,
 * shared by every gated route; {@link refreshSetupStep} forgets it after a step is done. A failed /me reads
 * as "not gated" — the gate never locks anyone out on an error.
 */

export interface SetupState {
  step: SetupStep
  warehouseAddressNeeded: boolean
}

const OPEN: SetupState = { step: null, warehouseAddressNeeded: false }

let cache: { token: string; promise: Promise<SetupState> } | null = null

export function loadSetupState(): Promise<SetupState> {
  const token = getAccessToken()
  if (!token) return Promise.resolve(OPEN)
  if (cache?.token !== token) {
    const promise = request<{ setupStep?: unknown; warehouseAddressNeeded?: unknown } | null>('/me')
      .then(me => ({
        step: (me?.setupStep === 'shopify' || me?.setupStep === 'address') ? me.setupStep : null,
        warehouseAddressNeeded: me?.warehouseAddressNeeded === true,
      }) as SetupState)
      .catch(() => OPEN)
    cache = { token, promise }
  }
  return cache.promise
}

/** Forget the cached answer (after saving the address, or on the gate's own reload). */
export function refreshSetupStep() {
  cache = null
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
