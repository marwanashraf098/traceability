import { useEffect, useRef, useState, useCallback } from 'react'
import { useTranslation } from 'react-i18next'
import {
  X, ScanLine, Printer, RotateCcw, AlertTriangle, ClipboardCheck, WifiOff,
  Inbox, ArrowRightCircle, XCircle, ChevronLeft, ChevronRight,
} from 'lucide-react'
import {
  Badge, Button, EmptyState, Skeleton, StatCard, Modal, Alert, Spinner,
} from '../components/ui'
import Layout from '../components/Layout'
import { getAccessToken, clearAccessToken } from '../auth'
import { getRoleFromToken } from '../api'

const BASE = '/api/v1'

function authHeaders(): Record<string, string> {
  const t = getAccessToken()
  return t ? { Authorization: `Bearer ${t}` } : {}
}

interface ApiErrorBody {
  code?: string
  message_en?: string
  message_ar?: string
  details?: Record<string, unknown>
}

class ApiError extends Error {
  status: number
  body: ApiErrorBody
  constructor(status: number, body: ApiErrorBody, message: string) {
    super(message)
    this.status = status
    this.body = body
  }
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
  if (res.status === 401) { clearAccessToken(); window.location.href = '/login'; throw new Error('401') }
  if (!res.ok) {
    const body = await res.json().catch(() => ({})) as ApiErrorBody
    throw new ApiError(res.status, body, body?.message_en ?? `HTTP ${res.status}`)
  }
  if (res.status === 204 || res.headers.get('content-length') === '0') return undefined as T
  return res.json()
}

// ── Piece label reprint (blob PDF, not base64) — reused for both open-session
// items and AWB-expected (not-yet-scanned) rows. ─────────────────────────────

async function printSessionPieceLabel(sessionId: string, pieceId: string): Promise<void> {
  const token = getAccessToken()
  const headers: Record<string, string> = {}
  if (token) headers.Authorization = `Bearer ${token}`
  const res = await fetch(BASE + `/returns/sessions/${sessionId}/pieces/${pieceId}/reprint-label`, {
    method: 'POST', headers,
  })
  if (res.status === 401) { clearAccessToken(); window.location.href = '/login'; return }
  if (!res.ok) {
    const b = await res.json().catch(() => ({}))
    throw Object.assign(new Error(b?.message_en ?? `HTTP ${res.status}`), { status: res.status })
  }
  const blob = await res.blob()
  window.open(URL.createObjectURL(blob), '_blank')
}

// SAFETY-CRITICAL — do not modify
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

// ── Types ──────────────────────────────────────────────────────────────────────

interface LandingSession {
  id: string
  status: 'open' | 'closed' | 'abandoned'
  opened_by: string | null
  opened_at: string
  closed_at: string | null
  piece_count: number
  restocked_count: number
  damaged_count: number
  mismatch_count: number
}

interface UnassignedPiece {
  pieceId: string
  barcode: string
  productTitle: string
  sku: string | null
  lastEventAt: string
}

interface Analytics {
  totalReturns: number
  restockedCount: number
  damagedCount: number
  mismatchCount: number
  expectedNotScannedCount: number
  unassignedPendingCount: number
  unassignedPending: UnassignedPiece[]
}

interface SessionItem {
  id: string
  piece_id: string
  barcode: string
  status: string
  variant_title: string
  product_title: string
  sku: string | null
  disposition: 'pending' | 'restocked' | 'damaged' | 'mismatch'
  unexpected: boolean
  damage_reason: string | null
}

interface ExpectedPiece {
  id: string
  barcode: string
  status: string
  variant_title: string
  product_title: string
  sku: string | null
}

interface SessionDetail {
  id: string
  status: 'open' | 'closed' | 'abandoned'
  opened_by: string | null
  opened_at: string
  items: SessionItem[]
  expectedPieces: ExpectedPiece[]
}

interface CloseSummary {
  sessionId: string
  pieceCount: number
  restockedCount: number
  damagedCount: number
  mismatchCount: number
  shipmentCount: number
  closedAt: string
}

// ── Root ──────────────────────────────────────────────────────────────────────

type View = { type: 'landing' } | { type: 'session'; sessionId: string }

