import { useState, useRef, useEffect, useCallback } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import {
  lookup, LookupResult, PieceLookupResult, TrackingLookupResult, TimelineEvent,
  adjustPiece, releasePieceForAdjust, ADJUST_REASONS, AdjustReason, PieceCommittedError,
  getRoleFromToken,
} from '../api'
import { Badge, Button, EmptyState, Spinner } from '../components/ui'

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
    <span className="inline-flex items-center gap-1 text-caption text-muted font-mono">
      {from && <span>{from.replace(/_/g, ' ')}</span>}
      {from && to && <span className="text-line">→</span>}
      {to && <span className="text-muted">{to.replace(/_/g, ' ')}</span>}
    </span>
  )
}

// ── Meta field ────────────────────────────────────────────────────────────────

function MetaField({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div>
      <p className="text-caption text-muted uppercase tracking-wider mb-1">{label}</p>
      <p className="text-body text-primary">{children}</p>
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

        {/* Meta grid */}
        <div className="grid grid-cols-2 sm:grid-cols-4 gap-4 pt-5 border-t border-line">
          <MetaField label={t('lookup.location')}>
            {result.currentLocation?.name ?? <span className="text-muted">{t('common.na')}</span>}
          </MetaField>
          <MetaField label={t('lookup.order')}>
            {result.currentOrder ? (
              <Link
                to={`/orders/${result.currentOrder.id}`}
                className="text-trace-blue hover:text-trace-blue-hover font-medium transition-colors"
              >
                {result.currentOrder.number ?? result.currentOrder.id.slice(-8)}
              </Link>
            ) : (
              <span className="text-muted">{t('common.na')}</span>
            )}
          </MetaField>
          <MetaField label={t('lookup.shipment')}>
            {result.currentShipment ? (
              // Tracking number — mono
              <span className="font-mono text-cyan text-small">
                {result.currentShipment.trackingNumber}
              </span>
            ) : (
              <span className="text-muted">{t('common.na')}</span>
            )}
          </MetaField>
          <MetaField label={t('lookup.receivedAt')}>
            {formatDate(result.receivedAt)}
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
          <ol className="relative">
            {/* Timeline spine — start-[7px] = inset-inline-start; mirrors to start side in RTL */}
            <div className="absolute start-[7px] top-4 bottom-4 w-px bg-line" aria-hidden />

            {result.timeline.map((event, idx) => (
              <li key={event.id} className="relative ps-8 pb-6 last:pb-0">
                {/* Node dot — absolute start-0 = inset-inline-start: 0 */}
                <div className="absolute start-0 top-1">
                  {idx === 0 ? (
                    // Live node: animate-dotPing pulse, trace-blue per DS
                    <span className="relative flex h-4 w-4">
                      <span className="animate-dotPing absolute inline-flex h-full w-full rounded-full bg-trace-blue opacity-75" />
                      <span className="relative inline-flex rounded-full h-4 w-4 bg-trace-blue" />
                    </span>
                  ) : (
                    <span className="inline-flex h-4 w-4 rounded-full border-2 border-line bg-elevated" />
                  )}
                </div>

                <div className="flex flex-col sm:flex-row sm:items-start sm:justify-between gap-1">
                  <div className="space-y-1">
                    <p className="text-body text-primary font-medium">
                      <TimelinePhrase event={event} />
                    </p>
                    <div className="flex flex-wrap items-center gap-x-2 gap-y-0.5">
                      <span className={`text-small ${event.isSystem ? 'italic text-muted' : 'text-muted'}`}>
                        {event.isSystem ? t('lookup.system') : event.actor}
                      </span>
                      {(event.fromStatus || event.toStatus) && (
                        <>
                          <span className="text-line text-caption">·</span>
                          <TransitionPill from={event.fromStatus} to={event.toStatus} />
                        </>
                      )}
                      {event.locationName && (
                        <>
                          <span className="text-line text-caption">·</span>
                          <span className="text-small text-muted">{event.locationName}</span>
                        </>
                      )}
                    </div>
                  </div>
                  <time className="text-small text-muted whitespace-nowrap shrink-0">
                    {formatDateTime(event.occurredAt)}
                  </time>
                </div>
              </li>
            ))}
          </ol>
        )}
      </div>
    </div>
  )
}

