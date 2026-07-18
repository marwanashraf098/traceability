import { useEffect, useRef, useState, useCallback } from 'react'
import { useTranslation } from 'react-i18next'
import { Badge, Button, Skeleton } from '../components/ui'

import { EmptyState } from '../components/ui'
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

function AwbLinkDialog({ orderId, onDone }: { orderId: string; onDone: () => void }) {
  const { t } = useTranslation()
  const inputRef = useRef<HTMLInputElement>(null)
  const [flash, setFlash] = useState<FlashState>('idle')
  const [linking, setLinking] = useState(false)
  const [linked, setLinked] = useState<{ tracking: string; shipmentId: string } | null>(null)
  const [conflictError, setConflictError] = useState(false)
  const [mismatchError, setMismatchError] = useState<{ scanned: string; existing: string } | null>(null)
  const [genericError, setGenericError] = useState(false)
  const [printing, setPrinting] = useState(false)
  const [awbMsg, setAwbMsg] = useState<AwbMsg>(null)

  useEffect(() => { inputRef.current?.focus() }, [])

  // SAFETY-CRITICAL — do not modify
  const triggerFlash = (state: 'success' | 'error') => {
    setFlash(state)
    setTimeout(() => setFlash('idle'), 600)
  }

  const handleLink = async (tracking: string) => {
    const trimmed = tracking.trim()
    if (!trimmed || linking) return
    setLinking(true)
    setConflictError(false)
    setMismatchError(null)
    setGenericError(false)
    try {
      const { data, status } = await api<AwbLinkResponse>(`/fulfill/${orderId}/link`, {
        method: 'POST',
        body: JSON.stringify({ trackingNumber: trimmed }),
      })
      if (status === 200 || status === 201) {
        playBeep(true)
        triggerFlash('success')
        setLinked({ tracking: data.trackingNumber, shipmentId: data.shipmentId })
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

  const handlePrint = async () => {
    if (!linked || printing) return
    setPrinting(true)
    setAwbMsg(null)
    try {
      const result = await printAwbPdf(linked.shipmentId)
      if (result === 'emailed') {
        setAwbMsg({ type: 'info', text: t('fulfill.printAwb.emailed') })
      }
    } catch (e: unknown) {
      setAwbMsg({ type: 'error', text: (e as Error).message || t('fulfill.printAwb.error') })
    } finally {
      setPrinting(false)
    }
  }

  // SAFETY-CRITICAL — flash overlay: do not modify
  const flashOverlay =
    flash === 'success' ? 'fixed inset-0 bg-success/20 pointer-events-none z-[60] animate-flash'
    : flash === 'error' ? 'fixed inset-0 bg-danger/20 pointer-events-none z-[60] animate-flash'
    : 'hidden'

  return (
    <div className="fixed inset-0 bg-black/70 flex items-center justify-center z-50">
      {/* SAFETY-CRITICAL flash overlay — do not modify */}
      <div className={flashOverlay} />
      <div className="bg-panel rounded-xl shadow-2xl p-8 w-full max-w-md mx-4">
        <h2 className="text-h2 text-primary mb-1">{t('fulfill.linkAwb.title')}</h2>
        <p className="text-small text-muted mb-6">{t('fulfill.linkAwb.subtitle')}</p>

        {linked ? (
          <div className="text-center space-y-4 py-4">
            <div className="text-5xl text-success">✓</div>
            <p className="text-success font-medium text-body">
              {t('fulfill.linkAwb.success', { tracking: linked.tracking })}
            </p>
            <div className="space-y-2 pt-2">
              <Button
                loading={printing}
                onClick={handlePrint}
                className="w-full"
              >
                {printing ? t('fulfill.printAwb.opening') : t('fulfill.printAwb.print')}
              </Button>
              {awbMsg && (
                <p className={`text-small text-center ${awbMsg.type === 'error' ? 'text-danger' : 'text-muted'}`}>
                  {awbMsg.text}
                </p>
              )}
              <Button variant="tertiary" onClick={onDone} className="w-full">
                {t('fulfill.linkAwb.done')}
              </Button>
            </div>
          </div>
        ) : (
          <>
            {/* SAFETY-CRITICAL scan input — ref, onKeyDown, disabled behavior untouched */}
            <input
              ref={inputRef}
              type="text"
              placeholder={t('fulfill.linkAwb.placeholder')}
              className="input-scan w-full mb-3"
              disabled={linking}
              onKeyDown={e => {
                if (e.key === 'Enter') handleLink((e.target as HTMLInputElement).value)
              }}
            />
            {conflictError && (
              <p className="text-danger text-small font-medium mb-3">✗ {t('fulfill.linkAwb.conflict')}</p>
            )}
            {mismatchError && (
              <p className="text-danger text-small font-medium mb-3">
                ✗ {t('fulfill.linkAwb.awbMismatch', { scanned: mismatchError.scanned, existing: mismatchError.existing })}
              </p>
            )}
            {genericError && (
              <p className="text-danger text-small font-medium mb-3">✗ {t('fulfill.linkAwb.error')}</p>
            )}
            <Button variant="tertiary" onClick={onDone} className="w-full mt-2">
              {t('fulfill.linkAwb.skip')}
            </Button>
          </>
        )}
      </div>
    </div>
  )
}

// ── Handover screen (self_pickup_pending orders) ───────────────────────────────

function HandoverScreen({ order, onBack }: { order: QueueOrder; onBack: () => void }) {
  const { t } = useTranslation()
  const [confirming, setConfirming] = useState(false)
  const [done, setDone] = useState(false)
  const [count, setCount] = useState(0)

  async function confirm() {
    if (confirming) return
    setConfirming(true)
    try {
      const { data } = await api<{ deliveredPieces: number }>(`/fulfill/${order.id}/handover`, { method: 'POST' })
      setCount(data.deliveredPieces)
      setDone(true)
      setTimeout(() => onBack(), 2000)
    } finally {
      setConfirming(false)
    }
  }

  return (
    <div className="flex flex-col h-screen bg-base">
      <div className="bg-panel border-b border-line px-6 py-3 flex items-center justify-between">
        <Button variant="tertiary" size="sm" onClick={onBack}>
          ← {t('fulfill.back')}
        </Button>
        <div className="text-center">
          {/* Order number — font-mono per spec */}
          <p className="font-mono font-medium text-primary">{order.number ?? order.id.slice(-8)}</p>
          <p className="text-small text-muted">{order.customer_name ?? t('common.pendingConsignee')}</p>
        </div>
        <div className="w-24" />
      </div>

      <div className="flex-1 flex flex-col items-center justify-center p-8">
        {done ? (
          <div className="text-center">
            <div className="text-6xl mb-4">✓</div>
            <p className="text-h2 text-success">
              {t('fulfill.handoverSuccess', { count })}
            </p>
          </div>
        ) : (
          <>
            <div className="text-5xl mb-6">🤝</div>
            <h2 className="text-h1 text-primary mb-2 text-center">
              {t('fulfill.handoverTitle')}
            </h2>
            <p className="text-body text-muted mb-8 text-center">
              {t('fulfill.handoverSubtitle', { count: order.total_units })}
            </p>
            {/*
              py-5 is intentional: large tap target for warehouse handover.
              Kept as raw <button> so py-5 is not overridden by the Button component's
              size-based padding. btn-brand is a DS class (no hardcoded hex).
            */}
            <button
              onClick={confirm}
              disabled={confirming}
              className="btn-brand btn text-body w-full max-w-sm py-5"
            >
              {confirming ? '…' : t('fulfill.handoverConfirm')}
            </button>
          </>
        )}
      </div>
    </div>
  )
}

// ── Queue view ─────────────────────────────────────────────────────────────────

function QueueView({
  queue,
  loading,
  loadQueue,
  onSelect,
  onHandover,
}: {
  queue: QueueOrder[]
  loading: boolean
  loadQueue: () => void
  onSelect: (orderId: string) => void
  onHandover: (order: QueueOrder) => void
}) {
  const { t } = useTranslation()

  if (loading) return (
    <div className="p-6 max-w-4xl mx-auto space-y-3">
      <Skeleton className="h-8 w-48 rounded-xl" />
      {Array.from({ length: 3 }).map((_, i) => (
        <Skeleton key={i} className="h-20 rounded-2xl" />
      ))}
    </div>
  )

  const pickQueue     = queue.filter(o => o.status !== 'self_pickup_pending')
  const handoverQueue = queue.filter(o => o.status === 'self_pickup_pending')

  return (
    <div className="p-6 max-w-4xl mx-auto" data-testid="fulfill-queue">
      <div className="flex items-center justify-between mb-6">
        <h1 className="text-h1 text-primary">{t('fulfill.title')}</h1>
        <Button variant="tertiary" size="sm" onClick={loadQueue}>
          {t('fulfill.refresh')}
        </Button>
      </div>

      {/* Self-pickup pending section */}
      {handoverQueue.length > 0 && (
        <div className="mb-6">
          <h2 className="text-small text-muted font-medium uppercase tracking-wide mb-3">
            {t('fulfill.selfPickupPending')}
          </h2>
          <div className="space-y-3">
            {handoverQueue.map(order => (
              <div
                key={order.id}
                className="card border-warning/30 hover:border-warning/60 p-4 flex items-center justify-between cursor-pointer transition"
                onClick={() => onHandover(order)}
              >
                <div>
                  <p className="text-body font-medium text-primary flex items-center gap-2">
                    {/* Order number — font-mono per spec */}
                    <span className="font-mono">{order.number ?? order.id.slice(-8)}</span>
                    <Badge tone="warning" label={t('fulfill.selfPickup')} />
                  </p>
                  <p className="text-small text-muted">{order.customer_name ?? t('common.pendingConsignee')}</p>
                </div>
                <div className="flex items-center gap-3">
                  <span className="text-small text-muted">
                    {order.total_units} {t('fulfill.units')}
                  </span>
                  <Button variant="secondary" size="sm">
                    {t('fulfill.handoverBtn')}
                  </Button>
                </div>
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
            <h2 className="text-small text-muted font-medium uppercase tracking-wide mb-3">
              {t('fulfill.pickQueueHeader')}
            </h2>
          )}
          <div className="space-y-3">
            {pickQueue.map(order => {
              const progress = order.total_units > 0
                ? Math.round((order.scanned_units / order.total_units) * 100) : 0
              return (
                <div
                  key={order.id}
                  className="card hover:border-trace-blue/50 p-4 flex items-center justify-between cursor-pointer transition"
                  onClick={() => onSelect(order.id)}
                >
                  <div>
                    <p className="text-body font-medium text-primary flex items-center gap-2">
                      {/* Order number — font-mono per spec */}
                      <span className="font-mono">{order.number ?? order.id.slice(-8)}</span>
                      {order.is_self_pickup && (
                        <Badge tone="info" label={t('fulfill.selfPickup')} />
                      )}
                    </p>
                    <p className="text-small text-muted">
                      {order.customer_name ?? t('common.pendingConsignee')}
                      {order.payment_method === 'cod' && order.cod_amount && (
                        <span className="ms-2 text-warning font-medium">COD {order.cod_amount}</span>
                      )}
                    </p>
                  </div>
                  <div className="flex items-center gap-4">
                    <div className="text-end">
                      <p className="text-small text-muted">
                        {order.scanned_units} / {order.total_units} {t('fulfill.units')}
                      </p>
                      <div className="w-32 bg-elevated h-2 rounded-full mt-1">
                        <div
                          className="bg-trace-blue h-2 rounded-full transition-all"
                          style={{ width: `${progress}%` }}
                        />
                      </div>
                    </div>
                    <Badge status={order.status} />
                    {order.locked_by && (
                      <span className="text-caption text-muted italic">{t('fulfill.locked')}</span>
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

  const packedPieces = order.items
    .flatMap(i => i.allocatedPieces)
    .filter(p => p.piece_status === 'packed')

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
      <div className="card border-success/40 bg-success/5 p-6 text-center">
        <div className="text-3xl mb-2">✓</div>
        <p className="text-success font-medium text-body">{t('fulfill.unpackDone')}</p>
      </div>
    )
  }

  return (
    <div className="card border-warning/30 bg-warning/5 p-4">
      <p className="text-small font-medium text-warning mb-3">
        {t('fulfill.cancelRequested', { count: packedPieces.length })}
      </p>
      <div className="space-y-2">
        {packedPieces.map(p => (
          <div
            key={p.piece_id}
            className="flex items-center justify-between bg-panel rounded border border-warning/20 px-3 py-2"
          >
            {/* Barcode — font-mono per spec */}
            <span className="font-mono text-small text-primary">{p.barcode.slice(-10)}</span>
            <Button
              variant="destructive"
              size="sm"
              loading={unpacking === p.piece_id}
              onClick={() => unpack(p.piece_id)}
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

function PickScreen({ orderId, onBack }: { orderId: string; onBack: () => void }) {
  const { t } = useTranslation()
  const [order, setOrder] = useState<OrderDetail | null>(null)
  const [loading, setLoading] = useState(true)
  const [flash, setFlash] = useState<FlashState>('idle')
  const [lastResult, setLastResult] = useState<ScanResult | null>(null)
  const [scanning, setScanning] = useState(false)
  const [completing, setCompleting] = useState(false)
  const [cancelling, setCancelling] = useState(false)
  const [showAwbDialog, setShowAwbDialog] = useState(false)
  const [selfPickupSuccess, setSelfPickupSuccess] = useState(false)
  const [showCancelConfirm, setShowCancelConfirm] = useState(false)
  const [awbPrinting, setAwbPrinting] = useState(false)
  const [awbMsg, setAwbMsg] = useState<AwbMsg>(null)
  const inputRef = useRef<HTMLInputElement>(null)

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
        setSelfPickupSuccess(true)
        setTimeout(() => onBack(), 2000)
      } else {
        setShowAwbDialog(true)
      }
    } finally {
      setCompleting(false)
    }
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

  const allComplete = order.items.every(i => i.allocated >= i.quantity)
  const hasCancelRequest = !!order.cancel_requested_at

  // SAFETY-CRITICAL — flash overlay computation: do not modify
  const flashOverlay =
    flash === 'success' ? 'fixed inset-0 bg-success/20 pointer-events-none z-50 animate-flash'
    : flash === 'error' ? 'fixed inset-0 bg-danger/20 pointer-events-none z-50 animate-flash'
    : 'hidden'

  return (
    <div className="flex flex-col h-screen bg-base" data-testid="fulfill-pick">
      {/* SAFETY-CRITICAL flash overlay — do not modify */}
      <div className={flashOverlay} />

      {/* Header */}
      <div className="bg-panel border-b border-line px-6 py-3 flex items-center justify-between">
        <Button variant="tertiary" size="sm" onClick={onBack}>
          ← {t('fulfill.backToQueue')}
        </Button>
        <div className="text-center">
          <p className="font-medium text-primary flex items-center gap-2">
            {/* Order number — font-mono per spec */}
            <span className="font-mono">{order.number ?? order.id.slice(-8)}</span>
            {order.is_self_pickup && (
              <Badge tone="info" label={t('fulfill.selfPickup')} />
            )}
          </p>
          <p className="text-small text-muted">{order.customer_name ?? t('common.pendingConsignee')}</p>
        </div>
        {!hasCancelRequest ? (
          <Button variant="destructive" size="sm" onClick={() => setShowCancelConfirm(true)}>
            {t('fulfill.cancelOrder')}
          </Button>
        ) : (
          <div className="w-24" />
        )}
      </div>

      {/* Cancel confirm dialog */}
      {showCancelConfirm && (
        <div className="fixed inset-0 bg-black/60 flex items-center justify-center z-50" onClick={() => setShowCancelConfirm(false)}>
          <div className="bg-panel rounded-xl shadow-2xl p-6 w-full max-w-sm mx-4" onClick={e => e.stopPropagation()}>
            <h3 className="text-body font-medium text-primary mb-2">{t('fulfill.cancelDialogTitle')}</h3>
            <p className="text-small text-muted mb-4">
              {order.status === 'packed'
                ? t('fulfill.cancelPackedHint')
                : t('fulfill.cancelPreHint')}
            </p>
            <div className="flex gap-3 justify-end">
              <Button variant="secondary" size="sm" onClick={() => setShowCancelConfirm(false)}>
                {t('fulfill.back')}
              </Button>
              <Button
                variant="destructive"
                size="sm"
                loading={cancelling}
                onClick={handleCancel}
              >
                {t('fulfill.cancelConfirmBtn')}
              </Button>
            </div>
          </div>
        </div>
      )}

      {/* Self-pickup success banner */}
      {selfPickupSuccess && (
        <div className="bg-success/5 border-b border-success/30 px-6 py-3 text-center">
          <p className="text-success font-medium text-body">{t('fulfill.selfPickupPacked')}</p>
        </div>
      )}

      {/* Guided unpack panel (cancel_requested) */}
      {hasCancelRequest && (
        <div className="px-6 py-4">
          <GuidedUnpackPanel order={order} onUnpacked={loadOrder} />
        </div>
      )}

      {/* SAFETY-CRITICAL scan input — autoFocus, ref, onKeyDown, disabled=scanning: do not modify */}
      {!hasCancelRequest && (
        <div className="bg-panel border-b border-line px-6 py-4">
          <input
            ref={inputRef}
            type="text"
            placeholder={t('fulfill.scanPlaceholder')}
            className="input-scan w-full"
            disabled={scanning}
            onKeyDown={e => {
              if (e.key === 'Enter') handleScan((e.target as HTMLInputElement).value)
            }}
            autoFocus
          />
          {lastResult && (
            <div className={`mt-2 text-small font-medium ${lastResult.success ? 'text-success' : 'text-danger'}`}>
              {lastResult.success
                ? <><span>✓ </span><span className="font-mono">{lastResult.barcode}</span></>
                : `✗ ${t(`fulfill.rejection.${lastResult.code}`, { defaultValue: lastResult.message ?? lastResult.code })}`}
            </div>
          )}
        </div>
      )}

      {/* Items */}
      <div className="flex-1 overflow-y-auto p-6 space-y-4">
        {order.items.map(item => {
          const complete = item.allocated >= item.quantity
          return (
            <div
              key={item.id}
              className={`card p-4 ${complete ? 'border-success/40' : ''}`}
            >
              <div className="flex items-start justify-between mb-3">
                <div>
                  <p className="text-body font-medium text-primary">{item.product_title}</p>
                  {item.variant_title && item.variant_title !== 'Default Title' && (
                    <p className="text-small text-muted">{item.variant_title}</p>
                  )}
                  {/* SKU — font-mono per spec */}
                  {item.sku && <p className="text-caption text-muted font-mono">{item.sku}</p>}
                </div>
                <span className={`text-body font-medium px-3 py-1 rounded-full ${
                  complete ? 'bg-success/10 text-success' : 'bg-elevated text-muted'
                }`}>
                  {item.allocated}/{item.quantity}
                </span>
              </div>
              {item.allocatedPieces.length > 0 && (
                <div className="flex flex-wrap gap-2">
                  {item.allocatedPieces.map(p => (
                    <div
                      key={p.piece_id}
                      className="flex items-center gap-1 bg-trace-blue/10 border border-trace-blue/20 rounded px-2 py-1"
                    >
                      {/* Barcode — font-mono per spec */}
                      <span className="text-caption font-mono text-trace-blue">{p.barcode.slice(-10)}</span>
                      {!hasCancelRequest && p.allocation_status === 'active' && (
                        <button
                          onClick={() => handleUnscan(p.piece_id)}
                          className="text-danger hover:text-danger/80 text-small ms-1"
                          title={t('fulfill.unscan')}
                        >
                          ✕
                        </button>
                      )}
                    </div>
                  ))}
                </div>
              )}
            </div>
          )
        })}
      </div>

      {/* Bottom bar — Print Waybill + Complete (hidden during guided unpack) */}
      {!hasCancelRequest && (
        <div className="bg-panel border-t border-line px-6 py-4 space-y-2">
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
          ) : (
            /* NOT-YET-LINKED — kept as raw <button> to preserve data-testid */
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

          {allComplete && (
            <Button
              loading={completing}
              onClick={handleComplete}
              className="w-full"
            >
              {completing ? t('common.loading') : t('fulfill.complete')}
            </Button>
          )}
        </div>
      )}

      {/* AWB-scan dialog */}
      {showAwbDialog && (
        <AwbLinkDialog orderId={orderId} onDone={onBack} />
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

  // Returning from PickScreen triggers an explicit refetch so the just-packed
  // order reflects its new status before the queue re-renders.
  const backFromPick = useCallback(() => {
    loadQueue()
    setView({ type: 'queue' })
  }, [loadQueue])

  if (view.type === 'pick') {
    return <PickScreen orderId={view.orderId} onBack={backFromPick} />
  }
  if (view.type === 'handover') {
    return <HandoverScreen order={view.order} onBack={backFromPick} />
  }
  return (
    <QueueView
      queue={queue}
      loading={queueLoading}
      loadQueue={loadQueue}
      onSelect={orderId => setView({ type: 'pick', orderId })}
      onHandover={order => setView({ type: 'handover', order })}
    />
  )
}
