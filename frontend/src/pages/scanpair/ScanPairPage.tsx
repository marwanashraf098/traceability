import { useCallback, useEffect, useRef, useState } from 'react'
import { useParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { CheckCircle2, CircleAlert, Clock, Loader2, ScanLine, Smartphone, Unplug, WifiOff } from 'lucide-react'
import {
  claimScanPair, getPhoneScan, getPhoneStatus, sendPhoneScan, PhoneContext, PhonePairError,
} from '../../api'
import CameraReader, { CameraError } from './CameraReader'

// S6 — the phone side of phone-as-scanner: /scan/:pairCode, opened from the QR on the tablet.
// Public — no login (outside RequireAuth / Layout). Claims the pair code once on load and keeps
// the device secret in sessionStorage (a reload stays paired; closing the tab ends it). Each
// read is sent once with an increasing seq and its outcome polled for up to 4 s — nothing is
// queued or retried behind the worker's back: "Not sent — scan again" / "Not confirmed — check
// the tablet" say exactly what happened. Same code within 2 s is read once.

const DUPLICATE_MS = 2000
const OUTCOME_WAIT_MS = 4000
const POLL_MS = 300
const STATUS_EVERY_MS = 5000

type Phase = 'claiming' | 'ready' | 'scanning' | 'ended' | 'invalid' | 'offline'
type Banner =
  | { kind: 'sending'; code: string }
  | { kind: 'accepted' | 'rejected'; code: string; message: string | null }
  | { kind: 'unconfirmed' | 'notSent'; code: string }
  | null

const secretKey = (pairCode: string) => `scanPair.secret.${pairCode}`
const seqKey = (pairCode: string) => `scanPair.seq.${pairCode}`

function store(key: string, value: string | null) {
  try {
    if (value === null) sessionStorage.removeItem(key)
    else sessionStorage.setItem(key, value)
  } catch { /* private mode / blocked storage: pairing lasts until reload */ }
}
function load(key: string): string | null {
  try { return sessionStorage.getItem(key) } catch { return null }
}

export default function ScanPairPage() {
  const { pairCode = '' } = useParams()
  const { t } = useTranslation()
  const [phase, setPhase] = useState<Phase>('claiming')
  const [context, setContext] = useState<PhoneContext | null>(null)
  const [banner, setBanner] = useState<Banner>(null)
  const [cameraError, setCameraError] = useState<CameraError | null>(null)
  const secretRef = useRef<string | null>(load(secretKey(pairCode)))
  const busyRef = useRef(false)
  const lastSentRef = useRef<{ code: string; at: number } | null>(null)
  const audioRef = useRef<AudioContext | null>(null)

  const endPairing = useCallback(() => {
    store(secretKey(pairCode), null)
    store(seqKey(pairCode), null)
    secretRef.current = null
    setPhase('ended')
    setBanner(null)
  }, [pairCode])

  // Claim once — or, after a reload, carry on with the stored device secret.
  const connect = useCallback(async () => {
    setPhase('claiming')
    try {
      if (secretRef.current) {
        setContext(await getPhoneStatus(secretRef.current))
      } else {
        const claimed = await claimScanPair(pairCode)
        secretRef.current = claimed.deviceSecret
        store(secretKey(pairCode), claimed.deviceSecret)
        setContext(claimed.context)
      }
      setPhase('ready')
    } catch (e) {
      if (e instanceof PhonePairError && e.kind === 'ended') {
        if (secretRef.current) endPairing()
        else setPhase('invalid')
      } else {
        setPhase('offline')
      }
    }
  }, [pairCode, endPairing])

  useEffect(() => { void connect() }, [connect])

  // Header context (worker + open order), refreshed every few seconds while scanning.
  const refreshStatus = useCallback(async () => {
    const secret = secretRef.current
    if (!secret) return
    try { setContext(await getPhoneStatus(secret)) } catch (e) {
      if (e instanceof PhonePairError && e.kind === 'ended') endPairing()
    }
  }, [endPairing])
  useEffect(() => {
    if (phase !== 'scanning' && phase !== 'ready') return
    const id = window.setInterval(() => void refreshStatus(), STATUS_EVERY_MS)
    return () => window.clearInterval(id)
  }, [phase, refreshStatus])

  function feedback(ok: boolean) {
    try { navigator.vibrate?.(ok ? 60 : [220, 90, 220]) } catch { /* no vibration */ }
    const ctx = audioRef.current
    if (!ctx) return
    try {
      const osc = ctx.createOscillator()
      const gain = ctx.createGain()
      osc.frequency.value = ok ? 1800 : 320
      gain.gain.value = 0.2
      osc.connect(gain).connect(ctx.destination)
      osc.start()
      osc.stop(ctx.currentTime + (ok ? 0.08 : 0.35))
    } catch { /* audio unavailable */ }
  }

  function start() {
    // Web Audio needs a tap first (iOS) — this one.
    try {
      if (!audioRef.current) {
        const Ctx = window.AudioContext ?? (window as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext
        if (Ctx) audioRef.current = new Ctx()
      }
      void audioRef.current?.resume()
    } catch { /* no audio — vibration only */ }
    setCameraError(null)
    setPhase('scanning')
  }

  function nextSeq(): number {
    const stored = Number(load(seqKey(pairCode)) ?? '0')
    const seq = Math.max(stored + 1, Date.now())         // unique per pairing, across reloads
    store(seqKey(pairCode), String(seq))
    return seq
  }

  const onRead = useCallback(async (raw: string) => {
    const code = raw.trim()
    const secret = secretRef.current
    if (!code || !secret || busyRef.current) return
    const now = Date.now()
    const last = lastSentRef.current
    if (last && last.code === code && now - last.at < DUPLICATE_MS) return
    lastSentRef.current = { code, at: now }
    busyRef.current = true
    setBanner({ kind: 'sending', code })
    try {
      let eventId: string
      try {
        eventId = (await sendPhoneScan(secret, nextSeq(), code)).eventId
      } catch (e) {
        if (e instanceof PhonePairError && e.kind === 'ended') { endPairing(); return }
        setBanner({ kind: 'notSent', code })
        feedback(false)
        return
      }
      const deadline = Date.now() + OUTCOME_WAIT_MS
      while (Date.now() < deadline) {
        await new Promise(r => setTimeout(r, POLL_MS))
        try {
          const s = await getPhoneScan(secret, eventId)
          if (s.status === 'accepted' || s.status === 'rejected') {
            setBanner({ kind: s.status, code, message: s.message })
            feedback(s.status === 'accepted')
            void refreshStatus()
            return
          }
          if (s.status === 'expired') break
        } catch (e) {
          if (e instanceof PhonePairError && e.kind === 'ended') { endPairing(); return }
          // a failed poll: keep trying until the deadline
        }
      }
      setBanner({ kind: 'unconfirmed', code })
      feedback(false)
    } finally {
      busyRef.current = false
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [endPairing, refreshStatus, pairCode])

  const onCameraError = useCallback((e: CameraError) => {
    setCameraError(e)
    setPhase(p => (p === 'scanning' ? 'ready' : p))
  }, [])

  // ── views ────────────────────────────────────────────────────────────────

  if (phase === 'ended' || phase === 'invalid') {
    return (
      <Shell>
        <div className="flex-1 flex flex-col items-center justify-center gap-4 text-center px-6" data-testid="pair-ended">
          <Unplug size={64} strokeWidth={1.5} className="text-muted" />
          <p className="text-h2 text-primary">
            {phase === 'ended' ? t('scanPair.endedTitle') : t('scanPair.invalidTitle')}
          </p>
          <p className="text-body text-muted max-w-sm">{t('scanPair.scanAgain')}</p>
        </div>
      </Shell>
    )
  }

  return (
    <Shell>
      <header className="px-4 pt-4 pb-3 space-y-1" data-testid="pair-header">
        {phase === 'claiming' ? (
          <p className="flex items-center gap-2 text-body text-muted"><Loader2 size={18} className="animate-spin" />{t('scanPair.connecting')}</p>
        ) : phase === 'offline' ? (
          <p className="flex items-center gap-2 text-body text-critical-text"><WifiOff size={18} />{t('scanPair.offline')}</p>
        ) : (
          <>
            <p className="flex items-center gap-2 text-body font-semibold text-primary">
              <Smartphone size={18} className="text-success" />
              {t('scanPair.connectedTo', { worker: context?.workerName ?? '' })}
            </p>
            <p className="text-small text-muted" data-testid="pair-order">
              {context?.order
                ? t('scanPair.order', { number: context.order.number ?? '', customer: context.order.customerName ?? '',
                    scanned: context.order.scanned, required: context.order.required })
                : t('scanPair.noOrder')}
            </p>
          </>
        )}
      </header>

      {phase === 'scanning' ? (
        <CameraReader active onRead={onRead} onError={onCameraError} />
      ) : (
        <div className="w-full aspect-[3/4] max-h-[62vh] bg-black/90 flex flex-col items-center justify-center gap-4 px-6 text-center">
          {phase === 'offline' ? (
            <button type="button" onClick={() => void connect()}
              className="rounded-2xl bg-trace-blue text-white text-xl font-semibold px-8 py-5">{t('scanPair.retry')}</button>
          ) : phase === 'ready' ? (
            <>
              {cameraError && <p className="text-white/85 text-base" role="alert">{t(`scanPair.camera.${cameraError}`)}</p>}
              <button type="button" onClick={start} data-testid="start-scanning"
                className="rounded-2xl bg-trace-blue text-white text-xl font-semibold px-8 py-5 flex items-center gap-3">
                <ScanLine size={26} /> {t('scanPair.start')}
              </button>
            </>
          ) : (
            <Loader2 size={36} className="animate-spin text-white/70" />
          )}
        </div>
      )}

      <ResultBanner banner={banner} />
    </Shell>
  )
}

function Shell({ children }: { children: React.ReactNode }) {
  return <div className="min-h-screen bg-base text-primary flex flex-col max-w-xl mx-auto" data-testid="scan-pair">{children}</div>
}

function ResultBanner({ banner }: { banner: Banner }) {
  const { t } = useTranslation()
  if (!banner) {
    return <p className="m-4 text-center text-body text-muted">{t('scanPair.pointCamera')}</p>
  }
  const tone = banner.kind === 'accepted' ? 'bg-success/[0.16] border-success/50 text-success-text'
    : banner.kind === 'sending' ? 'bg-elevated border-line text-primary'
    : banner.kind === 'unconfirmed' ? 'bg-warning/[0.16] border-warning/50 text-warning-text'
    : 'bg-critical/[0.14] border-critical/50 text-critical-text'
  const Icon = banner.kind === 'accepted' ? CheckCircle2 : banner.kind === 'sending' ? Loader2
    : banner.kind === 'unconfirmed' ? Clock : CircleAlert
  const title = banner.kind === 'sending' ? t('scanPair.sending')
    : banner.kind === 'accepted' ? (banner.message || t('scanPair.accepted'))
    : banner.kind === 'rejected' ? (banner.message || t('scanPair.rejected'))
    : banner.kind === 'unconfirmed' ? t('scanPair.unconfirmed') : t('scanPair.notSent')
  return (
    <div className={`m-4 rounded-2xl border-2 px-5 py-5 flex items-center gap-4 ${tone}`}
      role="status" data-testid="pair-result" data-kind={banner.kind}>
      <Icon size={40} className={'flex-shrink-0' + (banner.kind === 'sending' ? ' animate-spin' : '')} />
      <div className="min-w-0">
        <p className="text-2xl font-bold leading-tight break-words">{title}</p>
        <p className="text-small font-mono opacity-80 break-all">{banner.code}</p>
      </div>
    </div>
  )
}
