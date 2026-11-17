import { useCallback, useEffect, useRef, useState } from 'react'
import { BazarError, newRequestId } from './api'
import { sellTiming } from './useQuickSell'

// Bazar POS B3 — a return / exchange is sent like a sale (useQuickSell's rules):
//   confirm → sending → DONE                 the server said yes (a replay of the same request id counts)
//                     → error                the server said no (its code → clear copy)
//                     → checking … (same id) no definite answer (no response / timeout / 5xx): ask again with the
//                                            SAME client_request_id until the server answers — never a success
//                                            before it confirms, never a second return / exchange for one tap.

export type CommandState<T> =
  | { kind: 'idle' }
  | { kind: 'sending' }
  | { kind: 'checking'; attempts: number }
  | { kind: 'done'; result: T }
  | { kind: 'error'; error: BazarError }

const sleep = (ms: number) => new Promise<void>(r => setTimeout(r, ms))

export function useRetryingCommand<T>() {
  const [state, setState] = useState<CommandState<T>>({ kind: 'idle' })
  const mounted = useRef(true)
  const running = useRef(false)
  useEffect(() => { mounted.current = true; return () => { mounted.current = false } }, [])

  /** {@code attempt} is one try with the given request id and timeout; it is called again with the same id. */
  const run = useCallback(async (attempt: (requestId: string, timeoutMs: number) => Promise<T>) => {
    if (running.current) return                       // one command at a time: a second tap starts nothing
    running.current = true
    const requestId = newRequestId()
    try {
      setState({ kind: 'sending' })
      let attempts = 0
      for (;;) {
        try {
          const result = await attempt(requestId, sellTiming.timeoutMs)
          if (mounted.current) setState({ kind: 'done', result })
          return
        } catch (e) {
          if (e instanceof BazarError) {
            if (mounted.current) setState({ kind: 'error', error: e })
            return
          }
          if ((e as Error)?.message === 'Unauthenticated') throw e
          if (!mounted.current) return
          attempts += 1
          setState({ kind: 'checking', attempts })
          const delays = sellTiming.retryDelaysMs
          await sleep(delays[Math.min(attempts - 1, delays.length - 1)] ?? 1_000)
          if (!mounted.current) return
        }
      }
    } finally {
      running.current = false
    }
  }, [])

  const reset = useCallback(() => { if (!running.current) setState({ kind: 'idle' }) }, [])
  const busy = state.kind === 'sending' || state.kind === 'checking'
  return { state, run, reset, busy }
}
