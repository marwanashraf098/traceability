import { getAccessToken } from '../../auth'
import { refreshAccessToken, RelayScanEvent, ScanPairingStatus } from '../../api'

// S6 — the tablet's side of the phone relay: GET /pack-sessions/{id}/relay-stream as Server-Sent
// Events. Read with fetch rather than EventSource because EventSource can't send the Bearer
// token (the app keeps it in memory, not in a cookie). Behaves like EventSource: parses
// `event:` / `data:` frames, ignores comments (the 20 s heartbeat), and reconnects on its own
// (1 s, 2 s, 4 s … up to 10 s), refreshing the access token after a 401. Stops for good on a
// 403 / 404 / 409 (not this worker's session, or the session ended).

export interface RelayHandlers {
  onScan: (event: RelayScanEvent) => void
  onPairing: (status: ScanPairingStatus) => void
  /** true once the stream is open, false whenever it drops (it is retried). */
  onConnection: (up: boolean) => void
}

const STOP_STATUSES = new Set([403, 404, 409])

/** Opens the stream; returns a function that closes it. */
export function openRelayStream(sessionId: string, handlers: RelayHandlers): () => void {
  let stopped = false
  let controller: AbortController | null = null
  let attempt = 0

  const wait = (ms: number) => new Promise(r => setTimeout(r, ms))

  async function run() {
    while (!stopped) {
      controller = new AbortController()
      try {
        const token = getAccessToken()
        const res = await fetch(`/api/v1/pack-sessions/${sessionId}/relay-stream`, {
          headers: { Accept: 'text/event-stream', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
          credentials: 'include',
          signal: controller.signal,
        })
        if (res.status === 401) {
          await refreshAccessToken().catch(() => {})
        } else if (STOP_STATUSES.has(res.status)) {
          stopped = true
          handlers.onConnection(false)
          return
        } else if (res.ok && res.body) {
          attempt = 0
          handlers.onConnection(true)
          await readFrames(res.body, (name, data) => {
            try {
              if (name === 'scan') handlers.onScan(JSON.parse(data) as RelayScanEvent)
              else if (name === 'pairing') handlers.onPairing(JSON.parse(data) as ScanPairingStatus)
            } catch { /* a malformed frame is skipped */ }
          })
        }
      } catch {
        // network drop / abort — retried below unless stopped
      }
      if (stopped) return
      handlers.onConnection(false)
      await wait(Math.min(1000 * 2 ** attempt++, 10_000))
    }
  }

  void run()
  return () => {
    stopped = true
    controller?.abort()
  }
}

/** Reads SSE frames until the stream ends. */
async function readFrames(body: ReadableStream<Uint8Array>, onFrame: (name: string, data: string) => void) {
  const reader = body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  let name = 'message'
  let data: string[] = []
  for (;;) {
    const { value, done } = await reader.read()
    if (done) return
    buffer += decoder.decode(value, { stream: true })
    let nl: number
    while ((nl = buffer.indexOf('\n')) >= 0) {
      const line = buffer.slice(0, nl).replace(/\r$/, '')
      buffer = buffer.slice(nl + 1)
      if (line === '') {
        if (data.length > 0) onFrame(name, data.join('\n'))
        name = 'message'
        data = []
      } else if (line.startsWith(':')) {
        // comment (heartbeat)
      } else if (line.startsWith('event:')) {
        name = line.slice(6).trim()
      } else if (line.startsWith('data:')) {
        data.push(line.slice(5).replace(/^ /, ''))
      }
    }
  }
}
