import { useEffect, useRef, useState, useCallback } from 'react'
import { useTranslation } from 'react-i18next'
import { Badge, EmptyState, Spinner } from '../components/ui'

const BASE = '/api/v1'
function authHeaders(): Record<string, string> {
  const t = localStorage.getItem('token')
  return t ? { Authorization: `Bearer ${t}` } : {}
}
async function api<T = void>(path: string, opts: RequestInit = {}): Promise<T> {
  const res = await fetch(BASE + path, {
    ...opts,
    headers: {
      'Content-Type': 'application/json',
      ...authHeaders(),
      ...(opts.headers as Record<string, string> ?? {}),
    },
  })
  if (res.status === 401) { localStorage.removeItem('token'); window.location.href = '/login'; throw new Error('401') }
  if (!res.ok) { const b = await res.json().catch(() => ({})); throw Object.assign(new Error(b?.message ?? `HTTP ${res.status}`), { status: res.status }) }
  if (res.status === 204) return undefined as T
  return res.json()
}

type FlashState = 'idle' | 'success' | 'error'

function playBeep(ok: boolean) {
  try {
    const ctx = new AudioContext(), osc = ctx.createOscillator(), g = ctx.createGain()
    osc.connect(g); g.connect(ctx.destination)
    osc.frequency.value = ok ? 880 : 300; osc.type = 'sine'
    g.gain.setValueAtTime(0.3, ctx.currentTime)
    g.gain.exponentialRampToValueAtTime(0.001, ctx.currentTime + (ok ? 0.15 : 0.4))
    osc.start(); osc.stop(ctx.currentTime + (ok ? 0.15 : 0.4))
  } catch { /* silent */ }
}

interface IntakeResult { id: string; barcode?: string; status: string; variantTitle: string; productTitle: string; orderNumber?: string; trackingNumber?: string; isUnexpected: boolean }
interface PendingPiece { id: string; barcode: string; variant_title: string; product_title: string; order_number?: string; tracking_number?: string; location_name?: string }
interface NeverReceivedRow { id: string; barcode: string; variant_title: string; product_title: string; order_number?: string; tracking_number?: string; returned_at?: string }

// ── Intake tab ────────────────────────────────────────────────────────────────

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
        method: 'POST', body: JSON.stringify({ barcode: barcode.trim(), locationId: null }),
      })
      playBeep(true); triggerFlash('success'); setResult(data)
    } catch (e: unknown) {
      playBeep(false); triggerFlash('error')
      const status = (e as { status?: number }).status
      setError(status === 404 ? t('returns.notFound') : ((e as Error).message || t('common.error')))
    } finally {
      setScanning(false)
      if (inputRef.current) { inputRef.current.value = ''; inputRef.current.focus() }
    }
  }, [scanning, t])

  const flashOverlay =
    flash === 'success' ? 'fixed inset-0 bg-success/20 pointer-events-none z-50 animate-flash' :
    flash === 'error'   ? 'fixed inset-0 bg-danger/20 pointer-events-none z-50 animate-flash' :
    'hidden'

  return (
    <div className="max-w-xl mx-auto pt-4">
      <div className={flashOverlay} />

      <input
        ref={inputRef}
        type="text"
        placeholder={t('returns.scanPlaceholder')}
        className="input-scan w-full mb-4"
        disabled={scanning}
        onKeyDown={e => { if (e.key === 'Enter') handleScan((e.target as HTMLInputElement).value) }}
        autoFocus
      />

      {error && (
        <div className="card border-danger/30 bg-danger/5 p-4 mb-4 text-danger text-body">
          ✗ {error}
        </div>
      )}

      {result && (
        <div className={`card p-5 space-y-3 ${result.isUnexpected ? 'border-warning/40 bg-warning/5' : 'border-success/40 bg-success/5'}`}>
          <Row label={t('returns.col.barcode')}>
            <span className="font-mono text-small text-primary">{result.barcode ?? result.id}</span>
          </Row>
          <Row label={t('returns.col.variant')}>
            <span className="text-primary">{result.productTitle} / {result.variantTitle}</span>
          </Row>
          <Row label={t('returns.col.status')}>
            <Badge status={result.status} />
          </Row>
          {result.orderNumber && <Row label={t('returns.col.order')}><span className="text-primary">{result.orderNumber}</span></Row>}
          {result.trackingNumber && <Row label={t('lookup.shipment')}><span className="font-mono text-small text-cyan">{result.trackingNumber}</span></Row>}
          <div className={`pt-3 border-t ${result.isUnexpected ? 'border-warning/30 text-warning' : 'border-success/30 text-success'} text-body font-medium`}>
            {result.isUnexpected ? t('returns.unexpected') : `✓ ${t('returns.intakeSuccess')}`}
          </div>
        </div>
      )}
    </div>
  )
}

