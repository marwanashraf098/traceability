import { useState, useRef, useEffect, useCallback } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { lookup, LookupResult, PieceLookupResult, TrackingLookupResult, TimelineEvent } from '../api'

// ── Status badge ──────────────────────────────────────────────────────────────

const STATUS_COLOR: Record<string, string> = {
  available:                 'bg-green-100 text-green-800',
  reserved:                  'bg-yellow-100 text-yellow-800',
  packed:                    'bg-purple-100 text-purple-800',
  awaiting_pickup:           'bg-indigo-100 text-indigo-800',
  with_courier:              'bg-sky-100 text-sky-800',
  delivered:                 'bg-emerald-100 text-emerald-800',
  return_in_transit:         'bg-amber-100 text-amber-800',
  return_pending_inspection: 'bg-orange-100 text-orange-800',
  damaged:                   'bg-red-100 text-red-800',
  lost:                      'bg-red-200 text-red-900',
  destroyed:                 'bg-gray-200 text-gray-600',
}

function StatusBadge({ status }: { status: string }) {
  const { t } = useTranslation()
  return (
    <span className={`inline-flex px-2.5 py-0.5 rounded-full text-xs font-semibold ${STATUS_COLOR[status] ?? 'bg-gray-100 text-gray-700'}`}>
      {t(`lookup.pieceStatus.${status}`, { defaultValue: status.replace(/_/g, ' ') })}
    </span>
  )
}

// ── Timeline event phrase ─────────────────────────────────────────────────────

function TimelinePhrase({ event }: { event: TimelineEvent }) {
  const { t } = useTranslation()
  const phrase = t(`lookup.phrase.${event.phraseKey}`, {
    orderNumber:  event.orderNumber  ?? '',
    location:     event.locationName ?? '',
    toStatus:     event.toStatus     ?? '',
    defaultValue: event.phraseKey,
  })
  return <span>{phrase}</span>
}

// ── Piece lookup view ─────────────────────────────────────────────────────────

