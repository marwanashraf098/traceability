import { useState, useRef, useEffect, useCallback } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import {
  ScanLine, SearchX, ChevronDown, Lock, RotateCcw, Search,
} from 'lucide-react'
import {
  lookup, LookupResult, PieceLookupResult, TrackingLookupResult, OrderLookupResult, TimelineEvent,
  adjustPiece, releasePieceForAdjust, ADJUST_REASONS, AdjustReason, PieceCommittedError,
  voidPiece, holdPiece, unholdPiece, VOID_REASONS, HOLD_REASONS, VoidReason, HoldReason,
  getRoleFromToken,
} from '../api'
import { Badge, Spinner } from '../components/ui'
import OrderDrawer from '../components/OrderDrawer'

// ── Helpers ───────────────────────────────────────────────────────────────────

function formatDate(iso: string) {
  return new Date(iso).toLocaleDateString(undefined, { day: 'numeric', month: 'short', year: 'numeric' })
}

function formatDateTime(iso: string) {
  const d = new Date(iso)
  return d.toLocaleDateString(undefined, { day: 'numeric', month: 'short' })
    + ' · '
    + d.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' })
}

// ── Timeline phrase ───────────────────────────────────────────────────────────

function TimelinePhrase({ event }: { event: TimelineEvent }) {
  const { t } = useTranslation()
  return (
    <span>
      {t(`lookup.phrase.${event.phraseKey}`, {
        orderNumber:  event.orderNumber  ?? '',
        location:     event.locationName ?? '',
        toStatus:     event.toStatus     ?? '',
        defaultValue: event.phraseKey.replace(/_/g, ' '),
      })}
    </span>
  )
}

// ── Status→from→to mini-pills ─────────────────────────────────────────────────

function TransitionPill({ from, to }: { from: string | null; to: string | null }) {
  if (!from && !to) return null
  return (
    <span className="inline-flex items-center gap-1 text-caption text-muted font-mono bg-elevated border border-line rounded-full px-2 py-0.5">
      {from && <span>{from.replace(/_/g, ' ')}</span>}
      {from && to && <span className="text-line">→</span>}
      {!from && <span>—</span>}
      {to && <span className="text-muted">{to.replace(/_/g, ' ')}</span>}
    </span>
  )
}

// ── Meta field (bordered cell, per mockup) ──────────────────────────────────────

function MetaField({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="bg-surface border border-line rounded-lg p-3">
      <p className="text-caption text-muted mb-1">{label}</p>
      <p className="text-small text-primary font-semibold">{children}</p>
    </div>
  )
}

// ── Piece lookup view ─────────────────────────────────────────────────────────

