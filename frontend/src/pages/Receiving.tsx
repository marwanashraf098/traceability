import { useState, useEffect } from 'react'
import { useTranslation } from 'react-i18next'
import { Alert, Badge, Button, EmptyState, Input, Modal, TableSkeleton, useToast } from '../components/ui'
import ProductSelectionGrid from './receiving/ProductSelectionGrid'

import { getAccessToken, clearAccessToken } from '../auth'
import { getRoleFromToken } from '../api'

const BASE = '/api/v1'
function authHeaders() {
  const t = getAccessToken()
  return { 'Content-Type': 'application/json', ...(t ? { Authorization: `Bearer ${t}` } : {}) }
}
// Exported — ProductSelectionGrid reuses this exact fetch wrapper for its line
// mutations, so every Receiving call (session/lines/finalize) shares identical
// 401/error-handling behavior regardless of which component fires the request.
export async function api<T>(path: string, opts: RequestInit = {}): Promise<T> {
  const res = await fetch(BASE + path, { ...opts, headers: { ...authHeaders(), ...opts.headers as Record<string,string> } })
  if (res.status === 401) { clearAccessToken(); window.location.href = '/login'; throw new Error('Unauth') }
  if (!res.ok) { const txt = await res.text(); throw new Error(txt || res.statusText) }
  // PUT/DELETE on lines return 204 with NO body (ReceivingController.updateLine/
  // deleteLine are @ResponseStatus(NO_CONTENT) void methods) — calling .json() on
  // an empty body throws "Unexpected end of JSON input". Same guard as api.ts's
  // request().
  if (res.status === 204 || res.headers.get('content-length') === '0') return null as T
  return res.json()
}

interface Session {
  id: string; status: string; reference: string | null; supplier_name: string | null
  location_name: string | null; created_at: string; finalized_at: string | null
  line_units: number; piece_count: number
}
export interface Line {
  id: string; variant_id: string; variant_title: string; sku: string | null
  product_title: string; quantity: number; piece_count: number
}
interface SessionDetail extends Session { lines: Line[] }
interface Location { id: string; name: string }

