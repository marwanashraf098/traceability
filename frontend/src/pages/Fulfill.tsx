import { useEffect, useRef, useState, useCallback } from 'react'
import { useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import {
  X, ArrowLeft, ArrowRight, CheckCircle2, AlertTriangle, ScanLine,
  Lock, Layers, RefreshCw, ChevronRight,
} from 'lucide-react'
import { Badge, Button, Skeleton, EmptyState } from '../components/ui'
import Layout from '../components/Layout'
import { getAccessToken, clearAccessToken } from '../auth'

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
  cancel_requested_at: string | null
  shipment_id: string | null
  tracking_number: string | null
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
// Two call sites, same scan/link/error-handling logic:
//   - pre-Complete (variant='inline', onLinked set): the packer must link an unlinked order
//     before Complete can appear at all. On a successful scan, onLinked() fires immediately —
//     there is no "linked" success sub-view here, no backdrop, and no way to bypass linking.
//     PickScreen closes this and re-fetches the order; the existing Print Waybill button
//     (now enabled) takes over from there.
//   - post-Complete (variant='modal' default, onLinked unset): the mandatory verify-scan.
//     Shows the "linked" success view (Print Waybill + Done) exactly as before. The
//     "Skip — link later" escape hatch has been removed entirely — this step cannot be
//     bypassed, in either call site.

function AwbLinkDialog({
  orderId,
  onDone,
  onLinked,
  variant = 'modal',
}: {
  orderId: string
  onDone?: () => void
  onLinked?: (result: { tracking: string; shipmentId: string }) => void
  variant?: 'modal' | 'inline'
}) {
  const { t } = useTranslation()
  const inputRef = useRef<HTMLInputElement>(null)
  const [flash, setFlash] = useState<FlashState>('idle')
  const [linking, setLinking] = useState(false)
  const [linked, setLinked] = useState<{ tracking: string; shipmentId: string } | null>(null)
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

  // SAFETY-CRITICAL — do not modify
  const triggerFlash = (state: 'success' | 'error') => {
    setFlash(state)
    setTimeout(() => setFlash('idle'), 600)
  }

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
        if (onLinked) {
          onLinked({ tracking: data.trackingNumber, shipmentId: data.shipmentId })
        } else {
          setLinked({ tracking: data.trackingNumber, shipmentId: data.shipmentId })
        }
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

  // linked/print/done sub-view only ever renders in modal (post-Complete) usage — the
  // inline (pre-Complete) usage calls onLinked() the moment a scan succeeds and is closed
  // by the caller before this branch would ever be reached.
  const hasError = conflictError || mismatchError || genericError
  const stateTag = linked
    ? { text: t('fulfill.linkAwb.stateSuccess'), className: 'text-success-text' }
    : conflictError
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

      {linked ? (
        // No Print Waybill here — the AWB is already printed via the bottom-bar
        // button before Complete ever unlocks (see PickScreen's handlePrintAwb /
        // awbPrintedOnce gate). This view is the mandatory post-Complete verify-
        // scan's confirmation, not a second print opportunity.
        <div className="text-center space-y-4 py-2">
          <CheckCircle2 size={40} strokeWidth={2} className="text-success mx-auto" />
          <div>
            <p className="text-h4 text-primary font-bold">{t('fulfill.linkAwb.title')}</p>
            <p className="text-body font-mono text-muted mt-1">{linked.tracking}</p>
          </div>
          {onDone && (
            <div className="pt-2">
              <Button onClick={onDone} className="w-full">
                {t('fulfill.linkAwb.done')}
              </Button>
            </div>
          )}
        </div>
      ) : (
        <>
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
        </>
      )}
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
        <h1 className="text-h1 text-primary">{t('fulfill.title')}</h1>
        <div className="flex items-center gap-3">
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
        <EmptyState message={t('fulfill.empty')} icon="📦" />
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
  const [order, setOrder] = useState<OrderDetail | null>(null)
  const [loading, setLoading] = useState(true)
  const [flash, setFlash] = useState<FlashState>('idle')
  const [lastResult, setLastResult] = useState<ScanResult | null>(null)
  const [scanning, setScanning] = useState(false)
  const [completing, setCompleting] = useState(false)
  const [cancelling, setCancelling] = useState(false)
  const [showAwbDialog, setShowAwbDialog] = useState(false)
  const [showPreCompleteLink, setShowPreCompleteLink] = useState(false)
  const [awbPrintedOnce, setAwbPrintedOnce] = useState(false)
  const [completed, setCompleted] = useState(false)
  const [showCancelConfirm, setShowCancelConfirm] = useState(false)
  const [awbPrinting, setAwbPrinting] = useState(false)
  const [awbMsg, setAwbMsg] = useState<AwbMsg>(null)
  const inputRef = useRef<HTMLInputElement>(null)
  const autoBackTimer = useRef<ReturnType<typeof setTimeout> | null>(null)

  const loadOrder = useCallback(async () => {
    const { data } = await api<OrderDetail>(`/fulfill/${orderId}`)
    setOrder(data)
    setLoading(false)
  }, [orderId])

  useEffect(() => { loadOrder() }, [loadOrder])

  // SAFETY-CRITICAL — HID refocus: re-focuses scan input on any click; do not modify
  useEffect(() => {
    const refocus = () => {
      if (document.activeElement !== inputRef.current) inputRef.current?.focus()
    }
    document.addEventListener('click', refocus)
    inputRef.current?.focus()
    return () => document.removeEventListener('click', refocus)
  }, [order])

  // SAFETY-CRITICAL — flash trigger: do not modify
  const triggerFlash = (state: 'success' | 'error') => {
    setFlash(state)
    setTimeout(() => setFlash('idle'), 600)
  }

  // SAFETY-CRITICAL — scan handler: do not modify
  const handleScan = useCallback(async (barcode: string) => {
    if (!barcode.trim() || scanning) return
    setScanning(true)
    try {
      const { data: result } = await api<ScanResult>(`/fulfill/${orderId}/scan`, {
        method: 'POST',
        body: JSON.stringify({ barcode: barcode.trim() }),
      })
      setLastResult(result)
      if (result.success) {
        playBeep(true)
        triggerFlash('success')
        await loadOrder()
      } else {
        playBeep(false)
        triggerFlash('error')
      }
    } catch {
      playBeep(false)
      triggerFlash('error')
      setLastResult({ success: false, code: 'ERROR', message: t('common.error'), pieceId: null, barcode: null, allocatedCount: 0, requiredQuantity: 0, allComplete: false })
    } finally {
      setScanning(false)
      if (inputRef.current) { inputRef.current.value = ''; inputRef.current.focus() }
    }
  }, [orderId, scanning, loadOrder, t])

  const handleUnscan = async (pieceId: string) => {
    await api(`/fulfill/${orderId}/scan/${pieceId}`, { method: 'DELETE' })
    await loadOrder()
  }

  const handleComplete = async () => {
    if (completing) return
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
      setAwbMsg({ type: 'error', text: (e as Error).message || t('fulfill.printAwb.error') })
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
            className={`card p-3.5 ${complete ? 'border-success/40' : ''}`}
          >
            <div className="flex items-start justify-between gap-3 mb-2.5">
              <div className="min-w-0">
                <p className="text-body font-semibold text-primary truncate">
                  {item.product_title}
                  {item.variant_title && item.variant_title !== 'Default Title' && (
                    <span className="text-muted font-normal"> · {item.variant_title}</span>
                  )}
                </p>
                {/* SKU — font-mono per spec */}
                {item.sku && <p className="text-caption text-muted font-mono">{item.sku}</p>}
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

  // SAFETY-CRITICAL scan input — ref, onKeyDown, disabled=scanning, autoFocus: do not modify.
  // Rendered exactly once regardless of breakpoint; the surrounding layout repositions it
  // (mobile: top bar under the header; desktop: bottom of the right-hand scan panel).
  const scanInput = (
    <div className="relative">
      <span className="absolute start-3 top-1/2 -translate-y-1/2 text-trace-blue pointer-events-none">
        <ScanLine size={18} strokeWidth={2} />
      </span>
      <input
        ref={inputRef}
        type="text"
        placeholder={t('fulfill.scanPlaceholder')}
        className="input-scan w-full ps-10"
        disabled={scanning}
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
        </p>
        {!hasCancelRequest && (
          <button
            onClick={() => setShowCancelConfirm(true)}
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
          {order.tracking_number ? (
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
               scan the physical AWB before Print Waybill (and Complete) can appear at all. */
            <button
              onClick={() => setShowPreCompleteLink(true)}
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
              link/print gate below). Non-self-pickup orders must be linked AND printed first. */}
          {allComplete && (order.is_self_pickup || (!!order.tracking_number && awbPrintedOnce)) && (
            <Button
              loading={completing}
              onClick={handleComplete}
              className="w-full"
            >
              {completing ? t('common.loading') : t('fulfill.complete')}
            </Button>
          )}

          {/* Pre-Complete link step — inline, not a blocking modal, so unscanning an item
              (which flips allComplete back to false) simply removes this section again. */}
          {showPreCompleteLink && (
            <AwbLinkDialog
              orderId={orderId}
              variant="inline"
              onLinked={() => { setShowPreCompleteLink(false); loadOrder() }}
            />
          )}
        </div>
      )}

      {/* Post-Complete AWB-scan dialog — mandatory verify-scan, no skip. Done now
          lands on the completion view (Next order / Back to queue) instead of
          going straight back — AwbLinkDialog itself is untouched either way. */}
      {showAwbDialog && (
        <AwbLinkDialog orderId={orderId} onDone={() => { setShowAwbDialog(false); setCompleted(true) }} />
      )}
    </div>
  )
}

// ── Root ───────────────────────────────────────────────────────────────────────

type View =
  | { type: 'queue' }
  | { type: 'pick'; orderId: string }
  | { type: 'handover'; order: QueueOrder }

export default function Fulfill() {
  const [view, setView] = useState<View>({ type: 'queue' })
  const [queue, setQueue] = useState<QueueOrder[]>([])
  const [queueLoading, setQueueLoading] = useState(true)
  const [highlightOrderId, setHighlightOrderId] = useState<string | null>(null)

  const loadQueue = useCallback(async () => {
    setQueueLoading(true)
    try {
      const { data } = await api<QueueOrder[]>('/fulfill/queue')
      setQueue(data)
    } finally {
      setQueueLoading(false)
    }
  }, [])

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
