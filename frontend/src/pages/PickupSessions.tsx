import { useState, useEffect, useRef, useCallback } from 'react'
import { useTranslation } from 'react-i18next'
import { EmptyState, Spinner } from '../components/ui'
import { getPickupManifest, getRoleFromToken, PickupManifest, request } from '../api'
import { useCapabilities } from '../capabilities'
import ScanHelperChips from '../components/scanHelpers/ScanHelperChips'
import { useScanner, ScanMeta, ScanOutcome } from '../hooks/useScanner'

// Every call goes through the shared request(): an expired access token is refreshed and the call
// retried once. The page's old helper cleared the token on the first 401 and sent a worker to /login —
// a pickup session sits open for hours, so no worker's Close ever reached the server (Nuvira, 2026-10).
const api = request

// ── API types ─────────────────────────────────────────────────────────────────

interface SessionSummary {
  id: string
  sessionStatus: string
  scheduledDate: string
  scheduledTimeSlot: string | null
  scannedCount: number
  openedByName: string | null
  createdAt: string
}

interface ScanEntry {
  shipmentId: string
  trackingNumber: string
  orderNumber: string | null
  codAmount: number | null
  scannedAt: string | null
  scannedByName: string | null
  /** G3: a generic (non-Bosta courier) parcel's carrier ('' when it names none); absent for Bosta. */
  carrierName?: string
}

interface SessionDetail extends SessionSummary {
  notes: string | null
  closedByName: string | null
  closedAt: string | null
  scans: ScanEntry[]
  /** G3: the courier of a session of other couriers' parcels; absent for Bosta sessions. */
  carrier?: string
}

interface ScanResponse {
  outcome: string
  entry: ScanEntry | null
  /** G3: on WRONG_CARRIER — the courier this session is for ("Bosta" or a carrier name). */
  carrier?: string
}

// ── Main component ────────────────────────────────────────────────────────────

type View = 'list' | 'create' | 'session'

