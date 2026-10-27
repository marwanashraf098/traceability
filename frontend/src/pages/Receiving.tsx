import { useState, useEffect } from 'react'
import { useTranslation } from 'react-i18next'
import { Alert, Badge, Button, EmptyState, Input, Modal, Tabs, TableSkeleton, useToast } from '../components/ui'
import ProductSelectionGrid from './receiving/ProductSelectionGrid'
import { ImportFromExcelButton, ImportPanel } from './receiving/ReceivingImport'
import { useCapabilities } from '../capabilities'
import FactoryRuns from './receiving/FactoryRuns'
import { ArrowLeft, Barcode, PackageCheck } from 'lucide-react'

import { getAccessToken } from '../auth'
import { authFetch, getRoleFromToken, getLabelSettings } from '../api'

function authHeaders() {
  const t = getAccessToken()
  return { 'Content-Type': 'application/json', ...(t ? { Authorization: `Bearer ${t}` } : {}) }
}
// Exported — ProductSelectionGrid reuses this exact fetch wrapper for its line
// mutations, so every Receiving call (session/lines/finalize) shares identical
// 401/error-handling behavior regardless of which component fires the request.
// authFetch: an expired access token is refreshed and the call retried once (a receiving session sits
// open for hours); the old helper logged the worker out on the first 401.
export async function api<T>(path: string, opts: RequestInit = {}): Promise<T> {
  const res = await authFetch(path, { ...opts, headers: { ...authHeaders(), ...opts.headers as Record<string,string> } })
  if (!res.ok) {
    // Never surface the raw response body — Spring Security's default 403 body
    // (ACCESS_DENIED + granted authorities) is not meant for display. Business
    // exceptions carry a message_en field (ApiExceptionHandler); anything else
    // falls back to a generic HTTP-status message.
    const body = await res.json().catch(() => null) as { message_en?: string } | null
    throw new Error(body?.message_en || `HTTP ${res.status}`)
  }
  // PUT/DELETE on lines return 204 with NO body (ReceivingController.updateLine/
  // deleteLine are @ResponseStatus(NO_CONTENT) void methods) — calling .json() on
  // an empty body throws "Unexpected end of JSON input". Same guard as api.ts's
  // request().
  if (res.status === 204 || res.headers.get('content-length') === '0') return null as T
  return res.json()
}

