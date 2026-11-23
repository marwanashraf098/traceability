import { getAccessToken } from './auth'

/**
 * Bazar B10 — the browser's ONE session (the traced_refresh cookie) can change under an open tab: another tab
 * redeemed a seller invite, signed in, or PIN-switched. When that happens a tab must never keep acting — or showing a
 * name — as the user it started with (prod 2026-11-22: the owner's Close day page silently ran as the seller and got
 * 403s while the sidebar still said "Owner"). Both signals start the app over as the real current user:
 *   - api.ts doRefresh: a refresh that returns ANOTHER user's token (the cookie changed) → reload, no retry.
 *   - a seller-invite redeem announces itself on a BroadcastChannel → every other signed-in tab reloads at once.
 */
export const sessionSwitch = {
  /** Start over as whoever the cookie belongs to now — every in-memory cache (/me, capabilities, pages) is rebuilt. */
  reload: () => { window.location.replace('/') },
}

const CHANNEL = 'traced-session'

/** The user id (JWT sub) of an access token, or null. */
export function userOfToken(token: string | null): string | null {
  if (!token) return null
  try {
    const payload = JSON.parse(atob(token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')))
    return typeof payload?.sub === 'string' ? payload.sub : null
  } catch {
    return null
  }
}

/** Tell this browser's other tabs that its session now belongs to someone else. */
export function announceSessionChange() {
  try {
    const c = new BroadcastChannel(CHANNEL)
    c.postMessage('changed')
    c.close()
  } catch { /* no BroadcastChannel — the other tabs still notice at their next refresh */ }
}

/** Installed once at startup (main.tsx): a signed-in tab reloads when another tab changed the session. */
export function listenForSessionChanges() {
  try {
    const c = new BroadcastChannel(CHANNEL)
    c.onmessage = () => { if (getAccessToken()) sessionSwitch.reload() }
  } catch { /* no BroadcastChannel */ }
}
