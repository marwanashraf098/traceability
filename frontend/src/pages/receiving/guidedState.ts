import type { ReceivingMode } from './ReceivingModeCards'

/**
 * Onboarding O4 — which receiving session is the guided first one, for this browser tab (sessionStorage; nothing is
 * stored on the server). It lets the guided flow come back after a reload or after High control's full-screen scan
 * loop (which returns to /receiving?session=<id>), and it is cleared when Flow-7 is left — so Flow-7 shows once.
 * Storage may be blocked: every access is wrapped and a failure reads as "no guided session".
 */
export interface GuidedSession {
  id: string
  mode: ReceivingMode
  /** Shopify on-hand at Traced Main for the suggested variant before the session (null when unknown). */
  mainBefore: number | null
}

const KEY = 'traced.guidedReceiving'

export function readGuidedSession(): GuidedSession | null {
  try {
    const raw = sessionStorage.getItem(KEY)
    if (!raw) return null
    const v = JSON.parse(raw) as GuidedSession
    return v && typeof v.id === 'string' && typeof v.mode === 'string' ? v : null
  } catch {
    return null
  }
}

export function writeGuidedSession(v: GuidedSession) {
  try { sessionStorage.setItem(KEY, JSON.stringify(v)) } catch { /* storage blocked: no resume after reload */ }
}

export function clearGuidedSession() {
  try { sessionStorage.removeItem(KEY) } catch { /* storage blocked */ }
}

/** Per session: the guide panel was hidden ("Hide guide" — for this session only). */
export function guideHidden(sessionId: string): boolean {
  try { return sessionStorage.getItem(`${KEY}.hidden.${sessionId}`) === '1' } catch { return false }
}

export function setGuideHidden(sessionId: string, hidden: boolean) {
  try {
    if (hidden) sessionStorage.setItem(`${KEY}.hidden.${sessionId}`, '1')
    else sessionStorage.removeItem(`${KEY}.hidden.${sessionId}`)
  } catch { /* storage blocked: the state still holds for this page */ }
}