function PieceView({ result, onRefresh }: { result: PieceLookupResult; onRefresh: () => void }) {
  const { t } = useTranslation()

  return (
    <div className="space-y-4 animate-fadeIn">
      {/* ── Header card ── */}
      <div className="card p-6">
        <div className="flex items-start justify-between gap-4 mb-5">
          <div className="min-w-0">
            {/* Barcode — mono as spec requires for IDs */}
            <p className="text-caption text-muted font-mono uppercase tracking-widest mb-1">
              {result.barcode}
            </p>
            <h2 className="text-h2 text-primary leading-tight">
              {result.variant.productTitle}
              {result.variant.title && result.variant.title !== 'Default Title' && (
                <span className="text-muted font-normal"> / {result.variant.title}</span>
              )}
            </h2>
            {/* SKU — mono */}
            {result.variant.sku && (
              <p className="text-small text-muted font-mono mt-1">{result.variant.sku}</p>
            )}
          </div>
          <Badge status={result.status} className="flex-shrink-0 mt-1" />
        </div>

        {/* Meta grid — bordered cells per mockup */}
        <div className="grid grid-cols-2 sm:grid-cols-5 gap-3 pt-5 border-t border-line">
          <MetaField label={t('lookup.location')}>
            {result.currentLocation?.name ?? <span className="text-muted font-normal">{t('common.na')}</span>}
          </MetaField>
          <MetaField label={t('lookup.order')}>
            {result.currentOrder ? (
              <span className="font-mono text-trace-blue">
                {result.currentOrder.number ?? result.currentOrder.id.slice(-8)}
              </span>
            ) : (
              <span className="text-muted font-normal">{t('common.na')}</span>
            )}
          </MetaField>
          <MetaField label={t('lookup.shipment')}>
            {result.currentShipment ? (
              <span className="font-mono text-cyan">
                {result.currentShipment.trackingNumber}
              </span>
            ) : (
              <span className="text-muted font-normal">{t('common.na')}</span>
            )}
          </MetaField>
          <MetaField label={t('lookup.receivedAt')}>
            {formatDate(result.receivedAt)}
          </MetaField>
          <MetaField label={t('lookup.condition')}>
            <span className={result.condition === 'damaged' ? 'text-danger' : 'text-success-text'}>
              {t(`lookup.conditionValue.${result.condition}`)}
            </span>
          </MetaField>
        </div>

        {result.receivingSession && (
          <div className="mt-4 pt-4 border-t border-line">
            <p className="text-caption text-muted uppercase tracking-wider mb-1">
              {t('lookup.receivingSession')}
            </p>
            <Link to="/receiving" className="text-small text-trace-blue hover:text-trace-blue-hover transition-colors">
              {result.receivingSession.locationName ?? result.receivingSession.id.slice(-8)}
            </Link>
          </div>
        )}

        <AdjustPanel pieceId={result.id} pieceStatus={result.status} onDone={onRefresh} />
      </div>

      {/* ── Chain-of-custody timeline ── */}
      <div className="card p-6">
        <h3 className="text-h3 text-primary mb-5">{t('lookup.timeline')}</h3>

        {result.timeline.length === 0 ? (
          <p className="text-body text-muted">{t('lookup.noTimeline')}</p>
        ) : (
          <div className="flex flex-col">
            {result.timeline.map((event, idx) => {
              const isLast = idx === result.timeline.length - 1
              return (
                <div key={event.id} className="flex gap-3.5">
                  {/* Node column — pulsing success dot for the latest event, small muted dot for the rest */}
                  <div className="w-3.5 flex flex-col items-center flex-shrink-0">
                    {idx === 0 ? (
                      <span className="relative flex h-2.5 w-2.5 mt-0.5">
                        <span className="animate-dotPing absolute inline-flex h-full w-full rounded-full bg-success opacity-75" />
                        <span className="relative inline-flex rounded-full h-2.5 w-2.5 bg-success" />
                      </span>
                    ) : (
                      <span className="inline-block h-2 w-2 rounded-full bg-muted mt-1" />
                    )}
                    {!isLast && <span className="w-px flex-1 bg-line mt-1" />}
                  </div>

                  <div className={cnPad(isLast)}>
                    <p className="text-body text-primary leading-relaxed">
                      <TimelinePhrase event={event} />
                    </p>
                    <div className="flex flex-wrap items-center gap-x-2 gap-y-0.5 mt-1.5">
                      <span className={`text-small ${event.isSystem ? 'italic text-muted' : 'text-muted'}`}>
                        {event.isSystem ? t('lookup.system') : event.actor}
                      </span>
                      {(event.fromStatus || event.toStatus) && (
                        <TransitionPill from={event.fromStatus} to={event.toStatus} />
                      )}
                      {event.locationName && (
                        <span className="text-small text-muted">{event.locationName}</span>
                      )}
                    </div>
                    <time className="block text-caption text-muted font-mono mt-1">
                      {formatDateTime(event.occurredAt)}
                    </time>
                  </div>
                </div>
              )
            })}
          </div>
        )}
      </div>
    </div>
  )
}

function cnPad(isLast: boolean) {
  return isLast ? 'flex-1' : 'flex-1 pb-5'
}

// ── Adjust panel (FR-13 + FR-13.x Void/On Hold) ────────────────────────────────

type AdjustTarget = 'lost' | 'damaged' | 'destroyed' | 'void' | 'on_hold'

const TARGET_REASONS: Record<AdjustTarget, readonly string[]> = {
  lost: ADJUST_REASONS,
  damaged: ADJUST_REASONS,
  destroyed: ADJUST_REASONS,
  void: VOID_REASONS,
  on_hold: HOLD_REASONS,
}