/** print_stick = today's flow (a label per piece); count_only = flexible labeling, pieces go in untagged. */
type ReceivingMode = 'print_stick' | 'count_only'
interface Session {
  id: string; status: string; reference: string | null; supplier_name: string | null
  location_name: string | null; created_at: string; finalized_at: string | null
  line_units: number; piece_count: number; mode?: ReceivingMode
}
export interface Line {
  id: string; variant_id: string; variant_title: string; sku: string | null
  product_title: string; quantity: number; piece_count: number
}
/** Count-only finished sessions: units of this session's Shopify +N by outcome. */
interface ShopifySync { applied: number; pending: number; failed: number; skipped: number }
interface SessionDetail extends Session { lines: Line[]; shopify?: ShopifySync }
interface Location { id: string; name: string; is_fulfillment?: boolean }

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
  const { flexibleLabeling } = useCapabilities()
  // Flexible labeling slice 6a: "Factory runs" lives here as a tab (no sidebar entry), only with the switch on.
  const [tab, setTab] = useState<'sessions' | 'runs'>('sessions')

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
        flexibleLabeling={flexibleLabeling}
        onCreated={(id) => { loadSessions(); openSession(id) }}
        onCancel={() => setView('list')}
      />
    )
  }

  if (view === 'session' && activeSession) {
    return (
      <SessionView
        session={activeSession}
        flexibleLabeling={flexibleLabeling}
        onRefresh={() => openSession(activeSession.id)}
        onBack={() => { loadSessions(); setView('list') }}
      />
    )
  }

  const tabs = flexibleLabeling && (
    <Tabs activeKey={tab} onChange={k => setTab(k as 'sessions' | 'runs')} tabs={[
      { key: 'sessions', label: t('labelRuns.tab.sessions') },
      { key: 'runs', label: t('labelRuns.tab.runs') },
    ]} />
  )

  if (flexibleLabeling && tab === 'runs') {
    return (
      <div className="space-y-4">
        <h1 className="text-h1 text-primary">{t('receiving.title')}</h1>
        {tabs}
        <FactoryRuns />
      </div>
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
      {tabs}

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
                    {s.mode === 'count_only' && (
                      <span className="ms-2 font-sans"><Badge tone="neutral" label={t('receiving.countOnly.badge')} /></span>
                    )}
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

const MODES: ReceivingMode[] = ['print_stick', 'count_only']

/**
 * Switch ON: two steps — "How do you want to receive this delivery?" (two cards; a click picks the mode and goes
 * straight on), then today's form with the chosen mode on top ("… · Change") and a Back link. Switch OFF: today's
 * form exactly (no chooser, no mode sent).
 */
function CreateSessionForm({ locations, flexibleLabeling, onCreated, onCancel }: {
  locations: Location[]; flexibleLabeling: boolean; onCreated: (id: string) => void; onCancel: () => void
}) {
  const { t } = useTranslation()
  const [locationId, setLocationId] = useState(locations[0]?.id ?? '')
  const [mode, setMode]             = useState<ReceivingMode>('print_stick')
  const [step, setStep]             = useState<'choose' | 'form'>(flexibleLabeling ? 'choose' : 'form')
  // Slice 8: the store's default (Settings › Labels) is pre-selected on step 1 — any card can still be clicked.
  const [defaultMode, setDefaultMode] = useState<ReceivingMode>('print_stick')
  useEffect(() => {
    if (!flexibleLabeling) return
    let cancelled = false
    getLabelSettings()
      .then(s => {
        if (cancelled || !s?.receivingDefaultMode) return
        setDefaultMode(s.receivingDefaultMode)
        setMode(s.receivingDefaultMode)
      })
      .catch(() => { /* print & stick stays pre-selected */ })
    return () => { cancelled = true }
  }, [flexibleLabeling])
  // Count only receives into the main warehouse (packing labels untagged pieces there) — server-enforced too.
  const mainWarehouse = locations.find(l => l.is_fulfillment)
  const countOnly = flexibleLabeling && mode === 'count_only'
  const effectiveLocationId = countOnly ? (mainWarehouse?.id ?? '') : locationId
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
        body: JSON.stringify({
          locationId: effectiveLocationId || null, reference: reference || null, supplierName: supplier || null, note: note || null,
          // Sent only when the chooser is shown — every other tenant's request is exactly today's.
          ...(flexibleLabeling ? { mode } : {}),
        }),
      })
      onCreated(res.sessionId)
    } catch (e: unknown) { setError((e as Error).message) }
    finally { setSaving(false) }
  }

  if (flexibleLabeling && step === 'choose') {
    return (
      <div className="max-w-3xl space-y-4" data-testid="receiving-mode-chooser">
        <button type="button" onClick={onCancel} className="text-small text-trace-blue hover:text-trace-blue-hover transition-colors">
          ← {t('receiving.back')}
        </button>
        <h1 className="text-h1 text-primary">{t('receiving.mode.title')}</h1>
        <div className="grid gap-3 sm:grid-cols-2">
          {MODES.map(m => {
            const Icon = m === 'print_stick' ? Barcode : PackageCheck
            return (
              <button
                key={m}
                type="button"
                aria-pressed={mode === m}
                data-testid={`receiving-mode-${m}`}
                onClick={() => { setMode(m); setStep('form') }}
                className={`card text-start p-5 flex flex-col gap-3 transition-colors focus-visible:border-trace-blue ${
                  mode === m ? 'border-trace-blue bg-trace-blue/[0.06]' : 'hover:border-trace-blue'}`}
              >
                <div className="flex items-start justify-between gap-2">
                  <Icon size={22} strokeWidth={1.75} className="text-trace-blue" />
                  {m === defaultMode && (
                    <span data-testid={`receiving-mode-default-${m}`}><Badge tone="info" label={t('receiving.mode.yourDefault')} /></span>
                  )}
                </div>
                <div className="space-y-1.5 flex-1">
                  <p className="text-h3 text-primary">{t(`receiving.mode.${m}.title`)}</p>
                  <p className="text-small text-muted">{t(`receiving.mode.${m}.body`)}</p>
                </div>
                <p className="text-caption text-muted border-t border-line pt-3">{t(`receiving.mode.${m}.footer`)}</p>
              </button>
            )
          })}
        </div>
      </div>
    )
  }

  return (
    <div className="max-w-lg space-y-4">
      {flexibleLabeling && (
        <button type="button" onClick={() => setStep('choose')} data-testid="receiving-mode-back"
          className="inline-flex items-center gap-1 text-small text-trace-blue hover:text-trace-blue-hover transition-colors">
          <ArrowLeft size={14} strokeWidth={2} className="rtl:rotate-180" /> {t('receiving.mode.back')}
        </button>
      )}
      <h1 className="text-h1 text-primary">{t('receiving.newTitle')}</h1>
      {error && <Alert tone="critical" title={error} />}
      <form onSubmit={submit} className="card p-5 space-y-4">
        {flexibleLabeling && (
          <p className="text-small text-primary" data-testid="receiving-mode-chosen">
            <span className="font-semibold">{t(`receiving.mode.${mode}.title`)}</span>
            {' · '}
            <button type="button" onClick={() => setStep('choose')} className="text-trace-blue hover:text-trace-blue-hover">
              {t('receiving.mode.change')}
            </button>
          </p>
        )}
        <div className="space-y-1.5">
          <label className="text-small text-muted">{t('receiving.location')}</label>
          {countOnly ? (
            <>
              <p className="input w-full bg-elevated text-primary" data-testid="count-only-location">
                {mainWarehouse?.name ?? '—'}
              </p>
              <p className="text-caption text-muted">{t('receiving.mode.mainWarehouseOnly')}</p>
            </>
          ) : locations.length > 0 ? (
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
          <Button type="submit" loading={saving} disabled={countOnly && !mainWarehouse}>
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

function SessionView({ session, flexibleLabeling, onRefresh, onBack }: {
  session: SessionDetail; flexibleLabeling: boolean; onRefresh: () => void; onBack: () => void
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
  const [importOpen, setImportOpen]     = useState(false)
  const isOpen = session.status === 'open'
  const countOnly = session.mode === 'count_only'
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
      const res = await authFetch(`/receiving/sessions/${session.id}/labels`, {
        headers: authHeaders(),
      })
      if (!res.ok) throw new Error(res.statusText)
      window.open(URL.createObjectURL(await res.blob()), '_blank')
    } catch (e: unknown) { setPrintError((e as Error).message) }
  }

  async function reprintLabels() {
    setPrintError(null)
    try {
      const res = await authFetch(`/receiving/sessions/${session.id}/reprint`, {
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
      const res = await authFetch(
        `/receiving/sessions/${session.id}/variants/${variantId}/labels`,
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
            {flexibleLabeling && (
              <span data-testid="receiving-session-mode">
                <Badge tone="neutral" label={t(countOnly ? 'receiving.countOnly.badge' : 'receiving.mode.printStickBadge')} />
              </span>
            )}
          </p>
        </div>
        {session.status === 'finalized' && !countOnly && (
          <div className="flex gap-2">
            <Button size="sm" onClick={printLabels}>
              {t('receiving.printLabels')} ({session.piece_count})
            </Button>
            <Button size="sm" variant="secondary" onClick={reprintLabels}>
              {t('receiving.reprint')}
            </Button>
          </div>
        )}
        {(isOpen || canDelete) && (
          <div className="flex flex-wrap gap-2">
            {/* Import a Shopify inventory export into this session (both modes; the server gates count only). */}
            {isOpen && <ImportFromExcelButton onClick={() => setImportOpen(true)} />}
            {canDelete && (
              <Button size="sm" variant="destructive" onClick={() => setShowDeleteModal(true)}>
                {t('receiving.deleteSession')}
              </Button>
            )}
          </div>
        )}
      </div>

      {printError && <Alert tone="critical" title={printError} />}
      {deleteError && <Alert tone="critical" title={deleteError} />}

      {/* Open: the product-card selection grid IS the entry mechanism —
          replaces the old type-ahead search + flat lines table entirely. */}
      {isOpen && importOpen && (
        <ImportPanel sessionId={session.id} onClose={() => setImportOpen(false)}
          onApplied={() => { setImportOpen(false); onRefresh() }} />
      )}

      {isOpen && (
        <ProductSelectionGrid
          sessionId={session.id}
          lines={session.lines}
          onRefresh={onRefresh}
          onFinalizeClick={() => setShowFinalizeModal(true)}
          finalizing={finalizing}
          {...(countOnly ? {
            finalizeLabel: (units: number) => t('receiving.countOnly.finish', { count: units }),
            notice: (units: number) => (
              <div className="card p-4 space-y-1.5" data-testid="count-only-notice">
                <p className="text-body text-primary">{t('receiving.countOnly.noLabels')}</p>
                <p className="text-small text-muted">{t('receiving.countOnly.shopify', { count: units })}</p>
              </div>
            ),
          } : {})}
        />
      )}

      {/* Count only, finished: what went in — no labels to print. */}
      {!isOpen && countOnly && <CountOnlyDone session={session} />}

      {/* Finalized: per-variant piece counts + label printing, unchanged. */}
      {!isOpen && !countOnly && (
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

      {session.status === 'finalized' && !countOnly && (
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
              {countOnly
                ? t('receiving.countOnly.confirmBody', { count: totalUnits })
                : t('receiving.finalizeModalBody', {
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
                {countOnly ? t('receiving.countOnly.finish', { count: totalUnits }) : t('receiving.finalizeModalConfirm')}
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

// ── Count only: finished ──────────────────────────────────────────────────────

/** Per-variant +N (lines merged by variant), the Shopify outcome, and that no labels were printed. */
function CountOnlyDone({ session }: { session: SessionDetail }) {
  const { t } = useTranslation()
  const perVariant = new Map<string, { line: Line; qty: number }>()
  for (const l of session.lines) {
    const v = perVariant.get(l.variant_id)
    perVariant.set(l.variant_id, { line: l, qty: (v?.qty ?? 0) + l.quantity })
  }
  const total = session.piece_count
  const s = session.shopify
  const shopify = !s || s.pending > 0 ? t('receiving.countOnly.shopifyUpdating', { count: total })
    : s.failed > 0 ? t('receiving.countOnly.shopifyFailed')
    : s.applied > 0 ? t('receiving.countOnly.shopifyUpdated', { count: s.applied })
    : t('receiving.countOnly.shopifyNone')
  return (
    <div className="card p-5 space-y-4" data-testid="count-only-done">
      <div>
        <h2 className="text-h2 text-primary">{t('receiving.countOnly.doneTitle', { count: total })}</h2>
        <p className="text-small text-muted mt-0.5">{t('receiving.countOnly.doneSub')}</p>
      </div>
      <ul className="divide-y divide-line border-y border-line">
        {[...perVariant.values()].map(({ line, qty }) => (
          <li key={line.variant_id} className="flex items-center justify-between gap-3 py-2.5">
            <div className="min-w-0">
              <p className="text-body font-medium text-primary truncate"><bdi>{line.product_title}</bdi></p>
              <p className="text-small text-muted truncate"><bdi>{line.variant_title}</bdi>
                {line.sku && <> · <span className="font-mono" dir="ltr">{line.sku}</span></>}</p>
            </div>
            <span className="text-body font-semibold text-success-text tabular-nums" dir="ltr">+{qty}</span>
          </li>
        ))}
      </ul>
      <dl className="grid grid-cols-[auto,1fr] gap-x-6 gap-y-1.5 text-small">
        <dt className="text-muted">{t('receiving.countOnly.shopifyLabel')}</dt>
        <dd className="text-primary" data-testid="count-only-shopify">{shopify}</dd>
        <dt className="text-muted">{t('receiving.countOnly.labelsLabel')}</dt>
        <dd className="text-primary">{t('receiving.countOnly.labelsNone')}</dd>
      </dl>
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
