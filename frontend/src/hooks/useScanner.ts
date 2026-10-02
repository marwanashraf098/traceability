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
//
// NO LOST SCANS (approved by Marawan, 2026-10-01 — explicit approval to edit the
// SAFETY-CRITICAL blocks below for exactly this change). The original copy disabled
// the input while a scan was in flight and called focus() in `finally`, before
// React re-enabled it: every keystroke of a scan arriving during the request was
// dropped silently (no beep, no server trace — in stock-take, a piece physically
// scanned could then be written off at finalize), and on desktop Chrome focus left
// the input for good, so every later scan was lost until a tap. Now:
//   • the input is never disabled for scanning (ScanShell) — keystrokes always land;
//   • Enter pushes the trimmed code onto a FIFO queue and clears the input at once;
//   • one worker processes the queue strictly in order, one onScan at a time, each
//     with its own beep/flash/recent-scan entry exactly as before — no parallel
//     requests, no reordering, no dedup (servers already handle repeats). The worker
//     runs from an effect, so each queued scan uses the onScan of the latest render
//     (a screen whose onScan depends on what the previous scan changed sees it);
//   • at most MAX_QUEUED_SCANS waiting — beyond that the scan is refused loudly
//     (error beep + flash + `queueFull` notice), never silently;
//   • `clearQueue()` drops waiting scans (screens call it when queued scans must not
//     be applied); unmounting drops them too; `pending` = how many are waiting;
//   • focus returns to the input after every scan and when the queue drains — only
//     ever while the input is enabled and the screen hasn't paused focus (an open
//     dialog: `focusPaused`). The click-to-refocus listener is unchanged.
// PickScreen still has the old disabled-during-scan pattern — separate, gated fix.
//
// SINGLE FLIGHT (approved by Marawan, 2026-10-02 — explicit approval to edit the
// SAFETY-CRITICAL worker block for exactly this change). The worker used to guard on the
// render-time `scanning` state. React gives updates made inside an effect at most Default
// priority, but an Enter keydown's at Sync priority, and renders the Sync update first
// without the Default ones: two Enters right after the worker started a scan produced a
// render where `scanning` was still false and `pending` had changed, so the worker started
// the next scan while the first was in flight (2 at once — the WebKit scannerBurst flake).
// On PackSessionScreen that sent a piece scan to the waybill endpoint (order not open yet)
// or a waybill to the old order. Now an in-flight ref is the guard, set before the next
// code is taken, and it is released only by the render that commits the scan's
// completion — the same render that holds the finished onScan's own state updates — so
// each queued scan runs with the onScan built from the previous scan's result.
// useScannerRace.browser.test.tsx reproduces the race deterministically.
//
// SCAN METADATA (approved by Marawan, 2026-10-02 — S6 phone as scanner; an additive change to
// the SAFETY-CRITICAL scan handler and worker for exactly this): handleScan(code, meta?) queues
// `meta` with the code and the worker passes it to onScan(code, meta) untouched — the hook never
// reads it. A keyboard / HID scan has no meta and behaves exactly as before. clearQueue() (not
// marked) returns the meta of the scans it drops so the screen can answer for them.

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

/** Caller metadata for one scan (e.g. S6's phone relay event id), carried through the queue to
 *  onScan untouched — the hook never reads it. A keyboard / HID scan has none. */
export type ScanMeta = Readonly<Record<string, unknown>>

export interface UseScannerOptions {
  onScan: (barcode: string, meta?: ScanMeta) => Promise<ScanOutcome>
  recentScansLimit?: number
  /** True while the screen shows a dialog / overlay: the hook won't pull focus back
   *  to the scan input (the click-to-refocus listener is unaffected — dialogs keep
   *  their clicks by stopping propagation, as Modal already does). */
  focusPaused?: boolean
}

/** Scans that may wait behind the one in flight; one more is refused loudly. */
export const MAX_QUEUED_SCANS = 20

