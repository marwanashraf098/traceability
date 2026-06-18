import { useEffect, useRef, useState, useCallback } from 'react'
import { useTranslation } from 'react-i18next'

const BASE = '/api/v1'

function authHeaders(): Record<string, string> {
  const token = localStorage.getItem('token')
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
    localStorage.removeItem('token')
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
  trackingNumber: string
  linkedPieces: number
  orderStatus: string
}

type FlashState = 'idle' | 'success' | 'error'

// ── Audio ──────────────────────────────────────────────────────────────────────

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

// ── AWB-scan dialog ────────────────────────────────────────────────────────────

function AwbLinkDialog({ orderId, onDone }: { orderId: string; onDone: () => void }) {
  const { t } = useTranslation()
  const inputRef = useRef<HTMLInputElement>(null)
  const [flash, setFlash] = useState<FlashState>('idle')
  const [linking, setLinking] = useState(false)
  const [linked, setLinked] = useState<string | null>(null)
  const [conflictError, setConflictError] = useState(false)
  const [genericError, setGenericError] = useState(false)

  useEffect(() => { inputRef.current?.focus() }, [])

  const triggerFlash = (state: 'success' | 'error') => {
    setFlash(state)
    setTimeout(() => setFlash('idle'), 600)
  }

  const handleLink = async (tracking: string) => {
    const trimmed = tracking.trim()
    if (!trimmed || linking) return
    setLinking(true)
    setConflictError(false)
    setGenericError(false)
    try {
      const { data, status } = await api<AwbLinkResponse>(`/fulfill/${orderId}/link`, {
        method: 'POST',
        body: JSON.stringify({ trackingNumber: trimmed }),
      })
      if (status === 200 || status === 201) {
        playBeep(true)
        triggerFlash('success')
        setLinked(data.trackingNumber)
        setTimeout(() => onDone(), 1800)
      } else if (status === 409) {
        playBeep(false)
        triggerFlash('error')
        setConflictError(true)
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

  const flashClass = flash === 'success'
    ? 'fixed inset-0 bg-green-400 opacity-30 pointer-events-none z-[60] animate-flash'
    : flash === 'error'
    ? 'fixed inset-0 bg-red-400 opacity-30 pointer-events-none z-[60] animate-flash'
    : 'hidden'

  return (
    <div className="fixed inset-0 bg-black/60 flex items-center justify-center z-50">
      <div className={flashClass} />
      <div className="bg-white rounded-xl shadow-2xl p-8 w-full max-w-md mx-4">
        <h2 className="text-xl font-bold text-gray-900 mb-1">{t('fulfill.linkAwb.title')}</h2>
        <p className="text-sm text-gray-500 mb-6">{t('fulfill.linkAwb.subtitle')}</p>

        {linked ? (
          <div className="text-center py-6">
            <div className="text-5xl text-green-500 mb-3">✓</div>
            <p className="text-green-700 font-semibold text-lg">
              {t('fulfill.linkAwb.success', { tracking: linked })}
            </p>
          </div>
        ) : (
          <>
            <input
              ref={inputRef}
              type="text"
              placeholder={t('fulfill.linkAwb.placeholder')}
              className="w-full border-2 border-indigo-300 rounded-lg px-4 py-3 text-lg font-mono focus:outline-none focus:border-indigo-500 mb-3"
              disabled={linking}
              onKeyDown={e => {
                if (e.key === 'Enter') handleLink((e.target as HTMLInputElement).value)
              }}
            />
            {conflictError && (
              <p className="text-red-600 text-sm font-medium mb-3">✗ {t('fulfill.linkAwb.conflict')}</p>
            )}
            {genericError && (
              <p className="text-red-600 text-sm font-medium mb-3">✗ {t('fulfill.linkAwb.error')}</p>
            )}
            <button onClick={onDone} className="w-full text-center text-sm text-gray-400 hover:text-gray-600 mt-2 py-2">
              {t('fulfill.linkAwb.skip')}
            </button>
          </>
        )}
      </div>
    </div>
  )
}

// ── Handover screen (self_pickup_pending orders) ───────────────────────────────

function HandoverScreen({ order, onBack }: { order: QueueOrder; onBack: () => void }) {
  const { i18n } = useTranslation()
  const isAr = i18n.language === 'ar'
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
    <div className="flex flex-col h-screen bg-gray-50">
      <div className="bg-white border-b border-gray-200 px-6 py-3 flex items-center justify-between">
        <button onClick={onBack} className="text-indigo-600 hover:text-indigo-800 font-medium text-sm">
          ← {isAr ? 'العودة' : 'Back'}
        </button>
        <div className="text-center">
          <p className="font-bold text-gray-900">{order.number ?? order.id.slice(-8)}</p>
          <p className="text-sm text-gray-500">{order.customer_name}</p>
        </div>
        <div className="w-24" />
      </div>

      <div className="flex-1 flex flex-col items-center justify-center p-8">
        {done ? (
          <div className="text-center">
            <div className="text-6xl mb-4">✓</div>
            <p className="text-xl font-bold text-green-700">
              {isAr ? `تم التسليم — ${count} قطعة` : `Collected — ${count} piece(s) delivered`}
            </p>
          </div>
        ) : (
          <>
            <div className="text-5xl mb-6">🤝</div>
            <h2 className="text-2xl font-bold text-gray-900 mb-2 text-center">
              {isAr ? 'تأكيد تسليم العميل' : 'Confirm Customer Collection'}
            </h2>
            <p className="text-gray-600 mb-8 text-center">
              {isAr
                ? `العميل في المستودع — ${order.total_units} قطعة جاهزة للتسليم`
                : `Customer is at the counter — ${order.total_units} piece(s) ready`}
            </p>
            <button
              onClick={confirm}
              disabled={confirming}
              className="w-full max-w-sm bg-green-600 hover:bg-green-700 disabled:bg-green-300 text-white font-bold py-5 rounded-xl text-lg transition"
            >
              {confirming ? '…' : isAr ? 'تأكيد الاستلام' : 'Confirm Handover'}
            </button>
          </>
        )}
      </div>
    </div>
  )
}

// ── Queue view ─────────────────────────────────────────────────────────────────

function QueueView({
  onSelect,
  onHandover,
}: {
  onSelect: (orderId: string) => void
  onHandover: (order: QueueOrder) => void
}) {
  const { t, i18n } = useTranslation()
  const isAr = i18n.language === 'ar'
  const [queue, setQueue] = useState<QueueOrder[]>([])
  const [loading, setLoading] = useState(true)

  const loadQueue = useCallback(async () => {
    try {
      const { data } = await api<QueueOrder[]>('/fulfill/queue')
      setQueue(data)
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { loadQueue() }, [loadQueue])

  if (loading) return <p className="p-6 text-gray-500">{t('common.loading')}</p>

  const pickQueue     = queue.filter(o => o.status !== 'self_pickup_pending')
  const handoverQueue = queue.filter(o => o.status === 'self_pickup_pending')

  return (
    <div className="p-6 max-w-4xl mx-auto">
      <div className="flex items-center justify-between mb-6">
        <h1 className="text-2xl font-bold text-gray-900">{t('fulfill.title')}</h1>
        <button onClick={loadQueue} className="text-sm text-indigo-600 hover:text-indigo-800 font-medium">
          {t('fulfill.refresh')}
        </button>
      </div>

      {/* Self-pickup pending section */}
      {handoverQueue.length > 0 && (
        <div className="mb-6">
          <h2 className="text-sm font-semibold text-gray-500 uppercase tracking-wide mb-3">
            {isAr ? 'في انتظار الاستلام' : 'Awaiting Collection'}
          </h2>
          <div className="space-y-3">
            {handoverQueue.map(order => (
              <div
                key={order.id}
                className="bg-amber-50 rounded-lg border border-amber-200 p-4 flex items-center justify-between hover:border-amber-400 cursor-pointer transition"
                onClick={() => onHandover(order)}
              >
                <div>
                  <p className="font-semibold text-gray-900 flex items-center gap-2">
                    {order.number ?? order.id.slice(-8)}
                    <span className="text-xs px-2 py-0.5 rounded bg-amber-100 text-amber-700 font-semibold">
                      {isAr ? 'استلام شخصي' : 'Self-Pickup'}
                    </span>
                  </p>
                  <p className="text-sm text-gray-500">{order.customer_name ?? t('common.na')}</p>
                </div>
                <div className="flex items-center gap-3">
                  <span className="text-sm font-medium text-gray-600">
                    {order.total_units} {t('fulfill.units')}
                  </span>
                  <button className="text-sm px-4 py-2 bg-amber-500 text-white rounded-lg hover:bg-amber-600 font-medium">
                    {isAr ? 'تسليم' : 'Handover →'}
                  </button>
                </div>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* Normal pick queue */}
      {pickQueue.length === 0 && handoverQueue.length === 0 ? (
        <div className="text-center py-20 text-gray-400">{t('fulfill.empty')}</div>
      ) : pickQueue.length === 0 ? null : (
        <>
          {handoverQueue.length > 0 && (
            <h2 className="text-sm font-semibold text-gray-500 uppercase tracking-wide mb-3">
              {isAr ? 'قائمة التجميع' : 'Pick Queue'}
            </h2>
          )}
          <div className="space-y-3">
            {pickQueue.map(order => {
              const progress = order.total_units > 0
                ? Math.round((order.scanned_units / order.total_units) * 100) : 0
              return (
                <div
                  key={order.id}
                  className="bg-white rounded-lg border border-gray-200 p-4 flex items-center justify-between hover:border-indigo-400 cursor-pointer transition"
                  onClick={() => onSelect(order.id)}
                >
                  <div>
                    <p className="font-semibold text-gray-900 flex items-center gap-2">
                      {order.number ?? order.id.slice(-8)}
                      {order.is_self_pickup && (
                        <span className="text-xs px-2 py-0.5 rounded bg-indigo-100 text-indigo-700 font-medium">
                          {isAr ? 'استلام شخصي' : 'Self-Pickup'}
                        </span>
                      )}
                    </p>
                    <p className="text-sm text-gray-500">
                      {order.customer_name ?? t('common.na')}
                      {order.payment_method === 'cod' && order.cod_amount && (
                        <span className="ml-2 text-amber-600 font-medium">COD {order.cod_amount}</span>
                      )}
                    </p>
                  </div>
                  <div className="flex items-center gap-4">
                    <div className="text-right">
                      <p className="text-sm font-medium text-gray-700">
                        {order.scanned_units} / {order.total_units} {t('fulfill.units')}
                      </p>
                      <div className="w-32 bg-gray-200 rounded-full h-2 mt-1">
                        <div
                          className="bg-indigo-500 h-2 rounded-full transition-all"
                          style={{ width: `${progress}%` }}
                        />
                      </div>
                    </div>
                    <span className={`
                      px-2 py-0.5 rounded text-xs font-semibold
                      ${order.status === 'new' ? 'bg-blue-100 text-blue-700' : 'bg-yellow-100 text-yellow-700'}
                    `}>
                      {order.status}
                    </span>
                    {order.locked_by && (
                      <span className="text-xs text-gray-400 italic">{t('fulfill.locked')}</span>
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
  const { i18n } = useTranslation()
  const isAr = i18n.language === 'ar'
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
        onUnpacked() // refresh order
      }
    } finally {
      setUnpacking(null)
    }
  }

  if (done) {
    return (
      <div className="bg-green-50 border border-green-200 rounded-lg p-6 text-center">
        <div className="text-3xl mb-2">✓</div>
        <p className="text-green-700 font-semibold">
          {isAr ? 'تمت إزالة جميع القطع — الطلب ملغى' : 'All pieces unpacked — order cancelled'}
        </p>
      </div>
    )
  }

  return (
    <div className="bg-amber-50 border border-amber-200 rounded-lg p-4">
      <p className="text-sm font-semibold text-amber-800 mb-3">
        {isAr
          ? `طلب الإلغاء — يجب فك تعبئة ${packedPieces.length} قطعة`
          : `Cancellation requested — unpack ${packedPieces.length} piece(s)`}
      </p>
      <div className="space-y-2">
        {packedPieces.map(p => (
          <div
            key={p.piece_id}
            className="flex items-center justify-between bg-white rounded border border-amber-200 px-3 py-2"
          >
            <span className="font-mono text-sm text-gray-700">{p.barcode.slice(-10)}</span>
            <button
              onClick={() => unpack(p.piece_id)}
              disabled={unpacking === p.piece_id}
              className="text-xs px-3 py-1 bg-amber-500 text-white rounded hover:bg-amber-600 disabled:opacity-50"
            >
              {unpacking === p.piece_id ? '…' : isAr ? 'فك التعبئة' : 'Unpack'}
            </button>
          </div>
        ))}
      </div>
    </div>
  )
}

// ── Pick screen ────────────────────────────────────────────────────────────────

function PickScreen({ orderId, onBack }: { orderId: string; onBack: () => void }) {
  const { t, i18n } = useTranslation()
  const isAr = i18n.language === 'ar'
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
  const inputRef = useRef<HTMLInputElement>(null)

  const loadOrder = useCallback(async () => {
    const { data } = await api<OrderDetail>(`/fulfill/${orderId}`)
    setOrder(data)
    setLoading(false)
  }, [orderId])

  useEffect(() => { loadOrder() }, [loadOrder])

  useEffect(() => {
    const refocus = () => {
      if (document.activeElement !== inputRef.current) inputRef.current?.focus()
    }
    document.addEventListener('click', refocus)
    inputRef.current?.focus()
    return () => document.removeEventListener('click', refocus)
  }, [order])

  const triggerFlash = (state: 'success' | 'error') => {
    setFlash(state)
    setTimeout(() => setFlash('idle'), 600)
  }

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
        // Pre-pack: order is cancelled, go back to queue
        onBack()
      } else {
        // Post-pack: cancel_requested, show guided unpack (order re-fetched)
        await loadOrder()
      }
    } finally {
      setCancelling(false)
    }
  }

  if (loading) return <p className="p-6 text-gray-500">{t('common.loading')}</p>
  if (!order) return null

  const allComplete = order.items.every(i => i.allocated >= i.quantity)
  const hasCancelRequest = !!order.cancel_requested_at

  const flashClass = flash === 'success'
    ? 'fixed inset-0 bg-green-400 opacity-30 pointer-events-none z-50 animate-flash'
    : flash === 'error'
    ? 'fixed inset-0 bg-red-400 opacity-30 pointer-events-none z-50 animate-flash'
    : 'hidden'

  return (
    <div className="flex flex-col h-screen bg-gray-50">
      <div className={flashClass} />

      {/* Header */}
      <div className="bg-white border-b border-gray-200 px-6 py-3 flex items-center justify-between">
        <button onClick={onBack} className="text-indigo-600 hover:text-indigo-800 font-medium text-sm">
          ← {t('fulfill.backToQueue')}
        </button>
        <div className="text-center">
          <p className="font-bold text-gray-900 flex items-center gap-2">
            {order.number ?? order.id.slice(-8)}
            {order.is_self_pickup && (
              <span className="text-xs px-2 py-0.5 rounded bg-indigo-100 text-indigo-700 font-medium">
                {isAr ? 'استلام شخصي' : 'Self-Pickup'}
              </span>
            )}
          </p>
          <p className="text-sm text-gray-500">{order.customer_name ?? t('common.na')}</p>
        </div>
        {/* Cancel button (top-right) */}
        {!hasCancelRequest && (
          <button
            onClick={() => setShowCancelConfirm(true)}
            className="text-xs px-3 py-1.5 border border-red-200 text-red-600 rounded hover:bg-red-50"
          >
            {isAr ? 'إلغاء الطلب' : 'Cancel'}
          </button>
        )}
        {!showCancelConfirm && hasCancelRequest === false && <div className="w-20" />}
      </div>

      {/* Cancel confirm dialog */}
      {showCancelConfirm && (
        <div className="fixed inset-0 bg-black/40 flex items-center justify-center z-50" onClick={() => setShowCancelConfirm(false)}>
          <div className="bg-white rounded-lg shadow-xl p-6 w-full max-w-sm mx-4" onClick={e => e.stopPropagation()}>
            <h3 className="font-semibold text-gray-900 mb-2">{isAr ? 'إلغاء الطلب؟' : 'Cancel this order?'}</h3>
            <p className="text-sm text-gray-600 mb-4">
              {order.status === 'packed'
                ? isAr ? 'الطلب معبَّأ — يحتاج إلى فك التعبئة يدوياً.' : 'Order is packed — pieces must be manually unpacked.'
                : isAr ? 'سيتم إعادة القطع المحجوزة للمخزون.' : 'Reserved pieces will be returned to stock.'}
            </p>
            <div className="flex gap-3 justify-end">
              <button onClick={() => setShowCancelConfirm(false)} className="px-4 py-2 text-sm border rounded hover:bg-gray-50">
                {isAr ? 'رجوع' : 'Back'}
              </button>
              <button
                onClick={handleCancel}
                disabled={cancelling}
                className="px-4 py-2 text-sm bg-red-600 text-white rounded hover:bg-red-700 disabled:opacity-50"
              >
                {cancelling ? '…' : isAr ? 'تأكيد الإلغاء' : 'Confirm Cancel'}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Self-pickup success banner */}
      {selfPickupSuccess && (
        <div className="bg-green-50 border-b border-green-200 px-6 py-3 text-center">
          <p className="text-green-700 font-semibold">
            {isAr ? 'تم التعبئة — في انتظار الاستلام' : 'Packed — awaiting customer collection'}
          </p>
        </div>
      )}

      {/* Guided unpack panel (cancel_requested) */}
      {hasCancelRequest && (
        <div className="px-6 py-4">
          <GuidedUnpackPanel order={order} onUnpacked={loadOrder} />
        </div>
      )}

      {/* Scan input (hidden when cancel is requested) */}
      {!hasCancelRequest && (
        <div className="bg-white border-b border-gray-200 px-6 py-4">
          <input
            ref={inputRef}
            type="text"
            placeholder={t('fulfill.scanPlaceholder')}
            className="w-full border-2 border-indigo-300 rounded-lg px-4 py-3 text-lg font-mono focus:outline-none focus:border-indigo-500"
            disabled={scanning}
            onKeyDown={e => {
              if (e.key === 'Enter') handleScan((e.target as HTMLInputElement).value)
            }}
            autoFocus
          />
          {lastResult && (
            <div className={`mt-2 text-sm font-medium ${lastResult.success ? 'text-green-600' : 'text-red-600'}`}>
              {lastResult.success
                ? `✓ ${lastResult.barcode}`
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
              className={`bg-white rounded-lg border p-4 ${complete ? 'border-green-300' : 'border-gray-200'}`}
            >
              <div className="flex items-start justify-between mb-3">
                <div>
                  <p className="font-semibold text-gray-900">{item.product_title}</p>
                  {item.variant_title && item.variant_title !== 'Default Title' && (
                    <p className="text-sm text-gray-500">{item.variant_title}</p>
                  )}
                  {item.sku && <p className="text-xs text-gray-400 font-mono">{item.sku}</p>}
                </div>
                <span className={`
                  text-lg font-bold px-3 py-1 rounded-full
                  ${complete ? 'bg-green-100 text-green-700' : 'bg-gray-100 text-gray-700'}
                `}>
                  {item.allocated}/{item.quantity}
                </span>
              </div>
              {item.allocatedPieces.length > 0 && (
                <div className="flex flex-wrap gap-2">
                  {item.allocatedPieces.map(p => (
                    <div
                      key={p.piece_id}
                      className="flex items-center gap-1 bg-indigo-50 border border-indigo-200 rounded px-2 py-1"
                    >
                      <span className="text-xs font-mono text-indigo-700">{p.barcode.slice(-10)}</span>
                      {!hasCancelRequest && p.allocation_status === 'active' && (
                        <button
                          onClick={() => handleUnscan(p.piece_id)}
                          className="text-red-400 hover:text-red-600 text-xs ml-1"
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

      {/* Complete button */}
      {allComplete && !hasCancelRequest && (
        <div className="bg-white border-t border-gray-200 px-6 py-4">
          <button
            onClick={handleComplete}
            disabled={completing}
            className="w-full bg-green-600 hover:bg-green-700 disabled:bg-green-300 text-white font-bold py-4 rounded-lg text-lg transition"
          >
            {completing ? t('common.loading') : t('fulfill.complete')}
          </button>
        </div>
      )}

      {/* AWB-scan dialog — for non-self-pickup orders after pack completes */}
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

  if (view.type === 'pick') {
    return <PickScreen orderId={view.orderId} onBack={() => setView({ type: 'queue' })} />
  }
  if (view.type === 'handover') {
    return <HandoverScreen order={view.order} onBack={() => setView({ type: 'queue' })} />
  }
  return (
    <QueueView
      onSelect={orderId => setView({ type: 'pick', orderId })}
      onHandover={order => setView({ type: 'handover', order })}
    />
  )
}
