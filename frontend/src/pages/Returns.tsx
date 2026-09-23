import { useEffect, useRef, useState, useCallback } from 'react'
import { useTranslation, Trans } from 'react-i18next'
import {
  X, ScanLine, Printer, RotateCcw, AlertTriangle, ClipboardCheck, WifiOff,
  Inbox, ArrowRightCircle, XCircle, ChevronLeft, ChevronRight, CheckCircle2, Package,
} from 'lucide-react'
import {
  Badge, Button, EmptyState, Skeleton, StatCard, Modal, Alert, Spinner, cn,
} from '../components/ui'
import Layout from '../components/Layout'
import { getAccessToken, clearAccessToken } from '../auth'
import { getRoleFromToken } from '../api'
import { formatSessionStart } from './returns/sessionStart'

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

/** Bosta's own description of a scanned courier-return (CRP) parcel — returnSpecs.packageDetails. */
interface CourierReturnInfo {
  awb: string
  itemsCount: number | null
  description: string | null
  descriptionAr: string | null
}

/** Step 5 — one card per AWB scanned in this session (GET /returns/sessions/{id} parcels[]). */
interface Parcel {
  shipmentId: string
  awb: string
  leg: 'return' | 'forward'
  orderNumber: string | null
  customerShortName: string | null
  returnedAt: string | null
  bosta: { itemsCount: number | null; description: string | null; descriptionAr: string | null } | null
  tracked: boolean
  intakeOutcome: 'scanned' | 'received_untracked' | null
  markedBy: string | null
  markedAt: string | null
  markedInThisSession: boolean
  expectedPieces: ExpectedPiece[]
  scannedItems: SessionItem[]
  counts: { expected: number; scanned: number }
  complete: boolean
}

interface LastScan {
  kind: 'piece' | 'awb' | 'marked_received'
  code: string | null
  label: string | null
  at: string
}

interface SessionDetail {
  id: string
  status: 'open' | 'closed' | 'abandoned'
  opened_by: string | null
  opened_at: string
  closed_by: string | null
  closed_at: string | null
  note: string | null
  items: SessionItem[]
  expectedPieces: ExpectedPiece[]
  courierReturns?: CourierReturnInfo[]
  // Step 5 parcel view — optional so older fixtures/responses still render (items then
  // count as "other" items and unclaimed expected pieces render on their own).
  parcels?: Parcel[]
  otherItems?: SessionItem[]
  lastScan?: LastScan | null
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

type View =
  | { type: 'landing' }
  | { type: 'session'; sessionId: string }
  | { type: 'summary'; sessionId: string }

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

  if (view.type === 'summary') {
    // Read-only inspection of a closed session — a "look at a record" view, not an
    // active scan loop, so it's Layout-wrapped like the landing table (never
    // OpenSessionScreen, which is reserved for status === 'open').
    return (
      <Layout>
        <SessionSummaryScreen
          key={view.sessionId}
          sessionId={view.sessionId}
          onBack={() => setView({ type: 'landing' })}
        />
      </Layout>
    )
  }

  return (
    <Layout>
      <LandingScreen
        onOpenSession={id => setView({ type: 'session', sessionId: id })}
        onOpenSummary={id => setView({ type: 'summary', sessionId: id })}
      />
    </Layout>
  )
}

// ── Landing (in-shell list) ──────────────────────────────────────────────────

const PAGE_SIZE = 10

/**
 * Landing callout — one component for both the unassigned-pending and the courier
 * returns awaiting-scan banners (same styling, same "Review →" action).
 */
function LandingCallout({ testId, title, subtitle, reviewLabel, onReview }: {
  testId: string
  title: string
  subtitle: string
  reviewLabel: string
  onReview: () => void
}) {
  return (
    <div className="card border-warning/30 bg-warning/5 p-4 flex items-center gap-4" data-testid={testId}>
      <Inbox size={20} strokeWidth={1.75} className="text-warning shrink-0" />
      <div className="flex-1 min-w-0">
        <p className="text-small font-semibold text-warning">{title}</p>
        <p className="text-caption text-muted">{subtitle}</p>
      </div>
      <Button variant="secondary" size="sm" onClick={onReview}>{reviewLabel}</Button>
    </div>
  )
}