// Which pills are reachable depends on the piece's CURRENT status — mirrors
// InventoryLedger.ALLOWED exactly: from available, all five edges exist
// (available:lost/damaged/destroyed/voided/on_hold); from on_hold, only the three
// escalation edges exist (on_hold:lost/damaged/destroyed) — on_hold has no self-edge
// and no on_hold:voided edge.
const AVAILABLE_TARGETS: AdjustTarget[] = ['lost', 'damaged', 'destroyed', 'void', 'on_hold']
const ON_HOLD_TARGETS: AdjustTarget[]   = ['lost', 'damaged', 'destroyed']

interface AdjustPanelProps {
  pieceId: string
  pieceStatus: string
  onDone: () => void
}

function AdjustPanel({ pieceId, pieceStatus, onDone }: AdjustPanelProps) {
  const { t } = useTranslation()
  const [open,        setOpen]        = useState(false)
  const [target,      setTarget]      = useState<AdjustTarget>('lost')
  const [reason,      setReason]      = useState<string>(TARGET_REASONS.lost[0])
  const [note,        setNote]        = useState('')
  const [submitting,  setSubmitting]  = useState(false)
  const [error,       setError]       = useState<string | null>(null)
  const [committed,   setCommitted]   = useState<PieceCommittedError | null>(null)
  const [releasing,   setReleasing]   = useState(false)

  const isLost      = pieceStatus === 'lost'
  const isOnHold    = pieceStatus === 'on_hold'
  const targets     = isOnHold ? ON_HOLD_TARGETS : AVAILABLE_TARGETS
  const role = getRoleFromToken()
  const canAdjust = role === 'owner' || role === 'manager'

  if (!canAdjust) return null

  function selectTarget(t: AdjustTarget) {
    setTarget(t)
    setReason(TARGET_REASONS[t][0])
  }

  function handleOpen() {
    selectTarget(targets[0])
    setOpen(true)
  }

  async function handleFoundIt() {
    setSubmitting(true)
    setError(null)
    try {
      await adjustPiece(pieceId, 'available', reason as AdjustReason, undefined)
      onDone()
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : 'Error')
    } finally {
      setSubmitting(false)
    }
  }

  async function handleReleaseFromHold() {
    setSubmitting(true)
    setError(null)
    try {
      await unholdPiece(pieceId)
      onDone()
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : 'Error')
    } finally {
      setSubmitting(false)
    }
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    if (reason === 'other' && !note.trim()) return
    setSubmitting(true)
    setError(null)
    setCommitted(null)
    try {
      if (target === 'void') {
        await voidPiece(pieceId, reason as VoidReason, note.trim() || undefined)
      } else if (target === 'on_hold') {
        await holdPiece(pieceId, reason as HoldReason, note.trim() || undefined)
      } else {
        await adjustPiece(pieceId, target, reason as AdjustReason, note.trim() || undefined)
      }
      setOpen(false)
      onDone()
    } catch (err: unknown) {
      if (err instanceof Response || (err instanceof Error && err.message.includes('409'))) {
        try {
          const body: PieceCommittedError = err instanceof Response
            ? await err.json()
            : JSON.parse(err.message.replace(/^409: /, ''))
          if (body.error === 'PIECE_COMMITTED') { setCommitted(body); return }
        } catch { /* fall through */ }
      }
      setError(err instanceof Error ? err.message : 'Error')
    } finally {
      setSubmitting(false)
    }
  }

  async function handleRelease() {
    if (!committed) return
    setReleasing(true)
    setError(null)
    try {
      await releasePieceForAdjust(pieceId)
      setCommitted(null)
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : 'Error')
    } finally {
      setReleasing(false)
    }
  }

  return (
    <div className="mt-4 pt-4 border-t border-line space-y-2">
      {/* ── Found It button (lost pieces) — Adjust stays alongside it: lost's only legal
          edge is lost:available (Found It itself), so opening Adjust here has no
          reachable pill. Preserved as-is (not removed) — appearance-only restyle must
          not smuggle a behavior change; flagged for a follow-up decision instead. ── */}
      {isLost && !open && (
        <div className="flex flex-wrap gap-2">
          <button
            data-testid="found-it-btn"
            onClick={handleFoundIt}
            disabled={submitting}
            className="btn-brand text-small"
          >
            {submitting ? <Spinner size={14} /> : t('adjust.foundIt')}
          </button>
          <button
            data-testid="adjust-open-btn"
            onClick={handleOpen}
            className="btn-outline text-small"
          >
            {t('adjust.title')}
          </button>
        </div>
      )}

      {/* ── Release from Hold + Adjust (on_hold pieces) — Adjust here is NOT dead: it's
          the only path to the on_hold->damaged/lost/destroyed escalation edges. ── */}
      {isOnHold && !open && (
        <div className="flex flex-wrap gap-2">
          <button
            data-testid="release-hold-btn"
            onClick={handleReleaseFromHold}
            disabled={submitting}
            className="btn-brand text-small inline-flex items-center gap-1.5"
          >
            {submitting ? <Spinner size={14} /> : <><RotateCcw size={12} />{t('adjust.releaseFromHold')}</>}
          </button>
          <button
            data-testid="adjust-open-btn"
            onClick={handleOpen}
            className="btn-outline text-small"
          >
            {t('adjust.title')}
          </button>
        </div>
      )}

      {/* ── Adjust button (available) ── */}
      {pieceStatus === 'available' && !open && (
        <button
          data-testid="adjust-open-btn"
          onClick={handleOpen}
          className="btn-outline text-small"
        >
          {t('adjust.title')}
        </button>
      )}

      {/* ── Committed guard — critical-tinted per mockup ── */}
      {committed && (
        <div className="rounded-lg border border-danger/30 bg-danger/10 p-3 flex items-center gap-2">
          <Lock size={13} className="text-danger flex-shrink-0" />
          <span className="text-small text-danger flex-1">
            {t('adjust.committedBody', {
              status: pieceStatus,
              orderNumber: committed.orderNumber,
            })}
          </span>
          <button
            data-testid="release-btn"
            onClick={handleRelease}
            disabled={releasing}
            className="bg-transparent border border-danger/30 text-danger rounded-md px-2.5 py-1 text-caption font-semibold hover:bg-danger/10 transition-colors flex-shrink-0"
          >
            {releasing ? <Spinner size={12} /> : t('adjust.releaseBtn')}
          </button>
        </div>
      )}

      {/* ── Adjust form ── */}
      {open && !committed && (
        <form onSubmit={handleSubmit} className="space-y-4 rounded-lg border border-warning/30 p-4 bg-surface">
          <p className="text-caption font-bold text-warning-text uppercase tracking-wider">{t('adjust.managerOnly')}</p>

          {/* Target status — segmented pills, per mockup */}
          <div className="space-y-1">
            <label className="text-caption text-muted uppercase tracking-wider">{t('adjust.toStatus')}</label>
            <div className="inline-flex flex-wrap bg-elevated border border-line rounded-lg p-0.5 gap-0.5">
              {targets.map(s => (
                <button
                  key={s}
                  type="button"
                  onClick={() => selectTarget(s)}
                  className={`px-3 py-1.5 rounded-md text-small font-semibold transition-colors ${
                    target === s
                      ? 'bg-danger text-white'
                      : 'text-muted hover:text-primary'
                  }`}
                >
                  {t(`adjust.statusLabel.${s}`)}
                </button>
              ))}
            </div>
          </div>

          {/* Reason — swaps options per target */}
          <div className="space-y-1">
            <label className="text-caption text-muted uppercase tracking-wider">{t('adjust.reason')}</label>
            <div className="relative">
              <select
                value={reason}
                onChange={e => setReason(e.target.value)}
                className="input w-full bg-elevated appearance-none pe-9"
              >
                {TARGET_REASONS[target].map(r => (
                  <option key={r} value={r}>{t(`adjust.reasonLabel.${r}`)}</option>
                ))}
              </select>
              <ChevronDown size={13} className="pointer-events-none absolute end-3 top-1/2 -translate-y-1/2 text-muted" />
            </div>
          </div>

          {/* Note (required for other) */}
          <div className="space-y-1">
            <label className="text-caption text-muted uppercase tracking-wider">
              {t('adjust.note')}
              {reason === 'other' && <span className="text-danger ms-1">*</span>}
            </label>
            <textarea
              data-testid="adjust-note"
              value={note}
              onChange={e => setNote(e.target.value)}
              rows={2}
              placeholder={t('adjust.notePlaceholder')}
              required={reason === 'other'}
              className="input w-full resize-none bg-elevated"
            />
          </div>

          {error && <p className="text-small text-danger">{error}</p>}

          <div className="flex gap-2">
            <button
              type="submit"
              disabled={submitting || (reason === 'other' && !note.trim())}
              data-testid="adjust-submit-btn"
              className="btn-danger text-small"
            >
              {submitting ? <Spinner size={14} /> : t('adjust.submit')}
            </button>
            <button
              type="button"
              onClick={() => { setOpen(false); setError(null); setCommitted(null) }}
              className="btn-outline text-small"
            >
              {t('common.cancel')}
            </button>
          </div>
        </form>
      )}

      {error && !open && !committed && (
        <p className="text-small text-danger">{error}</p>
      )}
    </div>
  )
}

