// ─────────────────────────────────────────────────────────────────────────────
// TEMPORARY — camera barcode-reading SPIKE. Delete after S6 (route in App.tsx,
// this file, and the @zxing/* dependencies) — tracked as a follow-up in
// docs/PROGRESS.md. Not a product screen: public, no API calls, stores nothing,
// English only.
//
// Answers: can a phone's rear camera read our Code 128 piece labels and Bosta
// waybills fast and reliably, with which engine (native BarcodeDetector vs
// @zxing/browser, pure JS — no zxing-wasm: CSP has no 'wasm-unsafe-eval'), and
// which symbology do Bosta's waybills actually use.
// ─────────────────────────────────────────────────────────────────────────────
import { useCallback, useEffect, useRef, useState } from 'react'
import { BrowserMultiFormatReader, BarcodeFormat, type IScannerControls } from '@zxing/browser'
import { DecodeHintType } from '@zxing/library'

// BarcodeDetector isn't in TS's DOM lib yet — the minimum this page uses.
interface DetectedBarcode { rawValue: string; format: string }
interface BarcodeDetectorLike { detect(source: CanvasImageSource): Promise<DetectedBarcode[]> }
interface BarcodeDetectorCtor {
  new (opts?: { formats: string[] }): BarcodeDetectorLike
  getSupportedFormats(): Promise<string[]>
}
const NativeDetector = (globalThis as unknown as { BarcodeDetector?: BarcodeDetectorCtor }).BarcodeDetector

type Engine = 'native' | 'zxing'

const CORE_NATIVE = ['code_128', 'qr_code', 'ean_13']
const ALL_NATIVE = [...CORE_NATIVE, 'code_39', 'code_93', 'codabar', 'itf', 'ean_8', 'upc_a', 'upc_e', 'data_matrix', 'pdf417', 'aztec']
const CORE_ZXING = [BarcodeFormat.CODE_128, BarcodeFormat.QR_CODE, BarcodeFormat.EAN_13]
const ALL_ZXING = [...CORE_ZXING, BarcodeFormat.CODE_39, BarcodeFormat.CODE_93, BarcodeFormat.CODABAR, BarcodeFormat.ITF,
  BarcodeFormat.EAN_8, BarcodeFormat.UPC_A, BarcodeFormat.UPC_E, BarcodeFormat.DATA_MATRIX, BarcodeFormat.PDF_417, BarcodeFormat.AZTEC]

const DEDUPE_MS = 2000
const LOG_MAX = 50

interface Read { n: number; at: number; text: string; format: string; ms: number; engine: Engine }

/** Raw text with whitespace and control characters made visible (space → "·"). */
export function visible(text: string): string {
  return Array.from(text).map(ch => {
    if (ch === ' ') return '·'
    if (ch === '\t') return '⇥'
    if (ch === '\n') return '↵'
    if (ch === '\r') return '␍'
    const c = ch.codePointAt(0)!
    if (c < 0x20 || c === 0x7f) return `\\x${c.toString(16).padStart(2, '0')}`
    if (c === 0xa0 || (c >= 0x2000 && c <= 0x200b) || c === 0x202f || c === 0x3000) return `⟨U+${c.toString(16).toUpperCase().padStart(4, '0')}⟩`
    return ch
  }).join('')
}

function zxingFormatName(f: BarcodeFormat): string {
  return (BarcodeFormat[f] ?? String(f)).toLowerCase()
}