function Row({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex items-center justify-between">
      <span className="text-small text-muted">{label}</span>
      {children}
    </div>
  )
}

// ── Pending inspection tab ────────────────────────────────────────────────────

function PendingTab() {
  const { t } = useTranslation()
  const [pieces, setPieces]   = useState<PendingPiece[]>([])
  const [loading, setLoading] = useState(true)
  const [damageTarget, setDamageTarget] = useState<string | null>(null)
  const [damageReason, setDamageReason] = useState('')

  const load = useCallback(async () => {
    setLoading(true)
    try { setPieces(await api<PendingPiece[]>('/returns/pending')) }
    finally { setLoading(false) }
  }, [])
  useEffect(() => { load() }, [load])

  const restock = async (id: string) => {
    await api(`/returns/pieces/${id}/restock`, { method: 'POST', body: JSON.stringify({ locationId: null }) })
    load()
  }
  const damage = async (id: string) => {
    if (!damageReason.trim()) return
    await api(`/returns/pieces/${id}/damage`, { method: 'POST', body: JSON.stringify({ reason: damageReason }) })
    setDamageTarget(null); setDamageReason(''); load()
  }

  if (loading) return <div className="flex justify-center pt-12"><Spinner size={28} /></div>
  if (pieces.length === 0) return <EmptyState message={t('returns.pending.empty')} icon="📭" />

  return (
    <div className="space-y-3 pt-4">
      {pieces.map(p => (
        <div key={p.id} className="card p-4">
          <div className="flex items-start justify-between gap-4">
            <div>
              <p className="text-body text-primary font-medium">{p.product_title}</p>
              <p className="text-small text-muted">{p.variant_title}</p>
              <p className="text-caption text-muted font-mono mt-1">{p.barcode}</p>
              {p.order_number && <p className="text-small text-muted mt-0.5">{t('returns.col.order')}: {p.order_number}</p>}
              {p.location_name && <p className="text-small text-muted">{t('returns.col.location')}: {p.location_name}</p>}
            </div>
            <div className="flex gap-2 shrink-0">
              <button onClick={() => restock(p.id)} className="btn-outline btn text-small">
                {t('returns.pending.restock')}
              </button>
              <button onClick={() => setDamageTarget(p.id)} className="btn-danger btn text-small">
                {t('returns.pending.damage')}
              </button>
            </div>
          </div>
          {damageTarget === p.id && (
            <div className="mt-3 pt-3 border-t border-line flex gap-2">
              <input
                type="text"
                value={damageReason}
                onChange={e => setDamageReason(e.target.value)}
                placeholder={t('returns.pending.damageReason')}
                className="input flex-1"
                autoFocus
                onKeyDown={e => { if (e.key === 'Enter') damage(p.id) }}
              />
              <button onClick={() => damage(p.id)} className="btn-danger btn text-small">
                {t('returns.pending.confirm')}
              </button>
              <button onClick={() => { setDamageTarget(null); setDamageReason('') }} className="btn-ghost btn text-small">
                {t('common.cancel')}
              </button>
            </div>
          )}
        </div>
      ))}
    </div>
  )
}

// ── Never-received tab ────────────────────────────────────────────────────────