// ── Tracking lookup view ──────────────────────────────────────────────────────

function PieceChip({ barcode }: { barcode: string }) {
  return (
    <Link
      to={`/lookup?q=${encodeURIComponent(barcode)}`}
      className="inline-flex items-center gap-1.5 bg-elevated border border-info/30 rounded-full px-2.5 py-1 text-small font-mono hover:border-info transition-colors"
    >
      <span className="w-1.5 h-1.5 rounded-full bg-info flex-shrink-0" />
      {barcode}
    </Link>
  )
}

function TrackingView({ result }: { result: TrackingLookupResult }) {
  const { t } = useTranslation()

  return (
    <div className="space-y-4 animate-fadeIn">
      <div className="card p-6">
        <div className="flex items-start justify-between gap-4">
          <div>
            <p className="text-caption text-muted uppercase tracking-wider mb-1">{t('lookup.trackingResult')}</p>
            {/* Tracking number — mono */}
            <h2 className="text-h2 text-primary font-mono">{result.trackingNumber}</h2>
          </div>
          <Badge status={result.internalState} className="flex-shrink-0 mt-1" />
        </div>
        <div className="mt-3 pt-3 border-t border-line">
          <MetaField label={t('lookup.order')}>
            <Link
              to={`/orders/${result.orderId}`}
              className="text-trace-blue hover:text-trace-blue-hover transition-colors"
            >
              {result.orderNumber ?? result.orderId.slice(-8)}
            </Link>
          </MetaField>
        </div>
      </div>

      <div className="card p-6">
        <h3 className="text-h3 text-primary mb-4">{t('lookup.trackingPieces')}</h3>
        {result.pieces.length === 0 ? (
          <p className="text-body text-muted">{t('lookup.noPieces')}</p>
        ) : (
          <div className="flex flex-wrap gap-2">
            {result.pieces.map(p => (
              <PieceChip key={p.pieceId} barcode={p.barcode} />
            ))}
          </div>
        )}
      </div>
    </div>
  )
}

