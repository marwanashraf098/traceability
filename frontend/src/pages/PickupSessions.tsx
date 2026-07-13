import { useState, useEffect, useRef, useCallback } from 'react'
import { useTranslation } from 'react-i18next'
import { EmptyState, Spinner } from '../components/ui'
import { getAccessToken, clearAccessToken } from '../auth'

const BASE = '/api/v1'
function authHeaders() {
  const t = getAccessToken()
  return { 'Content-Type': 'application/json', ...(t ? { Authorization: `Bearer ${t}` } : {}) }
}
async function api<T>(path: string, opts: RequestInit = {}): Promise<T> {
  const res = await fetch(BASE + path, { ...opts, headers: { ...authHeaders(), ...opts.headers as Record<string, string> } })
  if (res.status === 401) { clearAccessToken(); window.location.href = '/login'; throw new Error('Unauth') }
  if (!res.ok) { const txt = await res.text(); throw new Error(txt || res.statusText) }
  return res.json()
}

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
}

interface SessionDetail extends SessionSummary {
  notes: string | null
  closedByName: string | null
  closedAt: string | null
  scans: ScanEntry[]
}

interface ScanResponse {
  outcome: string
  entry: ScanEntry | null
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

type FeedbackOutcome = 'ACCEPTED' | 'DUPLICATE' | 'OTHER_SESSION' | 'NOT_FORWARD_LEG' | 'NOT_PACKED' | 'UNKNOWN_AWB' | null

function SessionView({ session: initial, onRefresh, onBack }: {
  session: SessionDetail
  onRefresh: () => void
  onBack: () => void
}) {
  const { t } = useTranslation()
  const [session, setSession] = useState<SessionDetail>(initial)
  const [feedback, setFeedback]   = useState<FeedbackOutcome>(null)
  const [feedbackTn, setFeedbackTn] = useState('')
  const [scans, setScans]           = useState<ScanEntry[]>(initial.scans)
  const [closing, setClosing]       = useState(false)
  const [showConfirm, setShowConfirm] = useState(false)
  const [closeMsg, setCloseMsg]     = useState<string | null>(null)
  const [scanInput, setScanInput]   = useState('')
  const [processing, setProcessing] = useState(false)
  const inputRef = useRef<HTMLInputElement>(null)
  const feedbackTimer = useRef<ReturnType<typeof setTimeout> | null>(null)

  const isOpen = session.sessionStatus === 'open'

  // Always refocus the input after any interaction.
  const refocus = useCallback(() => {
    setTimeout(() => inputRef.current?.focus(), 50)
  }, [])

  useEffect(() => {
    if (isOpen) refocus()
  }, [isOpen, refocus])

  function showFeedback(outcome: FeedbackOutcome, tn: string) {
    setFeedback(outcome)
    setFeedbackTn(tn)
    if (feedbackTimer.current) clearTimeout(feedbackTimer.current)
    if (outcome === 'ACCEPTED') {
      feedbackTimer.current = setTimeout(() => setFeedback(null), 1500)
    }
  }

  async function handleScan(rawValue: string) {
    const tn = rawValue.trim()
    if (!tn || processing) return
    setScanInput('')
    setProcessing(true)

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
      } else {
        // Rollback optimistic entry.
        setScans(prev => prev.filter(e => e.shipmentId !== optimisticEntry.shipmentId))
        showFeedback(res.outcome as FeedbackOutcome, tn)
      }
    } catch {
      setScans(prev => prev.filter(e => e.shipmentId !== optimisticEntry.shipmentId))
      showFeedback('UNKNOWN_AWB', tn)
    } finally {
      setProcessing(false)
      refocus()
    }
  }

  function handleKeyDown(e: React.KeyboardEvent<HTMLInputElement>) {
    if (e.key === 'Enter') {
      e.preventDefault()
      handleScan(scanInput)
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
    setClosing(true)
    try {
      const res = await api<{ shipmentsClosed: number; pieceExceptions: string[] }>(
        `/pickup-sessions/${session.id}/close`, { method: 'POST' })
      setShowConfirm(false)
      if (res.pieceExceptions.length > 0) {
        setCloseMsg(t('pickups.pieceExceptions', { count: res.pieceExceptions.length }))
      }
      // Reload session to get closed state
      const updated = await api<SessionDetail>(`/pickup-sessions/${session.id}`)
      setSession(updated)
      setScans(updated.scans)
    } catch (e: unknown) {
      setCloseMsg((e as Error).message)
    } finally {
      setClosing(false)
    }
  }

  async function printManifest() {
    window.open(`${BASE}/pickup-sessions/${session.id}/manifest`, '_blank')
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
          <p className="text-small text-muted mt-0.5 flex items-center gap-2">
            <SessionStatusBadge status={session.sessionStatus} />
            {session.openedByName && <span>{t('pickups.by', { name: session.openedByName })}</span>}
          </p>
        </div>
        {!isOpen && (
          <button onClick={printManifest} className="btn-outline btn text-small">
            {t('pickups.printManifest')}
          </button>
        )}
      </div>

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
            ref={inputRef}
            value={scanInput}
            onChange={e => setScanInput(e.target.value)}
            onKeyDown={handleKeyDown}
            onBlur={refocus}
            placeholder={t('pickups.scanPlaceholder')}
            className="input w-full text-lg py-3"
            autoComplete="off"
            autoCorrect="off"
            spellCheck={false}
          />

          {/* Feedback banner */}
          {feedback && (
            <div className={`rounded-lg px-4 py-4 text-xl font-bold text-center transition-all ${
              feedback === 'ACCEPTED'
                ? 'bg-success/15 text-success border border-success/30'
                : feedback === 'DUPLICATE'
                ? 'bg-warning/15 text-warning border border-warning/30'
                : 'bg-danger/15 text-danger border border-danger/30'
            }`}>
              {t(`pickups.scanOutcome.${feedback}`)}
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
                  <td className="tbl-cell font-mono text-caption text-muted">{scan.trackingNumber}</td>
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
            onClick={() => setShowConfirm(true)}
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
                onClick={() => { setShowConfirm(false); refocus() }}
                className="btn-outline btn text-small"
                disabled={closing}
              >
                {t('pickups.closeCancel')}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Closed session: manifest preview */}
      {!isOpen && (
        <div className="card p-4 space-y-2">
          <h2 className="text-caption text-muted uppercase tracking-widest">{t('pickups.manifestTitle')}</h2>
          <div className="text-small text-muted space-y-1">
            <p>{t('pickups.col.date')}: <span className="text-primary">{session.scheduledDate}</span></p>
            <p>{t('pickups.timeSlot')}: <span className="text-primary">{session.scheduledTimeSlot ?? '—'}</span></p>
            {session.closedByName && (
              <p>{t('pickups.by', { name: session.closedByName })}</p>
            )}
            {session.closedAt && (
              <p>{t('pickups.closedAt', { date: new Date(session.closedAt).toLocaleString() })}</p>
            )}
            <p className="font-semibold text-primary mt-2">
              {t('pickups.manifestPackages')}: {scans.length}
            </p>
          </div>
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
