import { useEffect, useRef, useState, useCallback } from 'react'
import { useNavigate, Link } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import {
  X, ArrowLeft, ArrowRight, CheckCircle2, AlertTriangle, ScanLine,
  Lock, Layers, RefreshCw, ChevronRight, Printer,
} from 'lucide-react'
import { Badge, Button, Skeleton, EmptyState, ProductThumb } from '../components/ui'
import Layout from '../components/Layout'
import PrintWaybillsDialog from './fulfill/PrintWaybillsDialog'
import { getAccessToken, clearAccessToken } from '../auth'
import { TransferCommandError } from '../api'
import { useCapabilities } from '../capabilities'
import ScanHelperChips from '../components/scanHelpers/ScanHelperChips'
import { useScanner, ScanMeta, ScanOutcome } from '../hooks/useScanner'
import { usePhoneScanRegistration, usePhoneScanTarget } from '../phone/usePhoneScanTarget'
import { PhoneScanButton } from '../phone/PhoneScanButton'

const BASE = '/api/v1'

function authHeaders(): Record<string, string> {
  const token = getAccessToken()
  return token ? { Authorization: `Bearer ${token}` } : {}
}

async function api<T = void>(path: string, opts: RequestInit = {}): Promise<{ data: T; status: number }> {
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    ...authHeaders(),
    ...(opts.headers as Record<string, string> ?? {}),
  }
  const res = await fetch(BASE + path, { ...opts, headers })
  if (res.status === 401) {
    clearAccessToken()
    window.location.href = '/login'
    throw new Error('Unauthenticated')
  }
  if (res.status === 204) return { data: undefined as T, status: 204 }
  const data = res.status !== 204 ? await res.json().catch(() => null) : null
  return { data, status: res.status }
}

// ── Types ──────────────────────────────────────────────────────────────────────

interface QueueOrder {
  id: string
  number: string | null
  customer_name: string | null
  status: string
  payment_method: string | null
  cod_amount: string | null
  total_units: number
  scanned_units: number
  locked_by: string | null
  locked_at: string | null
  is_self_pickup: boolean
  is_exchange: boolean
  /** S2 — the order's latest forward shipment is in a print batch. */
  awb_printed?: boolean
}

interface AllocatedPiece {
  piece_id: string
  barcode: string
  allocation_status: string
  piece_status: string
}

interface OrderItem {
  id: string
  variant_id: string
  sku: string | null
  variant_title: string
  product_title: string
  quantity: number
  /** Product image (products.image_url, Shopify CDN) — null when the product has none. */
  imageUrl: string | null
  allocated: number
  allocatedPieces: AllocatedPiece[]
}

interface OrderDetail {
  id: string
  number: string | null
  customer_name: string | null
  customer_phone: string | null
  status: string
  payment_method: string | null
  cod_amount: string | null
  locked_by: string | null
  is_self_pickup: boolean
  is_exchange: boolean
  cancel_requested_at: string | null
  shipment_id: string | null
  tracking_number: string | null
  shipment_has_courier: boolean
  /** S2 — this shipment's waybill was printed in a print batch. */
  awbPrinted?: boolean
  items: OrderItem[]
}

interface ScanResult {
  success: boolean
  code: string
  message: string | null
  pieceId: string | null
  barcode: string | null
  allocatedCount: number
  requiredQuantity: number
  allComplete: boolean
}

interface CancelResult {
  status: string
  message: string | null
  remainingPacked: number
}

interface AwbLinkResponse {
  shipmentId: string
  trackingNumber: string
  linkedPieces: number
  orderStatus: string
}

type FlashState = 'idle' | 'success' | 'error'
type AwbMsg = { type: 'error' | 'info'; text: string } | null

// ── Audio ──────────────────────────────────────────────────────────────────────

// SAFETY-CRITICAL — do not modify
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

// ── AWB PDF helper ─────────────────────────────────────────────────────────────

async function printAwbPdf(shipmentId: string): Promise<'opened' | 'emailed'> {
  const { data, status } = await api<{
    pdfBase64List: string[]
    emailMessage: string | null
    exceptions: Array<{ trackingNumber: string; reason: string }>
  }>('/bosta/awb/print', {
    method: 'POST',
    body: JSON.stringify({ shipmentIds: [shipmentId] }),
  })

  if (status < 200 || status >= 300) {
    // Typed {code, message_en, message_ar} bodies (e.g. NoBostaAccountException via
    // ApiExceptionHandler) — same contract TransferCommandError already wraps
    // elsewhere in the app; surfaced AS-IS by the caller instead of a bare status code.
    const body = data as unknown as
      { code?: string; message_en?: string; message_ar?: string } | null
    if (body?.code && body.message_en != null && body.message_ar != null) {
      throw new TransferCommandError(body as { code: string; message_en: string; message_ar: string })
    }
    throw new Error((data as { message?: string })?.message ?? `HTTP ${status}`)
  }

  if (data?.pdfBase64List?.length > 0) {
    const bytes = atob(data.pdfBase64List[0])
    const arr = new Uint8Array(bytes.length)
    for (let i = 0; i < bytes.length; i++) arr[i] = bytes.charCodeAt(i)
    const blob = new Blob([arr], { type: 'application/pdf' })
    window.open(URL.createObjectURL(blob), '_blank')
    return 'opened'
  }

  if (data?.emailMessage) return 'emailed'

  if (data?.exceptions?.length > 0) {
    throw new Error(data.exceptions[0].reason ?? 'AWB not printable')
  }

  throw new Error('No PDF returned from print endpoint')
}

// ── AWB-scan dialog ────────────────────────────────────────────────────────────
//
// Two call sites, same scan/link/error-handling logic, both variant='inline' at
// pre-Complete and variant='modal' at post-Complete — but BOTH now fire onLinked()
// the instant a scan succeeds, closing this dialog with no success sub-view of its
// own. There is exactly ONE completion screen in the whole flow: PickScreen's own
// `completed` card (Next order / Back to Queue). This dialog used to also show its
// own "linked" success view (checkmark + tracking + Done) for the post-Complete
// call site only, chaining straight into PickScreen's completion card right after —
// two success screens in a row for one action. Fixed by making the post-Complete
// call site behave exactly like the pre-Complete one already did.
//   - pre-Complete (variant='inline'): the packer must link an unlinked order
//     before Complete can appear at all. PickScreen closes this and re-fetches the
//     order; the existing Print Waybill button (now enabled) takes over.
//   - post-Complete (variant='modal'): the mandatory verify-scan. PickScreen closes
//     this and shows its own completion card.
// No "Skip — link later" escape hatch in either — this step cannot be bypassed.

