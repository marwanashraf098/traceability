import { useCallback, useEffect, useRef } from 'react'
import { useTranslation } from 'react-i18next'
import type { ScanMeta, ScanOutcome, UseScannerResult } from '../hooks/useScanner'
import { usePhoneScanApi, type PhoneTargetHandle } from './PhoneScanProvider'

// ── Q1 — a scanning screen's opt-in to phone scans ───────────────────────────
//
// useScanner itself is unchanged. A screen built on it opts in like this:
//
//   const phone = usePhoneScanTarget({ label, describe, paused })
//   const scanner = phone.attach(useScanner({ onScan: phone.wrap(onScan), focusPaused }))
//
// (onScan has to be wrapped before useScanner gets it, so the scanner is attached after.)
//   • mounted → the screen is pushed on the tablet's target stack; unmounted → popped, and every
//     phone scan it was handed but never started is answered "Not applied — scan again";
//   • phone scans arrive through scanner.handleScan(code, {relayEventId}) — the same queue, one
//     in flight, in order, as the station's own scanner (a code being typed is kept);
//   • the wrapped onScan answers each phone scan exactly once with describe(code, outcome),
//     read after the render that holds the scan's own state updates (so describe sees them);
//   • the attached scanner's clearQueue() answers every phone scan it drops "Not applied";
//   • `paused` (a dialog that doesn't take scans is open) → the provider rejects phone scans
//     "Tablet busy — finish the dialog" instead of queueing them.
// Without a PhoneScanProvider (unit tests of a screen) everything is a pass-through.

export interface PhoneScanTargetOptions {
  /** Shown in the phone header ("Pick & Pack · #1047"). */
  label: string
  /** The one line the phone shows for a finished phone scan (default: Done / Not accepted). */
  describe?: (code: string, outcome: ScanOutcome) => string | null | undefined
  paused?: boolean
}

type OnScan = (code: string, meta?: ScanMeta) => Promise<ScanOutcome>

export interface PhoneScanTarget {
  /** The screen's onScan, answering each phone scan once. Stable for a stable onScan. */
  wrap: (onScan: OnScan) => OnScan
  /** The scanner the screen uses: the same, with a clearQueue that answers dropped phone scans. */
  attach: (scanner: UseScannerResult) => UseScannerResult
}

const eventIdOf = (meta?: ScanMeta) => (typeof meta?.relayEventId === 'string' ? meta.relayEventId : null)

/**
 * Registers a phone-scan target for as long as the calling component is mounted. Low level —
 * screens on useScanner use usePhoneScanTarget; a dialog that takes scans another way
 * (AwbLinkDialog) uses this with its own deliver.
 */
export function usePhoneScanRegistration({ label, paused = false, deliver }: {
  label: string
  paused?: boolean
  deliver: (code: string, eventId: string) => void
}) {
  const api = usePhoneScanApi()
  const handle = useRef<PhoneTargetHandle>({ label, paused, deliver })
  handle.current = { label, paused, deliver }

  useEffect(() => {
    if (!api) return
    return api.register(() => handle.current)
  }, [api])

  useEffect(() => { api?.touch() }, [api, label, paused])

  return api
}

export function usePhoneScanTarget({ label, describe, paused = false }: PhoneScanTargetOptions): PhoneScanTarget {
  const { t } = useTranslation()
  const scannerRef = useRef<UseScannerResult | null>(null)
  const describeRef = useRef(describe)
  describeRef.current = describe
  const tRef = useRef(t)
  tRef.current = t
  const mounted = useRef(true)
  /** Phone scans whose onScan has finished, waiting for the render that holds their state. */
  const finished = useRef<{ eventId: string; code: string; outcome: ScanOutcome }[]>([])

  const deliver = useCallback((code: string, eventId: string) => {
    const s = scannerRef.current
    const input = s?.inputRef.current ?? null
    const typed = input?.value ?? ''
    if (!s) return                                    // unregistering answers it "Not applied"
    void s.handleScan(code, { relayEventId: eventId })
    if (input && typed) input.value = typed           // a phone scan never wipes a scan being typed here
  }, [])

  const api = usePhoneScanRegistration({ label, paused, deliver })

  const message = useCallback((code: string, outcome: ScanOutcome) =>
    describeRef.current?.(code, outcome) || tRef.current(outcome.success ? 'phone.accepted' : 'phone.rejected'), [])

  useEffect(() => {
    mounted.current = true
    return () => {
      mounted.current = false
      for (const f of finished.current.splice(0)) api?.answer(f.eventId, f.outcome.success, message(f.code, f.outcome))
    }
  }, [api, message])

  // After every render: answer the phone scans whose onScan finished before it.
  useEffect(() => {
    if (!api || finished.current.length === 0) return
    for (const f of finished.current.splice(0)) api.answer(f.eventId, f.outcome.success, message(f.code, f.outcome))
  })

  const lastWrap = useRef<{ inner: OnScan; outer: OnScan } | null>(null)
  const wrap = useCallback((inner: OnScan): OnScan => {
    if (lastWrap.current?.inner === inner) return lastWrap.current.outer
    const outer: OnScan = async (code, meta) => {
      const eventId = eventIdOf(meta)
      if (!eventId || !api) return inner(code, meta)
      api.started(eventId)
      let outcome: ScanOutcome
      try {
        outcome = await inner(code, meta)
      } catch (e) {
        api.answer(eventId, false, tRef.current('phone.rejected'))
        throw e
      }
      if (mounted.current) finished.current.push({ eventId, code, outcome })
      else api.answer(eventId, outcome.success, message(code, outcome))
      return outcome
    }
    lastWrap.current = { inner, outer }
    return outer
  }, [api, message])

  const clearQueue = useCallback(() => {
    const dropped = scannerRef.current?.clearQueue() ?? []
    for (const meta of dropped) {
      const eventId = eventIdOf(meta)
      if (eventId) api?.answer(eventId, false, tRef.current('phone.notApplied'))
    }
    return dropped
  }, [api])

  const attach = useCallback((scanner: UseScannerResult): UseScannerResult => {
    scannerRef.current = scanner
    return { ...scanner, clearQueue }
  }, [clearQueue])

  return { wrap, attach }
}