function PieceView({ result }: { result: PieceLookupResult }) {
  const { t } = useTranslation()

  return (
    <div className="space-y-6">
      {/* Header card */}
      <div className="bg-white rounded-xl shadow-sm border border-gray-200 p-6">
        <div className="flex items-start justify-between mb-4">
          <div>
            <p className="text-xs text-gray-400 font-mono mb-1">{result.barcode}</p>
            <h2 className="text-xl font-bold text-gray-900">
              {result.variant.productTitle}
              {result.variant.title && result.variant.title !== 'Default Title' && (
                <span className="text-gray-500 font-normal"> / {result.variant.title}</span>
              )}
            </h2>
            {result.variant.sku && (
              <p className="text-sm text-gray-400 font-mono mt-0.5">{result.variant.sku}</p>
            )}
          </div>
          <StatusBadge status={result.status} />
        </div>

        <div className="grid grid-cols-2 sm:grid-cols-4 gap-4 mt-4 pt-4 border-t border-gray-100">
          <div>
            <p className="text-xs text-gray-400 uppercase tracking-wide mb-1">{t('lookup.location')}</p>
            <p className="text-sm font-medium text-gray-800">
              {result.currentLocation?.name ?? <span className="text-gray-400">{t('common.na')}</span>}
            </p>
          </div>
          <div>
            <p className="text-xs text-gray-400 uppercase tracking-wide mb-1">{t('lookup.order')}</p>
            {result.currentOrder ? (
              <Link
                to={`/orders/${result.currentOrder.id}`}
                className="text-sm font-medium text-indigo-600 hover:text-indigo-800"
              >
                {result.currentOrder.number ?? result.currentOrder.id.slice(-8)}
              </Link>
            ) : (
              <p className="text-sm text-gray-400">{t('common.na')}</p>
            )}
          </div>
          <div>
            <p className="text-xs text-gray-400 uppercase tracking-wide mb-1">{t('lookup.shipment')}</p>
            {result.currentShipment ? (
              <p className="text-sm font-medium text-gray-800 font-mono">
                {result.currentShipment.trackingNumber}
              </p>
            ) : (
              <p className="text-sm text-gray-400">{t('common.na')}</p>
            )}
          </div>
          <div>
            <p className="text-xs text-gray-400 uppercase tracking-wide mb-1">{t('lookup.receivedAt')}</p>
            <p className="text-sm font-medium text-gray-800">
              {new Date(result.receivedAt).toLocaleDateString()}
            </p>
          </div>
        </div>

        {result.receivingSession && (
          <div className="mt-3 pt-3 border-t border-gray-100">
            <p className="text-xs text-gray-400 uppercase tracking-wide mb-1">{t('lookup.receivingSession')}</p>
            <Link
              to={`/receiving`}
              className="text-sm text-indigo-600 hover:text-indigo-800"
            >
              {result.receivingSession.locationName ?? result.receivingSession.id.slice(-8)}
            </Link>
          </div>
        )}
      </div>

      {/* Timeline */}
      <div className="bg-white rounded-xl shadow-sm border border-gray-200 p-6">
        <h3 className="text-base font-semibold text-gray-900 mb-4">{t('lookup.timeline')}</h3>
        {result.timeline.length === 0 ? (
          <p className="text-gray-400 text-sm">{t('lookup.noTimeline')}</p>
        ) : (
          <ol className="relative border-s border-gray-200 space-y-0">
            {result.timeline.map((event, idx) => (
              <li key={event.id} className="ms-6 pb-6 last:pb-0">
                {/* Dot */}
                <span className={`absolute -start-2 mt-1 flex h-4 w-4 items-center justify-center rounded-full ring-4 ring-white ${
                  idx === 0 ? 'bg-indigo-600' : 'bg-gray-300'
                }`} />

                <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-1">
                  <div>
                    <p className="text-sm font-medium text-gray-900">
                      <TimelinePhrase event={event} />
                    </p>
                    <p className="text-xs text-gray-400 mt-0.5">
                      <span className={event.isSystem ? 'italic' : ''}>
                        {event.isSystem ? t('lookup.system') : event.actor}
                      </span>
                      {event.fromStatus && (
                        <span className="mx-1 text-gray-300">·</span>
                      )}
                      {event.fromStatus && (
                        <span className="text-gray-400">
                          {event.fromStatus.replace(/_/g, ' ')} → {event.toStatus?.replace(/_/g, ' ')}
                        </span>
                      )}
                    </p>
                  </div>
                  <time className="text-xs text-gray-400 whitespace-nowrap">
                    {new Date(event.occurredAt).toLocaleString()}
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

// ── Tracking lookup view ──────────────────────────────────────────────────────

function TrackingView({ result }: { result: TrackingLookupResult }) {
  const { t } = useTranslation()

  return (
    <div className="space-y-6">
      <div className="bg-white rounded-xl shadow-sm border border-gray-200 p-6">
        <p className="text-xs text-gray-400 uppercase tracking-wide mb-1">{t('lookup.trackingResult')}</p>
        <h2 className="text-xl font-bold text-gray-900 font-mono">{result.trackingNumber}</h2>
        <div className="grid grid-cols-2 gap-4 mt-4 pt-4 border-t border-gray-100">
          <div>
            <p className="text-xs text-gray-400 uppercase tracking-wide mb-1">{t('lookup.order')}</p>
            <Link to={`/orders/${result.orderId}`} className="text-sm font-medium text-indigo-600 hover:text-indigo-800">
              {result.orderNumber ?? result.orderId.slice(-8)}
            </Link>
          </div>
          <div>
            <p className="text-xs text-gray-400 uppercase tracking-wide mb-1">{t('lookup.status')}</p>
            <p className="text-sm font-medium text-gray-800">{result.internalState.replace(/_/g, ' ')}</p>
          </div>
        </div>
      </div>

      <div className="bg-white rounded-xl shadow-sm border border-gray-200 p-6">
        <h3 className="text-base font-semibold text-gray-900 mb-4">{t('lookup.trackingPieces')}</h3>
        {result.pieces.length === 0 ? (
          <p className="text-sm text-gray-400">{t('lookup.noPieces')}</p>
        ) : (
          <div className="space-y-2">
            {result.pieces.map(p => (
              <div key={p.pieceId} className="flex items-center justify-between py-2 border-b border-gray-100 last:border-0">
                <div className="flex items-center gap-3">
                  <StatusBadge status={p.status} />
                  <span className="text-sm font-mono text-gray-700">{p.barcode}</span>
                </div>
                <Link
                  to={`/lookup?q=${encodeURIComponent(p.barcode)}`}
                  className="text-xs text-indigo-600 hover:text-indigo-800 font-medium"
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

  // Auto-lookup if q is in URL on mount
  useEffect(() => {
    const q = searchParams.get('q')
    if (q) doLookup(q)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  return (
    <div className="max-w-2xl mx-auto">
      <h1 className="text-xl font-semibold text-gray-900 mb-6">{t('lookup.title')}</h1>

      {/* Search bar */}
      <div className="flex gap-2 mb-8">
        <input
          ref={inputRef}
          type="text"
          value={query}
          onChange={e => setQuery(e.target.value)}
          onKeyDown={e => { if (e.key === 'Enter') doLookup(query) }}
          placeholder={t('lookup.placeholder')}
          className="flex-1 border-2 border-indigo-300 rounded-lg px-4 py-3 text-sm font-mono focus:outline-none focus:border-indigo-500"
          autoFocus
        />
        <button
          onClick={() => doLookup(query)}
          disabled={loading}
          className="px-5 py-3 bg-indigo-600 hover:bg-indigo-700 disabled:bg-indigo-300 text-white rounded-lg font-medium text-sm transition"
        >
          {loading ? '…' : t('lookup.search')}
        </button>
      </div>

      {notFound && (
        <div className="bg-red-50 border border-red-200 rounded-lg px-4 py-3 text-sm text-red-700">
          {t('lookup.notFound')}
        </div>
      )}

      {loading && (
        <p className="text-gray-400 text-sm text-center py-12">{t('common.loading')}</p>
      )}

      {result && !loading && (
        result.type === 'piece'
          ? <PieceView result={result as PieceLookupResult} />
          : <TrackingView result={result as TrackingLookupResult} />
      )}
    </div>
  )
}
