import { useState, useEffect, useRef } from 'react'
import { useTranslation } from 'react-i18next'
import { EmptyState, Spinner } from '../components/ui'

import { getAccessToken, clearAccessToken } from '../auth'

const BASE = '/api/v1'
function authHeaders() {
  const t = getAccessToken()
  return { 'Content-Type': 'application/json', ...(t ? { Authorization: `Bearer ${t}` } : {}) }
}
async function api<T>(path: string, opts: RequestInit = {}): Promise<T> {
  const res = await fetch(BASE + path, { ...opts, headers: { ...authHeaders(), ...opts.headers as Record<string,string> } })
  if (res.status === 401) { clearAccessToken(); window.location.href = '/login'; throw new Error('Unauth') }
  if (!res.ok) { const txt = await res.text(); throw new Error(txt || res.statusText) }
  return res.json()
}

interface Session {
  id: string; status: string; reference: string | null; supplier_name: string | null
  location_name: string | null; created_at: string; finalized_at: string | null
  line_units: number; piece_count: number
}
interface Line {
  id: string; variant_id: string; variant_title: string; sku: string | null
  product_title: string; quantity: number
}
interface SessionDetail extends Session { lines: Line[] }
interface VariantMatch { id: string; title: string; sku: string | null; product_title: string }
interface Location { id: string; name: string }