export default function Returns() {
  const [view, setView] = useState<View>({ type: 'landing' })

  if (view.type === 'session') {
    // NOT Layout-wrapped — full-screen immersive scan loop, per the /fulfill precedent.
    // key={sessionId} forces a full remount on "Start new session" (same convention
    // as Fulfill's key={view.orderId} on PickScreen) so no per-session transient
    // state (scan flash, damage-reason draft, close summary, ...) leaks across.
    return (
      <OpenSessionScreen
        key={view.sessionId}
        sessionId={view.sessionId}
        onExit={() => setView({ type: 'landing' })}
        onStartNew={id => setView({ type: 'session', sessionId: id })}
      />
    )
  }

  return (
    <Layout>
      <LandingScreen onOpenSession={id => setView({ type: 'session', sessionId: id })} />
    </Layout>
  )
}

// ── Landing (in-shell list) ──────────────────────────────────────────────────

const PAGE_SIZE = 10

function LandingScreen({ onOpenSession }: { onOpenSession: (sessionId: string) => void }) {
  const { t } = useTranslation()
  const isWorker = getRoleFromToken() === 'worker'
  const [page, setPage] = useState(0)
  const [sessions, setSessions] = useState<LandingSession[]>([])
  const [total, setTotal] = useState(0)
  const [analytics, setAnalytics] = useState<Analytics | null>(null)
  const [loading, setLoading] = useState(!isWorker)
  const [error, setError] = useState<string | null>(null)
  const [opening, setOpening] = useState(false)

  const load = useCallback(async () => {
    // Workers only get the "Open return session" intake action below — the
    // sessions list + analytics band are owner/manager-only endpoints
    // (blueprint.md §11), so a worker never calls them.
    if (isWorker) return
    setLoading(true); setError(null)
    try {
      const [sessResp, analyticsResp] = await Promise.all([
        api<{ items: LandingSession[]; total: number }>(`/returns/sessions?page=${page}&size=${PAGE_SIZE}`),
        api<Analytics>('/returns/analytics'),
      ])
      setSessions(sessResp.items)
      setTotal(sessResp.total)
      setAnalytics(analyticsResp)
    } catch (e: unknown) {
      setError((e as Error).message || t('common.error'))
    } finally {
      setLoading(false)
    }
  }, [page, t, isWorker])

  useEffect(() => { load() }, [load])

  const openSession = async () => {
    if (opening) return
    setOpening(true)
    try {
      const result = await api<{ sessionId: string }>('/returns/sessions', {
        method: 'POST', body: JSON.stringify({ note: null }),
      })
      onOpenSession(result.sessionId)
    } catch (e: unknown) {
      if (e instanceof ApiError && e.body.code === 'SESSION_ALREADY_OPEN' && e.body.details?.sessionId) {
        onOpenSession(e.body.details.sessionId as string)
      } else {
        setError((e as Error).message || t('common.error'))
      }
    } finally {
      setOpening(false)
    }
  }

  if (isWorker) {
    // Reduced worker landing — just the intake action. No sessions list, no
    // analytics band (owner/manager-only endpoints, never called for a worker).
    // "Open return session" resumes an already-open session server-side via
    // the SESSION_ALREADY_OPEN catch above, so a worker never needs the list.
    return (
      <div className="space-y-4" data-testid="returns-landing">
        <div className="flex items-center justify-between">
          <h1 className="text-h1 text-primary">{t('returns.title')}</h1>
          <button
            className="btn-brand"
            disabled={opening}
            onClick={openSession}
            data-testid="open-session-button"
          >
            {opening ? <Spinner size={16} /> : <ScanLine size={16} strokeWidth={2} />}
            {t('returns.landing.openSession')}
          </button>
        </div>
        {error && <Alert tone="critical" title={error} />}
      </div>
    )
  }

  const openSessionRow = sessions.find(s => s.status === 'open') ?? (page === 0 ? sessions[0] : undefined)
  const hasOpenSession = openSessionRow?.status === 'open'

  const fmtDate = (iso: string | null) => iso
    ? new Date(iso).toLocaleString(undefined, { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' })
    : '—'

  return (
    <div className="space-y-4" data-testid="returns-landing">
      <div className="flex items-center justify-between">
        <h1 className="text-h1 text-primary">{t('returns.title')}</h1>
        {/* raw <button> — Button doesn't spread data-testid */}
        <button
          className="btn-brand"
          disabled={opening}
          onClick={() => hasOpenSession ? onOpenSession(openSessionRow!.id) : openSession()}
          data-testid="open-session-button"
        >
          {opening
            ? <Spinner size={16} />
            : hasOpenSession ? <ArrowRightCircle size={16} strokeWidth={2} /> : <ScanLine size={16} strokeWidth={2} />}
          {hasOpenSession
            ? t('returns.landing.resumeSession', { id: shortId(openSessionRow!.id) })
            : t('returns.landing.openSession')}
        </button>
      </div>

      {hasOpenSession && (
        <div className="card border-line bg-elevated p-3 text-small text-muted" data-testid="already-open-note">
          {t('returns.landing.alreadyOpenNote', { count: openSessionRow!.piece_count })}
        </div>
      )}

      {loading ? (
        <div className="space-y-4">
          <Skeleton className="h-14 rounded-xl" />
          <Skeleton className="h-14 rounded-xl w-3/5" />
          <Skeleton className="h-14 rounded-xl w-2/5" />
        </div>
      ) : error ? (
        <div className="card p-10 flex flex-col items-center gap-3 text-center" data-testid="landing-error">
          <WifiOff size={32} strokeWidth={1.75} className="text-muted" />
          <p className="text-body font-semibold text-primary">{t('returns.landing.errorTitle')}</p>
          <p className="text-small text-muted">{t('returns.landing.errorSubtitle')}</p>
          <Button variant="secondary" size="sm" onClick={load}>{t('returns.landing.retry')}</Button>
        </div>
      ) : total === 0 ? (
        <EmptyState message={t('returns.landing.emptyTitle')} icon="↩️" />
      ) : (
        <>
          {analytics && (
            <div className="grid grid-cols-2 sm:grid-cols-5 gap-3" data-testid="analytics-band">
              <StatCard label={t('returns.landing.stats.total')} value={analytics.totalReturns} />
              <StatCard label={t('returns.landing.stats.restocked')} value={analytics.restockedCount} />
              <StatCard label={t('returns.landing.stats.damaged')} value={analytics.damagedCount} />
              <StatCard label={t('returns.landing.stats.mismatch')} value={analytics.mismatchCount} />
              <StatCard label={t('returns.landing.stats.expectedNotScanned')} value={analytics.expectedNotScannedCount} />
            </div>
          )}

          {analytics && analytics.unassignedPendingCount > 0 && (
            <div className="card border-warning/30 bg-warning/5 p-4 flex items-center gap-4" data-testid="unassigned-callout">
              <Inbox size={20} strokeWidth={1.75} className="text-warning shrink-0" />
              <div className="flex-1 min-w-0">
                <p className="text-small font-semibold text-warning">
                  {t('returns.landing.unassignedTitle', { count: analytics.unassignedPendingCount })}
                </p>
                <p className="text-caption text-muted">{t('returns.landing.unassignedSubtitle')}</p>
              </div>
              <Button
                variant="secondary"
                size="sm"
                onClick={() => hasOpenSession ? onOpenSession(openSessionRow!.id) : openSession()}
              >
                {t('returns.landing.unassignedReview')}
              </Button>
            </div>
          )}

          <div className="card overflow-hidden" data-testid="sessions-table">
            <div className="px-4 py-3 border-b border-line text-small font-medium text-muted">
              {t('returns.landing.tableTitle', { count: total })}
            </div>
            <table className="w-full text-small">
              <thead>
                <tr className="text-caption text-muted text-start border-b border-line">
                  <th className="px-4 py-2 text-start font-medium">{t('returns.landing.col.session')}</th>
                  <th className="px-4 py-2 text-start font-medium">{t('returns.landing.col.openedBy')}</th>
                  <th className="px-4 py-2 text-start font-medium">{t('returns.landing.col.opened')}</th>
                  <th className="px-4 py-2 text-start font-medium">{t('returns.landing.col.status')}</th>
                  <th className="px-4 py-2 text-start font-medium">{t('returns.landing.col.pieces')}</th>
                  <th className="px-4 py-2 text-start font-medium">{t('returns.landing.col.dispositions')}</th>
                </tr>
              </thead>
              <tbody>
                {sessions.map(s => (
                  <tr
                    key={s.id}
                    className="border-b border-line last:border-0 hover:bg-elevated cursor-pointer"
                    onClick={() => onOpenSession(s.id)}
                  >
                    <td className="px-4 py-3 font-mono font-medium text-primary">{shortId(s.id)}</td>
                    <td className="px-4 py-3 text-muted">{s.opened_by ? t('returns.landing.operator') : '—'}</td>
                    <td className="px-4 py-3 text-muted">{fmtDate(s.opened_at)}</td>
                    <td className="px-4 py-3">
                      <Badge
                        tone={s.status === 'open' ? 'info' : s.status === 'abandoned' ? 'neutral' : 'success'}
                        label={t(`returns.landing.status.${s.status}`)}
                      />
                    </td>
                    <td className="px-4 py-3 font-mono text-primary">{s.piece_count}</td>
                    <td className="px-4 py-3 text-muted">
                      {s.status === 'open'
                        ? t('returns.landing.inProgress')
                        : dispositionSummary(s, t)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            <div className="flex items-center justify-between px-4 py-3">
              <span className="text-caption text-muted">
                {t('returns.landing.showing', {
                  from: page * PAGE_SIZE + 1,
                  to: Math.min((page + 1) * PAGE_SIZE, total),
                  total,
                })}
              </span>
              <div className="flex gap-1.5">
                <Button variant="tertiary" size="sm" disabled={page === 0} onClick={() => setPage(p => p - 1)} iconStart={ChevronLeft}>
                  {t('returns.landing.prev')}
                </Button>
                <Button
                  variant="tertiary" size="sm"
                  disabled={(page + 1) * PAGE_SIZE >= total}
                  onClick={() => setPage(p => p + 1)}
                  iconEnd={ChevronRight}
                >
                  {t('returns.landing.next')}
                </Button>
              </div>
            </div>
          </div>
        </>
      )}
    </div>
  )
}

function shortId(id: string): string {
  return 'RT-' + id.replace(/-/g, '').slice(0, 4).toUpperCase()
}

function dispositionSummary(s: LandingSession, t: (k: string, o?: Record<string, unknown>) => string): string {
  const parts: string[] = []
  if (s.restocked_count > 0) parts.push(t('returns.landing.dispositionRestocked', { count: s.restocked_count }))
  if (s.damaged_count > 0)   parts.push(t('returns.landing.dispositionDamaged',   { count: s.damaged_count }))
  if (s.mismatch_count > 0)  parts.push(t('returns.landing.dispositionMismatch',  { count: s.mismatch_count }))
  return parts.length > 0 ? parts.join(' · ') : t('returns.landing.status.abandoned') === s.status ? '' : '—'
}

// ── Open session — full-screen immersive ─────────────────────────────────────

type FlashState = 'idle' | 'success' | 'error'

function OpenSessionScreen({ sessionId, onExit, onStartNew }: {
  sessionId: string
  onExit: () => void
  onStartNew: (sessionId: string) => void
}) {
  const { t } = useTranslation()
  const role = getRoleFromToken()
  const canManage = role === 'owner' || role === 'manager'
  const scanRef = useRef<HTMLInputElement>(null)

  const [detail, setDetail] = useState<SessionDetail | null>(null)
  const [loading, setLoading] = useState(true)
  const [flash, setFlash] = useState<FlashState>('idle')
  const [scanning, setScanning] = useState(false)
  const [rejectedScan, setRejectedScan] = useState<string | null>(null)
  const [damageTarget, setDamageTarget] = useState<string | null>(null)
  const [damageReason, setDamageReason] = useState('')
  const [damageReasonError, setDamageReasonError] = useState(false)
  const [dispositioning, setDispositioning] = useState<string | null>(null)
  const [reprinting, setReprinting] = useState<string | null>(null)
  const [reprintErrors, setReprintErrors] = useState<Record<string, string>>({})
  const [showAbandonModal, setShowAbandonModal] = useState(false)
  const [abandoning, setAbandoning] = useState(false)
  const [closing, setClosing] = useState(false)
  const [closeSummary, setCloseSummary] = useState<CloseSummary | null>(null)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(async () => {
    try {
      const data = await api<SessionDetail>(`/returns/sessions/${sessionId}`)
      setDetail(data)
    } catch (e: unknown) {
      setError((e as Error).message || t('common.error'))
    } finally {
      setLoading(false)
    }
  }, [sessionId, t])

  useEffect(() => { load() }, [load])

  // SAFETY-CRITICAL scan input refocus — later-mounted input wins the click-refocus
  // race; no other auto-focusing scan/text input is ever rendered alongside this one.
  useEffect(() => {
    const refocus = () => scanRef.current?.focus()
    document.addEventListener('click', refocus)
    scanRef.current?.focus()
    return () => document.removeEventListener('click', refocus)
  }, [])

  // SAFETY-CRITICAL — do not modify
  const triggerFlash = (s: 'success' | 'error') => {
    setFlash(s); setTimeout(() => setFlash('idle'), 600)
  }

  // SAFETY-CRITICAL scan handler — whitespace-strip, disabled-while-scanning,
  // clear+refocus regardless of outcome: do not modify
  const handleScan = useCallback(async (raw: string) => {
    const cleaned = raw.replace(/\s+/g, '')
    if (!cleaned || scanning) return
    setScanning(true); setRejectedScan(null)
    try {
      await api(`/returns/sessions/${sessionId}/scan`, {
        method: 'POST', body: JSON.stringify({ scan: cleaned, locationId: null }),
      })
      playBeep(true); triggerFlash('success')
      await load()
    } catch (e: unknown) {
      playBeep(false); triggerFlash('error')
      const status = (e as { status?: number }).status
      if (status === 422) {
        setRejectedScan(cleaned)
        setTimeout(() => setRejectedScan(null), 4000)
      } else {
        setError((e as Error).message || t('common.error'))
      }
    } finally {
      setScanning(false)
      if (scanRef.current) { scanRef.current.value = ''; scanRef.current.focus() }
    }
  }, [sessionId, scanning, load, t])

  const disposition = async (pieceId: string, verdict: 'restock' | 'damaged' | 'mismatch', reason?: string) => {
    if (verdict === 'damaged' && !reason?.trim()) { setDamageReasonError(true); return }
    setDamageReasonError(false)
    setDispositioning(pieceId)
    try {
      await api(`/returns/sessions/${sessionId}/items/${pieceId}/disposition`, {
        method: 'POST',
        body: JSON.stringify({ disposition: verdict, reason: reason ?? null, locationId: null }),
      })
      playBeep(true)
      setDamageTarget(null); setDamageReason(''); setDamageReasonError(false)
      await load()
    } catch (e: unknown) {
      playBeep(false)
      setError((e as Error).message || t('common.error'))
    } finally {
      setDispositioning(null)
    }
  }

  const reprint = async (pieceId: string) => {
    if (reprinting) return
    setReprinting(pieceId)
    setReprintErrors(prev => { const n = { ...prev }; delete n[pieceId]; return n })
    try { await printSessionPieceLabel(sessionId, pieceId) }
    catch (e: unknown) { setReprintErrors(prev => ({ ...prev, [pieceId]: (e as Error).message || t('common.error') })) }
    finally { setReprinting(null) }
  }

  const abandon = async () => {
    setAbandoning(true)
    try {
      await api(`/returns/sessions/${sessionId}`, { method: 'DELETE' })
      onExit()
    } catch (e: unknown) {
      setError((e as Error).message || t('common.error'))
    } finally {
      setAbandoning(false); setShowAbandonModal(false)
    }
  }

  const close = async () => {
    setClosing(true)
    try {
      const summary = await api<CloseSummary>(`/returns/sessions/${sessionId}/close`, { method: 'POST' })
      setCloseSummary(summary)
    } catch (e: unknown) {
      if (e instanceof ApiError && e.body.code === 'SESSION_CLOSE_BLOCKED') {
        await load() // refresh so the blocked callout reflects authoritative state
      } else {
        setError((e as Error).message || t('common.error'))
      }
    } finally {
      setClosing(false)
    }
  }

  const [startingNew, setStartingNew] = useState(false)
  const startNewSession = async () => {
    if (startingNew) return
    setStartingNew(true)
    try {
      const result = await api<{ sessionId: string }>('/returns/sessions', {
        method: 'POST', body: JSON.stringify({ note: null }),
      })
      // key={sessionId} at the root (Returns()) forces a full remount of this
      // component for the new session — no manual state reset needed here.
      onStartNew(result.sessionId)
    } catch (e: unknown) {
      setError((e as Error).message || t('common.error'))
      setStartingNew(false)
    }
  }

  // SAFETY-CRITICAL — flash overlay computation: do not modify
  const flashOverlay =
    flash === 'success' ? 'fixed inset-0 bg-success/20 pointer-events-none z-50 animate-flash' :
    flash === 'error'   ? 'fixed inset-0 bg-danger/20 pointer-events-none z-50 animate-flash' :
    'hidden'

  const pendingItems = detail?.items.filter(i => i.disposition === 'pending') ?? []
  const canClose = pendingItems.length === 0

  if (loading) {
    return (
      <div className="min-h-screen bg-base flex items-center justify-center">
        <Skeleton className="h-96 w-full max-w-2xl rounded-2xl" />
      </div>
    )
  }

  if (closeSummary) {
    return (
      <div className="min-h-screen bg-base flex items-center justify-center p-6" data-testid="close-summary">
        <div className="flex flex-col items-center gap-4 text-center max-w-sm">
          <ClipboardCheck size={44} strokeWidth={1.75} className="text-success" />
          <h2 className="text-h2 text-primary">{t('returns.openSession.closedTitle')}</h2>
          <p className="text-small text-muted">
            {shortId(closeSummary.sessionId)} · {closeSummary.pieceCount} {t('returns.openSession.piecesStat')}
          </p>
          <div className="flex gap-4">
            <SummaryStat value={closeSummary.restockedCount} label={t('returns.openSession.restocked')} tone="success" />
            <SummaryStat value={closeSummary.damagedCount} label={t('returns.openSession.damaged')} tone="critical" />
            <SummaryStat value={closeSummary.mismatchCount} label={t('returns.openSession.mismatchLabel')} tone="warning" />
          </div>
          <Button variant="primary" loading={startingNew} onClick={startNewSession} className="mt-2">
            {t('returns.openSession.startNew')}
          </Button>
          <button onClick={onExit} className="text-small text-muted hover:text-primary underline mt-1">
            {t('returns.openSession.backToLanding')}
          </button>
        </div>
      </div>
    )
  }

  return (
    <div className="min-h-screen bg-base flex flex-col" data-testid="open-session-screen">
      {/* SAFETY-CRITICAL flash overlay — do not modify */}
      <div className={flashOverlay} />

      <div className="h-14 border-b border-line bg-surface flex items-center gap-3 px-5 shrink-0">
        <button onClick={onExit} aria-label={t('common.cancel')} className="text-muted hover:text-primary">
          <X size={18} strokeWidth={2} />
        </button>
        <div className="text-body font-semibold text-primary">
          {t('returns.openSession.title')} <span className="font-mono">{shortId(sessionId)}</span>
        </div>
        {detail && (
          <span className="text-small text-muted">
            {t('returns.openSession.startedAt', {
              time: new Date(detail.opened_at).toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' }),
            })}
          </span>
        )}
        <div className="flex-1" />
        {canManage && (
          <button
            onClick={() => setShowAbandonModal(true)}
            className="text-small font-semibold text-critical hover:text-critical/80"
            data-testid="abandon-link"
          >
            {t('returns.openSession.abandon')}
          </button>
        )}
      </div>

      <div className="px-5 py-3.5 border-b border-line bg-base flex items-center gap-2.5 shrink-0">
        <ScanLine size={18} strokeWidth={2} className="text-trace-blue" />
        {/* SAFETY-CRITICAL scan input — ref, autoFocus, onKeyDown, disabled: do not modify */}
        <input
          ref={scanRef}
          type="text"
          placeholder={t('returns.openSession.scanPlaceholder')}
          className="input-scan flex-1"
          disabled={scanning}
          onKeyDown={e => { if (e.key === 'Enter') handleScan((e.target as HTMLInputElement).value) }}
          autoFocus
          data-testid="scan-input"
        />
        <span className="text-caption text-muted hidden sm:inline">{t('returns.openSession.autoFocused')}</span>
      </div>

      {error && (
        <div className="px-5 pt-3">
          <Alert tone="critical" title={error} />
        </div>
      )}

      <div className="flex-1 overflow-auto px-5 py-4 space-y-2.5 relative" data-testid="items-list">
        {detail && detail.items.length === 0 && detail.expectedPieces.length === 0 && (
          <div className="h-full flex flex-col items-center justify-center gap-2 text-center">
            <ScanLine size={36} strokeWidth={1.75} className="text-muted" />
            <p className="text-body font-semibold text-primary">{t('returns.openSession.emptyTitle')}</p>
            <p className="text-small text-muted">{t('returns.openSession.emptySubtitle')}</p>
          </div>
        )}

        {detail?.expectedPieces.map(p => (
          <div key={p.id} className="border border-line bg-elevated rounded-xl px-3.5 py-3 flex items-center gap-3.5" data-testid={`expected-${p.id}`}>
            <span className="w-2 h-2 rounded-full bg-info shrink-0" />
            <div className="flex-1 min-w-0">
              <p className="text-small font-semibold text-primary truncate">{p.product_title}</p>
              <p className="text-caption font-mono text-muted">{p.barcode}</p>
            </div>
            <Badge tone="info" label={t('returns.openSession.awaitingScan')} />
            <button
              title={t('returns.openSession.reprint')}
              onClick={() => reprint(p.id)}
              disabled={reprinting === p.id}
              className="border border-line rounded-lg p-1.5 text-muted hover:text-primary"
            >
              <Printer size={12} strokeWidth={2} />
            </button>
          </div>
        ))}

        {detail?.items.map(item => {
          const isPending = item.disposition === 'pending'
          const isIllegal = isPending && item.status !== 'return_pending_inspection'
          if (!isPending) {
            return (
              <div
                key={item.id}
                className="border border-line bg-surface rounded-xl px-3.5 py-3 flex items-center gap-3.5 opacity-85"
                data-testid={`item-${item.piece_id}`}
              >
                {item.disposition === 'restocked'
                  ? <RotateCcw size={16} strokeWidth={2} className="text-success shrink-0" />
                  : item.disposition === 'damaged'
                  ? <AlertTriangle size={16} strokeWidth={2} className="text-critical shrink-0" />
                  : <XCircle size={16} strokeWidth={2} className="text-warning shrink-0" />}
                <div className="flex-1 min-w-0">
                  <p className="text-small font-semibold text-primary truncate">{item.product_title}</p>
                  <p className="text-caption font-mono text-muted">
                    {item.barcode}
                    {item.damage_reason && ` · ${t('returns.openSession.reasonPrefix', { reason: item.damage_reason })}`}
                  </p>
                </div>
                <Badge
                  tone={item.disposition === 'restocked' ? 'success' : item.disposition === 'damaged' ? 'critical' : 'warning'}
                  label={item.disposition === 'restocked'
                    ? t('returns.openSession.restocked')
                    : item.disposition === 'damaged'
                    ? t('returns.openSession.damaged')
                    : t('returns.openSession.mismatchLabel')}
                />
              </div>
            )
          }
          return (
            <div
              key={item.id}
              className="border border-warning/30 bg-warning/5 rounded-xl px-3.5 py-3 flex items-center gap-3.5"
              data-testid={`item-${item.piece_id}`}
            >
              <span className="w-2 h-2 rounded-full bg-warning shrink-0 animate-pulse" />
              <div className="flex-1 min-w-0">
                <div className="flex items-center gap-2">
                  <p className="text-small font-semibold text-primary truncate">{item.product_title}</p>
                  {item.unexpected && <Badge tone="warning" label={t('returns.openSession.unexpected')} />}
                </div>
                <p className="text-caption font-mono text-muted">
                  {item.barcode}
                  {item.unexpected && !isIllegal && ` — ${t('returns.openSession.notOnManifest')}`}
                </p>
              </div>
              <Badge tone="warning" label={t('returns.openSession.needsDecision')} />
              <div className="flex gap-1.5 shrink-0">
                {!isIllegal && (
                  <>
                    <Button
                      variant="secondary" size="sm"
                      loading={dispositioning === item.piece_id}
                      onClick={() => disposition(item.piece_id, 'restock')}
                    >
                      {t('returns.openSession.restock')}
                    </Button>
                    <Button
                      variant="destructive" size="sm"
                      onClick={() => setDamageTarget(item.piece_id)}
                    >
                      {t('returns.openSession.damage')}
                    </Button>
                  </>
                )}
                <Button
                  variant="tertiary" size="sm"
                  loading={dispositioning === item.piece_id}
                  onClick={() => disposition(item.piece_id, 'mismatch')}
                >
                  {t('returns.openSession.mismatch')}
                </Button>
                <button
                  title={t('returns.openSession.reprint')}
                  onClick={() => reprint(item.piece_id)}
                  disabled={reprinting === item.piece_id}
                  className="border border-line rounded-lg p-1.5 text-muted hover:text-primary"
                  data-testid={`reprint-${item.piece_id}`}
                >
                  <Printer size={12} strokeWidth={2} />
                </button>
              </div>

              {damageTarget === item.piece_id && (
                <div className="basis-full mt-2 pt-2.5 border-t border-warning/30 flex gap-2">
                  <input
                    type="text"
                    value={damageReason}
                    onChange={e => { setDamageReason(e.target.value); setDamageReasonError(false) }}
                    placeholder={t('returns.openSession.damageReasonPlaceholder')}
                    className="input flex-1"
                    autoFocus
                    onKeyDown={e => { if (e.key === 'Enter') disposition(item.piece_id, 'damaged', damageReason) }}
                  />
                  <Button
                    variant="destructive" size="sm"
                    loading={dispositioning === item.piece_id}
                    onClick={() => disposition(item.piece_id, 'damaged', damageReason)}
                  >
                    {t('returns.pending.confirm')}
                  </Button>
                  <Button variant="tertiary" size="sm" onClick={() => { setDamageTarget(null); setDamageReason(''); setDamageReasonError(false) }}>
                    {t('common.cancel')}
                  </Button>
                </div>
              )}
              {damageReasonError && damageTarget === item.piece_id && (
                <p className="basis-full text-caption text-critical" data-testid="damage-reason-error">
                  {t('returns.openSession.damageReasonRequired')}
                </p>
              )}
              {reprintErrors[item.piece_id] && (
                <p className="basis-full text-caption text-critical">{reprintErrors[item.piece_id]}</p>
              )}
            </div>
          )
        })}

        {rejectedScan && (
          <div
            className="sticky bottom-2 mx-auto max-w-sm bg-critical/10 border border-critical/30 rounded-xl px-3.5 py-3 flex items-center gap-2.5 shadow-e3"
            data-testid="rejected-scan-toast"
          >
            <XCircle size={18} strokeWidth={2} className="text-critical shrink-0" />
            <div>
              <p className="text-small font-semibold text-critical">{t('returns.openSession.rejectedTitle')}</p>
              <p className="text-caption font-mono text-muted">
                {t('returns.openSession.rejectedBody', { scan: rejectedScan })}
              </p>
            </div>
          </div>
        )}
      </div>

      <div className="px-5 py-3.5 border-t border-line bg-surface shrink-0 flex flex-col gap-2">
        {!canClose && (
          <div className="bg-warning/10 border border-warning/30 rounded-lg px-3 py-2.5 text-small text-warning" data-testid="close-blocked-callout">
            <b>{t('returns.openSession.closeBlockedTitle')}</b> —{' '}
            {t('returns.openSession.closeBlockedBody', {
              count: pendingItems.length,
              list: pendingItems.map(i => `${i.barcode} (${i.product_title})`).join(', '),
            })}
          </div>
        )}
        {/* raw <button> — Button doesn't spread data-testid */}
        <button
          className="btn-brand w-full justify-center"
          disabled={!canClose || !canManage || closing}
          onClick={close}
          data-testid="close-session-button"
        >
          {closing && <Spinner size={16} />}
          {t('returns.openSession.close')}
        </button>
      </div>

      {showAbandonModal && (
        <Modal onClose={() => setShowAbandonModal(false)} title={t('returns.openSession.abandonConfirmTitle')}>
          <div className="space-y-4">
            <p className="text-small text-muted">
              {detail && detail.items.length > 0
                ? t('returns.openSession.abandonConfirmBodyWithItems', { count: detail.items.length })
                : t('returns.openSession.abandonConfirmBodyEmpty')}
            </p>
            <div className="flex gap-2 justify-end">
              <Button variant="tertiary" onClick={() => setShowAbandonModal(false)}>{t('common.cancel')}</Button>
              {/* raw <button> — Button doesn't spread data-testid */}
              <button className="btn-danger" disabled={abandoning} onClick={abandon} data-testid="confirm-abandon">
                {abandoning && <Spinner size={16} />}
                {t('returns.openSession.abandonConfirmButton')}
              </button>
            </div>
          </div>
        </Modal>
      )}
    </div>
  )
}

function SummaryStat({ value, label, tone }: { value: number; label: string; tone: 'success' | 'critical' | 'warning' }) {
  const toneClass = tone === 'success' ? 'text-success' : tone === 'critical' ? 'text-critical' : 'text-warning'
  return (
    <div className="text-center">
      <div className={`text-h3 font-mono font-bold ${toneClass}`}>{value}</div>
      <div className="text-caption text-muted">{label}</div>
    </div>
  )
}
