/**
 * In-memory access token store. The token lives only for the lifetime of the
 * JS execution context — a page reload clears it, which is correct: RequireAuth
 * calls POST /api/v1/auth/refresh on every reload to restore it from the httpOnly cookie.
 *
 * The refresh token is NEVER in this module or any frontend storage; it lives
 * exclusively in the traced_refresh httpOnly cookie and is invisible to JS.
 */

let _accessToken: string | null = null

export function getAccessToken(): string | null {
  return _accessToken
}

export function setAccessToken(token: string): void {
  _accessToken = token
}

export function clearAccessToken(): void {
  _accessToken = null
}