function AwbLinkDialog({
  orderId,
  onLinked: onLinkedProp,
  variant = 'modal',
  demoTracking = null,
  orderNumber = null,
}: {
  orderId: string
  onLinked: (result: { tracking: string; shipmentId: string }) => void
  /** Q1: shown in the paired phone's header while this step is open ("Link AWB · #1047"). */
  orderNumber?: string | null
  variant?: 'modal' | 'inline'
  /** scanHelpers tenants ONLY — demo / review (caller passes null otherwise): THIS order's own linked forward
   *  tracking number, from the order detail PickScreen already holds. Offered as a
   *  "Use this AWB" button that submits through handleLink — the same path as a typed
   *  or scanned AWB, so the server's AWB_MISMATCH/conflict checks still apply. It is
   *  not a skip: the dialog stays mandatory and the input is untouched. */
  demoTracking?: string | null
}) {
  const { t } = useTranslation()
  const inputRef = useRef<HTMLInputElement>(null)
  const [flash, setFlash] = useState<FlashState>('idle')
  const [linking, setLinking] = useState(false)
  const [conflictError, setConflictError] = useState(false)
  const [mismatchError, setMismatchError] = useState<{ scanned: string; existing: string } | null>(null)
  const [genericError, setGenericError] = useState(false)

  useEffect(() => { inputRef.current?.focus() }, [])

  // Bug fix — holds focus on THIS dialog's own input while it's open. PickScreen's
  // scan input has its own SAFETY-CRITICAL "refocus me on any click" document
  // listener that stays active the whole time PickScreen is mounted — including
  // while this dialog (inline pre-Complete, or the modal post-Complete verify-scan)
  // is open alongside/over it, since neither AwbLinkDialog variant unmounts
  // PickScreen. Without this, any click while typing/scanning into THIS input
  // (even the click that focuses it) gets immediately stolen back to PickScreen's
  // input — "select then immediately deselect". Listeners on the same target fire
  // in attachment order, and this one always attaches after PickScreen's (it can
  // only mount once PickScreen already has), so it always runs second and wins —
  // fixes the fight without touching PickScreen's marked-do-not-modify refocus
  // effect or its scan handler at all.
  useEffect(() => {
    const refocus = () => {
      if (document.activeElement !== inputRef.current) inputRef.current?.focus()
    }
    document.addEventListener('click', refocus)
    return () => document.removeEventListener('click', refocus)
  }, [])

  // P1 (2026-10-04): refocus the input once a link attempt finishes. handleLink's own focus()
  // calls run while `linking` still disables the input — a no-op — so after a 409 AWB_MISMATCH,
  // a conflict, another error or a network failure focus was lost until the next click. On
  // success the dialog closes (onLinked) and this never matters.
  const wasLinking = useRef(false)
  useEffect(() => {
    if (wasLinking.current && !linking) inputRef.current?.focus()
    wasLinking.current = linking
  }, [linking])

  // SAFETY-CRITICAL — do not modify
  const triggerFlash = (state: 'success' | 'error') => {
    setFlash(state)
    setTimeout(() => setFlash('idle'), 600)
  }

  // Q1 — phone scans while this step is open. The dialog registers as the tablet's scan target ON
  // TOP of PickScreen's (it mounts after it), and each phone scan goes through handleLink — the
  // same path as a typed / scanned AWB, untouched. One at a time; one outcome each: "AWB linked"
  // when handleLink reached onLinked; otherwise the error this step then shows (read after the
  // render that holds it); "Tablet busy" if a link from the tablet itself is still in flight.
  const linkedRef = useRef(false)
  const onLinked = (result: { tracking: string; shipmentId: string }) => {
    linkedRef.current = true
    onLinkedProp(result)
  }
  const linkingRef = useRef(false)
  linkingRef.current = linking
  const phoneChain = useRef<Promise<void>>(Promise.resolve())
  const phoneFailed = useRef<string[]>([])
  const [phoneTick, setPhoneTick] = useState(0)
  const phoneApi = usePhoneScanRegistration({
    label: orderNumber ? t('phone.target.link', { number: orderNumber }) : t('fulfill.linkAwb.title'),
    deliver: (code, eventId) => {
      phoneChain.current = phoneChain.current.then(async () => {
        const api = phoneApiRef.current
        if (!api) return
        if (linkingRef.current) { api.answer(eventId, false, t('phone.busy')); return }
        api.started(eventId)
        linkedRef.current = false
        linkingRef.current = true
        await handleLink(code)
        if (linkedRef.current) { api.answer(eventId, true, t('phone.linked')); return }
        phoneFailed.current.push(eventId)
        setPhoneTick(n => n + 1)
      })
    },
  })
  const phoneApiRef = useRef(phoneApi)
  phoneApiRef.current = phoneApi
  const phoneError = conflictError ? t('fulfill.linkAwb.conflict')
    : mismatchError ? t('fulfill.linkAwb.awbMismatch', { scanned: mismatchError.scanned, existing: mismatchError.existing })
    : t('fulfill.linkAwb.error')
  const phoneErrorRef = useRef(phoneError)
  phoneErrorRef.current = phoneError
  useEffect(() => {
    for (const id of phoneFailed.current.splice(0)) phoneApiRef.current?.answer(id, false, phoneErrorRef.current)
  }, [phoneTick])
  useEffect(() => () => {
    for (const id of phoneFailed.current.splice(0)) phoneApiRef.current?.answer(id, false, phoneErrorRef.current)
  }, [])

  const handleLink = async (tracking: string) => {
    // Strip ALL whitespace, not just the ends — a pasted value can carry internal
    // spaces/newlines (e.g. copied from a multi-line source) that .trim() alone
    // wouldn't catch, and the backend's normalizer expects a clean digit string.
    const normalized = tracking.replace(/\s+/g, '')
    if (!normalized || linking) return
    setLinking(true)
    setConflictError(false)
    setMismatchError(null)
    setGenericError(false)
    try {
      const { data, status } = await api<AwbLinkResponse>(`/fulfill/${orderId}/link`, {
        method: 'POST',
        body: JSON.stringify({ trackingNumber: normalized }),
      })
      if (status === 200 || status === 201) {
        playBeep(true)
        triggerFlash('success')
        onLinked({ tracking: data.trackingNumber, shipmentId: data.shipmentId })
      } else if (status === 409) {
        playBeep(false)
        triggerFlash('error')
        const errBody = data as unknown as { code?: string; scannedAwb?: string; existingAwb?: string }
        if (errBody?.code === 'AWB_MISMATCH') {
          setMismatchError({ scanned: errBody.scannedAwb ?? '', existing: errBody.existingAwb ?? '' })
        } else {
          setConflictError(true)
        }
        if (inputRef.current) { inputRef.current.value = ''; inputRef.current.focus() }
      } else {
        playBeep(false)
        triggerFlash('error')
        setGenericError(true)
        if (inputRef.current) { inputRef.current.value = ''; inputRef.current.focus() }
      }
    } catch {
      playBeep(false)
      triggerFlash('error')
      setGenericError(true)
    } finally {
      setLinking(false)
    }
  }

  // SAFETY-CRITICAL — flash overlay: do not modify
  const flashOverlay =
    flash === 'success' ? 'fixed inset-0 bg-success/20 pointer-events-none z-[60] animate-flash'
    : flash === 'error' ? 'fixed inset-0 bg-danger/20 pointer-events-none z-[60] animate-flash'
    : 'hidden'

  // No "linked" success sub-view here anymore — onLinked() fires the instant a scan
  // succeeds (both call sites), closing this dialog before any success state would
  // ever render. The single completion screen lives entirely in the caller
  // (PickScreen's `completed` card / the pre-Complete inline close+reload).
  const hasError = conflictError || mismatchError || genericError
  const stateTag = conflictError
    ? { text: t('fulfill.linkAwb.stateConflict'), className: 'text-critical-text' }
    : mismatchError
    ? { text: t('fulfill.linkAwb.stateMismatch'), className: 'text-warning-text' }
    : { text: t('fulfill.linkAwb.stateScanning'), className: 'text-muted' }

  const card = (
    <div className={variant === 'modal'
      ? 'bg-panel rounded-2xl shadow-e3 p-[22px] w-full max-w-md mx-4'
      : 'bg-panel rounded-2xl border border-line p-4 w-full'}>
      <p className={`text-caption font-semibold uppercase tracking-wide mb-2 ${stateTag.className}`}>
        {stateTag.text}
      </p>

      <h2 className="text-h4 text-primary mb-1">{t('fulfill.linkAwb.title')}</h2>
      <p className="text-small text-muted mb-4">{t('fulfill.linkAwb.subtitle')}</p>

      {/* SAFETY-CRITICAL scan input — ref, onKeyDown, disabled behavior untouched;
          icon is a purely visual sibling <span>, no input props/logic affected */}
      <div className="relative mb-3">
        <span className="absolute start-3 top-1/2 -translate-y-1/2 text-trace-blue pointer-events-none">
          <ScanLine size={18} strokeWidth={2} />
        </span>
        <input
          ref={inputRef}
          type="text"
          placeholder={t('fulfill.linkAwb.placeholder')}
          className="input-scan w-full ps-10"
          disabled={linking}
          onKeyDown={e => {
            if (e.key === 'Enter') handleLink((e.target as HTMLInputElement).value)
          }}
        />
      </div>

      {demoTracking && (
        <div className="flex items-center justify-between gap-2 mb-3 bg-elevated border border-dashed border-line rounded-lg px-3 py-2"
             data-testid="demo-awb-helper">
          <div className="min-w-0">
            <p className="text-caption text-muted">{t('fulfill.demoAwb.hint')}</p>
            <p className="text-small font-mono text-primary" dir="ltr">{demoTracking}</p>
          </div>
          <button
            type="button"
            disabled={linking}
            onClick={() => handleLink(demoTracking)}
            className="btn-brand btn text-small flex-shrink-0"
          >
            {t('fulfill.demoAwb.use')}
          </button>
        </div>
      )}

      {hasError && (
        <div className="flex items-start gap-2 mb-3">
          <AlertTriangle size={15} strokeWidth={2} className="text-critical flex-shrink-0 mt-0.5" />
          {conflictError && (
            <p className="text-critical-text text-small font-medium">{t('fulfill.linkAwb.conflict')}</p>
          )}
          {mismatchError && (
            <p className="text-warning-text text-small font-medium">
              {t('fulfill.linkAwb.awbMismatch', { scanned: mismatchError.scanned, existing: mismatchError.existing })}
            </p>
          )}
          {genericError && (
            <p className="text-critical-text text-small font-medium">{t('fulfill.linkAwb.error')}</p>
          )}
        </div>
      )}

      {/* No skip/bypass — the verify-scan is mandatory in every call site. */}
      <p className="text-caption text-muted">{t('fulfill.linkAwb.mandatoryNote')}</p>
    </div>
  )

  if (variant === 'inline') {
    return (
      <>
        {/* SAFETY-CRITICAL flash overlay — do not modify */}
        <div className={flashOverlay} />
        {card}
      </>
    )
  }

  return (
    <div className="fixed inset-0 bg-black/70 flex items-center justify-center z-50">
      {/* SAFETY-CRITICAL flash overlay — do not modify */}
      <div className={flashOverlay} />
      {card}
    </div>
  )
}

