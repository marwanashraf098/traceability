import { useEffect, useRef, useState, useCallback } from 'react'
import { useTranslation } from 'react-i18next'

const BASE = '/api/v1'

function authHeaders(): Record<string, string> {
  const token = localStorage.getItem('token')
  return token ? { Authorization: `Bearer ${token}` } : {}
}

async function api<T = void>(path: string, opts: RequestInit = {}): Promise<T> {
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
  if (!res.ok) throw new Error(`HTTP ${res.status}`)
  if (res.status === 204) return undefined as T
  return res.json()
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

// Flash state for scan feedback
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
    // AudioContext may be blocked on some browsers — silent fallback
  }
}

// ── Queue view ─────────────────────────────────────────────────────────────────

function QueueView({ onSelect }: { onSelect: (orderId: string) => void }) {
  const { t } = useTranslation()
  const [queue, setQueue] = useState<QueueOrder[]>([])
  const [loading, setLoading] = useState(true)

  const loadQueue = useCallback(async () => {
    try {
      const data = await api<QueueOrder[]>('/fulfill/queue')
      setQueue(data)
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { loadQueue() }, [loadQueue])

  if (loading) return <p className="p-6 text-gray-500">{t('common.loading')}</p>

  return (
    <div className="p-6 max-w-4xl mx-auto">
      <div className="flex items-center justify-between mb-6">
        <h1 className="text-2xl font-bold text-gray-900">{t('fulfill.title')}</h1>
        <button
          onClick={loadQueue}
          className="text-sm text-indigo-600 hover:text-indigo-800 font-medium"
        >
          {t('fulfill.refresh')}
        </button>
      </div>

      {queue.length === 0 ? (
        <div className="text-center py-20 text-gray-400">{t('fulfill.empty')}</div>
      ) : (
        <div className="space-y-3">
          {queue.map(order => {
            const progress = order.total_units > 0
              ? Math.round((order.scanned_units / order.total_units) * 100) : 0
            return (
              <div
                key={order.id}
                className="bg-white rounded-lg border border-gray-200 p-4 flex items-center justify-between hover:border-indigo-400 cursor-pointer transition"
                onClick={() => onSelect(order.id)}
              >
                <div>
                  <p className="font-semibold text-gray-900">
                    {order.number ?? order.id.slice(-8)}
                  </p>
                  <p className="text-sm text-gray-500">
                    {order.customer_name ?? t('common.na')}
                    {order.payment_method === 'cod' && order.cod_amount && (
                      <span className="ml-2 text-amber-600 font-medium">
                        COD {order.cod_amount}
                      </span>
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
      )}
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
  const inputRef = useRef<HTMLInputElement>(null)

  const loadOrder = useCallback(async () => {
    const data = await api<OrderDetail>(`/fulfill/${orderId}`)
    setOrder(data)
    setLoading(false)
  }, [orderId])

  useEffect(() => { loadOrder() }, [loadOrder])

  // Keep scan input auto-focused; HID barcode scanners act like keyboard input
  useEffect(() => {
    const refocus = () => {
      if (document.activeElement !== inputRef.current) {
        inputRef.current?.focus()
      }
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
      const result = await api<ScanResult>(`/fulfill/${orderId}/scan`, {
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
      // Clear input and refocus
      if (inputRef.current) {
        inputRef.current.value = ''
        inputRef.current.focus()
      }
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
      onBack()
    } finally {
      setCompleting(false)
    }
  }

  if (loading) return <p className="p-6 text-gray-500">{t('common.loading')}</p>
  if (!order) return null

  const allComplete = order.items.every(i => i.allocated >= i.quantity)

  const flashClass = flash === 'success'
    ? 'fixed inset-0 bg-green-400 opacity-30 pointer-events-none z-50 animate-flash'
    : flash === 'error'
    ? 'fixed inset-0 bg-red-400 opacity-30 pointer-events-none z-50 animate-flash'
    : 'hidden'

  return (
    <div className="flex flex-col h-screen bg-gray-50">
      {/* Flash overlay */}
      <div className={flashClass} />

      {/* Header */}
      <div className="bg-white border-b border-gray-200 px-6 py-3 flex items-center justify-between">
        <button onClick={onBack} className="text-indigo-600 hover:text-indigo-800 font-medium text-sm">
          ← {t('fulfill.backToQueue')}
        </button>
        <div className="text-center">
          <p className="font-bold text-gray-900">{order.number ?? order.id.slice(-8)}</p>
          <p className="text-sm text-gray-500">{order.customer_name ?? t('common.na')}</p>
        </div>
        <div className="w-24" />
      </div>

      {/* Scan input — always focused for HID scanner */}
      <div className="bg-white border-b border-gray-200 px-6 py-4">
        <input
          ref={inputRef}
          type="text"
          placeholder={t('fulfill.scanPlaceholder')}
          className="w-full border-2 border-indigo-300 rounded-lg px-4 py-3 text-lg font-mono focus:outline-none focus:border-indigo-500"
          disabled={scanning}
          onKeyDown={e => {
            if (e.key === 'Enter') {
              handleScan((e.target as HTMLInputElement).value)
            }
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
                      <span className="text-xs font-mono text-indigo-700">
                        {p.barcode.slice(-10)}
                      </span>
                      <button
                        onClick={() => handleUnscan(p.piece_id)}
                        className="text-red-400 hover:text-red-600 text-xs ml-1"
                        title={t('fulfill.unscan')}
                      >
                        ✕
                      </button>
                    </div>
                  ))}
                </div>
              )}
            </div>
          )
        })}
      </div>

      {/* Complete button */}
      {allComplete && (
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
    </div>
  )
}

// ── Root ───────────────────────────────────────────────────────────────────────

export default function Fulfill() {
  const [selectedOrderId, setSelectedOrderId] = useState<string | null>(null)

  if (selectedOrderId) {
    return <PickScreen orderId={selectedOrderId} onBack={() => setSelectedOrderId(null)} />
  }
  return <QueueView onSelect={setSelectedOrderId} />
}
