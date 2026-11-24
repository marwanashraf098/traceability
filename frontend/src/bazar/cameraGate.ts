import { useCallback, useEffect, useReducer, useRef } from 'react'

// Bazar C1 (2026-11-24) — one aim of the phone's camera is at most ONE scan.
//
// Prod 2026-11-22: one aim sold P000056 and P000055 0.8 s apart. The camera passes on every code it reads, and the
// screens only dropped the SAME code within 2 s, so a second label in view went to the scanner's queue and was sold
// right after the first. This is THE camera rule for every bazar screen that changes money or stock. It reads only
// camera frames — a HID scanner or a typed code never comes through here, and useScanner is not touched.
//
//   settle  the first frame with a code opens a short window (settleMs); every distinct code seen in it is collected.
//           Exactly one → it is sent. Two or more (in one frame or across the window) → nothing is sent and the
//           screen says "Two labels in view".
//   pause   ('pause' mode — quick sale, cart, Return / Exchange) after a code is sent the camera ignores everything
//           until (a) the result is shown, when the screen waits for one (quick sale: the SOLD / error card), and
//           then (b) it has seen no barcode for quietMs over at least quietFrames read attempts — or the seller taps
//           "Scan next". Any code seen during (b) restarts it.
//   pile    ('pile' mode — Leftovers scan-back) no pause: people scan a pile label after label. A code already sent
//           is ignored until the camera has seen an empty view; only codes not yet sent count toward "two labels".
//
// While the screen is busy (a quick sale in flight, from any source) every code is ignored, never queued.

export const cameraGateTiming = { settleMs: 250, quietMs: 600, quietFrames: 3 }

export type CameraGateMode = 'pause' | 'pile'
export type CameraGateNotice = 'twoLabels' | null
/** What the screen did with a sent code: 'await' = a result card will follow (call resultShown); anything else = done. */
export type CameraGateSend = (code: string) => 'await' | void

export class CameraGate {
  phase: 'open' | 'settling' | 'paused' = 'open'
  notice: CameraGateNotice = null
  /** Paused and still waiting for the screen's result (quiet frames don't count yet). */
  awaitingResult = false
  private settleCodes = new Set<string>()
  private settleUntil = 0
  /** Codes already sent: never sent again until an empty view. */
  private sent = new Set<string>()
  private quietSince: number | null = null
  private quietCount = 0

  constructor(public mode: CameraGateMode, private send: CameraGateSend, private timing = cameraGateTiming) {}

  /** One camera read attempt: every code it saw ([] = no barcode in view). */
  frame(codes: string[], now: number, busy = false): void {
    if (this.phase === 'settling' && now >= this.settleUntil) this.endSettle(busy)
    const distinct = [...new Set(codes.map(c => c.trim()).filter(Boolean))]
    if (distinct.length === 0) { this.empty(now); return }
    this.quietSince = null
    this.quietCount = 0
    if (busy || this.phase === 'paused') return
    const fresh = distinct.filter(c => !this.sent.has(c))
    if (fresh.length === 0) return
    if (this.phase === 'open') {
      this.phase = 'settling'
      this.settleUntil = now + this.timing.settleMs
      this.settleCodes = new Set()
    }
    // 'pause' mode is strict: a label already sent that is still in view beside a new one is still two labels.
    for (const c of this.mode === 'pause' ? distinct : fresh) this.settleCodes.add(c)
    if (this.settleCodes.size >= 2) this.twoLabels()
  }

  /** The settle window is over (the screen's timer, or a later frame): send its one code, if it has exactly one. */
  endSettle(busy = false): void {
    if (this.phase !== 'settling') return
    const seen = [...this.settleCodes]
    const codes = seen.filter(c => !this.sent.has(c))
    this.phase = 'open'
    this.settleCodes = new Set()
    if (busy) return
    if (seen.length >= 2) { this.twoLabels(); return }
    if (codes.length === 0) return
    const code = codes[0]
    this.notice = null
    this.sent.add(code)
    this.quietSince = null
    this.quietCount = 0
    const r = this.send(code)
    if (this.mode === 'pause') {
      this.phase = 'paused'
      this.awaitingResult = r === 'await'
    }
  }

  /** The screen showed the result of the code it was sent (quick sale: SOLD, error or undone card). */
  resultShown(): void {
    if (this.phase !== 'paused' || !this.awaitingResult) return
    this.awaitingResult = false
    this.quietSince = null
    this.quietCount = 0
  }

  /** "Scan next": the seller ends the pause by hand. A code already sent stays ignored until an empty view. */
  scanNext(): void {
    this.phase = 'open'
    this.awaitingResult = false
    this.notice = null
    this.settleCodes = new Set()
    this.quietSince = null
    this.quietCount = 0
  }

  private twoLabels() {
    this.phase = 'open'
    this.settleCodes = new Set()
    this.notice = 'twoLabels'
  }

  private empty(now: number) {
    if (this.phase === 'paused' && this.awaitingResult) return     // the quiet moment counts only after the result
    if (this.quietSince === null) this.quietSince = now
    this.quietCount += 1
    if (this.quietCount < this.timing.quietFrames || now - this.quietSince < this.timing.quietMs) return
    // Nothing in view for long enough: the next label is a new aim.
    if (this.phase === 'paused') this.phase = 'open'
    if (this.phase === 'open') {
      this.sent.clear()
      this.notice = null
    }
  }

  snapshot(): string {
    return `${this.phase}|${this.notice}|${this.awaitingResult}`
  }
}

/** React wrapper: pass onFrame / onRead to CameraReader; the gate calls {@code onCode} once per aim. */
export function useCameraGate({ mode, onCode, busy }: {
  mode: CameraGateMode
  onCode: CameraGateSend
  /** The screen can't take a camera code now (a sale in flight): codes are ignored, never queued. */
  busy?: boolean
}) {
  const onCodeRef = useRef(onCode)
  onCodeRef.current = onCode
  const busyRef = useRef(!!busy)
  busyRef.current = !!busy
  const [, rerender] = useReducer((x: number) => x + 1, 0)
  const gateRef = useRef<CameraGate | null>(null)
  if (!gateRef.current) gateRef.current = new CameraGate(mode, c => onCodeRef.current(c))
  const gate = gateRef.current
  gate.mode = mode
  const timer = useRef<number | undefined>(undefined)
  const mounted = useRef(true)
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; window.clearTimeout(timer.current) } }, [])

  const run = useCallback((fn: () => void) => {
    const before = gate.snapshot()
    const wasSettling = gate.phase === 'settling'
    fn()
    if (!wasSettling && gate.phase === 'settling') {
      window.clearTimeout(timer.current)
      timer.current = window.setTimeout(() => {
        if (!mounted.current) return
        run(() => gate.endSettle(busyRef.current))
      }, cameraGateTiming.settleMs)
    }
    if (mounted.current && gate.snapshot() !== before) rerender()
  }, [gate])

  const onFrame = useCallback((codes: string[]) => run(() => gate.frame(codes, Date.now(), busyRef.current)), [run, gate])
  /** A read without frame data (one code) — the same as a frame holding only that code. */
  const onRead = useCallback((code: string) => onFrame([code]), [onFrame])
  const resultShown = useCallback(() => run(() => gate.resultShown()), [run, gate])
  const scanNext = useCallback(() => run(() => gate.scanNext()), [run, gate])

  return {
    onFrame, onRead, resultShown, scanNext,
    notice: gate.notice,
    /** Paused with the result shown: the screen offers "Scan next". */
    waitingForClear: gate.phase === 'paused' && !gate.awaitingResult,
  }
}
