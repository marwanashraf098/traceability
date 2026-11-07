import { useEffect, useState } from 'react'
import { getOnboardingProgress, patchOnboardingPrefs, type OnboardingProgress } from './api'

/**
 * Onboarding O3 — GET /onboarding/progress, shared by Overview's Get started card and Layout's Setup chip / sidebar
 * dots: readers mounting together share ONE request (in-flight dedupe — Layout remounts on every route, so each
 * screen asks once, like the old /onboarding/status chip did), and one state for every reader. A failed or malformed answer reads as
 * "nothing to show" (null) — the card, chip and dots simply stay away. Owner / manager only (a worker gets 403).
 */

type Listener = (p: OnboardingProgress | null) => void

let inflight: Promise<OnboardingProgress | null> | null = null
let current: OnboardingProgress | null = null
const listeners = new Set<Listener>()

function valid(p: unknown): p is OnboardingProgress {
  const v = p as OnboardingProgress | null
  return !!v && Array.isArray(v.steps) && Array.isArray(v.tasks) && (v.card === 'full' || v.card === 'slim' || v.card === 'hidden')
}

function publish(p: OnboardingProgress | null) {
  current = p
  listeners.forEach(l => l(p))
}

export function loadOnboardingProgress(): Promise<OnboardingProgress | null> {
  if (!inflight) {
    inflight = getOnboardingProgress()
      .then(p => (valid(p) ? p : null))
      .catch(() => null)
      .then(p => { publish(p); return p })
      .finally(() => { inflight = null })
  }
  return inflight
}

/** Tests only: forget the last answer and any request in flight. */
export function forgetOnboardingProgress() {
  inflight = null
  current = null
}

/**
 * Write the card state (Minimise / × / Show): shown at once, then PATCH /onboarding/prefs. The server's answer
 * replaces the guess; a failed write keeps the guess for this page load (the card is a convenience, never a lock).
 */
export function setCardState(card: OnboardingProgress['card']) {
  if (current) publish({ ...current, card, cardChosen: true })
  patchOnboardingPrefs({ card })
    .then(p => { if (valid(p)) publish(p) })
    .catch(() => { /* keep the local choice */ })
}

/** undefined while loading, null when there is nothing to show. `enabled` false → never asks. */
export function useOnboardingProgress(enabled = true): OnboardingProgress | null | undefined {
  const [state, setState] = useState<OnboardingProgress | null | undefined>(enabled ? undefined : null)
  useEffect(() => {
    if (!enabled) { setState(null); return }
    let alive = true
    const l: Listener = p => { if (alive) setState(p) }
    listeners.add(l)
    loadOnboardingProgress().then(p => { if (alive) setState(p) })
    return () => { alive = false; listeners.delete(l) }
  }, [enabled])
  return state
}

/** Setup steps done (of the five). */
export function setupDone(p: OnboardingProgress): number {
  return p.steps.filter(s => s.done).length
}