function NeverReceivedTab() {
  const { t } = useTranslation()
  const [rows, setRows]       = useState<NeverReceivedRow[]>([])
  const [loading, setLoading] = useState(true)
  const [windowDays, setWindowDays] = useState(3)

  const load = useCallback(async () => {
    setLoading(true)
    try { setRows(await api<NeverReceivedRow[]>(`/returns/never-received?windowDays=${windowDays}`)) }
    finally { setLoading(false) }
  }, [windowDays])
  useEffect(() => { load() }, [load])

  const fmtDate = (iso?: string) => iso
    ? new Date(iso).toLocaleDateString(undefined, { day: 'numeric', month: 'short', year: 'numeric' })
    : '—'

  return (
    <div className="pt-4 space-y-4">
      {/* Alert banner */}
      <div className="card border-warning/30 bg-warning/5 p-4">
        <p className="text-body text-warning font-medium mb-3">{t('returns.neverReceived.subtitle')}</p>
        <div className="flex items-center gap-3">
          <label className="text-small text-muted">{t('returns.neverReceived.window')}:</label>
          <input
            type="number"
            min={1} max={90}
            value={windowDays}
            onChange={e => setWindowDays(Number(e.target.value))}
            className="input w-20"
          />
          <button onClick={load} className="btn-outline btn text-small">↻</button>
        </div>
      </div>

      {loading ? (
        <div className="flex justify-center py-8"><Spinner /></div>
      ) : rows.length === 0 ? (
        <div className="flex flex-col items-center py-12 gap-2 text-success">
          <span className="text-3xl">✓</span>
          <p className="text-body">{t('returns.neverReceived.empty')}</p>
        </div>
      ) : (
        <div className="card overflow-x-auto">
          <table className="min-w-full">
            <thead>
              <tr className="border-b border-line">
                {[
                  t('returns.neverReceived.piece'),
                  t('returns.col.variant'),
                  t('returns.neverReceived.order'),
                  t('returns.neverReceived.tracking'),
                  t('returns.neverReceived.returnedAt'),
                ].map(h => (
                  <th key={h} className="tbl-header">{h}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {rows.map(r => (
                <tr key={r.id} className="tbl-row">
                  <td className="tbl-cell font-mono text-small text-muted">{r.barcode}</td>
                  <td className="tbl-cell">
                    <p className="text-primary">{r.product_title}</p>
                    <p className="text-small text-muted">{r.variant_title}</p>
                  </td>
                  <td className="tbl-cell text-primary">{r.order_number ?? '—'}</td>
                  <td className="tbl-cell font-mono text-small text-cyan">{r.tracking_number ?? '—'}</td>
                  <td className="tbl-cell text-danger font-medium">{fmtDate(r.returned_at?.toString())}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}

// ── Root ──────────────────────────────────────────────────────────────────────

type Tab = 'intake' | 'pending' | 'never-received'

export default function Returns() {
  const { t } = useTranslation()
  const [tab, setTab] = useState<Tab>('intake')

  const tabs: { key: Tab; label: string }[] = [
    { key: 'intake',         label: t('returns.title') },
    { key: 'pending',        label: t('returns.pending.title') },
    { key: 'never-received', label: t('returns.neverReceived.title') },
  ]

  return (
    <div className="space-y-0">
      <div className="flex items-end justify-between mb-0">
        <h1 className="text-h1 text-primary mb-4">{t('returns.title')}</h1>
      </div>

      {/* Tabs */}
      <div className="flex border-b border-line gap-1">
        {tabs.map(({ key, label }) => (
          <button
            key={key}
            onClick={() => setTab(key)}
            className={`px-4 py-2.5 text-body font-medium border-b-2 -mb-px transition-colors ${
              tab === key
                ? 'border-brand text-primary'
                : 'border-transparent text-muted hover:text-primary'
            }`}
          >
            {label}
          </button>
        ))}
      </div>

      <div className="pt-2">
        {tab === 'intake'         && <IntakeTab />}
        {tab === 'pending'        && <PendingTab />}
        {tab === 'never-received' && <NeverReceivedTab />}
      </div>
    </div>
  )
}
