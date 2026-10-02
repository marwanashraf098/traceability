import { useCallback, useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { BrowserMultiFormatReader, BarcodeFormat, type IScannerControls } from '@zxing/browser'
import { DecodeHintType } from '@zxing/library'
import { Flashlight, FlashlightOff } from 'lucide-react'

// S6 — the phone's camera as a barcode reader (from the 2026-10-02 camera spike): rear camera
// (ideal 1920×1080, falling back to any camera), continuous autofocus and a torch where the
// camera exposes them. Engine: the browser's BarcodeDetector when it has Code 128 (Android
// Chrome), else @zxing/browser — pure JS, no WebAssembly (the CSP has no 'wasm-unsafe-eval');
// iPhone Safari has no BarcodeDetector. Formats: Code 128 (piece labels, Bosta's barcodes) and
// QR (Bosta's waybill QR holds "BOSTA_<tracking number>"). Every decode is passed to onRead;
// the page decides what to do with it (duplicate suppression, sending).

interface DetectedBarcode { rawValue: string; format: string }
interface BarcodeDetectorLike { detect(source: CanvasImageSource): Promise<DetectedBarcode[]> }
interface BarcodeDetectorCtor {
  new (opts?: { formats: string[] }): BarcodeDetectorLike
  getSupportedFormats(): Promise<string[]>
}
const NativeDetector = (globalThis as unknown as { BarcodeDetector?: BarcodeDetectorCtor }).BarcodeDetector

const NATIVE_FORMATS = ['code_128', 'qr_code']
const ZXING_FORMATS = [BarcodeFormat.CODE_128, BarcodeFormat.QR_CODE]

export type CameraError = 'insecure' | 'unsupported' | 'denied' | 'failed'

export default function CameraReader({ active, onRead, onError }: {
  /** Camera on (the page turns it off when the pairing ends). */
  active: boolean
  onRead: (code: string) => void
  onError: (e: CameraError) => void
}) {
  const { t } = useTranslation()
  const videoRef = useRef<HTMLVideoElement>(null)
  const streamRef = useRef<MediaStream | null>(null)
  const onReadRef = useRef(onRead)
  onReadRef.current = onRead
  const [ready, setReady] = useState(false)
  const [torchSupported, setTorchSupported] = useState(false)
  const [torchOn, setTorchOn] = useState(false)

  const stop = useCallback(() => {
    streamRef.current?.getTracks().forEach(tr => tr.stop())
    streamRef.current = null
    if (videoRef.current) videoRef.current.srcObject = null
    setReady(false)
    setTorchOn(false)
  }, [])

  useEffect(() => {
    if (!active) { stop(); return }
    let cancelled = false
    let stopDecode: (() => void) | null = null

    ;(async () => {
      if (!window.isSecureContext) { onError('insecure'); return }
      if (!navigator.mediaDevices?.getUserMedia) { onError('unsupported'); return }
      const attempts: MediaStreamConstraints[] = [
        { audio: false, video: { facingMode: { ideal: 'environment' }, width: { ideal: 1920 }, height: { ideal: 1080 } } },
        { audio: false, video: { facingMode: { ideal: 'environment' } } },
        { audio: false, video: true },
      ]
      let stream: MediaStream | null = null
      for (const c of attempts) {
        try { stream = await navigator.mediaDevices.getUserMedia(c); break } catch (e) {
          const name = (e as DOMException)?.name
          if (name === 'NotAllowedError' || name === 'SecurityError') { onError('denied'); return }
        }
      }
      if (!stream) { onError('failed'); return }
      if (cancelled) { stream.getTracks().forEach(tr => tr.stop()); return }
      streamRef.current = stream

      const track = stream.getVideoTracks()[0]
      type Caps = MediaTrackCapabilities & { focusMode?: string[]; torch?: boolean }
      const caps: Caps = (track.getCapabilities?.() ?? {}) as Caps
      if (caps.focusMode?.includes('continuous')) {
        track.applyConstraints({ advanced: [{ focusMode: 'continuous' } as MediaTrackConstraintSet] }).catch(() => {})
      }
      setTorchSupported(!!caps.torch)

      const video = videoRef.current!
      video.srcObject = stream
      video.setAttribute('playsinline', 'true')
      video.muted = true
      try { await video.play() } catch { /* muted + playsinline autoplay */ }
      if (cancelled) return
      setReady(true)

      const nativeFormats = NativeDetector ? await NativeDetector.getSupportedFormats().catch(() => []) : []
      if (NativeDetector && nativeFormats.includes('code_128')) {
        const detector = new NativeDetector({ formats: NATIVE_FORMATS.filter(f => nativeFormats.includes(f)) })
        let stopped = false
        let timer: number | undefined
        const loop = async () => {
          if (stopped) return
          try {
            if (video.readyState >= 2) for (const b of await detector.detect(video)) onReadRef.current(b.rawValue)
          } catch { /* unreadable frame */ }
          if (!stopped) timer = window.setTimeout(loop, 60)
        }
        loop()
        stopDecode = () => { stopped = true; window.clearTimeout(timer) }
      } else {
        const hints = new Map<DecodeHintType, unknown>()
        hints.set(DecodeHintType.POSSIBLE_FORMATS, ZXING_FORMATS)
        hints.set(DecodeHintType.TRY_HARDER, true)
        const reader = new BrowserMultiFormatReader(hints, { delayBetweenScanAttempts: 60, delayBetweenScanSuccess: 60 })
        let controls: IScannerControls | null = null
        let stopped = false
        reader.decodeFromVideoElement(video, result => { if (result) onReadRef.current(result.getText()) })
          .then(c => { if (stopped) c.stop(); else controls = c })
          .catch(() => onError('failed'))
        stopDecode = () => { stopped = true; controls?.stop() }
      }
    })()

    return () => {
      cancelled = true
      stopDecode?.()
      stop()
    }
  }, [active, onError, stop])

  async function toggleTorch() {
    const track = streamRef.current?.getVideoTracks()[0]
    if (!track) return
    try {
      await track.applyConstraints({ advanced: [{ torch: !torchOn } as MediaTrackConstraintSet] })
      setTorchOn(!torchOn)
    } catch { /* torch refused */ }
  }

  return (
    <div className="relative w-full aspect-[3/4] max-h-[62vh] bg-black overflow-hidden" data-testid="camera">
      <video ref={videoRef} className="w-full h-full object-cover" playsInline muted />
      {ready && <div className="absolute left-[8%] right-[8%] top-1/2 h-0.5 bg-red-500/70" aria-hidden />}
      {!ready && active && (
        <div className="absolute inset-0 flex items-center justify-center text-white/80 text-lg">
          {t('scanPair.cameraStarting')}
        </div>
      )}
      {ready && torchSupported && (
        <button type="button" onClick={toggleTorch}
          className="absolute bottom-3 end-3 w-14 h-14 rounded-full bg-black/55 text-white flex items-center justify-center"
          aria-label={torchOn ? t('scanPair.torchOff') : t('scanPair.torchOn')} data-testid="torch">
          {torchOn ? <FlashlightOff size={26} /> : <Flashlight size={26} />}
        </button>
      )}
    </div>
  )
}
