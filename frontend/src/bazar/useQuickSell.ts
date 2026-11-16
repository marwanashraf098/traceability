import { useCallback, useEffect, useRef, useState } from 'react'
import { BazarError, newRequestId, sellOnce, type BazarSale, type PaymentMethod } from './api'

// Bazar POS B6 — one quick sale per scan, the phone's state machine.
//
//   scan → sending → SOLD (green card)            the server said yes (a replay of the same request id counts: it went through)
//                  → error                         the server said no (its code → clear copy)
//                  → checking … (same request id)  no definite answer (no response / timeout / 5xx): ask again, with the
//                                                  SAME client_request_id, until the server answers — never a success
//                                                  before it confirms, never a second sale for one scan.
//
// The caller runs onScan inside useScanner's single-flight worker, so one sale is in flight at a time; scans of OTHER
// pieces wait in its queue. A scan of a code that is already in flight or waiting is dropped (enqueue → false) — a
// second request for the same piece never starts while one is pending.

export type SellCard =
  | { kind: 'idle' }
  | { kind: 'sending'; code: string }
  | { kind: 'checking'; code: string; attempts: number }
  | { kind: 'sold'; sale: BazarSale; shownAt: number }
  | { kind: 'undone'; sale: BazarSale }
  | { kind: 'error'; code: string; error: BazarError }

/** Retry timing — exported so tests can make it instant. */
export const sellTiming = {
  /** One attempt waits this long for an answer before it counts as "no answer". */
  timeoutMs: 10_000,
  /** Waits between attempts (the last one repeats). */
  retryDelaysMs: [1_000, 2_000, 4_000, 5_000],
  /** The SOLD card offers Undo for this long (the recent list keeps offering it while the API allows). */
  undoCardMs: 8_000,
}

const sleep = (ms: number) => new Promise<void>(r => setTimeout(r, ms))

export function useQuickSell({ eventId, method, onSold }: {
  eventId: string
  /** The method a new sale is recorded with (the seller changes it on the SOLD card). */
  method: PaymentMethod
  onSold?: (sale: BazarSale) => void
}) {
  const [card, setCard] = useState<SellCard>({ kind: 'idle' })
  const mounted = useRef(true)
  useEffect(() => { mounted.current = true; return () => { mounted.current = false } }, [])
  /** Codes in flight or waiting in the scanner's queue. */
  const active = useRef(new Set<string>())
  const onSoldRef = useRef(onSold)
  onSoldRef.current = onSold

  /** Accept a scan (true) or drop it because that piece is already being sold (false). */
  const enqueue = useCallback((raw: string): boolean => {
    const code = raw.trim()
    if (!code || active.current.has(code)) return false
    active.current.add(code)
    return true
  }, [])

  /** One scan → one sale. Resolves only once the server has answered. */
  const sell = useCallback(async (raw: string): Promise<{ success: boolean }> => {
    const code = raw.trim()
    const requestId = newRequestId()
    try {
      setCard({ kind: 'sending', code })
      let attempts = 0
      for (;;) {
        try {
          const sale = await sellOnce(eventId, code, requestId, method, sellTiming.timeoutMs)
          if (!mounted.current) return { success: true }
          setCard({ kind: 'sold', sale, shownAt: Date.now() })
          onSoldRef.current?.(sale)
          return { success: true }
        } catch (e) {
          if (e instanceof BazarError) {
            if (mounted.current) setCard({ kind: 'error', code, error: e })
            return { success: false }
          }
          if ((e as Error)?.message === 'Unauthenticated') throw e
          if (!mounted.current) return { success: false }
          attempts += 1
          setCard({ kind: 'checking', code, attempts })
          const delays = sellTiming.retryDelaysMs
          await sleep(delays[Math.min(attempts - 1, delays.length - 1)] ?? 1_000)
          if (!mounted.current) return { success: false }
        }
      }
    } finally {
      active.current.delete(code)
    }
  }, [eventId, method])

  /** The SOLD card's sale changed (method / customer edit) or was undone. */
  const updateSale = useCallback((sale: BazarSale) => {
    setCard(c => {
      if ((c.kind !== 'sold' && c.kind !== 'undone') || c.sale.id !== sale.id) return c
      return sale.status === 'undone' ? { kind: 'undone', sale } : { ...c, sale }
    })
  }, [])

  const busy = card.kind === 'sending' || card.kind === 'checking'
  return { card, enqueue, sell, updateSale, busy }
}