// ── Order lookup: no dedicated view component — resolving to an order opens the
// SAME OrderDrawer used from the Orders list (getOrder()/getOrderTimeline(), the one
// tenant-scoped order-detail data path), not a parallel Lookup-specific render. See
// LookupPage below for the orderId hand-off.

// ── Search card (prompt / not-found states, per mockup) ────────────────────────

interface SearchCardProps {
  query: string
  setQuery: (q: string) => void
  onSubmit: () => void
  loading: boolean
  notFound: boolean
  lastQuery: string
  hasResult: boolean
  inputRef: React.RefObject<HTMLInputElement>
}

function SearchCard({ query, setQuery, onSubmit, loading, notFound, lastQuery, hasResult, inputRef }: SearchCardProps) {
  const { t } = useTranslation()
  const idle = !hasResult && !notFound && !loading

  return (
    <div className="card p-5">
      <form
        onSubmit={e => { e.preventDefault(); onSubmit() }}
        className="relative"
      >
        <ScanLine
          size={18}
          className={`absolute start-4 top-1/2 -translate-y-1/2 pointer-events-none ${notFound ? 'text-muted' : 'text-trace-blue'}`}
        />
        <input
          ref={inputRef}
          type="text"
          value={query}
          onChange={e => setQuery(e.target.value)}
          placeholder={t('lookup.scanPrompt')}
          className={`input-scan ps-11 pe-14 w-full ${notFound ? 'border-line focus:border-brand' : ''}`}
          autoFocus
        />
        {/* Trailing slot: ⌘K hint when empty (matches the topbar's shortcut), a clickable
            submit icon once there's something to search — click-to-search must survive the
            restyle, not just Enter-to-submit. */}
        {query ? (
          <button
            type="submit"
            aria-label={t('lookup.search')}
            disabled={loading}
            className="absolute end-3 top-1/2 -translate-y-1/2 text-muted hover:text-trace-blue transition-colors disabled:opacity-50"
          >
            {loading ? <Spinner size={16} /> : <Search size={16} />}
          </button>
        ) : (
          <span className="absolute end-4 top-1/2 -translate-y-1/2 text-caption font-mono text-muted bg-charcoal border border-line rounded-[5px] px-[5px] py-[1px] pointer-events-none">
            ⌘K
          </span>
        )}
      </form>

      {idle && (
        <p className="text-center text-caption text-muted mt-3">
          {t('lookup.tryExamplesPrefix')}{' '}
          <span className="font-mono text-primary">PC-0048291</span>
          {t('lookup.tryExamplesSep')}
          <span className="font-mono text-primary">889213</span>
          {t('lookup.tryExamplesOr')}
          <span className="font-mono text-primary">#1042</span>
        </p>
      )}

      {notFound && (
        <div className="flex flex-col items-center gap-2 pt-6 pb-2 text-center">
          <SearchX size={30} className="text-muted" />
          <p className="text-body font-semibold text-primary">{t('lookup.notFoundTitle', { query: lastQuery })}</p>
          <p className="text-small text-muted">{t('lookup.notFoundHint')}</p>
        </div>
      )}
    </div>
  )
}