// ── Handover screen (self_pickup_pending orders) ───────────────────────────────

function HandoverScreen({ order, onBack }: { order: QueueOrder; onBack: () => void }) {
  const { t, i18n } = useTranslation()
  const [confirming, setConfirming] = useState(false)
  const [done, setDone] = useState(false)
  const [count, setCount] = useState(0)
  const [deliveredAt, setDeliveredAt] = useState<Date | null>(null)
  const BackIcon = i18n.language === 'ar' ? ArrowRight : ArrowLeft

  async function confirm() {
    if (confirming) return
    setConfirming(true)
    try {
      const { data } = await api<{ deliveredPieces: number }>(`/fulfill/${order.id}/handover`, { method: 'POST' })
      setCount(data.deliveredPieces)
      setDeliveredAt(new Date())
      setDone(true)
      setTimeout(() => onBack(), 2000)
    } finally {
      setConfirming(false)
    }
  }

  return (
    <div className="flex flex-col h-screen bg-base">
      <div className="bg-panel border-b border-line px-6 py-3.5 flex items-center gap-3">
        <button onClick={onBack} className="text-primary hover:text-muted transition-colors flex-shrink-0" aria-label={t('fulfill.back')}>
          <BackIcon size={20} strokeWidth={2} />
        </button>
        <p className="text-body font-medium text-primary">
          {t('fulfill.selfPickup')} — <span className="font-mono">{order.number ?? order.id.slice(-8)}</span>
        </p>
      </div>

      <div className="flex-1 flex flex-col items-center justify-center p-8">
        {done ? (
          <div className="text-center flex flex-col items-center gap-3">
            <CheckCircle2 size={64} strokeWidth={2} className="text-success" />
            <p className="text-h2 text-primary font-bold">
              {t('fulfill.handoverSuccess', { count })}
            </p>
            <p className="text-body text-muted">
              <span className="font-mono">{order.number ?? order.id.slice(-8)}</span> · {order.customer_name ?? t('common.pendingConsignee')}
            </p>
            {deliveredAt && (
              <p className="text-caption text-muted font-mono">{deliveredAt.toLocaleString(i18n.language)}</p>
            )}
          </div>
        ) : (
          <div className="w-full max-w-md flex flex-col gap-4">
            <div className="card p-[18px] flex flex-col gap-2.5">
              <p className="text-h4 text-primary font-bold">
                {order.customer_name ?? t('common.pendingConsignee')}
              </p>
              <p className="text-caption text-muted">{t('fulfill.handoverVerifyId')}</p>
              <div className="h-px bg-line my-1" />
              <div className="text-small flex justify-between text-primary">
                <span>{t('fulfill.units')}</span>
                <span className="text-muted font-mono">×{order.total_units}</span>
              </div>
              {order.payment_method === 'cod' && order.cod_amount && (
                <>
                  <div className="h-px bg-line my-1" />
                  <div className="text-small flex justify-between font-semibold text-primary">
                    <span>{t('fulfill.handoverCollectLabel')}</span>
                    <span className="font-mono">COD {order.cod_amount}</span>
                  </div>
                </>
              )}
            </div>

            {/*
              py-5 is intentional: large tap target for warehouse handover.
              Kept as raw <button> so py-5 is not overridden by the Button component's
              size-based padding. btn-brand is a DS class (no hardcoded hex).
            */}
            <button
              onClick={confirm}
              disabled={confirming}
              className="btn-brand btn text-body w-full py-5"
            >
              {confirming ? '…' : t('fulfill.handoverConfirm')}
            </button>
          </div>
        )}
      </div>
    </div>
  )
}

// ── Queue view ─────────────────────────────────────────────────────────────────

const QUEUE_GRID_COLS = 'grid-cols-1 md:grid-cols-[120px_1fr_180px_120px_140px]'

// ── Empty state: orders waiting for a Bosta waybill ──────────────────────────

interface AwaitingWaybillHint {
  count: number
  /** null = unknown (e.g. a worker, who can't read /connections) — no Connect link. */
  bostaConnected: boolean | null
}

async function loadAwaitingWaybillHint(): Promise<AwaitingWaybillHint | null> {
  try {
    const { data, status } = await api<{ count: number }>('/fulfill/queue/awaiting-waybill-count')
    if (status !== 200 || typeof data?.count !== 'number' || data.count <= 0) return null
    let bostaConnected: boolean | null = null
    try {
      const conn = await api<{ bosta?: { connected?: boolean } }>('/connections')
      if (conn.status === 200 && typeof conn.data?.bosta?.connected === 'boolean') {
        bostaConnected = conn.data.bosta.connected
      }
    } catch { /* unknown — link omitted */ }
    return { count: data.count, bostaConnected }
  } catch {
    return null
  }
}

function AwaitingWaybillEmptyState({ hint }: { hint: AwaitingWaybillHint }) {
  const { t } = useTranslation()
  return (
    <div className="flex flex-col items-center justify-center py-16 gap-3 text-center" data-testid="fulfill-awaiting-waybill">
      <div className="w-12 h-12 rounded-xl bg-elevated flex items-center justify-center text-xl">📦</div>
      <p className="text-body font-semibold text-primary">{t('fulfill.empty')}</p>
      <p className="text-small text-muted max-w-md">
        {t('fulfill.awaitingWaybill', { count: hint.count })}
      </p>
      {hint.bostaConnected === false && (
        <Link to="/settings?tab=connections" className="btn-brand btn text-small mt-1" data-testid="fulfill-connect-bosta">
          {t('fulfill.connectBosta')}
        </Link>
      )}
    </div>
  )
}

