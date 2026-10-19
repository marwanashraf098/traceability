import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import { authFetch } from '../api'
import { setAccessToken, getAccessToken, clearAccessToken } from '../auth'

// authFetch — the 401 handling the worker screens' own helpers (Returns, Receiving, Fulfill) now share
// with request(): refresh once, retry once with the new token, /login only if the refresh fails.

const res = (status: number, body: unknown = {}) => Promise.resolve(new Response(JSON.stringify(body), {
  status, headers: { 'content-type': 'application/json' },
}))

describe('authFetch', () => {
  let fetchFn: ReturnType<typeof vi.fn>
  beforeEach(() => { setAccessToken('old-token') })
  afterEach(() => { vi.unstubAllGlobals(); clearAccessToken() })

  test('an expired token is refreshed and the call retried with the new one; caller headers kept', async () => {
    fetchFn = vi.fn((url: string, opts: RequestInit = {}) => {
      if (url.endsWith('/auth/refresh')) return res(200, { accessToken: 'new-token' })
      const auth = (opts.headers as Record<string, string>).Authorization
      return auth === 'Bearer new-token' ? res(200, { ok: true }) : res(401)
    })
    vi.stubGlobal('fetch', fetchFn)

    const r = await authFetch('/returns/sessions/rs-1/scan', {
      method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: 'Bearer old-token' }, body: '{}',
    })

    expect(r.status).toBe(200)
    expect(getAccessToken()).toBe('new-token')
    const scanCalls = fetchFn.mock.calls.filter(([u]) => String(u).endsWith('/scan'))
    expect(scanCalls).toHaveLength(2)
    expect((scanCalls[1][1].headers as Record<string, string>)['Content-Type']).toBe('application/json')
  })

  test('a failed refresh logs out instead of looping', async () => {
    fetchFn = vi.fn((url: string) => (url.endsWith('/auth/refresh') ? res(401) : res(401)))
    vi.stubGlobal('fetch', fetchFn)
    await expect(authFetch('/fulfill/queue')).rejects.toThrow('Unauthenticated')
    expect(getAccessToken()).toBeNull()
    expect(fetchFn.mock.calls.filter(([u]) => String(u).endsWith('/fulfill/queue'))).toHaveLength(1)
  })
})