// open → warning, finalized → success, unknown → neutral
function sessionTone(status: string): 'warning' | 'success' | 'neutral' {
  if (status === 'open') return 'warning'
  if (status === 'finalized') return 'success'
  return 'neutral'
}

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
        <Button size="sm" onClick={() => setView('create')}>
          + {t('receiving.new')}
        </Button>
      </div>

      {error && <Alert tone="critical" title={error} />}

      {loading ? (
        <div className="card overflow-hidden">
          <TableSkeleton rows={3} cols={6} />
        </div>
      ) : sessions.length === 0 ? (
        <EmptyState
          message={t('receiving.empty')}
          icon="📥"
          action={{ label: '+ ' + t('receiving.new'), onClick: () => setView('create') }}
        />
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
                  {/* Reference — font-mono, trace-blue like other identifiers */}
                  <td className="tbl-cell font-mono font-medium text-trace-blue">
                    {s.reference ?? <span className="text-muted font-sans font-normal">{t('common.na')}</span>}
                  </td>
                  <td className="tbl-cell text-muted">{s.location_name ?? '—'}</td>
                  <td className="tbl-cell text-primary">{s.line_units}</td>
                  <td className="tbl-cell text-primary">{s.piece_count}</td>
                  <td className="tbl-cell">
                    <Badge tone={sessionTone(s.status)} label={s.status} />
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
      {error && <Alert tone="critical" title={error} />}
      <form onSubmit={submit} className="card p-5 space-y-4">
        <div className="space-y-1.5">
          <label className="text-small text-muted">{t('receiving.location')}</label>
          {locations.length > 0 ? (
            <select value={locationId} onChange={e => setLocationId(e.target.value)} className="input w-full">
              {locations.map(l => <option key={l.id} value={l.id}>{l.name}</option>)}
            </select>
          ) : (
            <Input value={locationId} onChange={e => setLocationId(e.target.value)}
              placeholder="Location UUID" />
          )}
        </div>
        <FormField label={t('receiving.reference')} value={reference} onChange={setReference} placeholder="PO-123" />
        <FormField label={t('receiving.supplier')}  value={supplier}  onChange={setSupplier}  placeholder="Supplier name" />
        <FormField label={t('receiving.note')}      value={note}      onChange={setNote}      placeholder="Optional note" />
        <div className="flex gap-3 pt-1">
          <Button type="submit" loading={saving}>
            {t('receiving.create')}
          </Button>
          <Button type="button" variant="secondary" onClick={onCancel}>
            {t('common.cancel')}
          </Button>
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
  const { toast } = useToast()
  const [finalizing, setFinalizing]     = useState(false)
  const [finalizeError, setFinalizeError] = useState<string | null>(null)
  const [showFinalizeModal, setShowFinalizeModal] = useState(false)
  const [printError, setPrintError]     = useState<string | null>(null)
  const [printingVariant, setPrintingVariant] = useState<string | null>(null)
  const [deleting, setDeleting]         = useState(false)
  const [deleteError, setDeleteError]   = useState<string | null>(null)
  const [showDeleteModal, setShowDeleteModal] = useState(false)
  const isOpen = session.status === 'open'
  const role = getRoleFromToken()
  const canDelete = isOpen && (role === 'owner' || role === 'manager')
  const totalUnits = session.lines.reduce((s, l) => s + l.quantity, 0)

  async function deleteSession() {
    setDeleting(true); setDeleteError(null)
    try {
      await api<null>(`/receiving/sessions/${session.id}`, { method: 'DELETE' })
      toast({ tone: 'success', message: t('receiving.deleteSuccess') })
      onBack()
    } catch (e: unknown) { setDeleteError((e as Error).message) }
    finally { setDeleting(false) }
  }

  async function finalize() {
    setFinalizing(true); setFinalizeError(null)
    try {
      const res = await api<{ piecesCreated: number }>(`/receiving/sessions/${session.id}/finalize`, { method: 'POST' })
      setShowFinalizeModal(false)
      toast({ tone: 'success', message: t('receiving.piecesCreated', { count: res.piecesCreated }) })
      onRefresh()
    } catch (e: unknown) { setFinalizeError((e as Error).message) }
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

  async function printVariantLabels(variantId: string) {
    setPrintError(null)
    setPrintingVariant(variantId)
    try {
      const res = await fetch(
        `${BASE}/receiving/sessions/${session.id}/variants/${variantId}/labels`,
        { headers: authHeaders() }
      )
      if (!res.ok) throw new Error(res.statusText)
      window.open(URL.createObjectURL(await res.blob()), '_blank')
    } catch (e: unknown) { setPrintError((e as Error).message) }
    finally { setPrintingVariant(null) }
  }

  return (
    <div className="space-y-4">
      {/* Back link — matches OrderDetail pattern */}
      <button onClick={onBack} className="text-small text-trace-blue hover:text-trace-blue-hover transition-colors">
        ← {t('receiving.back')}
      </button>

      <div className="flex items-start justify-between">
        <div>
          <h1 className="text-h1 text-primary">
            {session.reference ?? t('receiving.untitled')}
          </h1>
          <p className="text-small text-muted mt-0.5 flex items-center gap-2">
            {session.location_name} · <Badge tone={sessionTone(session.status)} label={session.status} />
          </p>
        </div>
        {session.status === 'finalized' && (
          <div className="flex gap-2">
            <Button size="sm" onClick={printLabels}>
              {t('receiving.printLabels')} ({session.piece_count})
            </Button>
            <Button size="sm" variant="secondary" onClick={reprintLabels}>
              {t('receiving.reprint')}
            </Button>
          </div>
        )}
        {canDelete && (
          <Button size="sm" variant="destructive" onClick={() => setShowDeleteModal(true)}>
            {t('receiving.deleteSession')}
          </Button>
        )}
      </div>

      {printError && <Alert tone="critical" title={printError} />}
      {deleteError && <Alert tone="critical" title={deleteError} />}

      {/* Open: the product-card selection grid IS the entry mechanism —
          replaces the old type-ahead search + flat lines table entirely. */}
      {isOpen && (
        <ProductSelectionGrid
          sessionId={session.id}
          lines={session.lines}
          onRefresh={onRefresh}
          onFinalizeClick={() => setShowFinalizeModal(true)}
          finalizing={finalizing}
        />
      )}

      {/* Finalized: per-variant piece counts + label printing, unchanged. */}
      {!isOpen && (
        <div className="card overflow-hidden">
          <table className="min-w-full">
            <thead>
              <tr className="border-b border-line">
                <th className="tbl-header">{t('receiving.col.product')}</th>
                <th className="tbl-header">{t('receiving.col.sku')}</th>
                <th className="tbl-header text-end">{t('receiving.col.qty')}</th>
                <th className="tbl-header w-8" />
              </tr>
            </thead>
            <tbody>
              {session.lines.length === 0 ? (
                <tr>
                  <td colSpan={4} className="px-4 py-8 text-center text-small text-muted">
                    {t('receiving.noLines')}
                  </td>
                </tr>
              ) : (() => {
                // Track first occurrence of each variant_id — Print Barcodes button
                // appears once per unique variant so nothing double-prints.
                const firstVariantIdx = new Map<string, number>()
                session.lines.forEach((l, i) => {
                  if (!firstVariantIdx.has(l.variant_id)) firstVariantIdx.set(l.variant_id, i)
                })
                return session.lines.map((l, idx) => {
                  const isFirstOccurrence = firstVariantIdx.get(l.variant_id) === idx
                  return (
                    <tr key={l.id} className="tbl-row">
                      <td className="tbl-cell">
                        <div className="text-body font-medium text-primary">{l.product_title}</div>
                        <div className="text-small text-muted">{l.variant_title}</div>
                      </td>
                      {/* SKU — font-mono per spec */}
                      <td className="tbl-cell font-mono text-small text-muted">{l.sku ?? '—'}</td>
                      <td className="tbl-cell text-end font-semibold text-primary">{l.quantity}</td>
                      <td className="tbl-cell text-end">
                        {isFirstOccurrence && l.piece_count > 0 ? (
                          <Button size="sm"
                            loading={printingVariant === l.variant_id}
                            onClick={() => printVariantLabels(l.variant_id)}>
                            {t('receiving.printBarcodes')} ({l.piece_count})
                          </Button>
                        ) : null}
                      </td>
                    </tr>
                  )
                })
              })()}
            </tbody>
            {session.lines.length > 0 && (
              <tfoot className="border-t border-line bg-elevated">
                <tr>
                  <td colSpan={2} className="px-4 py-2 text-small text-muted">{t('receiving.total')}</td>
                  <td className="px-4 py-2 text-end text-body font-bold text-primary">
                    {session.lines.reduce((s, l) => s + l.quantity, 0)}
                  </td>
                  <td />
                </tr>
              </tfoot>
            )}
          </table>
        </div>
      )}

      {session.status === 'finalized' && (
        <p className="text-small text-muted">
          {t('receiving.piecesCreated', { count: session.piece_count })} ·{' '}
          {t('receiving.finalizedAt', { date: new Date(session.finalized_at!).toLocaleString() })}
        </p>
      )}

      {showFinalizeModal && (
        <Modal
          title={t('receiving.finalizeModalTitle', { ref: session.reference ?? session.id.slice(-8) })}
          onClose={() => { if (!finalizing) setShowFinalizeModal(false) }}
        >
          <div className="space-y-4">
            {finalizeError && <Alert tone="critical" title={finalizeError} />}
            <p className="text-body text-muted">
              {t('receiving.finalizeModalBody', {
                count: session.lines.reduce((s, l) => s + l.quantity, 0),
              })}
            </p>
            <div className="flex gap-2">
              <Button
                variant="secondary"
                className="flex-1"
                disabled={finalizing}
                onClick={() => setShowFinalizeModal(false)}
              >
                {t('common.cancel')}
              </Button>
              <Button className="flex-1" loading={finalizing} onClick={finalize}>
                {t('receiving.finalizeModalConfirm')}
              </Button>
            </div>
          </div>
        </Modal>
      )}

      {showDeleteModal && (
        <Modal
          title={t('receiving.deleteModalTitle', { ref: session.reference ?? session.id.slice(-8) })}
          onClose={() => { if (!deleting) setShowDeleteModal(false) }}
        >
          <div className="space-y-4">
            {deleteError && <Alert tone="critical" title={deleteError} />}
            <p className="text-body text-muted">
              {session.lines.length > 0
                ? t('receiving.deleteModalBodyWithLines', { lines: session.lines.length, units: totalUnits })
                : t('receiving.deleteModalBodyEmpty')}
            </p>
            <div className="flex gap-2">
              <Button
                variant="secondary"
                className="flex-1"
                disabled={deleting}
                onClick={() => setShowDeleteModal(false)}
              >
                {t('common.cancel')}
              </Button>
              <Button variant="destructive" className="flex-1" loading={deleting} onClick={deleteSession}>
                {t('receiving.deleteModalConfirm')}
              </Button>
            </div>
          </div>
        </Modal>
      )}
    </div>
  )
}

// ── Small components ──────────────────────────────────────────────────────────

function FormField({ label, value, onChange, placeholder }: {
  label: string; value: string; onChange: (v: string) => void; placeholder?: string
}) {
  return (
    <div className="space-y-1.5">
      <label className="text-small text-muted">{label}</label>
      <Input value={value} onChange={e => onChange(e.target.value)} placeholder={placeholder} />
    </div>
  )
}
