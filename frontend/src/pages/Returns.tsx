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
  if (!res.ok) {
    const b = await res.json().catch(() => ({}))
    throw Object.assign(new Error(b?.message ?? `HTTP ${res.status}`), { status: res.status })
  }
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

interface SessionPiece {
  id: string
  barcode: string
  status: string
  variant_title: string
  product_title: string
  sku?: string
  processed: boolean
}

interface SessionSummary {
  sessionId: string
  waybillNumber: string
  orderId: string
  orderNumber: string
}

interface FinalizeSummary {
  processedCount: number
  unresolvedRtoCount: number
  deliveredKeptCount: number
}

// ── Piece label reprint (blob PDF, not base64) ───────────────────────────────

async function printPieceLabel(pieceId: string): Promise<void> {
  const token = localStorage.getItem('token')
  const headers: Record<string, string> = {}
  if (token) headers.Authorization = `Bearer ${token}`
  const res = await fetch(BASE + `/returns/pieces/${pieceId}/label`, { headers })
  if (res.status === 401) { localStorage.removeItem('token'); window.location.href = '/login'; return }
  if (!res.ok) {
    const b = await res.json().catch(() => ({}))
    throw Object.assign(new Error(b?.message ?? `HTTP ${res.status}`), { status: res.status })
  }
  const blob = await res.blob()
  window.open(URL.createObjectURL(blob), '_blank')
}

// ── Session tab (PRIMARY — waybill-driven) ────────────────────────────────────

