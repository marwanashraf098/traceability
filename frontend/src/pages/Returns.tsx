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
  if (!res.ok) {
    const body = await res.json().catch(() => ({}))
    throw Object.assign(new Error(body?.message ?? `HTTP ${res.status}`), { status: res.status })
  }
  if (res.status === 204) return undefined as T
  return res.json()
}

type FlashState = 'idle' | 'success' | 'error'

function playBeep(success: boolean) {
  try {
    const ctx = new AudioContext()
    const osc = ctx.createOscillator()
    const gain = ctx.createGain()
    osc.connect(gain); gain.connect(ctx.destination)
    osc.frequency.setValueAtTime(success ? 880 : 300, ctx.currentTime)
    osc.type = 'sine'
    gain.gain.setValueAtTime(0.3, ctx.currentTime)
    gain.gain.exponentialRampToValueAtTime(0.001, ctx.currentTime + (success ? 0.15 : 0.4))
    osc.start(ctx.currentTime); osc.stop(ctx.currentTime + (success ? 0.15 : 0.4))
  } catch { /* silent fallback */ }
}

interface IntakeResult {
  id: string
  barcode?: string
  status: string
  variantTitle: string
  productTitle: string
  orderNumber?: string
  trackingNumber?: string
  isUnexpected: boolean
}

interface PendingPiece {
  id: string
  barcode: string
  variant_title: string
  product_title: string
  order_number?: string
  tracking_number?: string
  location_name?: string
  last_event_at?: string
}

interface NeverReceivedRow {
  id: string
  barcode: string
  variant_title: string
  product_title: string
  order_number?: string
  tracking_number?: string
  returned_at?: string
}

// ── Intake tab ─────────────────────────────────────────────────────────────────

function IntakeTab() {
  const { t } = useTranslation()
  const inputRef = useRef<HTMLInputElement>(null)
  const [flash, setFlash] = useState<FlashState>('idle')
  const [scanning, setScanning] = useState(false)
  const [result, setResult] = useState<IntakeResult | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => { inputRef.current?.focus() }, [])

  const triggerFlash = (s: 'success' | 'error') => {
    setFlash(s); setTimeout(() => setFlash('idle'), 600)
  }

  const handleScan = useCallback(async (barcode: string) => {
    if (!barcode.trim() || scanning) return
    setScanning(true); setResult(null); setError(null)
    try {
      const data = await api<IntakeResult>('/returns/intake', {
        method: 'POST',
        body: JSON.stringify({ barcode: barcode.trim(), locationId: null }),
      })
      playBeep(true); triggerFlash('success')
      setResult(data)
    } catch (e: unknown) {
      playBeep(false); triggerFlash('error')
      const status = (e as { status?: number }).status
      setError(status === 404 ? t('returns.notFound') : t('common.error'))
    } finally {
      setScanning(false)
      if (inputRef.current) { inputRef.current.value = ''; inputRef.current.focus() }
    }
  }, [scanning, t])

  const flashClass = flash === 'success'
    ? 'fixed inset-0 bg-green-400 opacity-30 pointer-events-none z-50 animate-flash'
    : flash === 'error'
    ? 'fixed inset-0 bg-red-400 opacity-30 pointer-events-none z-50 animate-flash'
    : 'hidden'

  return (
    <div className="max-w-xl mx-auto py-6 px-4">
      <div className={flashClass} />

      <input
        ref={inputRef}
        type="text"
        placeholder={t('returns.scanPlaceholder')}
        className="w-full border-2 border-indigo-300 rounded-lg px-4 py-3 text-lg font-mono focus:outline-none focus:border-indigo-500 mb-4"
        disabled={scanning}
        onKeyDown={e => {
          if (e.key === 'Enter') handleScan((e.target as HTMLInputElement).value)
        }}
        autoFocus
      />

      {error && (
        <div className="bg-red-50 border border-red-200 rounded-lg p-4 mb-4 text-red-700 text-sm font-medium">
          ✗ {error}
        </div>
      )}

      {result && (
        <div className={`rounded-lg border p-5 space-y-3 ${result.isUnexpected ? 'border-amber-300 bg-amber-50' : 'border-green-300 bg-green-50'}`}>
          <div className="flex items-center justify-between">
            <span className="text-sm font-semibold text-gray-500">{t('returns.col.barcode')}</span>
            <span className="font-mono text-sm text-gray-900">{result.barcode ?? result.id}</span>
          </div>
          <div className="flex items-center justify-between">
            <span className="text-sm font-semibold text-gray-500">{t('returns.col.variant')}</span>
            <span className="text-sm text-gray-900">{result.variantTitle}</span>
          </div>
          <div className="flex items-center justify-between">
            <span className="text-sm font-semibold text-gray-500">{t('returns.col.status')}</span>
            <span className="text-sm text-indigo-700 font-medium">{result.status}</span>
          </div>
          {result.orderNumber && (
            <div className="flex items-center justify-between">
              <span className="text-sm font-semibold text-gray-500">{t('returns.col.order')}</span>
              <span className="text-sm text-gray-900">{result.orderNumber}</span>
            </div>
          )}
          {result.trackingNumber && (
            <div className="flex items-center justify-between">
              <span className="text-sm font-semibold text-gray-500">{t('lookup.shipment')}</span>
              <span className="font-mono text-xs text-gray-700">{result.trackingNumber}</span>
            </div>
          )}
          {result.isUnexpected && (
            <div className="pt-2 border-t border-amber-300 text-amber-700 text-sm font-medium">
              {t('returns.unexpected')}
            </div>
          )}
          {!result.isUnexpected && (
            <div className="pt-2 border-t border-green-300 text-green-700 text-sm font-medium">
              ✓ {t('returns.intakeSuccess')}
            </div>
          )}
        </div>
      )}
    </div>
  )
}

