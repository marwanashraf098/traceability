import { useCallback, useEffect, useRef, useState } from 'react'

// ── FR-21 Step 6.2 — transport-agnostic shared scanner ──────────────────────
//
// Sourced faithfully from Fulfill.tsx's PickScreen (the live pick-and-pack scan
// path) — every block below marked SAFETY-CRITICAL is copied verbatim from
// there, not rewritten. PickScreen and AwbLinkDialog are NOT touched by this
// file and are NOT rewired onto it — they keep their own inline copies (the
// live pick path is load-bearing; migrating them is a separate, gated refactor
// with its own test pass). This hook is UX only: it does no fetching. Callers
// supply an `onScan(barcode)` callback that does the actual network call and
// returns whether it counts as a "success" flash/beep or a "fail" flash/beep —
// stock-take's classification (match/mismatch/unexpected/unknown) collapses
// onto this same success/fail signal the same way PickScreen's ScanResult does.

export type FlashState = 'idle' | 'success' | 'error'

export interface ScanOutcome {
  success: boolean
  /** Optional caller-supplied label shown on the recent-scans strip (e.g. a
   *  classification like "matched" / "flagged") — the hook itself is agnostic
   *  to what it means, it just carries it through. */
  label?: string
  /** Optional caller-supplied payload (e.g. a resolved pieceId) carried through
   *  to the matching RecentScan entry untouched — the hook never reads it. */
  data?: unknown
}

export interface RecentScan {
  key: string
  barcode: string
  success: boolean
  label?: string
  data?: unknown
}

export interface UseScannerOptions {
  onScan: (barcode: string) => Promise<ScanOutcome>
  recentScansLimit?: number
}

export interface UseScannerResult {
  inputRef: React.RefObject<HTMLInputElement>
  flash: FlashState
  scanning: boolean
  recentScans: RecentScan[]
  handleScan: (barcode: string) => Promise<void>
  removeRecentScan: (key: string) => void
  clearRecentScans: () => void
}

// SAFETY-CRITICAL — do not modify: Web Audio success/fail beep, copied verbatim
// from Fulfill.tsx's module-private playBeep().
function playBeep(success: boolean) {
  try {
    const ctx = new AudioContext()
    const osc = ctx.createOscillator()
    const gain = ctx.createGain()
    osc.connect(gain)
    gain.connect(ctx.destination)
    osc.frequency.setValueAtTime(success ? 880 : 300, ctx.currentTime)
    osc.type = 'sine'
    gain.gain.setValueAtTime(0.3, ctx.currentTime)
    gain.gain.exponentialRampToValueAtTime(0.001, ctx.currentTime + (success ? 0.15 : 0.4))
    osc.start(ctx.currentTime)
    osc.stop(ctx.currentTime + (success ? 0.15 : 0.4))
  } catch {
    // AudioContext may be blocked — silent fallback
  }
}

export function useScanner({ onScan, recentScansLimit = 20 }: UseScannerOptions): UseScannerResult {
  const inputRef = useRef<HTMLInputElement>(null)
  const [flash, setFlash] = useState<FlashState>('idle')
  const [scanning, setScanning] = useState(false)
  const [recentScans, setRecentScans] = useState<RecentScan[]>([])

  // SAFETY-CRITICAL — HID refocus: re-focuses scan input on any click; copied
  // verbatim from PickScreen (there it re-runs on `[order]`; here there is no
  // per-order reset concept, so it runs once on mount — same click-refocus
  // behavior for the input's whole lifetime).
  useEffect(() => {
    const refocus = () => {
      if (document.activeElement !== inputRef.current) inputRef.current?.focus()
    }
    document.addEventListener('click', refocus)
    inputRef.current?.focus()
    return () => document.removeEventListener('click', refocus)
  }, [])

  // SAFETY-CRITICAL — flash trigger: do not modify. Copied verbatim from PickScreen.
  const triggerFlash = useCallback((state: 'success' | 'error') => {
    setFlash(state)
    setTimeout(() => setFlash('idle'), 600)
  }, [])

  // SAFETY-CRITICAL — scan handler shape (trim/guard/scanning-lock/beep/flash/
  // refocus-and-clear-on-finally) copied verbatim from PickScreen.handleScan().
  // The one deliberate difference: PickScreen calls its own inline api() fetch
  // helper directly; this hook calls the caller-supplied onScan() instead, so
  // it never does network I/O itself (transport-agnostic, per 6.2).
  const handleScan = useCallback(async (barcode: string) => {
    const trimmed = barcode.trim()
    if (!trimmed || scanning) return
    setScanning(true)
    try {
      const result = await onScan(trimmed)
      if (result.success) {
        playBeep(true)
        triggerFlash('success')
      } else {
        playBeep(false)
        triggerFlash('error')
      }
      setRecentScans(prev =>
        [{ key: `${Date.now()}-${trimmed}`, barcode: trimmed, success: result.success,
           label: result.label, data: result.data }, ...prev]
          .slice(0, recentScansLimit)
      )
    } catch {
      playBeep(false)
      triggerFlash('error')
    } finally {
      setScanning(false)
      if (inputRef.current) { inputRef.current.value = ''; inputRef.current.focus() }
    }
  }, [scanning, onScan, triggerFlash, recentScansLimit])

  function removeRecentScan(key: string) {
    setRecentScans(prev => prev.filter(s => s.key !== key))
  }

  function clearRecentScans() {
    setRecentScans([])
  }

  return { inputRef, flash, scanning, recentScans, handleScan, removeRecentScan, clearRecentScans }
}