export default function PickupSessions() {
  const { t } = useTranslation()
  const [sessions, setSessions] = useState<SessionSummary[]>([])
  const [loading, setLoading]   = useState(true)
  const [error, setError]       = useState<string | null>(null)
  const [view, setView]         = useState<View>('list')
  const [activeSession, setActiveSession] = useState<SessionDetail | null>(null)

  const loadSessions = useCallback(async () => {
    try { setSessions(await api<SessionSummary[]>('/pickup-sessions')) }
    catch (e: unknown) { setError((e as Error).message) }
    finally { setLoading(false) }
  }, [])

  useEffect(() => { loadSessions() }, [loadSessions])

  async function openSession(id: string) {
    const detail = await api<SessionDetail>(`/pickup-sessions/${id}`)
    setActiveSession(detail)
    setView('session')
  }

  if (loading) return <div className="flex justify-center pt-16"><Spinner size={28} /></div>
  if (error)   return <p className="text-small text-danger">{error}</p>

  if (view === 'create') {
    return (
      <CreateSessionForm
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

  // ── Session list ────────────────────────────────────────────────────────────
  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h1 className="text-h1 text-primary">{t('pickups.title')}</h1>
        <button onClick={() => setView('create')} className="btn-brand btn text-small">
          + {t('pickups.new')}
        </button>
      </div>

      {sessions.length === 0 ? (
        <EmptyState message={t('pickups.empty')} icon="📦" />
      ) : (
        <div className="card overflow-hidden">
          <table className="min-w-full">
            <thead>
              <tr className="border-b border-line">
                {(['date','timeSlot','scanned','openedBy','status'] as const).map(col => (
                  <th key={col} className="tbl-header">{t(`pickups.col.${col}`)}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {sessions.map(s => (
                <tr key={s.id} className="tbl-row cursor-pointer" onClick={() => openSession(s.id)}>
                  <td className="tbl-cell font-medium text-brand">
                    {s.scheduledDate ?? <span className="text-muted">—</span>}
                  </td>
                  <td className="tbl-cell text-muted text-small">{s.scheduledTimeSlot ?? '—'}</td>
                  <td className="tbl-cell text-primary font-semibold">{s.scannedCount}</td>
                  <td className="tbl-cell text-muted text-small">{s.openedByName ?? '—'}</td>
                  <td className="tbl-cell">
                    <SessionStatusBadge status={s.sessionStatus} />
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

// ── Create session form ───────────────────────────────────────────────────────

function CreateSessionForm({ onCreated, onCancel }: {
  onCreated: (id: string) => void
  onCancel: () => void
}) {
  const { t } = useTranslation()
  const today = new Date().toISOString().split('T')[0]
  const [date, setDate]   = useState(today)
  const [slot, setSlot]   = useState('10:00 to 13:00')
  const [notes, setNotes] = useState('')
  const [saving, setSaving] = useState(false)
  const [error, setError]   = useState<string | null>(null)

  async function submit(e: React.FormEvent) {
    e.preventDefault()
    setSaving(true); setError(null)
    try {
      const res = await api<{ sessionId: string }>('/pickup-sessions', {
        method: 'POST',
        body: JSON.stringify({ scheduledDate: date, scheduledTimeSlot: slot, notes: notes || null }),
      })
      onCreated(res.sessionId)
    } catch (e: unknown) { setError((e as Error).message) }
    finally { setSaving(false) }
  }

  return (
    <div className="max-w-lg space-y-4">
      <h1 className="text-h1 text-primary">{t('pickups.newTitle')}</h1>
      {error && <p className="text-small text-danger">{error}</p>}
      <form onSubmit={submit} className="card p-5 space-y-4">
        <div>
          <label className="block text-small text-muted mb-1.5">{t('pickups.date')}</label>
          <input type="date" value={date} min={today}
            onChange={e => setDate(e.target.value)} className="input w-full" required />
        </div>
        <div>
          <label className="block text-small text-muted mb-1.5">{t('pickups.timeSlot')}</label>
          <select value={slot} onChange={e => setSlot(e.target.value)} className="input w-full">
            <option value="10:00 to 13:00">{t('pickups.slot1013')}</option>
            <option value="13:00 to 16:00">{t('pickups.slot1316')}</option>
          </select>
        </div>
        <div>
          <label className="block text-small text-muted mb-1.5">{t('pickups.notes')}</label>
          <input value={notes} onChange={e => setNotes(e.target.value)}
            placeholder={t('pickups.notes')} className="input w-full" />
        </div>
        <div className="flex gap-3 pt-1">
          <button type="submit" disabled={saving} className="btn-brand btn text-small disabled:opacity-50">
            {saving ? <Spinner size={14} /> : t('pickups.create')}
          </button>
          <button type="button" onClick={onCancel} className="btn-outline btn text-small">
            {t('common.cancel')}
          </button>
        </div>
      </form>
    </div>
  )
}

// ── Session view (scan screen + closed manifest) ──────────────────────────────

type FeedbackOutcome = 'ACCEPTED' | 'DUPLICATE' | 'OTHER_SESSION' | 'NOT_FORWARD_LEG' | 'LEG_ENDED' | 'NOT_PACKED' | 'UNKNOWN_AWB'
  | 'WRONG_CARRIER' | 'AMBIGUOUS' | null

/** A scanned waybill whose leg was cancelled / deleted in Bosta before close — not handed over. */
interface LeftOut { shipmentId: string; trackingNumber: string; orderNumber: string | null }

function SessionView({ session: initial, onRefresh, onBack }: {
  session: SessionDetail
  onRefresh: () => void
  onBack: () => void
}) {
  const { t } = useTranslation()
  const [session, setSession] = useState<SessionDetail>(initial)
  const [feedback, setFeedback]   = useState<FeedbackOutcome>(null)
  const [feedbackTn, setFeedbackTn] = useState('')
  // G3: WRONG_CARRIER names the session's courier ("This parcel goes with Aramex").
  const [feedbackCarrier, setFeedbackCarrier] = useState<string | undefined>(undefined)
  const [scans, setScans]           = useState<ScanEntry[]>(initial.scans)
  const [closing, setClosing]       = useState(false)
  const [showConfirm, setShowConfirm] = useState(false)
  const [closeMsg, setCloseMsg]     = useState<string | null>(null)
  const [leftOut, setLeftOut]       = useState<LeftOut[]>([])
  const [closeError, setCloseError] = useState(false)
  const feedbackTimer = useRef<ReturnType<typeof setTimeout> | null>(null)

  const isOpen = session.sessionStatus === 'open'
  const { scanHelpers, genericCourier } = useCapabilities()
  // G3: one courier per session — the first accepted parcel's courier names the session (a generic carrier;
  // undefined for a Bosta session, which shows nothing new).
  const firstReal = [...scans].reverse().find(s => !s.shipmentId.startsWith('optimistic-'))
  const sessionCarrier = session.carrier ?? firstReal?.carrierName

  // Handover manifest: fetched through the authenticated request, shown in the manifest card,
  // then the page is printed (print styles below keep only that card). COD is money — owner /
  // manager only, never on a worker's screen or printout.
  const [manifest, setManifest] = useState<PickupManifest | null>(null)
  const [manifestLoading, setManifestLoading] = useState(false)
  const [manifestError, setManifestError] = useState(false)
  const [printPending, setPrintPending] = useState(false)
  const role = getRoleFromToken()
  const showMoney = role === 'owner' || role === 'manager'

  // Print only after the fetched rows are on the page.
  useEffect(() => {
    if (!printPending || !manifest) return
    setPrintPending(false)
    window.print()
  }, [printPending, manifest])

  // R1: scans go through useScanner — queued while one is in flight and sent one at a time, in
  // order; the input is uncontrolled and cleared on Enter, so a fast next scan never appends to
  // the previous code. onScan is the old handleScan: optimistic row, rollback on failure,
  // outcome feedback. Focus: useScanner's own (after every scan, any click), plus this screen's
  // refocus on blur / after removing a scan, as before.
  const onScan = useCallback(async (tn: string, _meta?: ScanMeta): Promise<ScanOutcome> => {
    // Optimistic: add a placeholder immediately so worker sees instant response.
    const optimisticEntry: ScanEntry = {
      shipmentId: 'optimistic-' + tn,
      trackingNumber: tn,
      orderNumber: null,
      codAmount: null,
      scannedAt: null,
      scannedByName: null,
    }
    setScans(prev => [optimisticEntry, ...prev])

    try {
      const res = await api<ScanResponse>(`/pickup-sessions/${session.id}/scans`, {
        method: 'POST',
        body: JSON.stringify({ trackingNumber: tn }),
      })
      if (res.outcome === 'ACCEPTED' && res.entry) {
        // Replace optimistic entry with real data.
        setScans(prev => [res.entry!, ...prev.filter(e => e.shipmentId !== optimisticEntry.shipmentId)])
        showFeedback('ACCEPTED', tn)
        return { success: true }
      }
      // Rollback optimistic entry.
      setScans(prev => prev.filter(e => e.shipmentId !== optimisticEntry.shipmentId))
      showFeedback(res.outcome as FeedbackOutcome, tn, res.carrier)
      return { success: false }
    } catch {
      setScans(prev => prev.filter(e => e.shipmentId !== optimisticEntry.shipmentId))
      showFeedback('UNKNOWN_AWB', tn)
      return { success: false }
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [session.id])

  // The close confirm owns focus while it's open.
  const scanner = useScanner({ onScan, focusPaused: showConfirm })
  const handleScan = scanner.handleScan

  // Always refocus the input after any interaction.
  const refocus = useCallback(() => {
    setTimeout(() => scanner.inputRef.current?.focus(), 50)
  }, [scanner.inputRef])

  useEffect(() => {
    if (isOpen) refocus()
  }, [isOpen, refocus])

  function showFeedback(outcome: FeedbackOutcome, tn: string, carrier?: string) {
    setFeedback(outcome)
    setFeedbackTn(tn)
    setFeedbackCarrier(carrier)
    if (feedbackTimer.current) clearTimeout(feedbackTimer.current)
    if (outcome === 'ACCEPTED') {
      feedbackTimer.current = setTimeout(() => setFeedback(null), 1500)
    }
  }

  function handleKeyDown(e: React.KeyboardEvent<HTMLInputElement>) {
    if (e.key === 'Enter') {
      e.preventDefault()
      handleScan(e.currentTarget.value)
    }
  }

  async function removeScan(shipmentId: string) {
    setScans(prev => prev.filter(e => e.shipmentId !== shipmentId))
    try {
      await api(`/pickup-sessions/${session.id}/scans/${shipmentId}`, { method: 'DELETE' })
    } catch {
      // Rollback: reload
      onRefresh()
    }
    refocus()
  }

  async function confirmClose() {
    scanner.clearQueue()                       // nothing waiting may land on a closing session
    setClosing(true)
    setCloseError(false)
    try {
      const res = await api<{ shipmentsClosed: number; pieceExceptions: string[]; leftOut?: LeftOut[] }>(
        `/pickup-sessions/${session.id}/close`, { method: 'POST' })
      setShowConfirm(false)
      setLeftOut(res.leftOut ?? [])
      if (res.pieceExceptions.length > 0) {
        setCloseMsg(t('pickups.pieceExceptions', { count: res.pieceExceptions.length }))
      }
      // Reload session to get closed state
      const updated = await api<SessionDetail>(`/pickup-sessions/${session.id}`)
      setSession(updated)
      setScans(updated.scans)
    } catch {
      // Shown inside the confirm dialog — the dialog stays open over the page, so a message on the
      // page itself was hidden behind it.
      setCloseError(true)
    } finally {
      setClosing(false)
    }
  }

  async function printManifest() {
    if (manifestLoading) return
    setManifestLoading(true)
    setManifestError(false)
    try {
      const m = await getPickupManifest(session.id)
      if (!m || !Array.isArray(m.shipments)) throw new Error('empty manifest')
      setManifest(m)
      setPrintPending(true)
    } catch {
      setManifestError(true)
    } finally {
      setManifestLoading(false)
    }
  }

  return (
    <div className="space-y-4">
      <button onClick={onBack} className="text-small text-brand hover:text-brand-hover transition-colors">
        {t('pickups.back')}
      </button>

      <div className="flex items-start justify-between">
        <div>
          <h1 className="text-h1 text-primary">
            {session.scheduledDate}
            {session.scheduledTimeSlot && (
              <span className="text-muted font-normal ms-2 text-body">· {session.scheduledTimeSlot}</span>
            )}
          </h1>
          <p className="text-small text-muted mt-0.5 flex flex-wrap items-center gap-2">
            <SessionStatusBadge status={session.sessionStatus} />
            {session.openedByName && <span>{t('pickups.by', { name: session.openedByName })}</span>}
            {sessionCarrier != null && (
              <span className="badge border bg-info/10 text-info border-info/20" data-testid="pickup-carrier">
                {t('pickups.courier', { carrier: sessionCarrier || t('pickups.anotherCourier') })}
              </span>
            )}
          </p>
        </div>
        {!isOpen && (
          <button
            onClick={printManifest}
            disabled={manifestLoading}
            aria-busy={manifestLoading}
            className="btn-outline btn text-small disabled:opacity-60"
          >
            {manifestLoading ? t('pickups.printManifestLoading') : t('pickups.printManifest')}
          </button>
        )}
      </div>

      {manifestError && (
        <div role="alert" className="rounded-lg border border-danger/30 bg-danger/10 px-4 py-3 text-small text-danger">
          {t('pickups.manifestError')}
        </div>
      )}

      {leftOut.length > 0 && (
        <div role="alert" data-testid="pickup-left-out"
             className="rounded-lg border border-danger/30 bg-danger/10 px-4 py-3 text-small text-danger space-y-1">
          <p className="font-semibold">{t('pickups.leftOut.title', { count: leftOut.length })}</p>
          <ul className="space-y-0.5">
            {leftOut.map(l => (
              <li key={l.shipmentId}>
                <span className="font-mono">{l.trackingNumber}</span>
                {l.orderNumber && <span> · {t('pickups.orderNumber')} {l.orderNumber}</span>}
              </li>
            ))}
          </ul>
          <p>{t('pickups.leftOut.body')}</p>
        </div>
      )}

      {closeMsg && (
        <div className="rounded-lg border border-warning/30 bg-warning/10 px-4 py-3 text-small text-warning">
          {closeMsg}
        </div>
      )}

      {/* Scan input — open sessions only */}
      {isOpen && (
        <div className="card p-4 space-y-3">
          <div className="flex items-center justify-between">
            <h2 className="text-caption text-muted uppercase tracking-widest">{t('pickups.scanTitle')}</h2>
            <span className="text-2xl font-bold text-primary tabular-nums">
              {t('pickups.scanCount', { count: scans.filter(s => !s.shipmentId.startsWith('optimistic-')).length })}
            </span>
          </div>

          <input
            ref={scanner.inputRef}
            onKeyDown={handleKeyDown}
            onBlur={refocus}
            placeholder={t('pickups.scanPlaceholder')}
            className="input w-full text-lg py-3"
            autoComplete="off"
            autoCorrect="off"
            spellCheck={false}
          />

          {/* Review mode S7: click-to-scan chips (demo / review tenant only) — through handleScan. */}
          {scanHelpers && (
            <ScanHelperChips context="pickup" disabled={scanner.scanning} onScan={handleScan}
              refreshKey={scans.filter(s => !s.shipmentId.startsWith('optimistic-')).length} />
          )}

          {/* Feedback banner */}
          {feedback && (
            <div data-testid="pickup-scan-feedback" className={`rounded-lg px-4 py-4 text-xl font-bold text-center transition-all ${
              feedback === 'ACCEPTED'
                ? 'bg-success/15 text-success border border-success/30'
                : feedback === 'DUPLICATE'
                ? 'bg-warning/15 text-warning border border-warning/30'
                : 'bg-danger/15 text-danger border border-danger/30'
            }`}>
              {feedback === 'WRONG_CARRIER'
                ? t('pickups.scanOutcome.WRONG_CARRIER', { carrier: feedbackCarrier || t('pickups.anotherCourier') })
                : feedback === 'LEG_ENDED' && genericCourier
                ? t('pickups.scanOutcome.LEG_ENDED_NEUTRAL')
                : t(`pickups.scanOutcome.${feedback}`)}
              {feedbackTn && <div className="text-sm font-mono mt-1 opacity-70">{feedbackTn}</div>}
            </div>
          )}
        </div>
      )}

      {/* Scanned list */}
      <div className="card overflow-hidden">
        <div className="px-4 py-3 border-b border-line flex items-center justify-between">
          <h2 className="text-caption text-muted uppercase tracking-widest">
            {t('pickups.scanTitle')}
          </h2>
          <span className="text-body font-semibold text-primary">
            {t('pickups.scanCount', { count: scans.length })}
          </span>
        </div>
        {scans.length === 0 ? (
          <div className="px-4 py-8 text-center text-small text-muted">{t('pickups.noScans')}</div>
        ) : (
          <table className="min-w-full">
            <thead>
              <tr className="border-b border-line">
                <th className="tbl-header">AWB</th>
                <th className="tbl-header">{t('pickups.orderNumber')}</th>
                <th className="tbl-header">{t('pickups.cod')}</th>
                <th className="tbl-header">{t('pickups.col.scanned')}</th>
                {isOpen && <th className="tbl-header w-8" />}
              </tr>
            </thead>
            <tbody>
              {scans.map(scan => (
                <tr key={scan.shipmentId} className={`tbl-row ${scan.shipmentId.startsWith('optimistic-') ? 'opacity-50' : ''}`}>
                  <td className="tbl-cell font-mono text-caption text-muted" dir="ltr">{scan.trackingNumber}</td>
                  <td className="tbl-cell text-primary">{scan.orderNumber ?? '—'}</td>
                  <td className="tbl-cell text-warning">
                    {scan.codAmount != null ? `${scan.codAmount.toLocaleString()} EGP` : <span className="text-muted">—</span>}
                  </td>
                  <td className="tbl-cell text-muted text-small">
                    {scan.scannedAt ? new Date(scan.scannedAt).toLocaleTimeString() : '…'}
                    {scan.scannedByName && <span className="ms-1">· {scan.scannedByName}</span>}
                  </td>
                  {isOpen && (
                    <td className="tbl-cell text-end">
                      {!scan.shipmentId.startsWith('optimistic-') && (
                        <button
                          onClick={() => removeScan(scan.shipmentId)}
                          className="text-caption text-danger hover:text-danger/70 transition-colors"
                        >
                          ✕
                        </button>
                      )}
                    </td>
                  )}
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      {/* Close button */}
      {isOpen && scans.length > 0 && (
        <div className="flex justify-end">
          <button
            onClick={() => { scanner.clearQueue(); setShowConfirm(true) }}
            className="btn-brand btn text-small"
            disabled={closing}
          >
            {t('pickups.closeSession')}
          </button>
        </div>
      )}

      {/* Close confirmation modal */}
      {showConfirm && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4">
          <div className="bg-panel rounded-xl border border-line shadow-elevated max-w-md w-full p-6 space-y-4">
            <h2 className="text-h2 text-primary">{t('pickups.closeConfirmTitle')}</h2>
            <p className="text-body text-muted">
              {t('pickups.closeConfirmBody', {
                count: scans.filter(s => !s.shipmentId.startsWith('optimistic-')).length
              })}
            </p>
            {closeError && (
              <p role="alert" data-testid="pickup-close-error"
                 className="rounded-lg border border-danger/30 bg-danger/10 px-3 py-2 text-small text-danger">
                {t('pickups.closeFailed')}
              </p>
            )}
            <div className="flex gap-3 pt-2">
              <button
                onClick={confirmClose}
                disabled={closing}
                className="btn-brand btn text-small flex-1 disabled:opacity-50"
              >
                {closing
                  ? <Spinner size={14} />
                  : t('pickups.closeConfirm', {
                      count: scans.filter(s => !s.shipmentId.startsWith('optimistic-')).length
                    })}
              </button>
              <button
                onClick={() => { setShowConfirm(false); setCloseError(false); refocus() }}
                className="btn-outline btn text-small"
                disabled={closing}
              >
                {t('pickups.closeCancel')}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Closed session: manifest preview — and, once fetched, the printable shipment list.
          Print-only rules: A4, light theme (the app is dark-only), everything except this card
          hidden. :has() keeps the card's ancestors (Layout's main column) and hides their other
          children, so the sidebar / top bar / scan list never reach the paper. */}
      {!isOpen && (
        <style>{`
          @media print {
            @page { size: A4; margin: 12mm; }
            body *:not(:has(.pickup-manifest)):not(.pickup-manifest):not(.pickup-manifest *) { display: none !important; }
            *:has(.pickup-manifest) {
              display: block !important; position: static !important; overflow: visible !important;
              height: auto !important; min-height: 0 !important; max-width: none !important; width: auto !important;
              margin: 0 !important; padding: 0 !important; transform: none !important;
              background: #ffffff !important; border: 0 !important; box-shadow: none !important;
            }
            html, body { background: #ffffff !important; color: #111111 !important; }
            .pickup-manifest { border: 0 !important; box-shadow: none !important; padding: 0 !important; background: #ffffff !important; }
            .pickup-manifest .text-primary { color: #111111 !important; }
            .pickup-manifest .text-muted { color: #444444 !important; }
            .pickup-manifest .border-line { border-color: #bbbbbb !important; }
            .pickup-manifest table { width: 100% !important; border-collapse: collapse; font-size: 10pt; }
            .pickup-manifest thead { display: table-header-group; }
            .pickup-manifest tr { break-inside: avoid; }
            .pickup-manifest th, .pickup-manifest td {
              color: #111111 !important; border-bottom: 1px solid #bbbbbb !important; padding: 1.6mm 2mm !important;
            }
          }
        `}</style>
      )}
      {!isOpen && (
        <div className="card p-4 space-y-2 pickup-manifest" data-testid="pickup-manifest">
          <h2 className="text-caption text-muted uppercase tracking-widest">{t('pickups.manifestTitle')}</h2>
          <div className="text-small text-muted space-y-1">
            <p>{t('pickups.col.date')}: <span className="text-primary">{session.scheduledDate}</span></p>
            <p>{t('pickups.timeSlot')}: <span className="text-primary">{session.scheduledTimeSlot ?? '—'}</span></p>
            {(manifest?.carrier ?? sessionCarrier) != null && (
              <p data-testid="pickup-manifest-carrier">
                {t('pickups.courier', { carrier: (manifest?.carrier ?? sessionCarrier) || t('pickups.anotherCourier') })}
              </p>
            )}
            {session.closedByName && (
              <p>{t('pickups.by', { name: session.closedByName })}</p>
            )}
            {session.closedAt && (
              <p>{t('pickups.closedAt', { date: new Date(session.closedAt).toLocaleString() })}</p>
            )}
            <p className="font-semibold text-primary mt-2">
              {t('pickups.manifestPackages')}: {manifest ? manifest.shipments.length : scans.length}
            </p>
          </div>
          {manifest && manifest.shipments.length > 0 && (
            <table className="min-w-full mt-3 border-t border-line">
              <thead>
                <tr className="border-b border-line">
                  <th className="tbl-header w-10">#</th>
                  <th className="tbl-header">AWB</th>
                  <th className="tbl-header">{t('pickups.orderNumber')}</th>
                  {showMoney && <th className="tbl-header">{t('pickups.cod')}</th>}
                  <th className="tbl-header">{t('pickups.col.scanned')}</th>
                </tr>
              </thead>
              <tbody>
                {manifest.shipments.map((row, i) => (
                  <tr key={row.shipmentId} className="border-b border-line">
                    <td className="tbl-cell text-muted tabular-nums">{i + 1}</td>
                    <td className="tbl-cell font-mono text-primary">{row.trackingNumber}</td>
                    <td className="tbl-cell text-primary">{row.orderNumber ?? '—'}</td>
                    {showMoney && (
                      <td className="tbl-cell text-primary tabular-nums">
                        {row.codAmount != null ? `${row.codAmount.toLocaleString()} EGP` : '—'}
                      </td>
                    )}
                    <td className="tbl-cell text-muted text-small">
                      {row.scannedAt ? new Date(row.scannedAt).toLocaleTimeString() : '—'}
                      {row.scannedByName && <span className="ms-1">· {row.scannedByName}</span>}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
          <p className="text-small text-muted border-t border-line pt-3 mt-3">
            {t('pickups.manifestSignature')}: ___________________________
          </p>
        </div>
      )}
    </div>
  )
}

// ── Small components ──────────────────────────────────────────────────────────

function SessionStatusBadge({ status }: { status: string }) {
  const { t } = useTranslation()
  const styles: Record<string, string> = {
    open:   'bg-warning/10 text-warning border-warning/20',
    closed: 'bg-success/10 text-success border-success/20',
  }
  return (
    <span className={`badge border ${styles[status] ?? 'bg-muted/10 text-muted border-muted/20'}`}>
      {t(`pickups.status.${status}`, { defaultValue: status })}
    </span>
  )
}