export default function ScanSpike() {
  const videoRef = useRef<HTMLVideoElement>(null)
  const streamRef = useRef<MediaStream | null>(null)
  const audioRef = useRef<AudioContext | null>(null)
  const stopEngineRef = useRef<(() => void) | null>(null)
  const sinceRef = useRef<number>(0)                 // camera ready / last counted read / Start test
  const lastSeenRef = useRef<Map<string, number>>(new Map())
  const msListRef = useRef<number[]>([])

  const [nativeFormats, setNativeFormats] = useState<string[] | null>(null)
  const [engine, setEngine] = useState<Engine>(NativeDetector ? 'native' : 'zxing')
  const [allFormats, setAllFormats] = useState(false)
  const [camera, setCamera] = useState<'off' | 'starting' | 'on'>('off')
  const [cameraInfo, setCameraInfo] = useState<string>('')
  const [error, setError] = useState<string | null>(null)
  const [torchSupported, setTorchSupported] = useState(false)
  const [torchOn, setTorchOn] = useState(false)
  const [reads, setReads] = useState<Read[]>([])
  const [count, setCount] = useState(0)
  const [unique, setUnique] = useState(0)
  const [avgMs, setAvgMs] = useState<number | null>(null)
  const [copied, setCopied] = useState(false)
  const uniqueRef = useRef<Set<string>>(new Set())
  const countRef = useRef(0)

  useEffect(() => {
    if (!NativeDetector) { setNativeFormats([]); return }
    NativeDetector.getSupportedFormats().then(setNativeFormats).catch(() => setNativeFormats([]))
  }, [])

  const feedback = useCallback(() => {
    try { navigator.vibrate?.(60) } catch { /* not supported */ }
    const ctx = audioRef.current
    if (!ctx) return
    try {
      const osc = ctx.createOscillator()
      const gain = ctx.createGain()
      osc.frequency.value = 1800
      gain.gain.value = 0.15
      osc.connect(gain).connect(ctx.destination)
      osc.start()
      osc.stop(ctx.currentTime + 0.07)
    } catch { /* audio unavailable */ }
  }, [])

  const onDecoded = useCallback((text: string, format: string, eng: Engine) => {
    const now = performance.now()
    const last = lastSeenRef.current.get(text)
    if (last !== undefined && now - last < DEDUPE_MS) return     // same code within 2 s: counted once
    lastSeenRef.current.set(text, now)
    const ms = Math.round(now - sinceRef.current)
    sinceRef.current = now
    msListRef.current.push(ms)
    uniqueRef.current.add(text)
    countRef.current += 1
    const n = countRef.current
    setCount(n)
    setUnique(uniqueRef.current.size)
    setAvgMs(Math.round(msListRef.current.reduce((a, b) => a + b, 0) / msListRef.current.length))
    setReads(prev => [{ n, at: Date.now(), text, format, ms, engine: eng }, ...prev].slice(0, LOG_MAX))
    feedback()
  }, [feedback])

  // ── decode engines (both read the same <video>; the page owns the stream) ──
  const startEngine = useCallback((eng: Engine, all: boolean) => {
    stopEngineRef.current?.()
    stopEngineRef.current = null
    const video = videoRef.current
    if (!video || !streamRef.current) return

    if (eng === 'native') {
      if (!NativeDetector) return
      const supported = nativeFormats ?? []
      const formats = (all ? ALL_NATIVE : CORE_NATIVE).filter(f => supported.length === 0 || supported.includes(f))
      let detector: BarcodeDetectorLike
      try { detector = new NativeDetector({ formats }) } catch (e) {
        setError(`BarcodeDetector failed: ${(e as Error).message}`); return
      }
      let stopped = false
      let timer: number | undefined
      const loop = async () => {
        if (stopped) return
        try {
          if (video.readyState >= 2) {
            const found = await detector.detect(video)
            for (const b of found) onDecoded(b.rawValue, b.format, 'native')
          }
        } catch { /* a frame that couldn't be read — keep going */ }
        if (!stopped) timer = window.setTimeout(loop, 50)
      }
      loop()
      stopEngineRef.current = () => { stopped = true; window.clearTimeout(timer) }
      return
    }

    const hints = new Map<DecodeHintType, unknown>()
    hints.set(DecodeHintType.POSSIBLE_FORMATS, all ? ALL_ZXING : CORE_ZXING)
    hints.set(DecodeHintType.TRY_HARDER, true)
    const reader = new BrowserMultiFormatReader(hints, { delayBetweenScanAttempts: 50, delayBetweenScanSuccess: 50 })
    let controls: IScannerControls | null = null
    let cancelled = false
    reader.decodeFromVideoElement(video, result => {
      if (result) onDecoded(result.getText(), zxingFormatName(result.getBarcodeFormat()), 'zxing')
    }).then(c => { if (cancelled) c.stop(); else controls = c })
      .catch(e => setError(`zxing failed: ${(e as Error).message}`))
    stopEngineRef.current = () => { cancelled = true; controls?.stop() }
  }, [nativeFormats, onDecoded])

  useEffect(() => {
    if (camera === 'on') startEngine(engine, allFormats)
  }, [camera, engine, allFormats, startEngine])

  // ── camera ──
  async function startCamera() {
    setError(null)
    setCamera('starting')
    // Web Audio must be created/resumed inside a user gesture (iOS).
    try {
      if (!audioRef.current) {
        const Ctx = window.AudioContext ?? (window as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext
        if (Ctx) audioRef.current = new Ctx()
      }
      await audioRef.current?.resume()
    } catch { /* no audio — beep is skipped */ }

    if (!window.isSecureContext || !navigator.mediaDevices?.getUserMedia) {
      setError(window.isSecureContext ? 'This browser has no camera API (getUserMedia).' : 'Camera needs HTTPS (not a secure context).')
      setCamera('off')
      return
    }
    const attempts: MediaStreamConstraints[] = [
      { audio: false, video: { facingMode: { ideal: 'environment' }, width: { ideal: 1920 }, height: { ideal: 1080 } } },
      { audio: false, video: { facingMode: { ideal: 'environment' } } },
      { audio: false, video: true },
    ]
    let stream: MediaStream | null = null
    let lastErr: unknown = null
    for (const c of attempts) {
      try { stream = await navigator.mediaDevices.getUserMedia(c); break } catch (e) {
        lastErr = e
        const name = (e as DOMException)?.name
        if (name === 'NotAllowedError' || name === 'SecurityError') break    // no point retrying a denial
      }
    }
    if (!stream) {
      const e = lastErr as DOMException | null
      setError(`Camera failed: ${e?.name ?? 'Error'} — ${e?.message ?? ''}`)
      setCamera('off')
      return
    }
    streamRef.current = stream
    const track = stream.getVideoTracks()[0]
    type Caps = MediaTrackCapabilities & { focusMode?: string[]; torch?: boolean }
    const caps: Caps = (track.getCapabilities?.() ?? {}) as Caps
    const notes: string[] = []
    if (caps.focusMode?.includes('continuous')) {
      try {
        await track.applyConstraints({ advanced: [{ focusMode: 'continuous' } as MediaTrackConstraintSet] })
        notes.push('continuous autofocus')
      } catch { notes.push('autofocus: request failed') }
    } else {
      notes.push('autofocus: not controllable')
    }
    setTorchSupported(!!caps.torch)
    setTorchOn(false)
    const s = track.getSettings()
    setCameraInfo(`${s.width ?? '?'}×${s.height ?? '?'}${s.frameRate ? ` @ ${Math.round(s.frameRate)}fps` : ''} · ${track.label || 'camera'} · ${notes.join(' · ')}${caps.torch ? ' · torch available' : ''}`)

    const video = videoRef.current!
    video.srcObject = stream
    video.setAttribute('playsinline', 'true')
    video.muted = true
    try { await video.play() } catch { /* autoplay with muted+playsinline should pass */ }
    sinceRef.current = performance.now()
    setCamera('on')
  }

  function stopCamera() {
    stopEngineRef.current?.()
    stopEngineRef.current = null
    streamRef.current?.getTracks().forEach(t => t.stop())
    streamRef.current = null
    if (videoRef.current) videoRef.current.srcObject = null
    setCamera('off')
    setTorchOn(false)
  }

  useEffect(() => () => {                       // leaving the page releases the camera
    stopEngineRef.current?.()
    streamRef.current?.getTracks().forEach(t => t.stop())
    audioRef.current?.close().catch(() => {})
  }, [])

  async function toggleTorch() {
    const track = streamRef.current?.getVideoTracks()[0]
    if (!track) return
    try {
      await track.applyConstraints({ advanced: [{ torch: !torchOn } as MediaTrackConstraintSet] })
      setTorchOn(!torchOn)
    } catch (e) { setError(`Torch failed: ${(e as Error).message}`) }
  }

  function startTest() {
    lastSeenRef.current.clear()
    msListRef.current = []
    uniqueRef.current.clear()
    countRef.current = 0
    sinceRef.current = performance.now()
    setReads([])
    setCount(0)
    setUnique(0)
    setAvgMs(null)
    try { audioRef.current?.resume() } catch { /* ignore */ }
  }

  async function copyLog() {
    const header = `scan-spike log · ${new Date().toISOString()} · ${navigator.userAgent}\n` +
      `camera: ${cameraInfo || 'off'}\nengines: native=${NativeDetector ? (nativeFormats ?? []).join(',') || 'yes' : 'no'} zxing=yes\n` +
      `reads=${count} unique=${unique} avgMs=${avgMs ?? '-'}\n` +
      `#\ttime\tengine\tformat\tms\tlen\ttext(visible)\n`
    const lines = [...reads].reverse().map(r =>
      `${r.n}\t${new Date(r.at).toLocaleTimeString()}\t${r.engine}\t${r.format}\t${r.ms}\t${r.text.length}\t${visible(r.text)}`)
    const text = header + lines.join('\n') + '\n'
    try {
      await navigator.clipboard.writeText(text)
    } catch {
      const ta = document.createElement('textarea')
      ta.value = text
      document.body.appendChild(ta)
      ta.select()
      document.execCommand('copy')
      ta.remove()
    }
    setCopied(true)
    window.setTimeout(() => setCopied(false), 1500)
  }

  const btn = 'rounded-xl px-4 py-3 text-lg font-semibold active:scale-[0.98] transition disabled:opacity-40'

  return (
    <div className="min-h-screen bg-base text-primary p-4 space-y-4 max-w-xl mx-auto" data-testid="scan-spike">
      <header className="space-y-1">
        <h1 className="text-2xl font-bold">Camera scan spike</h1>
        <p className="text-sm text-muted">Temporary test page — nothing is sent or saved.</p>
      </header>

      <section className="rounded-xl border border-line p-3 text-sm space-y-1" data-testid="engines">
        <p><b>Native BarcodeDetector:</b>{' '}
          {NativeDetector
            ? (nativeFormats === null ? 'checking…' : `available (${nativeFormats.join(', ') || 'no formats listed'})`)
            : 'not available on this browser'}</p>
        <p><b>@zxing/browser:</b> available (pure JS)</p>
      </section>

      <div className="relative rounded-2xl overflow-hidden bg-black aspect-[3/4]">
        <video ref={videoRef} className="w-full h-full object-cover" playsInline muted />
        {camera !== 'on' && (
          <div className="absolute inset-0 flex items-center justify-center text-white/80 text-lg">
            {camera === 'starting' ? 'Starting camera…' : 'Camera off'}
          </div>
        )}
        {camera === 'on' && <div className="absolute left-[8%] right-[8%] top-1/2 h-0.5 bg-red-500/70" aria-hidden />}
      </div>
      {cameraInfo && <p className="text-xs text-muted" data-testid="camera-info">{cameraInfo}</p>}
      {error && <p className="rounded-xl bg-red-500/10 text-red-600 p-3 text-base" role="alert">{error}</p>}

      <div className="grid grid-cols-2 gap-3">
        {camera === 'on'
          ? <button className={`${btn} bg-elevated`} onClick={stopCamera}>Stop camera</button>
          : <button className={`${btn} bg-trace-blue text-white`} onClick={startCamera} disabled={camera === 'starting'}>Start camera</button>}
        <button className={`${btn} bg-trace-blue text-white`} onClick={startTest}>Start test</button>
        <button className={`${btn} bg-elevated`} onClick={toggleTorch} disabled={camera !== 'on' || !torchSupported}>
          {torchSupported ? (torchOn ? 'Torch off' : 'Torch on') : 'No torch'}
        </button>
        <button className={`${btn} bg-elevated`} onClick={copyLog}>{copied ? 'Copied ✓' : 'Copy log'}</button>
      </div>

      <div className="grid grid-cols-2 gap-3" role="radiogroup" aria-label="Engine">
        {(['native', 'zxing'] as Engine[]).map(e => (
          <button key={e} role="radio" aria-checked={engine === e}
            className={`${btn} border-2 ${engine === e ? 'border-trace-blue bg-trace-blue/10' : 'border-line'}`}
            disabled={e === 'native' && !NativeDetector} onClick={() => setEngine(e)}>
            {e === 'native' ? 'Native' : 'zxing'}
          </button>
        ))}
      </div>
      <label className="flex items-center gap-3 text-base">
        <input type="checkbox" className="w-6 h-6" checked={allFormats} onChange={e => setAllFormats(e.target.checked)} />
        All formats (default: Code 128, QR, EAN-13)
      </label>

      <div className="grid grid-cols-3 gap-3 text-center" data-testid="counters">
        <div className="rounded-xl border border-line p-3"><p className="text-sm text-muted">Reads</p><p className="text-3xl font-bold font-mono">{count}</p></div>
        <div className="rounded-xl border border-line p-3"><p className="text-sm text-muted">Unique</p><p className="text-3xl font-bold font-mono">{unique}</p></div>
        <div className="rounded-xl border border-line p-3"><p className="text-sm text-muted">Avg ms</p><p className="text-3xl font-bold font-mono">{avgMs ?? '–'}</p></div>
      </div>

      <ol className="space-y-2" data-testid="read-log">
        {reads.length === 0 && <li className="text-muted text-base">No reads yet.</li>}
        {reads.map(r => (
          <li key={r.n} className="rounded-xl border border-line p-3">
            <p className="font-mono text-xl break-all">{visible(r.text)}</p>
            <p className="text-sm text-muted">#{r.n} · {r.format} · {r.ms} ms · {r.engine} · {r.text.length} chars</p>
          </li>
        ))}
      </ol>
    </div>
  )
}