function QueueView({
  queue,
  loading,
  loadQueue,
  onSelect,
  onHandover,
  highlightOrderId,
}: {
  queue: QueueOrder[]
  loading: boolean
  loadQueue: () => void
  onSelect: (orderId: string) => void
  onHandover: (order: QueueOrder) => void
  highlightOrderId: string | null
}) {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const [showPrint, setShowPrint] = useState(false)

  // Empty-queue hint: open orders held out of the queue ONLY because no Bosta waybill
  // exists yet (PICKABLE_ORDERS_FILTER requires a 'created' forward shipment). Fetched
  // only when the queue is fully empty — a non-empty queue makes no extra calls. Any
  // failure falls back to the plain "No orders ready to pick" message. /connections is
  // owner/manager-only; for a worker it fails and the Connect Bosta link is omitted.
  const queueEmpty = !loading && queue.length === 0
  const [waybillHint, setWaybillHint] = useState<AwaitingWaybillHint | null>(null)
  useEffect(() => {
    if (!queueEmpty) { setWaybillHint(null); return }
    let cancelled = false
    loadAwaitingWaybillHint().then(h => { if (!cancelled) setWaybillHint(h) })
    return () => { cancelled = true }
  }, [queueEmpty, queue])

  if (loading) return (
    <div className="space-y-3">
      <Skeleton className="h-8 w-48 rounded-xl" />
      {Array.from({ length: 3 }).map((_, i) => (
        <Skeleton key={i} className="h-20 rounded-2xl" />
      ))}
    </div>
  )

  const pickQueue     = queue.filter(o => o.status !== 'self_pickup_pending')
  const handoverQueue = queue.filter(o => o.status === 'self_pickup_pending')
  // S2 — waybills that can be printed: the queue minus self-pickup (same set the server prints).
  const printable     = queue.filter(o => !o.is_self_pickup && o.status !== 'self_pickup_pending')
  const printedCount  = printable.filter(o => o.awb_printed).length

  function paymentPill(order: QueueOrder) {
    if (order.payment_method === 'cod' && order.cod_amount) {
      return (
        <span className="inline-flex items-center whitespace-nowrap bg-elevated border border-line text-muted text-caption font-semibold px-2 py-0.5 rounded-full">
          COD {order.cod_amount}
        </span>
      )
    }
    return (
      <span className="inline-flex items-center whitespace-nowrap bg-muted/[0.14] border border-muted/[0.30] text-neutral-text text-caption font-semibold px-2 py-0.5 rounded-full">
        {t('common.prepaid', { defaultValue: 'Prepaid' })}
      </span>
    )
  }

  function progressBar(order: QueueOrder, progress: number) {
    return (
      <div className="flex items-center gap-2">
        <div className="flex-1 h-1.5 bg-elevated rounded-full min-w-[64px]">
          <div
            className={`h-full rounded-full transition-all ${order.locked_by ? 'bg-warning' : progress >= 100 ? 'bg-success' : 'bg-trace-blue'}`}
            style={{ width: `${progress}%` }}
          />
        </div>
        <span className={`text-caption font-mono flex-shrink-0 ${progress >= 100 ? 'text-success-text' : 'text-muted'}`}>
          {order.scanned_units}/{order.total_units}
        </span>
      </div>
    )
  }

  return (
    <div data-testid="fulfill-queue">
      <div className="flex items-center justify-between mb-6">
        <div>
          <h1 className="text-h1 text-primary">{t('fulfill.title')}</h1>
          {printable.length > 0 && (
            <p className="text-small text-muted mt-1" data-testid="waybill-status-line">
              {t('fulfill.printBatch.statusLine', {
                printed: printedCount, notPrinted: printable.length - printedCount,
              })}
            </p>
          )}
        </div>
        <div className="flex items-center gap-3">
          {printable.length > 0 && (
            <Button size="sm" iconStart={Printer} onClick={() => setShowPrint(true)}>
              {t('fulfill.printBatch.button')}
            </Button>
          )}
          <Button variant="secondary" size="sm" iconStart={Layers} onClick={() => navigate('/fulfill/gather')}>
            {t('fulfill.gatherBtn')}
          </Button>
          <button
            onClick={loadQueue}
            aria-label={t('fulfill.refresh')}
            className="text-muted hover:text-primary transition-colors"
          >
            <RefreshCw size={16} strokeWidth={2} />
          </button>
        </div>
      </div>

      {/* Self-pickup pending section */}
      {handoverQueue.length > 0 && (
        <div className="mb-6 bg-warning/[0.14] border border-warning/[0.30] rounded-xl p-4 flex flex-col gap-2.5">
          <p className="text-caption font-bold text-warning-text uppercase tracking-wide">
            {t('fulfill.selfPickupPending')} ({handoverQueue.length})
          </p>
          <div className="flex flex-col md:flex-row md:flex-wrap gap-2 md:gap-6">
            {handoverQueue.map(order => (
              <div
                key={order.id}
                className="flex items-center justify-between md:justify-start gap-2.5 text-small cursor-pointer hover:opacity-80 transition-opacity"
                onClick={() => onHandover(order)}
              >
                <span className="font-mono font-semibold text-primary">{order.number ?? order.id.slice(-8)}</span>
                <span className="text-muted">{order.customer_name ?? t('common.pendingConsignee')}</span>
                <ChevronRight size={14} strokeWidth={2} className="text-warning-text rtl:rotate-180" />
              </div>
            ))}
          </div>
        </div>
      )}

      {/* Normal pick queue */}
      {pickQueue.length === 0 && handoverQueue.length === 0 ? (
        waybillHint
          ? <AwaitingWaybillEmptyState hint={waybillHint} />
          : <EmptyState message={t('fulfill.empty')} icon="📦" />
      ) : pickQueue.length === 0 ? null : (
        <>
          {handoverQueue.length > 0 && (
            <h2 className="text-caption text-muted font-semibold uppercase tracking-wide mb-3">
              {t('fulfill.pickQueueHeader')}
            </h2>
          )}

          {/* Real column grid — desktop: 5 aligned columns with a header row.
              Mobile: same grid collapses to grid-cols-1, so each field stacks as
              its own line — one tree, no duplicated content (jsdom doesn't apply
              media queries, so a genuinely separate mobile/desktop tree produces
              duplicate text nodes and breaks getByText/getByTestId — learned this
              the hard way in the previous pass). */}
          <div className={`hidden md:grid ${QUEUE_GRID_COLS} gap-4 px-3 pb-2 text-caption text-muted uppercase tracking-wide font-semibold`}>
            <span>{t('common.order')}</span>
            <span>{t('common.customer')}</span>
            <span>{t('common.progress')}</span>
            <span>{t('common.payment')}</span>
            <span>{t('common.status')}</span>
          </div>

          <div className="space-y-2">
            {pickQueue.map(order => {
              const progress = order.total_units > 0
                ? Math.round((order.scanned_units / order.total_units) * 100) : 0
              const highlighted = order.id === highlightOrderId
              return (
                <div
                  key={order.id}
                  className={`grid ${QUEUE_GRID_COLS} gap-2 md:gap-4 md:items-center card p-3 md:py-2.5 cursor-pointer transition hover:border-trace-blue/50 ${
                    order.locked_by ? 'opacity-70' : ''
                  } ${highlighted ? 'border-trace-blue shadow-ring-accent' : ''}`}
                  onClick={() => onSelect(order.id)}
                >
                  <span className="font-mono text-small font-semibold text-primary">
                    {order.number ?? order.id.slice(-8)}
                  </span>

                  <div className="flex items-center gap-2 min-w-0">
                    <span className="text-caption md:text-small text-muted truncate">
                      {order.customer_name ?? t('common.pendingConsignee')}
                    </span>
                    {order.is_self_pickup && <Badge tone="info" label={t('fulfill.selfPickup')} />}
                    {order.is_exchange && <Badge tone="info" label={t('exchange.badge')} />}
                  </div>

                  {progressBar(order, progress)}

                  {paymentPill(order)}

                  <div>
                    {order.locked_by ? (
                      <span className="inline-flex items-center gap-1.5 bg-warning/[0.14] border border-warning/[0.30] text-warning-text text-caption font-semibold px-2 py-0.5 rounded-full">
                        <Lock size={10} strokeWidth={2} />
                        {t('fulfill.locked')}
                      </span>
                    ) : (
                      <Badge status={order.status} />
                    )}
                  </div>
                </div>
              )
            })}
          </div>
        </>
      )}

      {showPrint && (
        <PrintWaybillsDialog
          newCount={printable.length - printedCount}
          allCount={printable.length}
          onClose={printed => { setShowPrint(false); if (printed) loadQueue() }}
        />
      )}
    </div>
  )
}