// ── Root page ─────────────────────────────────────────────────────────────────

export default function LookupPage() {
  const { t } = useTranslation()
  const [searchParams, setSearchParams] = useSearchParams()
  const [query, setQuery]   = useState(searchParams.get('q') ?? '')
  const [lastQuery, setLastQuery] = useState('')
  const [result, setResult] = useState<LookupResult | null>(null)
  const [loading, setLoading] = useState(false)
  const [notFound, setNotFound] = useState(false)
  // Raw ref kept here (same pattern as Layout search) — Input wraps in div, no forwardRef
  const inputRef = useRef<HTMLInputElement>(null)

  const doLookup = useCallback(async (q: string) => {
    const trimmed = q.trim()
    if (!trimmed) return
    setLoading(true)
    setNotFound(false)
    setResult(null)
    setLastQuery(trimmed)
    setSearchParams({ q: trimmed }, { replace: true })
    try {
      const res = await lookup(trimmed)
      setResult(res)
    } catch (err: unknown) {
      if (err instanceof Error && err.message.startsWith('404')) setNotFound(true)
    } finally {
      setLoading(false)
      inputRef.current?.select()
    }
  }, [setSearchParams])

  useEffect(() => {
    const q = searchParams.get('q')
    if (q) doLookup(q)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  return (
    <div className="max-w-2xl mx-auto space-y-4">
      <h1 className="text-h1 text-primary">{t('lookup.title')}</h1>

      <SearchCard
        query={query}
        setQuery={setQuery}
        onSubmit={() => doLookup(query)}
        loading={loading}
        notFound={notFound}
        lastQuery={lastQuery}
        hasResult={!!result}
        inputRef={inputRef}
      />

      {loading && !result && (
        <div className="flex justify-center py-16">
          <Spinner size={32} />
        </div>
      )}

      {result && !loading && result.type === 'piece' && (
        <PieceView result={result as PieceLookupResult} onRefresh={() => doLookup(query)} />
      )}
      {result && !loading && result.type === 'tracking' && (
        <TrackingView result={result as TrackingLookupResult} />
      )}

      {/* Order lookup opens the SAME OrderDrawer the Orders list uses — not a
          Lookup-specific render — see the OrderLookupResult comment in api.ts. */}
      <OrderDrawer
        orderId={result?.type === 'order' ? (result as OrderLookupResult).orderId : null}
        onClose={() => setResult(null)}
      />
    </div>
  )
}
