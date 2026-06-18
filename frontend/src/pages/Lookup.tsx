import { useState, useRef, useEffect, useCallback } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { lookup, LookupResult, PieceLookupResult, TrackingLookupResult, TimelineEvent } from '../api'
import { Badge, Spinner } from '../components/ui'

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

// ── Piece lookup view ─────────────────────────────────────────────────────────

function PieceView({ result }: { result: PieceLookupResult }) {
  const { t } = useTranslation()

  return (
    <div className="space-y-4 animate-fadeIn">
      {/* ── Header card ── */}
      <div className="card p-6">
        <div className="flex items-start justify-between gap-4 mb-5">
          <div className="min-w-0">
            <p className="text-caption text-muted font-mono uppercase tracking-widest mb-1">
              {result.barcode}
            </p>
            <h2 className="text-h2 text-primary leading-tight">
              {result.variant.productTitle}
              {result.variant.title && result.variant.title !== 'Default Title' && (
                <span className="text-muted font-normal"> / {result.variant.title}</span>
              )}
            </h2>
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
                className="text-brand hover:text-brand-hover font-medium transition-colors"
              >
                {result.currentOrder.number ?? result.currentOrder.id.slice(-8)}
              </Link>
            ) : (
              <span className="text-muted">{t('common.na')}</span>
            )}
          </MetaField>
          <MetaField label={t('lookup.shipment')}>
            {result.currentShipment ? (
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
            <Link to="/receiving" className="text-small text-brand hover:text-brand-hover transition-colors">
              {result.receivingSession.locationName ?? result.receivingSession.id.slice(-8)}
            </Link>
          </div>
        )}
      </div>

      {/* ── Timeline ── */}
      <div className="card p-6">
        <h3 className="text-h3 text-primary mb-5">{t('lookup.timeline')}</h3>

        {result.timeline.length === 0 ? (
          <p className="text-body text-muted">{t('lookup.noTimeline')}</p>
        ) : (
          <ol className="relative">
            {/* Vertical line */}
            <div className="absolute start-[7px] top-4 bottom-4 w-px bg-line" aria-hidden />

            {result.timeline.map((event, idx) => (
              <li key={event.id} className="relative ps-8 pb-6 last:pb-0">
                {/* Dot */}
                <div className="absolute start-0 top-1">
                  {idx === 0 ? (
                    <span className="relative flex h-4 w-4">
                      <span className="animate-dotPing absolute inline-flex h-full w-full rounded-full bg-brand opacity-75" />
                      <span className="relative inline-flex rounded-full h-4 w-4 bg-brand" />
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

function MetaField({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div>
      <p className="text-caption text-muted uppercase tracking-wider mb-1">{label}</p>
      <p className="text-body text-primary">{children}</p>
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
        <h2 className="text-h1 text-primary font-mono mt-1">{result.trackingNumber}</h2>
        <div className="grid grid-cols-2 gap-4 mt-5 pt-5 border-t border-line">
          <MetaField label={t('lookup.order')}>
            <Link to={`/orders/${result.orderId}`} className="text-brand hover:text-brand-hover transition-colors">
              {result.orderNumber ?? result.orderId.slice(-8)}
            </Link>
          </MetaField>
          <MetaField label={t('lookup.status')}>
            <span className="badge border bg-cyan/10 text-cyan border-cyan/20">
              {result.internalState.replace(/_/g, ' ')}
            </span>
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
                  <span className="text-small font-mono text-muted">{p.barcode}</span>
                </div>
                <Link
                  to={`/lookup?q=${encodeURIComponent(p.barcode)}`}
                  className="text-small text-brand hover:text-brand-hover font-medium transition-colors"
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

      {/* Search bar */}
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
        <button
          onClick={() => doLookup(query)}
          disabled={loading}
          className="btn-brand btn px-5 py-3"
        >
          {loading ? <Spinner size={16} /> : t('lookup.search')}
        </button>
      </div>

      {notFound && (
        <div className="card p-4 border-danger/30 bg-danger/5 text-danger text-body mb-4">
          {t('lookup.notFound')}
        </div>
      )}

      {loading && !result && (
        <div className="flex justify-center py-16">
          <Spinner size={32} />
        </div>
      )}

      {result && !loading && (
        result.type === 'piece'
          ? <PieceView result={result as PieceLookupResult} />
          : <TrackingView result={result as TrackingLookupResult} />
      )}
    </div>
  )
}