// ── Pending inspection tab ─────────────────────────────────────────────────────

function PendingTab() {
  const { t } = useTranslation()
  const [pieces, setPieces] = useState<PendingPiece[]>([])
  const [loading, setLoading] = useState(true)
  const [damageTarget, setDamageTarget] = useState<string | null>(null)
  const [damageReason, setDamageReason] = useState('')

  const load = useCallback(async () => {
    setLoading(true)
    try { setPieces(await api<PendingPiece[]>('/returns/pending')) }
    finally { setLoading(false) }
  }, [])

  useEffect(() => { load() }, [load])

  const restock = async (pieceId: string) => {
    await api(`/returns/pieces/${pieceId}/restock`, {
      method: 'POST',
      body: JSON.stringify({ locationId: null }),
    })
    load()
  }

  const damage = async (pieceId: string) => {
    if (!damageReason.trim()) return
    await api(`/returns/pieces/${pieceId}/damage`, {
      method: 'POST',
      body: JSON.stringify({ reason: damageReason }),
    })
    setDamageTarget(null); setDamageReason(''); load()
  }

  if (loading) return <p className="p-6 text-gray-500">{t('common.loading')}</p>

  return (
    <div className="max-w-2xl mx-auto py-6 px-4">
      {pieces.length === 0 ? (
        <div className="text-center py-16 text-gray-400">{t('returns.pending.empty')}</div>
      ) : (
        <div className="space-y-3">
          {pieces.map(p => (
            <div key={p.id} className="bg-white border border-gray-200 rounded-lg p-4">
              <div className="flex items-start justify-between">
                <div>
                  <p className="font-semibold text-gray-900">{p.product_title}</p>
                  <p className="text-sm text-gray-500">{p.variant_title}</p>
                  <p className="text-xs font-mono text-gray-400 mt-1">{p.barcode}</p>
                  {p.order_number && (
                    <p className="text-xs text-gray-500">
                      {t('returns.col.order')}: {p.order_number}
                    </p>
                  )}
                  {p.location_name && (
                    <p className="text-xs text-gray-400">
                      {t('returns.col.location')}: {p.location_name}
                    </p>
                  )}
                </div>
                <div className="flex gap-2 mt-1">
                  <button
                    onClick={() => restock(p.id)}
                    className="text-sm bg-green-100 text-green-700 hover:bg-green-200 font-medium px-3 py-1 rounded"
                  >
                    {t('returns.pending.restock')}
                  </button>
                  <button
                    onClick={() => setDamageTarget(p.id)}
                    className="text-sm bg-red-100 text-red-700 hover:bg-red-200 font-medium px-3 py-1 rounded"
                  >
                    {t('returns.pending.damage')}
                  </button>
                </div>
              </div>

              {damageTarget === p.id && (
                <div className="mt-3 pt-3 border-t border-gray-200 flex gap-2">
                  <input
                    type="text"
                    value={damageReason}
                    onChange={e => setDamageReason(e.target.value)}
                    placeholder={t('returns.pending.damageReason')}
                    className="flex-1 border border-gray-300 rounded px-3 py-1.5 text-sm focus:outline-none focus:border-red-400"
                    autoFocus
                    onKeyDown={e => { if (e.key === 'Enter') damage(p.id) }}
                  />
                  <button
                    onClick={() => damage(p.id)}
                    className="bg-red-600 text-white text-sm font-medium px-3 py-1.5 rounded hover:bg-red-700"
                  >
                    {t('returns.pending.confirm')}
                  </button>
                  <button
                    onClick={() => { setDamageTarget(null); setDamageReason('') }}
                    className="text-sm text-gray-500 hover:text-gray-700 px-2"
                  >
                    {t('common.cancel')}
                  </button>
                </div>
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  )
}

// ── Never-received tab ─────────────────────────────────────────────────────────

function NeverReceivedTab() {
  const { t } = useTranslation()
  const [rows, setRows] = useState<NeverReceivedRow[]>([])
  const [loading, setLoading] = useState(true)
  const [windowDays, setWindowDays] = useState(3)

  const load = useCallback(async () => {
    setLoading(true)
    try { setRows(await api<NeverReceivedRow[]>(`/returns/never-received?windowDays=${windowDays}`)) }
    finally { setLoading(false) }
  }, [windowDays])

  useEffect(() => { load() }, [load])

  const formatDate = (iso?: string) => iso
    ? new Date(iso).toLocaleDateString(undefined, { day: 'numeric', month: 'short', year: 'numeric' })
    : '—'

  return (
    <div className="max-w-3xl mx-auto py-6 px-4">
      <div className="bg-amber-50 border border-amber-200 rounded-lg p-4 mb-6">
        <p className="text-sm font-semibold text-amber-800 mb-1">{t('returns.neverReceived.subtitle')}</p>
        <div className="flex items-center gap-3 mt-2">
          <label className="text-xs text-amber-700">{t('returns.neverReceived.window')}:</label>
          <input
            type="number"
            min={1}
            max={90}
            value={windowDays}
            onChange={e => setWindowDays(Number(e.target.value))}
            className="w-16 border border-amber-300 rounded px-2 py-1 text-sm focus:outline-none"
          />
          <button
            onClick={load}
            className="text-sm bg-amber-100 text-amber-800 hover:bg-amber-200 font-medium px-3 py-1 rounded"
          >
            ↻
          </button>
        </div>
      </div>

      {loading ? (
        <p className="text-gray-500">{t('common.loading')}</p>
      ) : rows.length === 0 ? (
        <div className="text-center py-16 text-green-600 font-medium">
          ✓ {t('returns.neverReceived.empty')}
        </div>
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-gray-200 text-left text-xs text-gray-500 uppercase tracking-wide">
                <th className="pb-2 pr-4">{t('returns.neverReceived.piece')}</th>
                <th className="pb-2 pr-4">{t('returns.col.variant')}</th>
                <th className="pb-2 pr-4">{t('returns.neverReceived.order')}</th>
                <th className="pb-2 pr-4">{t('returns.neverReceived.tracking')}</th>
                <th className="pb-2">{t('returns.neverReceived.returnedAt')}</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {rows.map(r => (
                <tr key={r.id} className="hover:bg-amber-50">
                  <td className="py-2 pr-4 font-mono text-xs text-gray-700">{r.barcode}</td>
                  <td className="py-2 pr-4">
                    <p className="text-gray-900">{r.product_title}</p>
                    <p className="text-gray-400 text-xs">{r.variant_title}</p>
                  </td>
                  <td className="py-2 pr-4 text-gray-700">{r.order_number ?? '—'}</td>
                  <td className="py-2 pr-4 font-mono text-xs text-gray-600">
                    {r.tracking_number ?? '—'}
                  </td>
                  <td className="py-2 text-red-600 font-medium">{formatDate(r.returned_at?.toString())}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}

// ── Root ───────────────────────────────────────────────────────────────────────

type Tab = 'intake' | 'pending' | 'never-received'

export default function Returns() {
  const { t } = useTranslation()
  const [tab, setTab] = useState<Tab>('intake')

  const tabClass = (active: boolean) =>
    `px-4 py-2 text-sm font-medium border-b-2 transition ${
      active
        ? 'border-indigo-600 text-indigo-700'
        : 'border-transparent text-gray-500 hover:text-gray-700'
    }`

  return (
    <div>
      <div className="border-b border-gray-200 px-6">
        <div className="flex items-center justify-between py-4">
          <h1 className="text-xl font-bold text-gray-900">{t('returns.title')}</h1>
        </div>
        <nav className="flex gap-2 -mb-px">
          <button className={tabClass(tab === 'intake')} onClick={() => setTab('intake')}>
            {t('returns.title')}
          </button>
          <button className={tabClass(tab === 'pending')} onClick={() => setTab('pending')}>
            {t('returns.pending.title')}
          </button>
          <button className={tabClass(tab === 'never-received')} onClick={() => setTab('never-received')}>
            {t('returns.neverReceived.title')}
          </button>
        </nav>
      </div>

      {tab === 'intake'         && <IntakeTab />}
      {tab === 'pending'        && <PendingTab />}
      {tab === 'never-received' && <NeverReceivedTab />}
    </div>
  )
}
