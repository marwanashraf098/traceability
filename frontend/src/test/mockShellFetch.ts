import { vi } from 'vitest'

/**
 * Any page rendered inside the shared Layout component (sidebar + topbar)
 * triggers Layout's own background GET /me and GET /exceptions/count fetches
 * on mount (real identity in the sidebar, exceptions-count notification bell —
 * see components/Layout.tsx). Those go through the SAME global fetch a test
 * stubs for the page's own calls, so without this a sequential
 * mock.mockReturnValueOnce() queue silently loses a response to whichever of
 * these fires first and desyncs every response after it — this produced a
 * false "10 of 11 tests failed" in fulfill.test.tsx once its queue view
 * started rendering inside Layout.
 *
 * Call this from a test file's own beforeEach, LAST, after building the
 * page's own fetch mock — it wraps that mock and intercepts only these two
 * URLs before they ever reach it, so the page's own sequential queue is
 * never touched by Layout's background calls. No effect on any other URL.
 *
 * If Layout ever grows more background calls, add their paths to SHELL_PATHS.
 */

const SHELL_PATHS = ['/me', '/exceptions/count']

function isShellPath(url: string): boolean {
  return SHELL_PATHS.some(path => url.endsWith(path) || url.includes(path))
}

// api.ts's shared request() (used by Layout, unlike a page's own local fetch
// wrapper if it has one) also reads res.headers — a bare {ok,status,json} fake
// isn't enough, so this always returns a fuller stand-in.
function shellDefaultResponse() {
  return Promise.resolve({
    ok: true,
    status: 200,
    headers: { get: (k: string) => (k === 'content-type' ? 'application/json' : null) },
    json: async () => ({}),
  })
}

export function stubFetchWithShellDefaults(
  appFetch: (url: string, opts?: RequestInit) => unknown,
): void {
  vi.stubGlobal('fetch', (url: string, opts?: RequestInit) => {
    if (typeof url === 'string' && isShellPath(url)) return shellDefaultResponse()
    return appFetch(url, opts)
  })
}