// ── Guided unpack panel ────────────────────────────────────────────────────────

function GuidedUnpackPanel({
  order,
  onUnpacked,
}: {
  order: OrderDetail
  onUnpacked: () => void
}) {
  const { t } = useTranslation()
  const [unpacking, setUnpacking] = useState<string | null>(null)
  const [done, setDone] = useState(false)

  const packedPieces = order.items.flatMap(i =>
    i.allocatedPieces
      .filter(p => p.piece_status === 'packed')
      .map(p => ({
        ...p,
        itemTitle: i.variant_title && i.variant_title !== 'Default Title'
          ? `${i.product_title} · ${i.variant_title}` : i.product_title,
      }))
  )

  async function unpack(pieceId: string) {
    if (unpacking) return
    setUnpacking(pieceId)
    try {
      const { data } = await api<{ cancelled: boolean; remainingPacked: number }>(
        `/fulfill/${order.id}/unpack/${pieceId}`,
        { method: 'POST' }
      )
      if (data.cancelled) {
        setDone(true)
        setTimeout(() => onUnpacked(), 1500)
      } else {
        onUnpacked()
      }
    } finally {
      setUnpacking(null)
    }
  }

  if (done) {
    return (
      <div className="card border-success/40 bg-success/[0.14] p-6 text-center">
        <CheckCircle2 size={32} strokeWidth={2} className="text-success mx-auto mb-2" />
        <p className="text-success font-medium text-body">{t('fulfill.unpackDone')}</p>
      </div>
    )
  }

  return (
    <div className="card border-warning/[0.30] bg-warning/[0.14] p-4">
      <p className="text-small font-medium text-warning-text mb-3">
        {t('fulfill.cancelRequested', { count: packedPieces.length })}
      </p>
      <div className="space-y-2">
        {packedPieces.map(p => (
          <div
            key={p.piece_id}
            className="flex items-center justify-between gap-2 bg-elevated rounded-lg border border-line px-3 py-2.5"
          >
            {/* Barcode — font-mono per spec */}
            <span className="font-mono text-caption text-primary truncate">
              {p.barcode.slice(-10)} <span className="text-muted font-sans">· {p.itemTitle}</span>
            </span>
            <Button
              variant="secondary"
              size="sm"
              loading={unpacking === p.piece_id}
              onClick={() => unpack(p.piece_id)}
              className="flex-shrink-0"
            >
              {t('fulfill.unpackPiece')}
            </Button>
          </div>
        ))}
      </div>
    </div>
  )
}

// ── Pick screen ────────────────────────────────────────────────────────────────

