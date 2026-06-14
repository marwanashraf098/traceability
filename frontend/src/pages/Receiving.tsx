import { useState, useEffect, useRef } from 'react'
import { useTranslation } from 'react-i18next'

const BASE = '/api/v1'
function authHeaders() {
  const t = localStorage.getItem('token')
  return { 'Content-Type': 'application/json', ...(t ? { Authorization: `Bearer ${t}` } : {}) }
}
async function api<T>(path: string, opts: RequestInit = {}): Promise<T> {
  const res = await fetch(BASE + path, { ...opts, headers: { ...authHeaders(), ...opts.headers as Record<string,string> } })
  if (res.status === 401) { localStorage.removeItem('token'); window.location.href = '/login'; throw new Error('Unauth') }
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
interface VariantMatch {
  id: string; title: string; sku: string | null; product_title: string
}
interface Location { id: string; name: string }

export default function Receiving() {
  const { t } = useTranslation()
  const [sessions, setSessions] = useState<Session[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [view, setView] = useState<'list' | 'create' | 'session'>('list')
  const [activeSession, setActiveSession] = useState<SessionDetail | null>(null)
  const [locations, setLocations] = useState<Location[]>([])

  useEffect(() => { loadSessions(); loadLocations() }, [])

  async function loadSessions() {
    try {
      const data = await api<Session[]>('/receiving/sessions')
      setSessions(data)
    } catch (e: unknown) { setError((e as Error).message) }
    finally { setLoading(false) }
  }

  async function loadLocations() {
    try {
      const data = await api<Location[]>('/api/v1/locations').catch(() =>
        // fallback: locations endpoint may not exist yet — use empty list
        [] as Location[])
      setLocations(data)
    } catch { setLocations([]) }
  }

  async function openSession(id: string) {
    try {
      const data = await api<SessionDetail>(`/receiving/sessions/${id}`)
      setActiveSession(data)
      setView('session')
    } catch (e: unknown) { setError((e as Error).message) }
  }

  if (loading) return <p className="text-gray-500">{t('common.loading')}</p>
  if (error)   return <p className="text-red-500">{error}</p>

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
    <div>
      <div className="flex items-center justify-between mb-6">
        <h1 className="text-2xl font-bold text-gray-900">{t('receiving.title')}</h1>
        <button
          onClick={() => setView('create')}
          className="bg-indigo-600 text-white px-4 py-2 rounded-md text-sm font-medium hover:bg-indigo-700"
        >
          {t('receiving.new')}
        </button>
      </div>

      {sessions.length === 0 ? (
        <p className="text-gray-500">{t('receiving.empty')}</p>
      ) : (
        <div className="bg-white shadow rounded-lg overflow-hidden">
          <table className="min-w-full divide-y divide-gray-200">
            <thead className="bg-gray-50">
              <tr>
                {['receiving.col.ref','receiving.col.location','receiving.col.units','receiving.col.pieces','receiving.col.status','receiving.col.date'].map(k => (
                  <th key={k} className="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                    {t(k)}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {sessions.map(s => (
                <tr key={s.id} className="hover:bg-gray-50 cursor-pointer" onClick={() => openSession(s.id)}>
                  <td className="px-4 py-3 text-sm font-medium text-indigo-600">
                    {s.reference || <span className="text-gray-400">—</span>}
                  </td>
                  <td className="px-4 py-3 text-sm text-gray-700">{s.location_name || '—'}</td>
                  <td className="px-4 py-3 text-sm text-gray-700">{s.line_units}</td>
                  <td className="px-4 py-3 text-sm text-gray-700">{s.piece_count}</td>
                  <td className="px-4 py-3">
                    <StatusBadge status={s.status} />
                  </td>
                  <td className="px-4 py-3 text-sm text-gray-500">
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

// ── Create Session Form ────────────────────────────────────────────────────────

function CreateSessionForm({ locations, onCreated, onCancel }: {
  locations: Location[]
  onCreated: (id: string) => void
  onCancel: () => void
}) {
  const { t } = useTranslation()
  const [locationId, setLocationId] = useState(locations[0]?.id ?? '')
  const [reference, setReference] = useState('')
  const [supplier, setSupplier] = useState('')
  const [note, setNote] = useState('')
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)

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
    <div className="max-w-lg">
      <h1 className="text-2xl font-bold text-gray-900 mb-6">{t('receiving.newTitle')}</h1>
      {error && <p className="mb-4 text-sm text-red-600">{error}</p>}
      <form onSubmit={submit} className="bg-white shadow rounded-lg p-6 space-y-4">
        <div>
          <label className="block text-sm font-medium text-gray-700 mb-1">{t('receiving.location')}</label>
          {locations.length > 0 ? (
            <select value={locationId} onChange={e => setLocationId(e.target.value)}
              className="w-full border border-gray-300 rounded-md px-3 py-2 text-sm">
              {locations.map(l => <option key={l.id} value={l.id}>{l.name}</option>)}
            </select>
          ) : (
            <input value={locationId} onChange={e => setLocationId(e.target.value)}
              placeholder="Location UUID" className="w-full border border-gray-300 rounded-md px-3 py-2 text-sm" />
          )}
        </div>
        <Field label={t('receiving.reference')} value={reference} onChange={setReference} placeholder="PO-123" />
        <Field label={t('receiving.supplier')} value={supplier} onChange={setSupplier} placeholder="Supplier name" />
        <Field label={t('receiving.note')} value={note} onChange={setNote} placeholder="Optional note" />
        <div className="flex gap-3 pt-2">
          <button type="submit" disabled={saving}
            className="bg-indigo-600 text-white px-4 py-2 rounded-md text-sm font-medium hover:bg-indigo-700 disabled:opacity-50">
            {saving ? t('common.loading') : t('receiving.create')}
          </button>
          <button type="button" onClick={onCancel}
            className="bg-white border border-gray-300 text-gray-700 px-4 py-2 rounded-md text-sm hover:bg-gray-50">
            {t('common.cancel')}
          </button>
        </div>
      </form>
    </div>
  )
}

// ── Session View ──────────────────────────────────────────────────────────────

function SessionView({ session, onRefresh, onBack }: {
  session: SessionDetail
  onRefresh: () => void
  onBack: () => void
}) {
  const { t } = useTranslation()
  const [query, setQuery] = useState('')
  const [variants, setVariants] = useState<VariantMatch[]>([])
  const [selectedVariant, setSelectedVariant] = useState<VariantMatch | null>(null)
  const [qty, setQty] = useState(1)
  const [addError, setAddError] = useState<string | null>(null)
  const [finalizing, setFinalizing] = useState(false)
  const [printError, setPrintError] = useState<string | null>(null)
  const searchTimer = useRef<ReturnType<typeof setTimeout> | null>(null)

  const isOpen = session.status === 'open'

  function doSearch(q: string) {
    setQuery(q)
    if (searchTimer.current) clearTimeout(searchTimer.current)
    if (q.length < 2) { setVariants([]); return }
    searchTimer.current = setTimeout(async () => {
      try {
        const res = await api<VariantMatch[]>(`/receiving/variants/search?q=${encodeURIComponent(q)}`)
        setVariants(res)
      } catch { setVariants([]) }
    }, 250)
  }

  async function addLine() {
    if (!selectedVariant) return
    setAddError(null)
    try {
      await api(`/receiving/sessions/${session.id}/lines`, {
        method: 'POST',
        body: JSON.stringify({ variantId: selectedVariant.id, quantity: qty }),
      })
      setSelectedVariant(null); setQuery(''); setVariants([]); setQty(1)
      onRefresh()
    } catch (e: unknown) { setAddError((e as Error).message) }
  }

  async function removeLine(lineId: string) {
    try {
      await api(`/receiving/sessions/${session.id}/lines/${lineId}`, { method: 'DELETE' })
      onRefresh()
    } catch (e: unknown) { setAddError((e as Error).message) }
  }

  async function finalize() {
    if (!confirm(t('receiving.finalizeConfirm'))) return
    setFinalizing(true)
    try {
      const res = await api<{ piecesCreated: number }>(
        `/receiving/sessions/${session.id}/finalize`, { method: 'POST' })
      alert(t('receiving.finalizeSuccess', { count: res.piecesCreated }))
      onRefresh()
    } catch (e: unknown) { setAddError((e as Error).message) }
    finally { setFinalizing(false) }
  }

  function printLabels() {
    setPrintError(null)
    // Open PDF in new tab — browser handles print dialog
    window.open(`${BASE}/receiving/sessions/${session.id}/labels`, '_blank')
  }

  async function reprintLabels() {
    setPrintError(null)
    try {
      const res = await fetch(`${BASE}/receiving/sessions/${session.id}/reprint`, {
        method: 'POST',
        headers: authHeaders(),
        body: JSON.stringify({ note: 'manual reprint' }),
      })
      if (!res.ok) throw new Error(res.statusText)
      const blob = await res.blob()
      const url = URL.createObjectURL(blob)
      window.open(url, '_blank')
    } catch (e: unknown) { setPrintError((e as Error).message) }
  }

  return (
    <div>
      <button onClick={onBack} className="text-sm text-indigo-600 hover:underline mb-4 block">
        ← {t('receiving.back')}
      </button>

      <div className="flex items-start justify-between mb-6">
        <div>
          <h1 className="text-2xl font-bold text-gray-900">
            {session.reference || t('receiving.untitled')}
          </h1>
          <p className="text-sm text-gray-500 mt-1">
            {session.location_name} · <StatusBadge status={session.status} />
          </p>
        </div>
        <div className="flex gap-2">
          {session.status === 'finalized' && (
            <>
              <button onClick={printLabels}
                className="bg-green-600 text-white px-4 py-2 rounded text-sm font-medium hover:bg-green-700">
                {t('receiving.printLabels')} ({session.piece_count})
              </button>
              <button onClick={reprintLabels}
                className="bg-gray-100 text-gray-700 border border-gray-300 px-3 py-2 rounded text-sm hover:bg-gray-200">
                {t('receiving.reprint')}
              </button>
            </>
          )}
          {isOpen && (
            <button onClick={finalize} disabled={finalizing || session.lines.length === 0}
              className="bg-indigo-600 text-white px-4 py-2 rounded text-sm font-medium hover:bg-indigo-700 disabled:opacity-50">
              {finalizing ? t('common.loading') : t('receiving.finalize')}
            </button>
          )}
        </div>
      </div>

      {printError && <p className="mb-4 text-sm text-red-600">{printError}</p>}

      {/* Add line (only when open) */}
      {isOpen && (
        <div className="bg-white shadow rounded-lg p-4 mb-6">
          <h2 className="text-sm font-semibold text-gray-700 mb-3">{t('receiving.addLine')}</h2>
          {addError && <p className="mb-2 text-sm text-red-600">{addError}</p>}
          <div className="flex gap-3 items-start">
            <div className="flex-1 relative">
              <input
                value={query}
                onChange={e => doSearch(e.target.value)}
                placeholder={t('receiving.searchVariant')}
                className="w-full border border-gray-300 rounded px-3 py-2 text-sm"
              />
              {variants.length > 0 && !selectedVariant && (
                <ul className="absolute z-10 left-0 right-0 mt-1 bg-white border border-gray-200 rounded shadow-lg max-h-48 overflow-y-auto">
                  {variants.map(v => (
                    <li key={v.id}
                      className="px-3 py-2 text-sm hover:bg-indigo-50 cursor-pointer"
                      onClick={() => { setSelectedVariant(v); setQuery(`${v.product_title} · ${v.title}`); setVariants([]) }}>
                      <span className="font-medium">{v.product_title}</span>
                      <span className="text-gray-500"> · {v.title}</span>
                      {v.sku && <span className="text-xs text-gray-400 ml-2">{v.sku}</span>}
                    </li>
                  ))}
                </ul>
              )}
            </div>
            <input type="number" min={1} value={qty} onChange={e => setQty(Number(e.target.value))}
              className="w-20 border border-gray-300 rounded px-3 py-2 text-sm text-center" />
            <button onClick={addLine} disabled={!selectedVariant}
              className="bg-indigo-600 text-white px-4 py-2 rounded text-sm font-medium hover:bg-indigo-700 disabled:opacity-50">
              {t('receiving.add')}
            </button>
          </div>
        </div>
      )}

      {/* Lines table */}
      <div className="bg-white shadow rounded-lg overflow-hidden">
        <table className="min-w-full divide-y divide-gray-200">
          <thead className="bg-gray-50">
            <tr>
              <th className="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">{t('receiving.col.product')}</th>
              <th className="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">{t('receiving.col.sku')}</th>
              <th className="px-4 py-3 text-right text-xs font-medium text-gray-500 uppercase">{t('receiving.col.qty')}</th>
              {isOpen && <th className="px-4 py-3" />}
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-100">
            {session.lines.length === 0 ? (
              <tr><td colSpan={4} className="px-4 py-6 text-center text-sm text-gray-400">{t('receiving.noLines')}</td></tr>
            ) : session.lines.map(l => (
              <tr key={l.id}>
                <td className="px-4 py-3 text-sm">
                  <div className="font-medium text-gray-900">{l.product_title}</div>
                  <div className="text-gray-500 text-xs">{l.variant_title}</div>
                </td>
                <td className="px-4 py-3 text-sm text-gray-500">{l.sku || '—'}</td>
                <td className="px-4 py-3 text-sm text-gray-900 text-right font-medium">{l.quantity}</td>
                {isOpen && (
                  <td className="px-4 py-3 text-right">
                    <button onClick={() => removeLine(l.id)}
                      className="text-xs text-red-500 hover:text-red-700">✕</button>
                  </td>
                )}
              </tr>
            ))}
          </tbody>
          {session.lines.length > 0 && (
            <tfoot className="bg-gray-50">
              <tr>
                <td colSpan={2} className="px-4 py-2 text-sm font-medium text-gray-700">{t('receiving.total')}</td>
                <td className="px-4 py-2 text-sm font-bold text-gray-900 text-right">
                  {session.lines.reduce((s, l) => s + l.quantity, 0)}
                </td>
                {isOpen && <td />}
              </tr>
            </tfoot>
          )}
        </table>
      </div>

      {session.status === 'finalized' && (
        <p className="mt-4 text-sm text-gray-500">
          {t('receiving.piecesCreated', { count: session.piece_count })} ·{' '}
          {t('receiving.finalizedAt', { date: new Date(session.finalized_at!).toLocaleString() })}
        </p>
      )}
    </div>
  )
}

// ── Small components ──────────────────────────────────────────────────────────

function Field({ label, value, onChange, placeholder }: {
  label: string; value: string; onChange: (v: string) => void; placeholder?: string
}) {
  return (
    <div>
      <label className="block text-sm font-medium text-gray-700 mb-1">{label}</label>
      <input value={value} onChange={e => onChange(e.target.value)} placeholder={placeholder}
        className="w-full border border-gray-300 rounded-md px-3 py-2 text-sm" />
    </div>
  )
}

function StatusBadge({ status }: { status: string }) {
  const colors: Record<string, string> = {
    open:      'bg-yellow-100 text-yellow-800',
    finalized: 'bg-green-100  text-green-800',
  }
  return (
    <span className={`inline-flex items-center px-2 py-0.5 rounded text-xs font-medium ${colors[status] ?? 'bg-gray-100 text-gray-600'}`}>
      {status}
    </span>
  )
}