// ── Adjust panel (FR-13) ──────────────────────────────────────────────────────

interface AdjustPanelProps {
  pieceId: string
  pieceStatus: string
  onDone: () => void
}

function AdjustPanel({ pieceId, pieceStatus, onDone }: AdjustPanelProps) {
  const { t } = useTranslation()
  const [open,        setOpen]        = useState(false)
  const [toStatus,    setToStatus]    = useState<'lost'|'damaged'|'destroyed'>('lost')
  const [reason,      setReason]      = useState<AdjustReason>('cycle_count_missing')
  const [note,        setNote]        = useState('')
  const [submitting,  setSubmitting]  = useState(false)
  const [error,       setError]       = useState<string | null>(null)
  const [committed,   setCommitted]   = useState<PieceCommittedError | null>(null)
  const [releasing,   setReleasing]   = useState(false)

  const isLost      = pieceStatus === 'lost'
  const isAdjustable = pieceStatus === 'available' || isLost
  const role = getRoleFromToken()
  const canAdjust = role === 'owner' || role === 'manager'

  if (!canAdjust) return null

  async function handleFoundIt() {
    setSubmitting(true)
    setError(null)
    try {
      await adjustPiece(pieceId, 'available', reason, undefined)
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
      await adjustPiece(pieceId, toStatus, reason, note.trim() || undefined)
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
      {/* ── Found It button (lost pieces only) ── */}
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
            onClick={() => setOpen(true)}
            className="btn-outline text-small"
          >
            {t('adjust.title')}
          </button>
        </div>
      )}

      {/* ── Adjust button (available + non-lost) ── */}
      {!isLost && isAdjustable && !open && (
        <button
          data-testid="adjust-open-btn"
          onClick={() => setOpen(true)}
          className="btn-outline text-small"
        >
          {t('adjust.title')}
        </button>
      )}

      {/* ── Committed guard ── */}
      {committed && (
        <div className="rounded-lg border border-warning/30 bg-warning/5 p-4 space-y-3">
          <p className="text-body font-semibold text-warning">{t('adjust.committedTitle')}</p>
          <p className="text-small text-muted">
            {t('adjust.committedBody', {
              status: pieceStatus,
              orderNumber: committed.orderNumber,
            })}
          </p>
          <div className="flex gap-2">
            <button
              data-testid="release-btn"
              onClick={handleRelease}
              disabled={releasing}
              className="btn-brand text-small"
            >
              {releasing ? <Spinner size={14} /> : t('adjust.releaseBtn')}
            </button>
            {/* Order number — mono */}
            <Link
              to={`/orders/${committed.orderId}`}
              className="btn-outline text-small font-mono"
            >
              #{committed.orderNumber}
            </Link>
          </div>
        </div>
      )}

      {/* ── Adjust form ── */}
      {open && !committed && (
        <form onSubmit={handleSubmit} className="space-y-4 rounded-lg border border-line p-4">
          <p className="text-body font-semibold text-primary">{t('adjust.title')}</p>

          {/* Target status */}
          <div className="space-y-1">
            <label className="text-caption text-muted uppercase tracking-wider">{t('adjust.toStatus')}</label>
            <div className="flex gap-2 flex-wrap">
              {(['lost','damaged','destroyed'] as const).map(s => (
                <button
                  key={s}
                  type="button"
                  onClick={() => setToStatus(s)}
                  className={`px-3 py-1.5 rounded-lg text-small font-medium border transition-colors ${
                    toStatus === s
                      ? 'bg-trace-blue text-white border-trace-blue'
                      : 'border-line text-muted hover:border-trace-blue'
                  }`}
                >
                  {t(`adjust.statusLabel.${s}`)}
                </button>
              ))}
            </div>
          </div>

          {/* Reason */}
          <div className="space-y-1">
            <label className="text-caption text-muted uppercase tracking-wider">{t('adjust.reason')}</label>
            <select
              value={reason}
              onChange={e => setReason(e.target.value as AdjustReason)}
              className="input w-full"
            >
              {ADJUST_REASONS.map(r => (
                <option key={r} value={r}>{t(`adjust.reasonLabel.${r}`)}</option>
              ))}
            </select>
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
              className="input w-full resize-none"
            />
          </div>

          {error && <p className="text-small text-danger">{error}</p>}

          <div className="flex gap-2">
            <button
              type="submit"
              disabled={submitting || (reason === 'other' && !note.trim())}
              data-testid="adjust-submit-btn"
              className="btn-brand text-small"
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

function TrackingView({ result }: { result: TrackingLookupResult }) {
  const { t } = useTranslation()

  return (
    <div className="space-y-4 animate-fadeIn">
      <div className="card p-6">
        <p className="text-caption text-muted uppercase tracking-wider mb-1">{t('lookup.trackingResult')}</p>
        {/* Tracking number — mono */}
        <h2 className="text-h1 text-primary font-mono mt-1">{result.trackingNumber}</h2>
        <div className="grid grid-cols-2 gap-4 mt-5 pt-5 border-t border-line">
          <MetaField label={t('lookup.order')}>
            <Link
              to={`/orders/${result.orderId}`}
              className="text-trace-blue hover:text-trace-blue-hover transition-colors"
            >
              {result.orderNumber ?? result.orderId.slice(-8)}
            </Link>
          </MetaField>
          <MetaField label={t('lookup.status')}>
            <Badge status={result.internalState} />
          </MetaField>
        </div>
      </div>

      <div className="card p-6">
        <h3 className="text-h3 text-primary mb-4">{t('lookup.trackingPieces')}</h3>
        {result.pieces.length === 0 ? (
          <p className="text-body text-muted">{t('lookup.noPieces')}</p>
        ) : (
          <div className="divide-y divide-line">
            {result.pieces.map(p => (
              <div key={p.pieceId} className="flex items-center justify-between py-3">
                <div className="flex items-center gap-3">
                  <Badge status={p.status} />
                  {/* Piece barcode — mono */}
                  <span className="text-small font-mono text-muted">{p.barcode}</span>
                </div>
                <Link
                  to={`/lookup?q=${encodeURIComponent(p.barcode)}`}
                  className="text-small text-trace-blue hover:text-trace-blue-hover font-medium transition-colors"
                >
                  {t('lookup.viewPiece')} →
                </Link>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  )
}

// ── Root page ─────────────────────────────────────────────────────────────────

export default function LookupPage() {
  const { t } = useTranslation()
  const [searchParams, setSearchParams] = useSearchParams()
  const [query, setQuery]   = useState(searchParams.get('q') ?? '')
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
    <div className="max-w-2xl mx-auto">
      <h1 className="text-h1 text-primary mb-6">{t('lookup.title')}</h1>

      {/* Search bar — raw input keeps ref for .select() after lookup */}
      <div className="flex gap-2 mb-6">
        <input
          ref={inputRef}
          type="text"
          value={query}
          onChange={e => setQuery(e.target.value)}
          onKeyDown={e => { if (e.key === 'Enter') doLookup(query) }}
          placeholder={t('lookup.placeholder')}
          className="input-scan flex-1"
          autoFocus
        />
        <Button
          variant="primary"
          size="lg"
          loading={loading}
          onClick={() => doLookup(query)}
        >
          {t('lookup.search')}
        </Button>
      </div>

      {notFound && (
        <div className="mb-4">
          <EmptyState message={t('lookup.notFound')} />
        </div>
      )}

      {loading && !result && (
        <div className="flex justify-center py-16">
          <Spinner size={32} />
        </div>
      )}

      {result && !loading && (
        result.type === 'piece'
          ? <PieceView result={result as PieceLookupResult} onRefresh={() => doLookup(query)} />
          : <TrackingView result={result as TrackingLookupResult} />
      )}
    </div>
  )
}
