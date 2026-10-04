import {
  createContext, lazy, ReactNode, Suspense, useCallback, useContext, useEffect, useMemo, useReducer, useRef, useState,
} from 'react'
import { useTranslation } from 'react-i18next'
import {
  createStationPairing, getStationPairing, postRelayOutcome, putStationTarget, unpairStationPairing,
  TransferCommandError, type RelayScanEvent, type ScanPairingCreated, type ScanPairingStatus,
} from '../api'
import { useStation } from '../components/StationProvider'
import { openRelayStream } from './relayStream'

// Lazy: the QR library loads only when a worker taps "Use phone".
const PhonePairModal = lazy(() => import('./PhonePairModal'))

// ── Q1 — phone as scanner, per tablet ───────────────────────────────────────
//
// One provider at the root (above the router, like StationProvider) owns, for THIS tablet:
//   • its stable device id (random, localStorage — a routing key, not a secret);
//   • the pairing status, the "Use phone" QR modal and Unpair;
//   • the ONE relay stream (fetch-based SSE, token refresh — relayStream.ts);
//   • a stack of scan TARGETS. A scanning screen registers while mounted
//     (usePhoneScanTarget); a dialog that takes scans (AwbLinkDialog) registers on top while open.
//
// Routing — every phone scan gets exactly one outcome, always:
//   • no target registered           → rejected "No scanning screen open on the tablet"
//   • top target paused (a dialog that isn't a target is open) → rejected "Tablet busy — finish the dialog"
//   • otherwise                      → the top target's queue (scanner.handleScan(code, {relayEventId}))
//   • a target unmounts, or drops its queue (clearQueue), with phone scans it hasn't started
//                                    → "Not applied — scan again"
// An answered event is never answered again (the server keeps the first verdict too).

const DEVICE_ID_KEY = 'stationDeviceId'
const DEVICE_ID_RE = /^[A-Za-z0-9_-]{16,64}$/
let memoryDeviceId: string | null = null