function PickScreen({
  orderId,
  onBack,
  nextOrderId,
  onGoToOrder,
}: {
  orderId: string
  onBack: () => void
  nextOrderId: string | null
  onGoToOrder: (orderId: string) => void
}) {
  const { t, i18n } = useTranslation()
  const DesktopBackIcon = i18n.language === 'ar' ? ArrowRight : ArrowLeft
  // Review mode S7: click-to-scan chips + "Use this AWB" for the demo / review tenant only (/me capability).
  const { scanHelpers } = useCapabilities()
  const [order, setOrder] = useState<OrderDetail | null>(null)
  const [loading, setLoading] = useState(true)
  const [flash, setFlash] = useState<FlashState>('idle')
  const [lastResult, setLastResult] = useState<ScanResult | null>(null)
  const [completing, setCompleting] = useState(false)
  const [cancelling, setCancelling] = useState(false)
  const [showAwbDialog, setShowAwbDialog] = useState(false)
  const [showPreCompleteLink, setShowPreCompleteLink] = useState(false)
  const [awbPrintedOnce, setAwbPrintedOnce] = useState(false)
  const [completed, setCompleted] = useState(false)
  const [showCancelConfirm, setShowCancelConfirm] = useState(false)
  const [awbPrinting, setAwbPrinting] = useState(false)
  const [awbMsg, setAwbMsg] = useState<AwbMsg>(null)
  const autoBackTimer = useRef<ReturnType<typeof setTimeout> | null>(null)

  const loadOrder = useCallback(async () => {
    const { data } = await api<OrderDetail>(`/fulfill/${orderId}`)
    setOrder(data)
    setLoading(false)
  }, [orderId])

  useEffect(() => { loadOrder() }, [loadOrder])

  // S2 — a waybill already printed in a print batch counts as printed: Complete doesn't ask
  // for a reprint. Only ever turns the flag on.
  useEffect(() => { if (order?.awbPrinted) setAwbPrintedOnce(true) }, [order?.awbPrinted])

  // SAFETY-CRITICAL — HID refocus (P1, approved by Marawan 2026-10-04): PickScreen's own
  // [order]-keyed click-refocus effect is gone. useScanner's marked refocus does the same job —
  // refocus on any click for the screen's lifetime, and focus back on the ENABLED input after
  // every scan / when its queue drains, never while a dialog has paused it (focusPaused) and
  // never out of another text field. Re-running on [order] used to pull focus out of the AWB
  // link step whenever the order reloaded (unscan), and after a rejected scan (no order change)
  // nothing refocused at all.

  // SAFETY-CRITICAL — flash trigger: do not modify
  const triggerFlash = (state: 'success' | 'error') => {
    setFlash(state)
    setTimeout(() => setFlash('idle'), 600)
  }

  // SAFETY-CRITICAL — scan handler (P1, approved by Marawan 2026-10-04): useScanner's onScan.
  // The hook queues scans that arrive while one is in flight and runs them strictly one at a
  // time, in order (no input disabling — no dropped keystrokes); it clears the input on Enter and
  // plays the success / fail beep itself (same tones). The flash stays PickScreen's own (its
  // marked trigger + overlay, unchanged). The same request, the same lastResult, the order
  // reloaded after a success — awaited, so the next queued scan sees the reloaded order.
  const onScan = useCallback(async (barcode: string, _meta?: ScanMeta): Promise<ScanOutcome> => {
    try {
      const { data: result } = await api<ScanResult>(`/fulfill/${orderId}/scan`, {
        method: 'POST',
        body: JSON.stringify({ barcode }),
      })
      setLastResult(result)
      if (result.success) {
        triggerFlash('success')
        await loadOrder()
        return { success: true }
      }
      triggerFlash('error')
      return { success: false }
    } catch {
      triggerFlash('error')
      setLastResult({ success: false, code: 'ERROR', message: t('common.error'), pieceId: null, barcode: null, allocatedCount: 0, requiredQuantity: 0, allComplete: false })
      return { success: false }
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [orderId, loadOrder, t])

  // Q1 — phone scans: PickScreen is the tablet's scan target while it's open. The AWB link step /
  // verify-scan registers its own target on top while open (AwbLinkDialog); the cancel confirm
  // pauses this one (phone scans meanwhile are answered "Tablet busy"). The phone's line is the
  // same result the screen shows (lastResult, read after the render that holds it). The attached
  // scanner's clearQueue answers every phone scan it drops "Not applied" — so the calls below
  // (Complete, cancel, the link step, completion) answer them unchanged.
  const phone = usePhoneScanTarget({
    label: order?.number ? t('phone.target.pick', { number: order.number }) : t('phone.target.pickNoOrder'),
    describe: (code, o) => lastResult
      ? (o.success
          ? `${lastResult.barcode ?? code} · ${lastResult.allocatedCount}/${lastResult.requiredQuantity}`
          : t(`fulfill.rejection.${lastResult.code}`, { defaultValue: lastResult.message ?? lastResult.code }))
      : null,
    paused: showCancelConfirm,
  })
  // A dialog owns focus while it's open (the AWB link step, the verify-scan modal, the cancel
  // confirm). The link step's own click-refocus attaches after the hook's, so it wins clicks too.
  const scanner = phone.attach(useScanner({ onScan: phone.wrap(onScan),
    focusPaused: showPreCompleteLink || showAwbDialog || showCancelConfirm }))
  const handleScan = scanner.handleScan

  // Scans still waiting are dropped (not sent) once the order moves past piece scanning.
  const { clearQueue } = scanner
  useEffect(() => { if (completed) clearQueue() }, [completed, clearQueue])

  // The link step belongs to a fully picked order: unscanning a piece closes it (re-tapping
  // "Scan to link" opens it again once the order is complete).
  const allPicked = !!order && order.items.every(i => i.allocated >= i.quantity)
  useEffect(() => { if (!allPicked) setShowPreCompleteLink(false) }, [allPicked])

  const handleUnscan = async (pieceId: string) => {
    await api(`/fulfill/${orderId}/scan/${pieceId}`, { method: 'DELETE' })
    await loadOrder()
  }

  const handleComplete = async () => {
    if (completing) return
    scanner.clearQueue()
    setCompleting(true)
    try {
      await api(`/fulfill/${orderId}/complete`, { method: 'POST' })
      if (order?.is_self_pickup) {
        setCompleted(true)
        // Fallback auto-return if the worker doesn't interact with the completion
        // view — cleared in goNext()/goBack() below if they click first.
        autoBackTimer.current = setTimeout(() => onBack(), 2500)
      } else {
        setShowAwbDialog(true)
      }
    } finally {
      setCompleting(false)
    }
  }

  // Completion-view navigation — clears the self-pickup auto-return fallback (if
  // pending) so it can never fire after the worker has already navigated away.
  const goNextFromCompletion = () => {
    if (autoBackTimer.current) clearTimeout(autoBackTimer.current)
    if (nextOrderId) onGoToOrder(nextOrderId)
  }
  const goBackFromCompletion = () => {
    if (autoBackTimer.current) clearTimeout(autoBackTimer.current)
    onBack()
  }

  const handleCancel = async () => {
    if (cancelling) return
    scanner.clearQueue()
    setCancelling(true)
    setShowCancelConfirm(false)
    try {
      const { data, status } = await api<CancelResult>(`/fulfill/${orderId}/cancel`, { method: 'POST' })
      if (status === 200 && data.status === 'cancelled') {
        onBack()
      } else {
        await loadOrder()
      }
    } finally {
      setCancelling(false)
    }
  }

  const handlePrintAwb = async () => {
    if (!order?.shipment_id || awbPrinting) return
    setAwbPrinting(true)
    setAwbMsg(null)
    try {
      const result = await printAwbPdf(order.shipment_id)
      if (result === 'emailed') {
        setAwbMsg({ type: 'info', text: t('fulfill.printAwb.emailed') })
      }
      // Precondition to Complete: linked AND printed. A successful print — opened or
      // emailed — satisfies this; only a thrown error (caught below) does not.
      setAwbPrintedOnce(true)
    } catch (e: unknown) {
      const text = e instanceof TransferCommandError
        ? (i18n.language === 'ar' ? e.messageAr : e.messageEn)
        : (e as Error).message || t('fulfill.printAwb.error')
      setAwbMsg({ type: 'error', text })
    } finally {
      setAwbPrinting(false)
    }
  }

  if (loading) return (
    <div className="flex flex-col h-screen bg-base">
      <Skeleton className="h-12 rounded-none" />
      <div className="p-6 space-y-4">
        <Skeleton className="h-16 rounded-2xl" />
        <Skeleton className="h-40 rounded-2xl" />
        <Skeleton className="h-40 rounded-2xl" />
      </div>
    </div>
  )
  if (!order) return null

  // Order-complete moment — same success-card language as AwbLinkDialog's linked
  // view (rounded-2xl shadow-e3 p-[22px] card), but a distinct step: this fires
  // right after handleComplete() succeeds (self-pickup) or the post-Complete AWB
  // dialog's Done is clicked (courier) — never merged with the AWB-link card itself.
  if (completed) return (
    <div className="flex flex-col h-screen bg-base items-center justify-center p-8" data-testid="fulfill-pick-complete">
      <div className="bg-panel rounded-2xl shadow-e3 p-[22px] w-full max-w-md flex flex-col items-center gap-4 text-center">
        <CheckCircle2 size={40} strokeWidth={2} className="text-success" />
        <div>
          <p className="text-h4 text-primary font-bold">
            {order.is_self_pickup ? t('fulfill.selfPickupPacked') : t('fulfill.orderComplete')}
          </p>
          <p className="text-body text-muted mt-1">
            <span className="font-mono">{order.number ?? order.id.slice(-8)}</span>
            {order.customer_name && <> · {order.customer_name}</>}
          </p>
        </div>
        <div className="w-full flex flex-col gap-2 pt-2">
          {nextOrderId && (
            <Button onClick={goNextFromCompletion} className="w-full">
              {t('fulfill.nextOrder')}
            </Button>
          )}
          <Button variant="secondary" onClick={goBackFromCompletion} className="w-full">
            {t('fulfill.backToQueue')}
          </Button>
        </div>
      </div>
    </div>
  )

  const allComplete = order.items.every(i => i.allocated >= i.quantity)
  const hasCancelRequest = !!order.cancel_requested_at

  // SAFETY-CRITICAL — flash overlay computation: do not modify
  const flashOverlay =
    flash === 'success' ? 'fixed inset-0 bg-success/20 pointer-events-none z-50 animate-flash'
    : flash === 'error' ? 'fixed inset-0 bg-danger/20 pointer-events-none z-50 animate-flash'
    : 'hidden'

  const itemsList = (
    <div className="flex-1 overflow-y-auto p-6 space-y-3">
      {order.items.map(item => {
        const complete = item.allocated >= item.quantity
        return (
          <div
            key={item.id}
            className={`card p-3.5 flex items-start gap-3 md:gap-4 ${complete ? 'border-success/40' : ''}`}
          >
            {/* Product image (S1) — fixed 88px tile in every state (image / none / failed),
                so a line never shifts; a missing or broken image shows ProductThumb's
                Package placeholder. Lazy-loaded <img>, never focusable — the scan input
                keeps focus. */}
            <ProductThumb
              src={item.imageUrl}
              alt={item.product_title}
              size={88}
              cdnWidth={176}
            />
            <div className="flex-1 min-w-0">
              <div className="flex items-start justify-between gap-3 mb-2.5">
                <div className="min-w-0">
                  <p className="text-body font-semibold text-primary break-words">
                    {item.product_title}
                    {item.variant_title && item.variant_title !== 'Default Title' && (
                      <span className="text-muted font-normal"> · {item.variant_title}</span>
                    )}
                  </p>
                  {/* SKU — font-mono per spec */}
                  {item.sku && <p className="text-caption text-muted font-mono break-words">{item.sku}</p>}
                </div>
                <span className={`text-body font-mono font-semibold flex-shrink-0 ${
                  complete ? 'text-success-text' : 'text-muted'
                }`}>
                  {item.allocated}/{item.quantity}
                </span>
              </div>
              {item.allocatedPieces.length > 0 ? (
                <div className="flex flex-wrap gap-1.5">
                  {item.allocatedPieces.map(p => (
                    <div
                      key={p.piece_id}
                      className="flex items-center gap-1.5 bg-elevated border border-line rounded-full px-2 py-1"
                    >
                      {/* Barcode — font-mono per spec */}
                      <span className="text-caption font-mono text-primary">{p.barcode.slice(-10)}</span>
                      {!hasCancelRequest && p.allocation_status === 'active' && (
                        <button
                          onClick={() => handleUnscan(p.piece_id)}
                          className="text-muted hover:text-critical-text transition-colors"
                          title={t('fulfill.unscan')}
                        >
                          <X size={10} strokeWidth={2.5} />
                        </button>
                      )}
                    </div>
                  ))}
                </div>
              ) : (
                <p className="text-caption text-muted">{t('fulfill.noPiecesScanned')}</p>
              )}
              {scanHelpers && !complete && !hasCancelRequest && (
                <ScanHelperChips
                  context="pieces"
                  variantId={item.variant_id}
                  refreshKey={item.allocated}
                  disabled={scanner.scanning}
                  onScan={handleScan}
                  showNoStock
                />
              )}
            </div>
          </div>
        )
      })}
    </div>
  )

  const scanFeedback = lastResult && (
    lastResult.success
      ? <><span>✓ </span><span className="font-mono">{lastResult.barcode}</span></>
      : `✗ ${t(`fulfill.rejection.${lastResult.code}`, { defaultValue: lastResult.message ?? lastResult.code })}`
  )

  // SAFETY-CRITICAL scan input — ref, onKeyDown, autoFocus: do not modify. P1 (approved by
  // Marawan 2026-10-04): ref is useScanner's, Enter queues through useScanner, and the input is
  // never disabled while a scan is in flight (that dropped the keystrokes of the next scan).
  // Rendered exactly once regardless of breakpoint; the surrounding layout repositions it
  // (mobile: top bar under the header; desktop: bottom of the right-hand scan panel).
  const scanInput = (
    <div className="relative">
      <span className="absolute start-3 top-1/2 -translate-y-1/2 text-trace-blue pointer-events-none">
        <ScanLine size={18} strokeWidth={2} />
      </span>
      <input
        ref={scanner.inputRef}
        type="text"
        placeholder={t('fulfill.scanPlaceholder')}
        className="input-scan w-full ps-10"
        aria-busy={scanner.scanning}
        onKeyDown={e => {
          if (e.key === 'Enter') handleScan((e.target as HTMLInputElement).value)
        }}
        autoFocus
      />
    </div>
  )

  return (
    <div className="flex flex-col h-screen bg-base" data-testid="fulfill-pick">
      {/* SAFETY-CRITICAL flash overlay — do not modify */}
      <div className={flashOverlay} />

      {/* Header — exit is a clear, labeled affordance (not a bare icon) so the
          worker always knows how to get back to the queue from the immersive
          full-screen scan loop. Same onBack handler as before, restyled only. */}
      <div className="bg-panel border-b border-line px-3 md:px-5 h-14 flex items-center gap-3 flex-shrink-0">
        <button
          onClick={onBack}
          className="flex items-center gap-1.5 text-primary hover:bg-elevated transition-colors flex-shrink-0 -ms-1 ps-2 pe-3 py-2 rounded-lg"
        >
          <DesktopBackIcon size={18} strokeWidth={2} />
          <span className="text-small font-semibold">{t('fulfill.backToQueue')}</span>
        </button>
        <p className="flex-1 text-body font-semibold text-primary flex items-center gap-2 truncate">
          <span className="font-mono">{order.number ?? order.id.slice(-8)}</span>
          {order.is_self_pickup && (
            <Badge tone="info" label={t('fulfill.selfPickup')} />
          )}
          {order.is_exchange && (
            <Badge tone="info" label={t('exchange.badge')} />
          )}
        </p>
        {/* Phone as scanner — in the header flow, next to the header actions. */}
        <PhoneScanButton />
        {!hasCancelRequest && (
          <button
            onClick={() => { scanner.clearQueue(); setShowCancelConfirm(true) }}
            className="text-critical-text text-caption font-medium hover:opacity-80 transition-opacity flex-shrink-0"
          >
            {t('fulfill.cancelOrder')}
          </button>
        )}
      </div>

      {/* Cancel confirm dialog */}
      {showCancelConfirm && (
        <div className="fixed inset-0 bg-black/60 flex items-center justify-center z-50" onClick={() => setShowCancelConfirm(false)}>
          <div className="bg-panel rounded-2xl shadow-e3 p-[22px] w-full max-w-sm mx-4" onClick={e => e.stopPropagation()}>
            <h3 className="text-h4 font-bold text-primary mb-2">{t('fulfill.cancelDialogTitle')}</h3>
            <p className="text-small text-muted mb-4">
              {order.status === 'packed'
                ? t('fulfill.cancelPackedHint')
                : t('fulfill.cancelPreHint')}
            </p>
            <div className="flex gap-2">
              <Button variant="secondary" size="sm" onClick={() => setShowCancelConfirm(false)} className="flex-1">
                {t('fulfill.back')}
              </Button>
              <Button
                variant="destructive"
                size="sm"
                loading={cancelling}
                onClick={handleCancel}
                className="flex-1"
              >
                {t('fulfill.cancelConfirmBtn')}
              </Button>
            </div>
          </div>
        </div>
      )}

      {/* Guided unpack panel (cancel_requested) */}
      {hasCancelRequest && (
        <div className="px-6 py-4">
          <GuidedUnpackPanel order={order} onUnpacked={loadOrder} />
        </div>
      )}

      {hasCancelRequest ? (
        itemsList
      ) : (
        <div className="flex-1 flex flex-col md:flex-row overflow-hidden min-h-0">
          {/* Item list */}
          <div className="flex-1 md:border-e md:border-line overflow-y-auto order-2 md:order-1">
            {itemsList}
          </div>

          {/* Scan panel — desktop: hero result + input side-by-side with items;
              mobile: input pinned under the header, no hero card */}
          <div className="flex-shrink-0 md:w-[360px] md:flex md:flex-col md:p-5 md:gap-3.5 order-1 md:order-2">
            {/* Desktop-only scan-result hero — same lastResult state as mobile's inline feedback below */}
            <div className={`hidden md:flex flex-1 flex-col items-center justify-center gap-2 rounded-xl border text-center px-4 ${
              lastResult
                ? lastResult.success ? 'border-success/30 bg-success/[0.14]' : 'border-critical/30 bg-critical/[0.14]'
                : 'border-line bg-surface'
            }`}>
              {lastResult ? (
                lastResult.success ? (
                  <>
                    <CheckCircle2 size={44} strokeWidth={2} className="text-success" />
                    <p className="text-h4 font-bold text-primary">{t('common.scanned', { defaultValue: 'Piece scanned' })}</p>
                    <p className="text-body font-mono text-muted">{lastResult.barcode}</p>
                  </>
                ) : (
                  <>
                    <AlertTriangle size={44} strokeWidth={2} className="text-critical" />
                    <p className="text-h4 font-bold text-primary">
                      {t(`fulfill.rejection.${lastResult.code}`, { defaultValue: lastResult.message ?? lastResult.code })}
                    </p>
                  </>
                )
              ) : (
                <p className="text-small text-muted">{t('fulfill.noPiecesScanned')}</p>
              )}
            </div>

            <div className="bg-panel md:bg-transparent border-b md:border-b-0 border-line px-6 md:px-0 py-4 md:py-0">
              {scanInput}
              {/* Mobile-only inline feedback — desktop shows the hero card above instead */}
              {scanFeedback && (
                <div className={`mt-2 text-small font-medium md:hidden ${lastResult?.success ? 'text-success' : 'text-danger'}`}>
                  {scanFeedback}
                </div>
              )}
            </div>
          </div>
        </div>
      )}

      {/* Bottom bar — Print Waybill + Complete (hidden during guided unpack) */}
      {!hasCancelRequest && (
        <div className="bg-panel border-t border-line px-4 md:px-5 py-3.5 space-y-2 flex-shrink-0">
          {order.tracking_number && !order.shipment_has_courier ? (
            /* Shipment exists (AWB scanned/linked) but no courier account backs it —
               there is no real courier-issued label to print. Complete's own gate
               below already allows this case through without awbPrintedOnce. */
            <p className="text-caption text-muted text-center" data-testid="awb-no-courier-note">
              {t('fulfill.printAwb.noCourierAccount')}
            </p>
          ) : order.tracking_number ? (
            /* PRINTABLE — kept as raw <button> to preserve data-testid (Button doesn't spread it) */
            <div className="space-y-1">
              <button
                onClick={handlePrintAwb}
                disabled={awbPrinting}
                className="btn-outline btn text-small w-full"
                data-testid="btn-print-awb"
              >
                {awbPrinting ? t('fulfill.printAwb.opening') : t('fulfill.printAwb.print')}
              </button>
              {awbMsg && (
                <p className={`text-caption text-center ${awbMsg.type === 'error' ? 'text-danger' : 'text-muted'}`}
                   data-testid="awb-msg">
                  {awbMsg.text}
                </p>
              )}
            </div>
          ) : allComplete && !order.is_self_pickup ? (
            /* Precondition to Complete: unlinked order, picking finished — the packer must
               scan the physical AWB before Print Waybill (and Complete) can appear at all.
               An exchange order never reaches this branch in practice — its forward
               shipment (and tracking_number) already exists from map-time, so the
               PRINTABLE branch above fires first, exactly like any Mode-B order whose AWB
               was webhook-auto-matched before pack. No exchange-specific check needed. */
            <button
              onClick={() => { scanner.clearQueue(); setShowPreCompleteLink(true) }}
              className="btn-brand btn text-small w-full"
              data-testid="btn-scan-to-link"
            >
              {t('fulfill.linkAwb.scanPrompt')}
            </button>
          ) : (
            /* NOT-YET-LINKED (still picking, or self-pickup) — kept as raw <button> to
               preserve data-testid */
            <div className="space-y-1">
              <button
                disabled
                className="btn-outline btn text-small w-full opacity-40 cursor-not-allowed"
                data-testid="btn-print-awb"
              >
                {t('fulfill.printAwb.print')}
              </button>
              <p className="text-caption text-muted text-center" data-testid="awb-not-linked-note">
                {t('fulfill.printAwb.notLinked')}
              </p>
            </div>
          )}

          {/* Complete: self-pickup never needs a Bosta AWB (unchanged, unaffected by the
              link/print gate below). Every other non-self-pickup order — exchange
              included — must be linked AND printed first; an exchange order satisfies
              "linked" from map-time onward, so this gate needs no exchange-specific
              bypass. A linked shipment with no courier account (!shipment_has_courier)
              has no real label to print, so it skips the awbPrintedOnce requirement —
              a shipment WITH a courier account still requires a successful print,
              exactly as before. */}
          {allComplete && (order.is_self_pickup ||
            (!!order.tracking_number && (awbPrintedOnce || !order.shipment_has_courier))) && (
            <Button
              loading={completing}
              onClick={handleComplete}
              className="w-full"
            >
              {completing ? t('common.loading') : t('fulfill.complete')}
            </Button>
          )}

          {/* Pre-Complete link step — inline, not a blocking modal. Unscanning an item flips
              allComplete back to false, which closes it (the allPicked effect above resets
              showPreCompleteLink, so it doesn't reappear by itself when the order is complete
              again — "Scan to link" opens it). */}
          {showPreCompleteLink && allComplete && (
            <AwbLinkDialog
              orderId={orderId}
              orderNumber={order.number}
              variant="inline"
              demoTracking={scanHelpers ? order.tracking_number : null}
              onLinked={() => { setShowPreCompleteLink(false); loadOrder() }}
            />
          )}
        </div>
      )}

      {/* Post-Complete AWB-scan dialog — mandatory verify-scan, no skip. onLinked
          closes it the instant the scan succeeds (same as the pre-Complete inline
          call site), landing directly on PickScreen's single completion card — no
          intermediate "AWB linked" screen of its own. */}
      {showAwbDialog && (
        <AwbLinkDialog
          orderId={orderId}
          orderNumber={order.number}
          demoTracking={scanHelpers ? order.tracking_number : null}
          onLinked={() => { setShowAwbDialog(false); setCompleted(true) }}
        />
      )}
    </div>
  )
}

// ── Root ───────────────────────────────────────────────────────────────────────

type View =
  | { type: 'queue' }
  | { type: 'pick'; orderId: string }
  | { type: 'handover'; order: QueueOrder }

export default function Fulfill({ selfPickupOnly = false }: {
  /** Pick & Pack S3 — waybill mode's "Self-pickup orders" entry: the same queue, self-pickup only. */
  selfPickupOnly?: boolean
} = {}) {
  const { t } = useTranslation()
  const [view, setView] = useState<View>({ type: 'queue' })
  const [queue, setQueue] = useState<QueueOrder[]>([])
  const [queueLoading, setQueueLoading] = useState(true)
  const [highlightOrderId, setHighlightOrderId] = useState<string | null>(null)

  const loadQueue = useCallback(async () => {
    setQueueLoading(true)
    try {
      const { data } = await api<QueueOrder[]>('/fulfill/queue')
      setQueue(selfPickupOnly ? data.filter(o => o.is_self_pickup || o.status === 'self_pickup_pending') : data)
    } finally {
      setQueueLoading(false)
    }
  }, [selfPickupOnly])

  useEffect(() => { loadQueue() }, [loadQueue])

  // "Next pickable order" — derived from the queue already in memory (same
  // filter QueueView uses to exclude self-pickup, which goes through
  // HandoverScreen instead). No new fetch: reuses the last loadQueue() result.
  const nextPickableOrderId = useCallback((currentOrderId: string): string | null => {
    const pickable = queue.filter(o => o.status !== 'self_pickup_pending')
    const idx = pickable.findIndex(o => o.id === currentOrderId)
    if (idx === -1) return null
    return pickable[idx + 1]?.id ?? null
  }, [queue])

  // Returning from PickScreen triggers an explicit refetch so the just-packed
  // order reflects its new status before the queue re-renders. When leaving a
  // pick session (completed or not), highlight whichever order was "next" from
  // there, so the queue makes it obvious where to resume.
  const backFromPick = useCallback((fromOrderId?: string) => {
    setHighlightOrderId(fromOrderId ? nextPickableOrderId(fromOrderId) : null)
    loadQueue()
    setView({ type: 'queue' })
  }, [loadQueue, nextPickableOrderId])

  if (view.type === 'pick') {
    return (
      // key={orderId} forces a full remount on every order switch (including
      // "Next order →" navigation, which changes orderId without unmounting
      // via the parent). Without it, React reuses this component instance and
      // per-order transient state (completed, showAwbDialog, awbPrintedOnce,
      // lastResult, ...) would leak from the just-finished order into the next
      // one's fresh data — e.g. showing the completion screen immediately for
      // an order that hasn't been touched yet.
      <PickScreen
        key={view.orderId}
        orderId={view.orderId}
        onBack={() => backFromPick(view.orderId)}
        nextOrderId={nextPickableOrderId(view.orderId)}
        onGoToOrder={orderId => setView({ type: 'pick', orderId })}
      />
    )
  }
  if (view.type === 'handover') {
    // Full-screen, no shell — matches the mockup's Self-Pickup Handover treatment
    // (phone frames + a centered card on desktop, no sidebar shown).
    return <HandoverScreen order={view.order} onBack={() => backFromPick()} />
  }
  // Queue view only — rendered inside the shell (sidebar + topbar), matching the
  // mockup's in-shell queue. pick/handover above stay full-screen immersive.
  return (
    <Layout>
      {selfPickupOnly && (
        <Link to="/fulfill" className="inline-flex items-center gap-1.5 text-small font-semibold text-trace-blue mb-4">
          <ArrowLeft size={14} strokeWidth={2} className="rtl:rotate-180" />
          {t('fulfill.waybill.backToWaybill')}
        </Link>
      )}
      <QueueView
        queue={queue}
        loading={queueLoading}
        loadQueue={loadQueue}
        onSelect={orderId => { setHighlightOrderId(null); setView({ type: 'pick', orderId }) }}
        onHandover={order => setView({ type: 'handover', order })}
        highlightOrderId={highlightOrderId}
      />
    </Layout>
  )
}