function SessionTab({ onSwitchToIntake }: { onSwitchToIntake: () => void }) {
  const { t } = useTranslation()
  const waybillRef = useRef<HTMLInputElement>(null)
  const [flash, setFlash]           = useState<FlashState>('idle')
  const [loading, setLoading]       = useState(false)
  const [session, setSession]       = useState<SessionSummary | null>(null)
  const [pieces, setPieces]         = useState<SessionPiece[]>([])
  const [error, setError]           = useState<string | null>(null)
  // per-piece damage reason prompt
  const [damageTarget, setDamageTarget] = useState<string | null>(null)
  const [damageReason, setDamageReason] = useState('')
  // out-of-window nudge: pieceId whose verdict was rejected because of the window guard
  const [outOfWindowPieceId, setOutOfWindowPieceId] = useState<string | null>(null)
  // finalize result
  const [finalized, setFinalized]   = useState<FinalizeSummary | null>(null)
  // reprint: piece IDs damaged in this session; piece stays at full opacity so button is clear
  const [damagedPieceIds, setDamagedPieceIds] = useState<Set<string>>(new Set())
  const [reprintPieceId, setReprintPieceId]   = useState<string | null>(null)
  const [reprintErrors, setReprintErrors]     = useState<Record<string, string>>({})
  // damage-reason validation
  const [damageReasonError, setDamageReasonError] = useState(false)

  useEffect(() => { waybillRef.current?.focus() }, [])

  const triggerFlash = (s: 'success' | 'error') => {
    setFlash(s); setTimeout(() => setFlash('idle'), 600)
  }

  const openSession = async (waybill: string) => {
    if (!waybill.trim() || loading) return
    setLoading(true); setError(null); setSession(null); setPieces([]); setFinalized(null)
    try {
      const sess = await api<SessionSummary>('/returns/sessions', {
        method: 'POST',
        body: JSON.stringify({ waybillNumber: waybill.trim(), locationId: null }),
      })
      setSession(sess)
      const data = await api<{ pieces: SessionPiece[] }>(`/returns/sessions/${sess.sessionId}/pieces`)
      setPieces(data.pieces)
      playBeep(true); triggerFlash('success')
    } catch (e: unknown) {
      playBeep(false); triggerFlash('error')
      setError((e as Error).message || t('common.error'))
    } finally {
      setLoading(false)
    }
  }

  const recordVerdict = async (pieceId: string, verdict: 'restock' | 'damaged', reason?: string) => {
    if (!session) return
    if (verdict === 'damaged' && !reason?.trim()) { setDamageReasonError(true); return }
    setDamageReasonError(false)
    setOutOfWindowPieceId(null)
    try {
      await api(`/returns/sessions/${session.sessionId}/pieces/${pieceId}/verdict`, {
        method: 'POST',
        body: JSON.stringify({ verdict, reason: reason ?? null, locationId: null }),
      })
      playBeep(true)
      // Mark piece as processed locally for instant feedback
      setPieces(prev => prev.map(p => p.id === pieceId ? { ...p, processed: true } : p))
      setDamageTarget(null); setDamageReason(''); setDamageReasonError(false)
      if (verdict === 'damaged') setDamagedPieceIds(prev => new Set([...prev, pieceId]))
    } catch (e: unknown) {
      playBeep(false)
      const status = (e as { status?: number }).status
      if (status === 422) {
        // Out-of-window guard fired — surface the "use waybill-less intake" path
        setOutOfWindowPieceId(pieceId)
      } else {
        setError((e as Error).message || t('common.error'))
      }
    }
  }

  const finalizeSession = async () => {
    if (!session) return
    setLoading(true)
    try {
      const summary = await api<FinalizeSummary>(`/returns/sessions/${session.sessionId}/finalize`, {
        method: 'POST',
      })
      setFinalized(summary)
      playBeep(true)
    } catch (e: unknown) {
      setError((e as Error).message || t('common.error'))
    } finally {
      setLoading(false)
    }
  }

  const handleReprint = async (pieceId: string) => {
    if (reprintPieceId) return
    setReprintPieceId(pieceId)
    setReprintErrors(prev => { const n = { ...prev }; delete n[pieceId]; return n })
    try { await printPieceLabel(pieceId) }
    catch (e: unknown) { setReprintErrors(prev => ({ ...prev, [pieceId]: (e as Error).message || t('common.error') })) }
    finally { setReprintPieceId(null) }
  }

  const flashOverlay =
    flash === 'success' ? 'fixed inset-0 bg-success/20 pointer-events-none z-50 animate-flash' :
    flash === 'error'   ? 'fixed inset-0 bg-danger/20 pointer-events-none z-50 animate-flash' :
    'hidden'

  // ── Finalized state ────────────────────────────────────────────────────────
  if (finalized) {
    return (
      <div className="max-w-xl mx-auto pt-4 space-y-4" data-testid="session-finalized">
        <div className="card border-success/40 bg-success/5 p-5 space-y-3">
          <p className="text-body text-success font-medium">✓ {t('returns.session.finalized')}</p>
          <Row label={t('returns.session.processed')}><span className="text-primary font-medium">{finalized.processedCount}</span></Row>
          {finalized.unresolvedRtoCount > 0 && (
            <Row label={t('returns.session.unresolvedRto')}>
              <span className="text-warning font-medium">{finalized.unresolvedRtoCount}</span>
            </Row>
          )}
          {finalized.deliveredKeptCount > 0 && (
            <Row label={t('returns.session.deliveredKept')}>
              <span className="text-muted">{finalized.deliveredKeptCount} — {t('returns.session.keptNote')}</span>
            </Row>
          )}
        </div>
        <button onClick={() => { setSession(null); setPieces([]); setFinalized(null); setTimeout(() => waybillRef.current?.focus(), 50) }}
                className="btn-outline btn text-body w-full">
          {t('returns.session.newSession')}
        </button>
      </div>
    )
  }

  return (
    <div className="max-w-2xl mx-auto pt-4">
      <div className={flashOverlay} />

      {/* Step 1: Waybill scan */}
      {!session && (
        <div className="space-y-4">
          <p className="text-body text-muted">{t('returns.session.hint')}</p>
          <input
            ref={waybillRef}
            type="text"
            placeholder={t('returns.session.waybillPlaceholder')}
            className="input-scan w-full"
            disabled={loading}
            onKeyDown={e => { if (e.key === 'Enter') openSession((e.target as HTMLInputElement).value) }}
            autoFocus
          />
          {error && (
            <div className="card border-danger/30 bg-danger/5 p-4 text-danger text-body" data-testid="session-error">✗ {error}</div>
          )}
          {loading && <div className="flex justify-center py-6"><Spinner /></div>}

          {/* UX split: explain when to use this tab vs the waybill-less intake */}
          <div className="card border-line bg-elevated p-4 space-y-2">
            <p className="text-small text-muted font-medium">{t('returns.session.whenToUse')}</p>
            <ul className="text-small text-muted list-disc list-inside space-y-1">
              <li>{t('returns.session.useSession')}</li>
              <li>
                {t('returns.session.useIntake')}{' '}
                <button onClick={onSwitchToIntake} className="text-brand underline text-small">
                  {t('returns.session.switchToIntake')}
                </button>
              </li>
            </ul>
          </div>
        </div>
      )}

      {/* Step 2 + 3: Piece list with verdicts */}
      {session && !finalized && (
        <div className="space-y-4">
          <div className="card bg-elevated border-line p-4 flex items-center justify-between">
            <div>
              <p className="text-body text-primary font-medium">{session.waybillNumber}</p>
              <p className="text-small text-muted">{t('returns.col.order')}: {session.orderNumber}</p>
            </div>
            <button onClick={finalizeSession} disabled={loading}
                    className="btn-brand btn text-small">
              {loading ? <Spinner size={14} /> : t('returns.session.finalize')}
            </button>
          </div>

          {error && (
            <div className="card border-danger/30 bg-danger/5 p-4 text-danger text-body" data-testid="session-error">✗ {error}</div>
          )}

          {pieces.length === 0 ? (
            <EmptyState message={t('returns.session.noPieces')} icon="📦" />
          ) : (
            <div className="space-y-2" data-testid="pieces-list">
              {pieces.map(p => (
                <div key={p.id}
                     className={`card p-4 ${p.processed && !damagedPieceIds.has(p.id) ? 'opacity-60' : ''}`}>
                  <div className="flex items-start gap-4">
                    <div className="flex-1 min-w-0">
                      <p className="text-body text-primary font-medium truncate">{p.product_title}</p>
                      <p className="text-small text-muted">{p.variant_title}</p>
                      <div className="flex items-center gap-2 mt-1">
                        <span className="font-mono text-caption text-muted">{p.barcode}</span>
                        <Badge status={p.status} />
                        {/* Delivered pieces are expected; only flag RTO pieces */}
                        {p.status === 'delivered' && !p.processed && (
                          <span className="text-caption text-muted italic">
                            {t('returns.session.deliveredOptional')}
                          </span>
                        )}
                      </div>
                    </div>

                    {!p.processed ? (
                      <div className="flex gap-2 shrink-0">
                        <button onClick={() => recordVerdict(p.id, 'restock')}
                                className="btn-outline btn text-small">
                          {t('returns.pending.restock')}
                        </button>
                        <button onClick={() => setDamageTarget(p.id)}
                                className="btn-danger btn text-small">
                          {t('returns.pending.damage')}
                        </button>
                      </div>
                    ) : (
                      <span className="text-success text-small shrink-0">✓ {t('returns.session.done')}</span>
                    )}
                  </div>

                  {/* Out-of-window nudge for this specific piece */}
                  {outOfWindowPieceId === p.id && (
                    <div className="mt-3 pt-3 border-t border-warning/30 bg-warning/5 rounded-b-lg px-3 pb-3 -mx-4 -mb-4" data-testid="out-of-window-nudge">
                      <p className="text-small text-warning font-medium mb-2">
                        {t('returns.session.outOfWindow')}
                      </p>
                      <button onClick={() => { setOutOfWindowPieceId(null); onSwitchToIntake() }}
                              className="btn-outline btn text-small border-warning/40 text-warning hover:bg-warning/10"
                              data-testid="switch-to-intake">
                        {t('returns.session.useIntakeFallback')}
                      </button>
                    </div>
                  )}

                  {/* Damage reason prompt */}
                  {damageTarget === p.id && (
                    <div className="mt-3 pt-3 border-t border-line space-y-2">
                      <div className="flex gap-2">
                        <input
                          type="text"
                          value={damageReason}
                          onChange={e => { setDamageReason(e.target.value); setDamageReasonError(false) }}
                          placeholder={t('returns.pending.damageReason')}
                          className="input flex-1"
                          autoFocus
                          onKeyDown={e => { if (e.key === 'Enter') recordVerdict(p.id, 'damaged', damageReason) }}
                        />
                        <button onClick={() => recordVerdict(p.id, 'damaged', damageReason)}
                                className="btn-danger btn text-small">
                          {t('returns.pending.confirm')}
                        </button>
                        <button onClick={() => { setDamageTarget(null); setDamageReason(''); setDamageReasonError(false) }}
                                className="btn-ghost btn text-small">
                          {t('common.cancel')}
                        </button>
                      </div>
                      {damageReasonError && (
                        <p className="text-danger text-caption" data-testid="damage-reason-error">
                          {t('returns.session.damageReasonRequired')}
                        </p>
                      )}
                    </div>
                  )}

                  {/* Reprint — offered immediately after a damage verdict in this session */}
                  {damagedPieceIds.has(p.id) && p.processed && (
                    <div className="mt-2 pt-2 border-t border-line flex items-center gap-2">
                      <button
                        onClick={() => handleReprint(p.id)}
                        disabled={!!reprintPieceId}
                        className="btn-outline btn text-caption"
                        data-testid={`reprint-${p.id}`}
                      >
                        {reprintPieceId === p.id
                          ? t('returns.session.printingLabel')
                          : t('returns.session.printLabel')}
                      </button>
                      {reprintErrors[p.id] && (
                        <span className="text-caption text-danger">{reprintErrors[p.id]}</span>
                      )}
                    </div>
                  )}
                </div>
              ))}
            </div>
          )}
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

// ── Waybill-less intake tab (SECONDARY / FALLBACK) ────────────────────────────
// Use this when: no waybill, or out-of-window customer returns the session rejected.

interface IntakeResult {
  id: string; barcode?: string; status: string; variantTitle: string;
  productTitle: string; orderNumber?: string; trackingNumber?: string; isUnexpected: boolean
}

function IntakeTab() {
  const { t } = useTranslation()
  const inputRef = useRef<HTMLInputElement>(null)
  const [flash, setFlash]     = useState<FlashState>('idle')
  const [scanning, setScanning] = useState(false)
  const [result, setResult]   = useState<IntakeResult | null>(null)
  const [error, setError]     = useState<string | null>(null)

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

      {/* Context: explain when this fallback is appropriate */}
      <div className="card border-line bg-elevated p-3 mb-4 text-small text-muted">
        {t('returns.intake.fallbackNote')}
      </div>

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

// ── Pending inspection tab ────────────────────────────────────────────────────

interface PendingPiece {
  id: string; barcode: string; variant_title: string; product_title: string;
  order_number?: string; tracking_number?: string; location_name?: string
}

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

interface NeverReceivedRow {
  id: string; barcode: string; variant_title: string; product_title: string;
  order_number?: string; tracking_number?: string; returned_at?: string
}

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
      <div className="card border-warning/30 bg-warning/5 p-4">
        <p className="text-body text-warning font-medium mb-3">{t('returns.neverReceived.subtitle')}</p>
        <div className="flex items-center gap-3">
          <label className="text-small text-muted">{t('returns.neverReceived.window')}:</label>
          <input type="number" min={1} max={90} value={windowDays}
                 onChange={e => setWindowDays(Number(e.target.value))} className="input w-20" />
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
                ].map(h => <th key={h} className="tbl-header">{h}</th>)}
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

// Tab order: Session (primary, default) → Waybill-less Intake (fallback) → Pending → Never-received
type Tab = 'session' | 'intake' | 'pending' | 'never-received'

export default function Returns() {
  const { t } = useTranslation()
  const [tab, setTab] = useState<Tab>('session')

  const tabs: { key: Tab; label: string }[] = [
    { key: 'session',        label: t('returns.session.title') },
    { key: 'intake',         label: t('returns.intake.title') },
    { key: 'pending',        label: t('returns.pending.title') },
    { key: 'never-received', label: t('returns.neverReceived.title') },
  ]

  return (
    <div className="space-y-0">
      <div className="flex items-end justify-between mb-0">
        <h1 className="text-h1 text-primary mb-4">{t('returns.title')}</h1>
      </div>

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
            {/* Subtle "fallback" hint on the Intake tab so workers know not to default here */}
            {key === 'intake' && (
              <span className="ms-1.5 text-caption text-muted">{t('returns.intake.fallbackBadge')}</span>
            )}
          </button>
        ))}
      </div>

      <div className="pt-2">
        {tab === 'session'        && <SessionTab onSwitchToIntake={() => setTab('intake')} />}
        {tab === 'intake'         && <IntakeTab />}
        {tab === 'pending'        && <PendingTab />}
        {tab === 'never-received' && <NeverReceivedTab />}
      </div>
    </div>
  )
}
