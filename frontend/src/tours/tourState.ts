import { useEffect, useState } from 'react'
import { getAccessToken } from '../auth'
import { getMe, getRoleFromToken, recordUiState } from '../api'
import { loadOnboardingProgress } from '../onboardingProgress'
import { canSeeTour, TOURS, type TourId } from './tours'

/**
 * Onboarding O5 — which tour is showing (one at a time, app-wide), whether one should start by itself, and
 * "done" (Skip or Done → POST /me/ui-state {tourDone}, fire-and-forget).
 *
 * The active tour is a module store, not page state, so a tour started on a list page keeps its step when the user
 * opens the full-screen scan screen (the card follows them there).
 *
 * Auto-start: only when (1) the signed-in user may see the tour, (2) the TENANT has never done that action, and
 * (3) this user hasn't finished or skipped it (GET /me uiState.toursDone, read with the CURRENT token — on a station
 * tablet that's the switched-in worker's). Tours done this page load count at once. (2) comes from progress tasks for
 * owners / managers; for a worker (progress is 403) and in station mode, pickups / scan_returns read /me's
 * hasEverClosedPickup / hasEverScannedReturn instead (booleans only, every role).
 */

export interface ActiveTour { id: TourId | 'pick_pack_mode'; step: number; replay: boolean }

let active: ActiveTour | null = null
const listeners = new Set<(a: ActiveTour | null) => void>()
const doneThisLoad = new Set<string>()
interface MeTours { done: string[]; everPickup: boolean | null; everReturn: boolean | null }
let meCache: { token: string | null; me: Promise<MeTours | null> } | null = null

function publish(a: ActiveTour | null) {
  active = a
  listeners.forEach(l => l(a))
}

export function startTour(id: ActiveTour['id'], replay = false) {
  publish({ id, step: 0, replay })
}

/** Auto-start: never over a tour that is already showing. */
export function startTourIfIdle(id: TourId) {
  if (!active) publish({ id, step: 0, replay: false })
}

export function goToStep(step: number) {
  if (active) publish({ ...active, step })
}

/** Close the card. `record` (Skip / Done): this user won't get it by itself again. */
export function endTour(record: boolean) {
  const a = active
  publish(null)
  if (a && record && a.id !== 'pick_pack_mode') markTourDone(a.id)
}

export function markTourDone(id: TourId) {
  doneThisLoad.add(id)
  recordUiState({ tourDone: id }).catch(() => { /* the tour may show once more next session — harmless */ })
}

export function useActiveTour(): ActiveTour | null {
  const [a, setA] = useState(active)
  useEffect(() => {
    listeners.add(setA)
    setA(active)
    return () => { listeners.delete(setA) }
  }, [])
  return a
}

/** This user's finished tours and the store's two tour signals (GET /me with the current token, once per token). */
function meTours(): Promise<MeTours | null> {
  const token = getAccessToken()
  if (!meCache || meCache.token !== token) {
    meCache = {
      token,
      me: getMe().then(m => (Array.isArray(m?.uiState?.toursDone) ? {
        done: m.uiState!.toursDone,
        everPickup: typeof m.hasEverClosedPickup === 'boolean' ? m.hasEverClosedPickup : null,
        everReturn: typeof m.hasEverScannedReturn === 'boolean' ? m.hasEverScannedReturn : null,
      } : null)).catch(() => null),
    }
  }
  return meCache.me
}

function stationMode(): boolean {
  try { return localStorage.getItem('stationMode') === 'true' } catch { return false }
}

/** Whether `id` should start by itself now (see the header). Never throws; unknown → false. */
export async function shouldAutoStart(id: TourId): Promise<boolean> {
  const role = getRoleFromToken()
  if (!canSeeTour(id, role) || doneThisLoad.has(id)) return false
  const me = await meTours()
  if (!me || me.done.includes(id)) return false
  let tried: boolean | null
  if (role === 'worker' || stationMode()) {
    // Only the two tours every role takes have a /me signal; anything else never starts by itself here.
    tried = id === 'pickups' ? me.everPickup : id === 'scan_returns' ? me.everReturn : null
  } else {
    const progress = await loadOnboardingProgress().catch(() => null)
    const task = progress?.tasks.find(t => t.key === TOURS[id].task)
    tried = task ? task.tried : null
  }
  if (tried !== false) return false
  return !doneThisLoad.has(id)
}

/** Tests only. */
export function forgetTours() {
  active = null
  doneThisLoad.clear()
  meCache = null
  listeners.forEach(l => l(null))
}