function randomDeviceId(): string {
  const bytes = new Uint8Array(18)
  crypto.getRandomValues(bytes)
  return btoa(String.fromCharCode(...bytes)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

/** This tablet's id: kept in localStorage; an in-memory one when storage is unavailable. */
export function stationDeviceId(): string {
  try {
    const stored = localStorage.getItem(DEVICE_ID_KEY)
    if (stored && DEVICE_ID_RE.test(stored)) return stored
    const fresh = randomDeviceId()
    localStorage.setItem(DEVICE_ID_KEY, fresh)
    return fresh
  } catch {
    memoryDeviceId ??= randomDeviceId()
    return memoryDeviceId
  }
}

/** What a registered target exposes to the router (read when a scan arrives). */
export interface PhoneTargetHandle {
  label: string
  paused: boolean
  /** Hand one phone scan to the screen. It is answered exactly once — by the screen's wrapped
   *  onScan, by a clearQueue, or by unregistering. */
  deliver: (code: string, eventId: string) => void
}

interface PhoneScanApi {
  /** Registers a target on top of the stack; returns its unregister. */
  register: (get: () => PhoneTargetHandle) => () => void
  /** A target's label / paused changed. */
  touch: () => void
  /** The event's one outcome (no-op if it already has one). */
  answer: (eventId: string, success: boolean, message: string) => void
  /** The screen has started the event's onScan — from here its wrapper answers it. */
  started: (eventId: string) => void
}

export interface PhoneStatus {
  pairing: ScanPairingStatus | null
  /** The relay link has been down for over 5 s. */
  reconnecting: boolean
  busy: boolean
  startPairing: () => void
  unpair: () => void
  /** The floating control is on screen (an authenticated page): the provider loads / streams. */
  setActive: (active: boolean) => void
}

const PhoneScanContext = createContext<PhoneScanApi | null>(null)
const PhoneStatusContext = createContext<PhoneStatus | null>(null)

export function usePhoneScanApi(): PhoneScanApi | null { return useContext(PhoneScanContext) }
export function usePhoneStatus(): PhoneStatus | null { return useContext(PhoneStatusContext) }

const NONE = (reason: string | null = null): ScanPairingStatus => ({
  status: 'none', pairingId: null, deviceLabel: null, pairCodeExpiresAt: null, claimedAt: null, expiresAt: null, reason,
})

export function PhoneScanProvider({ children }: { children: ReactNode }) {
  const { t, i18n } = useTranslation()
  const ar = i18n.language === 'ar'
  const { currentWorker } = useStation()
  const deviceId = useMemo(() => stationDeviceId(), [])
  const tRef = useRef(t)
  tRef.current = t

  const [active, setActive] = useState(false)
  const [pairing, setPairing] = useState<ScanPairingStatus | null>(null)
  const [modalOpen, setModalOpen] = useState(false)
  const [offer, setOffer] = useState<ScanPairingCreated | null>(null)
  const [busy, setBusy] = useState(false)
  const [pairError, setPairError] = useState<string | null>(null)
  const [linkDownSince, setLinkDownSince] = useState<number | null>(null)
  const [reconnecting, setReconnecting] = useState(false)

  // ── targets + outcome bookkeeping ─────────────────────────────────────────
  const targets = useRef<{ id: number; get: () => PhoneTargetHandle }[]>([])
  const nextId = useRef(1)
  const [targetsVersion, bumpTargets] = useReducer((n: number) => n + 1, 0)
  /** Events handed to a target whose onScan hasn't started: eventId → target id. */
  const handed = useRef(new Map<string, number>())
  const answered = useRef(new Set<string>())

  const answer = useCallback((eventId: string, success: boolean, message: string) => {
    if (answered.current.has(eventId)) return
    answered.current.add(eventId)
    if (answered.current.size > 500) answered.current.delete(answered.current.values().next().value as string)
    handed.current.delete(eventId)
    postRelayOutcome(eventId, success ? 'accepted' : 'rejected', message).catch(() => {})
  }, [])

  const started = useCallback((eventId: string) => { handed.current.delete(eventId) }, [])

  const register = useCallback((get: () => PhoneTargetHandle) => {
    const id = nextId.current++
    targets.current = [...targets.current, { id, get }]
    bumpTargets()
    return () => {
      targets.current = targets.current.filter(x => x.id !== id)
      for (const [eventId, targetId] of [...handed.current]) {
        if (targetId === id) answer(eventId, false, tRef.current('phone.notApplied'))
      }
      bumpTargets()
    }
  }, [answer])

  const touch = useCallback(() => bumpTargets(), [])

  const route = useCallback((ev: RelayScanEvent) => {
    if (answered.current.has(ev.id) || handed.current.has(ev.id)) return        // never twice
    const top = targets.current[targets.current.length - 1]
    if (!top) { answer(ev.id, false, tRef.current('phone.noTarget')); return }
    const handle = top.get()
    if (handle.paused) { answer(ev.id, false, tRef.current('phone.busy')); return }
    handed.current.set(ev.id, top.id)
    handle.deliver(ev.code, ev.id)
  }, [answer])

  const scanApi = useMemo<PhoneScanApi>(() => ({ register, touch, answer, started }), [register, touch, answer, started])

  // ── pairing status ────────────────────────────────────────────────────────
  // Loaded whenever an authenticated page shows the control, and again when the station's
  // worker changes (a PIN switch revokes the outgoing worker's pairing server-side).
  useEffect(() => {
    if (!active) { setPairing(null); return }
    let live = true
    getStationPairing(deviceId).then(p => { if (live) setPairing(p) }).catch(() => {})
    return () => { live = false }
  }, [active, deviceId, currentWorker])

  const streamKey = active && (pairing?.status === 'waiting' || pairing?.status === 'connected') ? pairing.pairingId : null
  useEffect(() => {
    if (!streamKey) { setLinkDownSince(null); return }
    return openRelayStream(deviceId, {
      onScan: ev => route(ev),
      onPairing: status => setPairing(status),
      onConnection: up => setLinkDownSince(prev => (up ? null : prev ?? Date.now())),
    })
  }, [streamKey, deviceId, route])

  // "Reconnecting…" only after 5 s down.
  useEffect(() => {
    if (linkDownSince === null) { setReconnecting(false); return }
    const id = window.setTimeout(() => setReconnecting(true), Math.max(0, linkDownSince + 5000 - Date.now()))
    return () => window.clearTimeout(id)
  }, [linkDownSince])

  // The phone claimed the code → close the QR.
  useEffect(() => {
    if (modalOpen && pairing?.status === 'connected') { setModalOpen(false); setOffer(null) }
  }, [modalOpen, pairing?.status])

  // ── the phone header: the top target's label, debounced ───────────────────
  const topLabel = (() => {
    void targetsVersion
    const top = targets.current[targets.current.length - 1]
    return top ? top.get().label : null
  })()
  const connected = pairing?.status === 'connected'
  useEffect(() => {
    if (!connected) return
    const id = window.setTimeout(() => { putStationTarget(deviceId, topLabel).catch(() => {}) }, 400)
    return () => window.clearTimeout(id)
  }, [connected, topLabel, deviceId, pairing?.pairingId])

  // ── actions ───────────────────────────────────────────────────────────────
  const startPairing = useCallback(async () => {
    setModalOpen(true)
    setPairError(null)
    setBusy(true)
    try {
      const created = await createStationPairing(deviceId)
      setOffer(created)
      setPairing({ status: 'waiting', pairingId: created.pairingId, deviceLabel: null,
        pairCodeExpiresAt: created.pairCodeExpiresAt, claimedAt: null, expiresAt: created.expiresAt, reason: null })
    } catch (e) {
      setPairError(e instanceof TransferCommandError ? (ar ? e.messageAr : e.messageEn) : t('fulfill.waybill.phone.createError'))
    } finally {
      setBusy(false)
    }
  }, [deviceId, ar, t])

  const unpair = useCallback(async () => {
    setBusy(true)
    try { await unpairStationPairing(deviceId) } catch { /* the server may already have ended it */ }
    setPairing(NONE('unpaired'))
    setModalOpen(false)
    setOffer(null)
    setBusy(false)
  }, [deviceId])

  const status = useMemo<PhoneStatus>(() => ({
    pairing, reconnecting, busy,
    startPairing: () => { void startPairing() },
    unpair: () => { void unpair() },
    setActive,
  }), [pairing, reconnecting, busy, startPairing, unpair])

  return (
    <PhoneScanContext.Provider value={scanApi}>
      <PhoneStatusContext.Provider value={status}>
        {children}
        {modalOpen && active && (
          <Suspense fallback={null}>
            <PhonePairModal offer={offer} starting={busy} error={pairError}
              onNewCode={() => void startPairing()} onCancel={() => void unpair()} />
          </Suspense>
        )}
      </PhoneStatusContext.Provider>
    </PhoneScanContext.Provider>
  )
}