export default function Receiving() {
  const { t } = useTranslation()
  const [sessions, setSessions] = useState<Session[]>([])
  const [loading, setLoading]   = useState(true)
  const [error, setError]       = useState<string | null>(null)
  const [view, setView]         = useState<'list' | 'create' | 'session'>('list')
  const [activeSession, setActiveSession] = useState<SessionDetail | null>(null)
  const [locations, setLocations] = useState<Location[]>([])

  useEffect(() => { loadSessions(); loadLocations() }, [])

  async function loadSessions() {
    try { setSessions(await api<Session[]>('/receiving/sessions')) }
    catch (e: unknown) { setError((e as Error).message) }
    finally { setLoading(false) }
  }

  async function loadLocations() {
    try { setLocations(await api<Location[]>('/locations').catch(() => [] as Location[])) }
    catch { setLocations([]) }
  }

  async function openSession(id: string) {
    try { setActiveSession(await api<SessionDetail>(`/receiving/sessions/${id}`)); setView('session') }
    catch (e: unknown) { setError((e as Error).message) }
  }

  if (loading) return <div className="flex justify-center pt-16"><Spinner size={28} /></div>
  if (error)   return <p className="text-small text-danger">{error}</p>

  if (view === 'create') {
    return (
      <CreateSessionForm
        locations={locations}
        onCreated={(id) => { loadSessions(); openSession(id) }}
        onCancel={() => setView('list')}
      />
    )
  }

  if (view === 'session' && activeSession) {
    return (
      <SessionView
        session={activeSession}
        onRefresh={() => openSession(activeSession.id)}
        onBack={() => { loadSessions(); setView('list') }}
      />
    )
  }

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h1 className="text-h1 text-primary">{t('receiving.title')}</h1>
        <button onClick={() => setView('create')} className="btn-brand btn text-small">
          + {t('receiving.new')}
        </button>
      </div>

      {sessions.length === 0 ? (
        <EmptyState message={t('receiving.empty')} icon="📥" />
      ) : (
        <div className="card overflow-hidden">
          <table className="min-w-full">
            <thead>
              <tr className="border-b border-line">
                {['receiving.col.ref','receiving.col.location','receiving.col.units','receiving.col.pieces','receiving.col.status','receiving.col.date'].map(k => (
                  <th key={k} className="tbl-header">{t(k)}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {sessions.map(s => (
                <tr key={s.id} className="tbl-row cursor-pointer" onClick={() => openSession(s.id)}>
                  <td className="tbl-cell font-medium text-brand">
                    {s.reference ?? <span className="text-muted">{t('common.na')}</span>}
                  </td>
                  <td className="tbl-cell text-muted">{s.location_name ?? '—'}</td>
                  <td className="tbl-cell text-primary">{s.line_units}</td>
                  <td className="tbl-cell text-primary">{s.piece_count}</td>
                  <td className="tbl-cell">
                    <SessionStatusBadge status={s.status} />
                  </td>
                  <td className="tbl-cell text-muted text-small">
                    {new Date(s.created_at).toLocaleDateString()}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}

// ── Create Session Form ───────────────────────────────────────────────────────

function CreateSessionForm({ locations, onCreated, onCancel }: {
  locations: Location[]; onCreated: (id: string) => void; onCancel: () => void
}) {
  const { t } = useTranslation()
  const [locationId, setLocationId] = useState(locations[0]?.id ?? '')
  const [reference, setReference]   = useState('')
  const [supplier, setSupplier]     = useState('')
  const [note, setNote]             = useState('')
  const [saving, setSaving]         = useState(false)
  const [error, setError]           = useState<string | null>(null)

  async function submit(e: React.FormEvent) {
    e.preventDefault()
    setSaving(true); setError(null)
    try {
      const res = await api<{ sessionId: string }>('/receiving/sessions', {
        method: 'POST',
        body: JSON.stringify({ locationId: locationId || null, reference: reference || null, supplierName: supplier || null, note: note || null }),
      })
      onCreated(res.sessionId)
    } catch (e: unknown) { setError((e as Error).message) }
    finally { setSaving(false) }
  }

  return (
    <div className="max-w-lg space-y-4">
      <h1 className="text-h1 text-primary">{t('receiving.newTitle')}</h1>
      {error && <p className="text-small text-danger">{error}</p>}
      <form onSubmit={submit} className="card p-5 space-y-4">
        <div>
          <label className="block text-small text-muted mb-1.5">{t('receiving.location')}</label>
          {locations.length > 0 ? (
            <select value={locationId} onChange={e => setLocationId(e.target.value)} className="input w-full">
              {locations.map(l => <option key={l.id} value={l.id}>{l.name}</option>)}
            </select>
          ) : (
            <input value={locationId} onChange={e => setLocationId(e.target.value)}
              placeholder="Location UUID" className="input w-full" />
          )}
        </div>
        <FormField label={t('receiving.reference')} value={reference} onChange={setReference} placeholder="PO-123" />
        <FormField label={t('receiving.supplier')}  value={supplier}  onChange={setSupplier}  placeholder="Supplier name" />
        <FormField label={t('receiving.note')}      value={note}      onChange={setNote}      placeholder="Optional note" />
        <div className="flex gap-3 pt-1">
          <button type="submit" disabled={saving} className="btn-brand btn text-small disabled:opacity-50">
            {saving ? t('common.loading') : t('receiving.create')}
          </button>
          <button type="button" onClick={onCancel} className="btn-outline btn text-small">
            {t('common.cancel')}
          </button>
        </div>
      </form>
    </div>
  )
}

// ── Session View ──────────────────────────────────────────────────────────────

function SessionView({ session, onRefresh, onBack }: {
  session: SessionDetail; onRefresh: () => void; onBack: () => void
}) {
  const { t } = useTranslation()
  const [query, setQuery]               = useState('')
  const [variants, setVariants]         = useState<VariantMatch[]>([])
  const [selectedVariant, setSelectedVariant] = useState<VariantMatch | null>(null)
  const [qty, setQty]                   = useState(1)
  const [addError, setAddError]         = useState<string | null>(null)
  const [finalizing, setFinalizing]     = useState(false)
  const [printError, setPrintError]     = useState<string | null>(null)
  const searchTimer = useRef<ReturnType<typeof setTimeout> | null>(null)
  const isOpen = session.status === 'open'

  function doSearch(q: string) {
    setQuery(q)
    if (searchTimer.current) clearTimeout(searchTimer.current)
    if (q.length < 2) { setVariants([]); return }
    searchTimer.current = setTimeout(async () => {
      try { setVariants(await api<VariantMatch[]>(`/receiving/variants/search?q=${encodeURIComponent(q)}`)) }
      catch { setVariants([]) }
    }, 250)
  }

  async function addLine() {
    if (!selectedVariant) return
    setAddError(null)
    try {
      await api(`/receiving/sessions/${session.id}/lines`, {
        method: 'POST', body: JSON.stringify({ variantId: selectedVariant.id, quantity: qty }),
      })
      setSelectedVariant(null); setQuery(''); setVariants([]); setQty(1); onRefresh()
    } catch (e: unknown) { setAddError((e as Error).message) }
  }

  async function removeLine(lineId: string) {
    try { await api(`/receiving/sessions/${session.id}/lines/${lineId}`, { method: 'DELETE' }); onRefresh() }
    catch (e: unknown) { setAddError((e as Error).message) }
  }

  async function finalize() {
    if (!confirm(t('receiving.finalizeConfirm'))) return
    setFinalizing(true)
    try {
      const res = await api<{ piecesCreated: number }>(`/receiving/sessions/${session.id}/finalize`, { method: 'POST' })
      alert(t('receiving.finalizeSuccess', { count: res.piecesCreated }))
      onRefresh()
    } catch (e: unknown) { setAddError((e as Error).message) }
    finally { setFinalizing(false) }
  }

  async function printLabels() {
    setPrintError(null)
    try {
      const res = await fetch(`${BASE}/receiving/sessions/${session.id}/labels`, {
        headers: authHeaders(),
      })
      if (!res.ok) throw new Error(res.statusText)
      window.open(URL.createObjectURL(await res.blob()), '_blank')
    } catch (e: unknown) { setPrintError((e as Error).message) }
  }

  async function reprintLabels() {
    setPrintError(null)
    try {
      const res = await fetch(`${BASE}/receiving/sessions/${session.id}/reprint`, {
        method: 'POST', headers: authHeaders(), body: JSON.stringify({ note: 'manual reprint' }),
      })
      if (!res.ok) throw new Error(res.statusText)
      window.open(URL.createObjectURL(await res.blob()), '_blank')
    } catch (e: unknown) { setPrintError((e as Error).message) }
  }

  return (
    <div className="space-y-4">
      <button onClick={onBack} className="text-small text-brand hover:text-brand-hover transition-colors">
        ← {t('receiving.back')}
      </button>

      <div className="flex items-start justify-between">
        <div>
          <h1 className="text-h1 text-primary">
            {session.reference ?? t('receiving.untitled')}
          </h1>
          <p className="text-small text-muted mt-0.5 flex items-center gap-2">
            {session.location_name} · <SessionStatusBadge status={session.status} />
          </p>
        </div>
        <div className="flex gap-2">
          {session.status === 'finalized' && (
            <>
              <button onClick={printLabels} className="btn-brand btn text-small">
                {t('receiving.printLabels')} ({session.piece_count})
              </button>
              <button onClick={reprintLabels} className="btn-outline btn text-small">
                {t('receiving.reprint')}
              </button>
            </>
          )}
          {isOpen && (
            <button onClick={finalize} disabled={finalizing || session.lines.length === 0}
              className="btn-brand btn text-small disabled:opacity-50">
              {finalizing ? t('common.loading') : t('receiving.finalize')}
            </button>
          )}
        </div>
      </div>

      {printError && <p className="text-small text-danger">{printError}</p>}

      {/* Add line (open only) */}
      {isOpen && (
        <div className="card p-4">
          <h2 className="text-caption text-muted uppercase tracking-widest mb-3">{t('receiving.addLine')}</h2>
          {addError && <p className="mb-2 text-small text-danger">{addError}</p>}
          <div className="flex gap-3 items-start">
            <div className="flex-1 relative">
              <input
                value={query}
                onChange={e => doSearch(e.target.value)}
                placeholder={t('receiving.searchVariant')}
                className="input w-full"
              />
              {variants.length > 0 && !selectedVariant && (
                <ul className="absolute z-10 start-0 end-0 mt-1 bg-panel border border-line rounded-lg shadow-elevated max-h-48 overflow-y-auto">
                  {variants.map(v => (
                    <li key={v.id}
                      className="px-3 py-2.5 text-body hover:bg-elevated cursor-pointer border-b border-line last:border-0"
                      onClick={() => { setSelectedVariant(v); setQuery(`${v.product_title} · ${v.title}`); setVariants([]) }}>
                      <span className="font-medium text-primary">{v.product_title}</span>
                      <span className="text-muted"> · {v.title}</span>
                      {v.sku && <span className="font-mono text-caption text-muted ms-2">{v.sku}</span>}
                    </li>
                  ))}
                </ul>
              )}
            </div>
            <input type="number" min={1} value={qty} onChange={e => setQty(Number(e.target.value))}
              className="input w-20 text-center" />
            <button onClick={addLine} disabled={!selectedVariant} className="btn-brand btn text-small disabled:opacity-50">
              {t('receiving.add')}
            </button>
          </div>
        </div>
      )}

      {/* Lines table */}
      <div className="card overflow-hidden">
        <table className="min-w-full">
          <thead>
            <tr className="border-b border-line">
              <th className="tbl-header">{t('receiving.col.product')}</th>
              <th className="tbl-header">{t('receiving.col.sku')}</th>
              <th className="tbl-header text-end">{t('receiving.col.qty')}</th>
              {isOpen && <th className="tbl-header w-8" />}
            </tr>
          </thead>
          <tbody>
            {session.lines.length === 0 ? (
              <tr>
                <td colSpan={4} className="px-4 py-8 text-center text-small text-muted">
                  {t('receiving.noLines')}
                </td>
              </tr>
            ) : session.lines.map(l => (
              <tr key={l.id} className="tbl-row">
                <td className="tbl-cell">
                  <div className="text-body font-medium text-primary">{l.product_title}</div>
                  <div className="text-small text-muted">{l.variant_title}</div>
                </td>
                <td className="tbl-cell font-mono text-small text-muted">{l.sku ?? '—'}</td>
                <td className="tbl-cell text-end font-semibold text-primary">{l.quantity}</td>
                {isOpen && (
                  <td className="tbl-cell text-end">
                    <button onClick={() => removeLine(l.id)}
                      className="text-caption text-danger hover:text-danger/70 transition-colors">✕</button>
                  </td>
                )}
              </tr>
            ))}
          </tbody>
          {session.lines.length > 0 && (
            <tfoot className="border-t border-line bg-elevated">
              <tr>
                <td colSpan={2} className="px-4 py-2 text-small text-muted">{t('receiving.total')}</td>
                <td className="px-4 py-2 text-end text-body font-bold text-primary">
                  {session.lines.reduce((s, l) => s + l.quantity, 0)}
                </td>
                {isOpen && <td />}
              </tr>
            </tfoot>
          )}
        </table>
      </div>

      {session.status === 'finalized' && (
        <p className="text-small text-muted">
          {t('receiving.piecesCreated', { count: session.piece_count })} ·{' '}
          {t('receiving.finalizedAt', { date: new Date(session.finalized_at!).toLocaleString() })}
        </p>
      )}
    </div>
  )
}

// ── Small components ──────────────────────────────────────────────────────────

function FormField({ label, value, onChange, placeholder }: {
  label: string; value: string; onChange: (v: string) => void; placeholder?: string
}) {
  return (
    <div>
      <label className="block text-small text-muted mb-1.5">{label}</label>
      <input value={value} onChange={e => onChange(e.target.value)} placeholder={placeholder}
        className="input w-full" />
    </div>
  )
}

function SessionStatusBadge({ status }: { status: string }) {
  const styles: Record<string, string> = {
    open:      'bg-warning/10 text-warning border-warning/20',
    finalized: 'bg-success/10 text-success border-success/20',
  }
  return (
    <span className={`badge border ${styles[status] ?? 'bg-muted/10 text-muted border-muted/20'}`}>
      {status}
    </span>
  )
}