export interface UseScannerResult {
  inputRef: React.RefObject<HTMLInputElement>
  flash: FlashState
  /** A scan is being processed (onScan in flight). */
  scanning: boolean
  /** Scans waiting behind the one in flight. */
  pending: number
  /** The last scan was refused because MAX_QUEUED_SCANS were already waiting. */
  queueFull: boolean
  recentScans: RecentScan[]
  /** Queues a scan (trimmed; empty ignored) and clears the input. Resolves once queued.
   *  `meta` (optional) reaches onScan with that scan, untouched. */
  handleScan: (barcode: string, meta?: ScanMeta) => Promise<void>
  /** Drops every waiting scan (not the one in flight); returns the meta of each dropped scan
   *  that had one, so the caller can answer for it (S6: a phone scan must get an outcome). */
  clearQueue: () => ScanMeta[]
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

/** Another place the person may be typing — the scanner never steals focus from it. */
function isTextEntry(el: HTMLElement): boolean {
  if (el.isContentEditable) return true
  if (el instanceof HTMLTextAreaElement || el instanceof HTMLSelectElement) return true
  if (el instanceof HTMLInputElement) {
    return !['button', 'submit', 'reset', 'checkbox', 'radio', 'range', 'color', 'file', 'image'].includes(el.type)
  }
  return false
}

export function useScanner({ onScan, recentScansLimit = 20, focusPaused = false }: UseScannerOptions): UseScannerResult {
  const inputRef = useRef<HTMLInputElement>(null)
  const [flash, setFlash] = useState<FlashState>('idle')
  const [scanning, setScanning] = useState(false)
  const [recentScans, setRecentScans] = useState<RecentScan[]>([])
  // FIFO of scans waiting behind the one in flight. The ref is the source of truth
  // (read synchronously by handleScan and the worker); `pending` mirrors its length
  // for rendering and to wake the worker effect.
  const queueRef = useRef<{ code: string; meta?: ScanMeta }[]>([])
  const [pending, setPending] = useState(0)
  const [queueFull, setQueueFull] = useState(false)
  // Single flight: busyRef is the in-flight guard (set synchronously when a scan starts);
  // startedRef numbers the scans started, `completed` is the number of the last one whose
  // completion has been rendered. `scanning` above is for display only.
  const busyRef = useRef(false)
  const startedRef = useRef(0)
  const [completed, setCompleted] = useState(0)
  const mountedRef = useRef(true)
  useEffect(() => {
    mountedRef.current = true
    return () => { mountedRef.current = false; queueRef.current = [] }
  }, [])

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

  // SAFETY-CRITICAL — scan handler: trim / ignore empty / beep / flash per scan, as
  // copied from PickScreen.handleScan() (calling the caller-supplied onScan instead of
  // a fetch — transport-agnostic, per 6.2). Changed 2026-10-01 (approved, see header):
  // the "scanning" guard that dropped a scan arriving mid-request is replaced by a FIFO
  // queue; the input is cleared on Enter, not in `finally` (clearing there would wipe a
  // scan being typed meanwhile); and focus is restored by the effect below, never on a
  // still-disabled input.
  const handleScan = useCallback(async (barcode: string, meta?: ScanMeta) => {
    if (inputRef.current) inputRef.current.value = ''
    const trimmed = barcode.trim()
    if (!trimmed) return
    if (queueRef.current.length >= MAX_QUEUED_SCANS) {
      playBeep(false)
      triggerFlash('error')
      setQueueFull(true)
      return
    }
    setQueueFull(false)
    queueRef.current.push({ code: trimmed, meta })
    setPending(queueRef.current.length)
  }, [triggerFlash])

  // SAFETY-CRITICAL — the single scan worker: takes the oldest waiting scan when none is
  // in flight, runs onScan, gives that scan its own beep/flash/recent entry.
  // SINGLE FLIGHT (approved by Marawan 2026-10-02, see header): busyRef — not the
  // render-time `scanning` — says a scan is running. It is set synchronously before the
  // next code is taken and stays set until the render that commits the scan's
  // completion (`completed === startedRef`). That render also holds every state update
  // the finished onScan made, so the next onScan is the one built from that state.
  useEffect(() => {
    if (busyRef.current) {
      if (completed !== startedRef.current) return     // in flight, or its result not rendered yet
      busyRef.current = false
    }
    if (queueRef.current.length === 0) return
    busyRef.current = true
    const seq = ++startedRef.current
    const { code, meta } = queueRef.current.shift()!
    setPending(queueRef.current.length)
    setScanning(true)
    ;(async () => {
      try {
        const result = await onScan(code, meta)
        if (!mountedRef.current) return
        if (result.success) {
          playBeep(true)
          triggerFlash('success')
        } else {
          playBeep(false)
          triggerFlash('error')
        }
        setRecentScans(prev =>
          [{ key: `${Date.now()}-${code}`, barcode: code, success: result.success,
             label: result.label, data: result.data }, ...prev]
            .slice(0, recentScansLimit)
        )
      } catch {
        if (!mountedRef.current) return
        playBeep(false)
        triggerFlash('error')
      } finally {
        if (mountedRef.current) {
          setScanning(false)
          setCompleted(seq)                              // wakes the worker once this is rendered
        }
      }
    })()
  }, [completed, pending, onScan, triggerFlash, recentScansLimit])

  // SAFETY-CRITICAL — keep the scan input focused: after every scan and when the queue
  // drains, but only on an ENABLED input (focus() on a disabled one is a no-op — the
  // original bug), never while the screen has a dialog open (focusPaused), and never
  // out of another text field the person is typing in (e.g. TransferReconcile's
  // shortfall quantities) — a finishing scan must not yank their cursor away.
  useEffect(() => {
    const input = inputRef.current
    if (!input || input.disabled || focusPaused) return
    const active = document.activeElement
    if (active === input) return
    if (active instanceof HTMLElement && active !== document.body && isTextEntry(active)) return
    input.focus()
  }, [scanning, pending, focusPaused])

  // The "too many waiting" notice stays until the queue has drained (or the next scan is accepted).
  useEffect(() => {
    if (!scanning && pending === 0) setQueueFull(false)
  }, [scanning, pending])

  const clearQueue = useCallback((): ScanMeta[] => {
    const dropped = queueRef.current.flatMap(q => (q.meta ? [q.meta] : []))
    queueRef.current = []
    setPending(0)
    setQueueFull(false)
    return dropped
  }, [])

  function removeRecentScan(key: string) {
    setRecentScans(prev => prev.filter(s => s.key !== key))
  }

  function clearRecentScans() {
    setRecentScans([])
  }

  return { inputRef, flash, scanning, pending, queueFull, recentScans, handleScan, clearQueue,
           removeRecentScan, clearRecentScans }
}