/** GET /returns/awaiting-scan — every role (incl. workers); no customer PII. */
interface AwaitingScan {
  count: number
  items: Array<{
    shipmentId: string
    trackingNumber: string
    orderNumber: string | null
    returnedAt: string | null
    itemsCount: number | null
    description: string | null
    descriptionAr: string | null
  }>
}

function LandingScreen({ onOpenSession, onOpenSummary }: {
  onOpenSession: (sessionId: string) => void
  onOpenSummary: (sessionId: string) => void
}) {
  const { t } = useTranslation()
  const isWorker = getRoleFromToken() === 'worker'
  const [page, setPage] = useState(0)
  const [sessions, setSessions] = useState<LandingSession[]>([])
  const [total, setTotal] = useState(0)
  const [analytics, setAnalytics] = useState<Analytics | null>(null)
  const [loading, setLoading] = useState(!isWorker)
  const [error, setError] = useState<string | null>(null)
  const [opening, setOpening] = useState(false)
  const [awaitingScanCount, setAwaitingScanCount] = useState(0)

  // Courier returns waiting to be scanned — fetched for every role, independent of the
  // owner-only sessions/analytics load, and never allowed to break the landing.
  useEffect(() => {
    let cancelled = false
    api<AwaitingScan>('/returns/awaiting-scan')
      .then(r => { if (!cancelled) setAwaitingScanCount(r?.count ?? 0) })
      .catch(() => { if (!cancelled) setAwaitingScanCount(0) })
    return () => { cancelled = true }
  }, [])

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
        {awaitingScanCount > 0 && (
          <LandingCallout
            testId="awaiting-scan-callout"
            title={t('returns.landing.awaitingScanTitle', { count: awaitingScanCount })}
            subtitle={t('returns.landing.awaitingScanSubtitle')}
            reviewLabel={t('returns.landing.unassignedReview')}
            onReview={openSession}
          />
        )}
      </div>
    )
  }

  const openSessionRow = sessions.find(s => s.status === 'open') ?? (page === 0 ? sessions[0] : undefined)
  const hasOpenSession = openSessionRow?.status === 'open'
  // Both callouts' "Review →": resume the open session, else open one.
  const reviewAction = () => hasOpenSession ? onOpenSession(openSessionRow!.id) : openSession()
  const awaitingScanCallout = awaitingScanCount > 0 ? (
    <LandingCallout
      testId="awaiting-scan-callout"
      title={t('returns.landing.awaitingScanTitle', { count: awaitingScanCount })}
      subtitle={t('returns.landing.awaitingScanSubtitle')}
      reviewLabel={t('returns.landing.unassignedReview')}
      onReview={reviewAction}
    />
  ) : null

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
        <>
          {awaitingScanCallout}
          <EmptyState message={t('returns.landing.emptyTitle')} icon="↩️" />
        </>
      ) : (
        <>
          {analytics && (
            <div className="grid grid-cols-2 sm:grid-cols-5 gap-3" data-testid="analytics-band">
              <StatCard label={t('returns.landing.stats.total')} value={analytics.totalReturns} tone="neutral" />
              <StatCard label={t('returns.landing.stats.restocked')} value={analytics.restockedCount} tone="success" />
              <StatCard label={t('returns.landing.stats.damaged')} value={analytics.damagedCount} tone="critical" />
              <StatCard label={t('returns.landing.stats.mismatch')} value={analytics.mismatchCount} tone="warning" />
              <StatCard label={t('returns.landing.stats.expectedNotScanned')} value={analytics.expectedNotScannedCount} tone="warning" />
            </div>
          )}

          {analytics && analytics.unassignedPendingCount > 0 && (
            <LandingCallout
              testId="unassigned-callout"
              title={t('returns.landing.unassignedTitle', { count: analytics.unassignedPendingCount })}
              subtitle={t('returns.landing.unassignedSubtitle')}
              reviewLabel={t('returns.landing.unassignedReview')}
              onReview={reviewAction}
            />
          )}

          {awaitingScanCallout}

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
                {sessions.map(s => {
                  // 'open' resumes the live scan loop; 'closed' opens a read-only
                  // summary; 'abandoned' is inert — the Abandoned badge is the
                  // explanation, and its pieces already resurface via the
                  // unassigned-pending pool, so there's nothing to view here.
                  const clickable = s.status !== 'abandoned'
                  const onRowClick = s.status === 'open' ? () => onOpenSession(s.id)
                    : s.status === 'closed' ? () => onOpenSummary(s.id)
                    : undefined
                  return (
                  <tr
                    key={s.id}
                    className={cn('border-b border-line last:border-0', clickable && 'hover:bg-elevated cursor-pointer')}
                    onClick={onRowClick}
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
                  )
                })}
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
  const { t, i18n } = useTranslation()
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

  // ── Step 5: courier-return parcels whose order Traced never tracked ──────────
  // Collapse override per parcel: true = collapsed by the user ("Leave it for now"),
  // false = expanded by the user. Default: newest incomplete parcel expanded.
  const [collapseOverride, setCollapseOverride] = useState<Record<string, boolean>>({})
  const [parcelBusy, setParcelBusy] = useState<string | null>(null)

  const parcelAction = async (shipmentId: string, action: 'mark-received' | 'undo-mark-received') => {
    if (parcelBusy) return
    setParcelBusy(shipmentId)
    try {
      await api(`/returns/sessions/${sessionId}/parcels/${shipmentId}/${action}`, { method: 'POST' })
      if (action === 'mark-received') playBeep(true)
      setCollapseOverride(prev => { const n = { ...prev }; delete n[shipmentId]; return n })
      await load()
    } catch (e: unknown) {
      playBeep(false)
      setError((e as Error).message || t('common.error'))
    } finally {
      setParcelBusy(null)
    }
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

  // Step 5 parcel view. Expected pieces / items already grouped under a parcel render
  // inside its card; anything left (older responses, plain piece scans) renders below.
  const parcels = detail?.parcels ?? []
  const otherItems = detail?.otherItems ?? detail?.items ?? []
  const parcelExpectedIds = new Set(parcels.flatMap(pc => pc.expectedPieces.map(e => e.id)))
  const orphanExpected = (detail?.expectedPieces ?? []).filter(e => !parcelExpectedIds.has(e.id))
  const newestIncompleteId = parcels.find(pc => !pc.complete)?.shipmentId
  const scannedCount = detail?.items.length ?? 0

  // Existing rows, unchanged — rendered inside parcel cards and in the "other" list.
  const renderExpected = (p: ExpectedPiece) => (
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
        )

  const renderItem = (item: SessionItem) => {
          const isPending = item.disposition === 'pending'
          const isIllegal = isPending && item.status !== 'return_pending_inspection'
          if (!isPending) {
            return <DispositionedItemRow key={item.id} item={item} />
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
        }

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
        <SessionSummary
          variant="post-close"
          data={{
            sessionId: closeSummary.sessionId,
            pieceCount: closeSummary.pieceCount,
            restockedCount: closeSummary.restockedCount,
            damagedCount: closeSummary.damagedCount,
            mismatchCount: closeSummary.mismatchCount,
          }}
          onPrimaryAction={startNewSession}
          primaryActionLoading={startingNew}
          onBack={onExit}
        />
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
            {(() => {
              const started = formatSessionStart(detail.opened_at, i18n.language)
              return t(started.sameDay ? 'returns.openSession.startedAt' : 'returns.openSession.startedOn',
                { time: started.text })
            })()}
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

      {detail?.lastScan && (
        <div className="px-5 pt-3 flex" data-testid="scan-feedback">
          <div className="inline-flex items-center gap-2 px-3.5 py-2 rounded-full bg-success/10 text-success text-small font-medium">
            <CheckCircle2 size={16} strokeWidth={2.2} className="shrink-0" />
            <span>
              {t(detail.lastScan.kind === 'piece' ? 'returns.openSession.feedback.itemScanned'
                : detail.lastScan.kind === 'awb' ? 'returns.openSession.feedback.awbRecognised'
                : 'returns.openSession.feedback.markedReceived')}
              {' · '}
              <bdi className="font-mono">
                {detail.lastScan.kind === 'piece' ? detail.lastScan.code : `AWB ${detail.lastScan.code}`}
              </bdi>
              {detail.lastScan.kind === 'piece' && detail.lastScan.label && ` ${detail.lastScan.label}`}
            </span>
          </div>
        </div>
      )}

      {error && (
        <div className="px-5 pt-3">
          <Alert tone="critical" title={error} />
        </div>
      )}

      <div className="flex-1 overflow-auto px-5 py-4 space-y-3 relative" data-testid="items-list">
        {detail && detail.items.length === 0 && parcels.length === 0 && orphanExpected.length === 0 && (
          <div className="h-full flex flex-col items-center justify-center gap-2 text-center">
            <ScanLine size={36} strokeWidth={1.75} className="text-muted" />
            <p className="text-body font-semibold text-primary">{t('returns.openSession.emptyTitle')}</p>
            <p className="text-small text-muted">{t('returns.openSession.emptySubtitle')}</p>
          </div>
        )}

        {parcels.map(parcel => (
          <ParcelCard
            key={parcel.shipmentId}
            parcel={parcel}
            expanded={collapseOverride[parcel.shipmentId] !== undefined
              ? !collapseOverride[parcel.shipmentId]
              : parcel.shipmentId === newestIncompleteId || (parcel.intakeOutcome === 'received_untracked' && parcel.markedInThisSession)}
            onToggle={expand => setCollapseOverride(prev => ({ ...prev, [parcel.shipmentId]: !expand }))}
            busy={parcelBusy === parcel.shipmentId}
            onMarkReceived={() => parcelAction(parcel.shipmentId, 'mark-received')}
            onUndo={() => parcelAction(parcel.shipmentId, 'undo-mark-received')}
            renderExpected={renderExpected}
            renderItem={renderItem}
          />
        ))}

        {orphanExpected.map(renderExpected)}
        {otherItems.map(renderItem)}

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
        {detail && (parcels.length > 0 || scannedCount > 0) && (
          <p className="text-caption text-muted text-center" data-testid="session-footer-summary">
            {canClose
              ? [
                  parcels.length > 0 ? t('returns.openSession.footer.parcels', { count: parcels.length }) : null,
                  scannedCount > 0 ? t('returns.openSession.footer.itemsScanned', { count: scannedCount }) : null,
                  t('returns.openSession.footer.nothingLeft'),
                ].filter(Boolean).join(' · ')
              : t('returns.openSession.footer.blocked')}
          </p>
        )}
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

// ── Parcel card (Step 5) — one per AWB scanned into the open session. Items inside
// render through the SAME row renderers as before (renderItem / renderExpected), so the
// restock / damaged / mismatch and reprint controls are the existing ones, unchanged. ──

function ParcelCard({ parcel, expanded, onToggle, busy, onMarkReceived, onUndo, renderExpected, renderItem }: {
  parcel: Parcel
  expanded: boolean
  onToggle: (expand: boolean) => void
  busy: boolean
  onMarkReceived: () => void
  onUndo: () => void
  renderExpected: (p: ExpectedPiece) => React.ReactNode
  renderItem: (item: SessionItem) => React.ReactNode
}) {
  const { t, i18n } = useTranslation()
  const lang = i18n.language
  const isAr = lang === 'ar'
  const received = parcel.intakeOutcome === 'received_untracked'
  const untrackedOpen = parcel.leg === 'return' && !parcel.tracked && !parcel.intakeOutcome
  const awaiting = parcel.expectedPieces.length
  const nothingToScan = parcel.tracked && awaiting === 0 && parcel.scannedItems.length === 0 && !parcel.intakeOutcome

  const pill: { tone: 'success' | 'warning' | 'neutral'; label: string } | null =
    received ? { tone: 'neutral', label: t('returns.openSession.parcel.receivedNotTracked') }
    : untrackedOpen ? { tone: 'neutral', label: t('returns.openSession.parcel.notTracked') }
    : parcel.complete ? { tone: 'success', label: expanded
        ? t('returns.openSession.parcel.allIn', { count: parcel.counts.scanned })
        : t('returns.openSession.parcel.allInShort') }
    : parcel.counts.expected > 0 ? { tone: 'warning', label: t('returns.openSession.parcel.progress',
        { scanned: parcel.counts.scanned, count: parcel.counts.expected }) }
    : null

  const order = parcel.orderNumber ?? '—'
  // Order numbers / Latin names inside translated sentences: Unicode first-strong isolate so
  // "#1052" doesn't flip to "1052#" in RTL (<bdi> can't be used inside an i18n string).
  const isolatedOrder = `\u2068${order}\u2069`
  const bostaDescription = parcel.bosta
    ? (isAr && parcel.bosta.descriptionAr ? parcel.bosta.descriptionAr : parcel.bosta.description)
    : null
  const bostaIsEnglishInAr = isAr && !!parcel.bosta && !parcel.bosta.descriptionAr
  const returnedAt = parcel.returnedAt
    ? new Date(parcel.returnedAt).toLocaleDateString(lang, { day: 'numeric', month: 'short' })
    : '—'

  if (!expanded) {
    const dispositions = Array.from(new Set(parcel.scannedItems.map(i => i.disposition)))
      .filter(d => d !== 'pending')
      .map(d => t(d === 'restocked' ? 'returns.openSession.parcel.dispRestocked'
        : d === 'damaged' ? 'returns.openSession.parcel.dispDamaged' : 'returns.openSession.parcel.dispMismatch'))
    const summary = !parcel.tracked
      ? t('returns.openSession.parcel.collapsedNotTracked', { order: isolatedOrder })
      : [t('returns.openSession.parcel.collapsedItems', { order: isolatedOrder, count: parcel.counts.expected }), ...dispositions].join(' · ')
    return (
      <button
        type="button"
        onClick={() => onToggle(true)}
        aria-expanded={false}
        aria-label={t('returns.openSession.parcel.expand')}
        className="card w-full text-start px-5 py-3.5 flex items-center gap-4 hover:border-trace-blue/40"
        data-testid={`parcel-collapsed-${parcel.awb}`}
      >
        <span className={cn('w-9 h-9 rounded-lg flex items-center justify-center shrink-0',
          parcel.complete && !received ? 'bg-success/10 text-success' : 'bg-elevated text-muted')}>
          {parcel.complete && !received ? <CheckCircle2 size={18} strokeWidth={2.2} /> : <Package size={18} strokeWidth={1.8} />}
        </span>
        <bdi className="font-mono text-body font-semibold text-primary w-52 shrink-0">AWB {parcel.awb}</bdi>
        <span className="text-small text-muted flex-1 min-w-0 truncate">{summary}</span>
        {pill && <Badge tone={pill.tone} label={pill.label} />}
      </button>
    )
  }

  return (
    <section className="card overflow-hidden" data-testid={`parcel-card-${parcel.awb}`}>
      <div className="px-5 py-4 flex flex-wrap items-center gap-x-6 gap-y-3">
        <span className={cn('w-11 h-11 rounded-lg flex items-center justify-center shrink-0',
          received || untrackedOpen ? 'bg-elevated text-muted'
            : parcel.complete ? 'bg-success/10 text-success' : 'bg-trace-blue/10 text-trace-blue')}>
          {parcel.complete && !received ? <CheckCircle2 size={22} strokeWidth={2.2} /> : <Package size={22} strokeWidth={1.8} />}
        </span>
        <div className="flex flex-col gap-0.5 w-56 shrink-0">
          <span className="text-caption font-semibold uppercase tracking-wider text-muted">
            {parcel.leg === 'return' ? t('returns.openSession.parcel.courierReturn') : t('returns.openSession.parcel.returnToSender')}
          </span>
          <bdi className="font-mono text-body-lg font-semibold text-primary">AWB {parcel.awb}</bdi>
        </div>
        <div className="flex gap-8 flex-1 min-w-0">
          <ParcelFact label={t('returns.openSession.parcel.order')} value={<bdi>{order}</bdi>} />
          {parcel.customerShortName && (
            <ParcelFact label={t('returns.openSession.parcel.customer')} value={<bdi>{parcel.customerShortName}</bdi>} />
          )}
          <ParcelFact label={t('returns.openSession.parcel.backAtWarehouse')} value={returnedAt} />
        </div>
        {pill && <span data-testid="parcel-pill"><Badge tone={pill.tone} label={pill.label} /></span>}
      </div>

      {bostaDescription && !received && (
        <div className="px-5 py-3 bg-elevated border-t border-line flex gap-4 items-baseline">
          <span className="text-caption font-semibold uppercase tracking-wider text-muted whitespace-nowrap">
            {t('returns.openSession.parcel.bostaNote')}
          </span>
          <span className="text-small text-primary" data-testid="parcel-bosta-note">
            {parcel.bosta?.itemsCount != null
              ? <>
                  {/* description interpolated as '' then rendered in <bdi> — the key ends with it */}
                  {t('returns.openSession.bostaSays', { count: parcel.bosta.itemsCount, description: '' })}
                  <bdi dir={bostaIsEnglishInAr ? 'ltr' : undefined}>{bostaDescription}</bdi>
                </>
              : <bdi dir={bostaIsEnglishInAr ? 'ltr' : undefined}>{bostaDescription}</bdi>}
          </span>
        </div>
      )}

      {received ? (
        <div className="px-5 py-4 border-t border-line flex items-center gap-4" data-testid="parcel-received-untracked">
          <div className="flex flex-col gap-1 flex-1">
            <p className="text-small text-primary">
              {parcel.markedBy
                ? <Trans i18nKey="returns.openSession.parcel.markedBy"
                    values={{ name: parcel.markedBy, time: parcel.markedAt ? new Date(parcel.markedAt).toLocaleTimeString(lang, { hour: 'numeric', minute: '2-digit' }) : '' }}
                    components={{ b: <b /> }} />
                : t('returns.openSession.parcel.markedBySystem', { time: parcel.markedAt ? new Date(parcel.markedAt).toLocaleTimeString(lang, { hour: 'numeric', minute: '2-digit' }) : '' })}
            </p>
            <p className="text-small text-muted leading-relaxed">{t('returns.openSession.parcel.markedBody')}</p>
          </div>
          {parcel.markedInThisSession && (
            <button className="btn-outline btn" disabled={busy} onClick={onUndo} data-testid="parcel-undo">
              {busy && <Spinner size={16} />}
              {t('returns.openSession.parcel.undo')}
            </button>
          )}
        </div>
      ) : untrackedOpen ? (
        <div className="px-5 py-5 border-t border-line flex flex-col gap-4" data-testid="parcel-untracked">
          <div className="flex gap-3.5 p-4 rounded-xl bg-warning/5 border border-warning/30">
            <AlertTriangle size={20} strokeWidth={2} className="text-warning shrink-0 mt-0.5" />
            <div className="flex flex-col gap-2">
              <p className="text-body font-semibold text-warning">{t('returns.openSession.parcel.notTrackedTitle')}</p>
              <p className="text-small text-primary leading-relaxed">{t('returns.openSession.parcel.notTrackedBody')}</p>
              <p className="text-small text-primary leading-relaxed font-semibold">{t('returns.openSession.parcel.notTrackedWarning')}</p>
            </div>
          </div>
          <div className="flex flex-wrap items-center gap-3">
            {/* raw <button>s — Button doesn't spread data-testid */}
            <button className="btn-brand" disabled={busy} onClick={onMarkReceived} data-testid="parcel-mark-received">
              {busy && <Spinner size={16} />}
              {t('returns.openSession.parcel.markReceived')}
            </button>
            <button className="btn-outline btn" onClick={() => onToggle(false)} data-testid="parcel-leave">
              {t('returns.openSession.parcel.leaveForNow')}
            </button>
            <span className="text-caption text-muted">{t('returns.openSession.parcel.leaveHint')}</span>
          </div>
        </div>
      ) : nothingToScan ? (
        <div className="px-5 py-4 border-t border-line text-small text-muted" data-testid="parcel-nothing-to-scan">
          {t('returns.openSession.parcel.forwardNothing')}
        </div>
      ) : (
        <div className="border-t border-line">
          <div className="px-5 py-3 flex items-center gap-4">
            <span className="text-small font-semibold text-primary">{t('returns.openSession.parcel.itemsOnOrder')}</span>
            <span className="flex-1" />
            <span className="text-caption text-muted">
              {awaiting > 0 ? t('returns.openSession.parcel.scanHint') : parcel.complete ? t('returns.openSession.parcel.allDecided') : null}
            </span>
          </div>
          <div className="px-5 pb-4 space-y-2.5">
            {parcel.scannedItems.map(renderItem)}
            {parcel.expectedPieces.map(renderExpected)}
          </div>
        </div>
      )}
    </section>
  )
}

function ParcelFact({ label, value }: { label: string; value: React.ReactNode }) {
  return (
    <div className="flex flex-col gap-0.5 min-w-0">
      <span className="text-caption text-muted">{label}</span>
      <span className="text-small font-semibold text-primary truncate">{value}</span>
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

// ── Dispositioned item row — read-only, shared by the live scan loop's resolved
// items AND the historical session summary below. ────────────────────────────

function DispositionedItemRow({ item }: { item: SessionItem }) {
  const { t } = useTranslation()
  return (
    <div
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

// ── Session summary — shared read-only receipt. 'post-close' fires right after
// Close (fed by the close() response, no items — a fresh, celebratory moment with
// a Start-new-session action). 'historical' fires from a closed row on the landing
// table (fed by GET /returns/sessions/{id}, full item list, zero write affordances
// — just a Back action). Same visual core, two data shapes, two action rails. ────

interface SessionSummaryData {
  sessionId: string
  pieceCount: number
  restockedCount: number
  damagedCount: number
  mismatchCount: number
  openedBy?: string | null
  openedAt?: string | null
  closedAt?: string | null
  note?: string | null
  /** Present only for the historical variant — CloseSummary (post-close) has no items. */
  items?: SessionItem[]
}

function SessionSummary({ data, variant, onPrimaryAction, primaryActionLoading, onBack }: {
  data: SessionSummaryData
  variant: 'post-close' | 'historical'
  onPrimaryAction?: () => void
  primaryActionLoading?: boolean
  onBack: () => void
}) {
  const { t } = useTranslation()
  const fmtTime = (iso: string | null | undefined) => iso
    ? new Date(iso).toLocaleString(undefined, { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' })
    : null

  return (
    <div className="flex flex-col items-center gap-4 text-center max-w-sm mx-auto w-full">
      <ClipboardCheck size={44} strokeWidth={1.75} className="text-success" />
      <h2 className="text-h2 text-primary">
        {variant === 'post-close' ? t('returns.openSession.closedTitle') : t('returns.summary.title')}
      </h2>
      <p className="text-small text-muted">
        {shortId(data.sessionId)} · {data.pieceCount} {t('returns.openSession.piecesStat')}
      </p>

      {variant === 'historical' && (data.openedAt || data.closedAt) && (
        <p className="text-caption text-muted -mt-2">
          {data.openedAt && t('returns.summary.opened', {
            time: fmtTime(data.openedAt),
            operator: data.openedBy ? t('returns.landing.operator') : '—',
          })}
          {data.closedAt && ` · ${t('returns.summary.closed', { time: fmtTime(data.closedAt) })}`}
        </p>
      )}
      {variant === 'historical' && data.note && (
        <p className="text-caption text-muted">{t('returns.summary.note', { note: data.note })}</p>
      )}

      <div className="flex gap-4">
        <SummaryStat value={data.restockedCount} label={t('returns.openSession.restocked')} tone="success" />
        <SummaryStat value={data.damagedCount} label={t('returns.openSession.damaged')} tone="critical" />
        <SummaryStat value={data.mismatchCount} label={t('returns.openSession.mismatchLabel')} tone="warning" />
      </div>

      {variant === 'historical' && data.items && (
        <div className="w-full text-start space-y-2 mt-2" data-testid="summary-items">
          {data.items.length === 0
            ? <p className="text-small text-muted text-center">{t('returns.summary.noItems')}</p>
            : data.items.map(item => <DispositionedItemRow key={item.id} item={item} />)}
        </div>
      )}

      {variant === 'post-close' ? (
        <>
          <Button variant="primary" loading={primaryActionLoading} onClick={onPrimaryAction} className="mt-2">
            {t('returns.openSession.startNew')}
          </Button>
          <button onClick={onBack} className="text-small text-muted hover:text-primary underline mt-1">
            {t('returns.openSession.backToLanding')}
          </button>
        </>
      ) : (
        <Button variant="secondary" onClick={onBack} className="mt-2">
          {t('returns.openSession.backToLanding')}
        </Button>
      )}
    </div>
  )
}

// ── Session summary screen — in-shell (Layout-wrapped by the Returns() root),
// per the list-view/scan-loop split: this is a read-only "look at a record" view,
// not an active scan loop, so it never mounts OpenSessionScreen or its inputs. ──

function SessionSummaryScreen({ sessionId, onBack }: { sessionId: string; onBack: () => void }) {
  const { t } = useTranslation()
  const [detail, setDetail] = useState<SessionDetail | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    api<SessionDetail>(`/returns/sessions/${sessionId}`)
      .then(data => { if (!cancelled) setDetail(data) })
      .catch((e: unknown) => { if (!cancelled) setError((e as Error).message || t('common.error')) })
      .finally(() => { if (!cancelled) setLoading(false) })
    return () => { cancelled = true }
  }, [sessionId, t])

  if (loading) {
    return (
      <div data-testid="session-summary-screen">
        <Skeleton className="h-96 w-full max-w-2xl mx-auto rounded-2xl" />
      </div>
    )
  }

  if (error || !detail) {
    return (
      <div className="space-y-4" data-testid="session-summary-screen">
        <Alert tone="critical" title={error ?? t('common.error')} />
        <Button variant="secondary" onClick={onBack}>{t('returns.openSession.backToLanding')}</Button>
      </div>
    )
  }

  const restockedCount = detail.items.filter(i => i.disposition === 'restocked').length
  const damagedCount   = detail.items.filter(i => i.disposition === 'damaged').length
  const mismatchCount  = detail.items.filter(i => i.disposition === 'mismatch').length

  return (
    <div data-testid="session-summary-screen">
      <SessionSummary
        variant="historical"
        data={{
          sessionId:      detail.id,
          pieceCount:     detail.items.length,
          restockedCount, damagedCount, mismatchCount,
          openedBy:       detail.opened_by,
          openedAt:       detail.opened_at,
          closedAt:       detail.closed_at,
          note:           detail.note,
          items:          detail.items,
        }}
        onBack={onBack}
      />
    </div>
  )
}
