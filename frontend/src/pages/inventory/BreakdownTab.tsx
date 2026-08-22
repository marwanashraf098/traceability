import { Fragment, useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router-dom'
import {
  Warehouse, Truck, CheckCircle2, Undo2, AlertTriangle, ChevronRight, ArrowUpRight,
} from 'lucide-react'
import {
  getInventoryBreakdown, getStatusTotals, getInventoryPieces,
  InventoryPhaseCounts, InventoryPieceRow,
} from '../../api'
import { Badge, EmptyState, Spinner, cn } from '../../components/ui'

type PhaseKey = 'inWarehouse' | 'onTheWayOut' | 'delivered' | 'comingBack' | 'problem'

const PHASE_ICON: Record<PhaseKey, typeof Warehouse> = {
  inWarehouse: Warehouse,
  onTheWayOut: Truck,
  delivered:   CheckCircle2,
  comingBack:  Undo2,
  problem:     AlertTriangle,
}

const PHASE_SEGBAR_COLOR: Record<PhaseKey, string> = {
  inWarehouse: 'bg-success',
  onTheWayOut: 'bg-info',
  delivered:   'bg-muted',
  comingBack:  'bg-warning',
  problem:     'bg-critical',
}

// Which raw piece statuses roll into each phase — must mirror InventoryStockController.
// breakdown()'s own bucketing exactly (backend is the source of truth; this list only
// drives the accordion's status sub-rows, not the phase totals themselves, which come
// straight from GET /inventory/breakdown).
const PHASE_STATUSES: Record<PhaseKey, string[]> = {
  inWarehouse: ['available', 'reserved'],
  onTheWayOut: ['packed', 'awaiting_pickup', 'with_courier'],
  delivered:   ['delivered'],
  comingBack:  ['return_in_transit', 'return_pending_inspection'],
  problem:     ['damaged', 'lost'],
}

const PHASE_ORDER: PhaseKey[] = ['inWarehouse', 'onTheWayOut', 'delivered', 'comingBack', 'problem']

const PIECE_PAGE_SIZE = 25

export default function BreakdownTab() {
  const { t } = useTranslation()

  const [counts, setCounts] = useState<InventoryPhaseCounts | null>(null)
  const [statusCounts, setStatusCounts] = useState<Record<string, number> | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(false)

  const [openPhase, setOpenPhase] = useState<PhaseKey | null>(null)
  const [openStatus, setOpenStatus] = useState<string | null>(null)

  // Piece drill state, keyed by status — one open drill at a time.
  const [pieces, setPieces] = useState<InventoryPieceRow[]>([])
  const [piecesCursor, setPiecesCursor] = useState<string | null>(null)
  const [piecesLoading, setPiecesLoading] = useState(false)

  useEffect(() => {
    setLoading(true); setError(false)
    Promise.all([getInventoryBreakdown(), getStatusTotals()])
      .then(([b, s]) => { setCounts(b); setStatusCounts(s.statusCounts) })
      .catch(() => setError(true))
      .finally(() => setLoading(false))
  }, [])

  function toggleStatus(status: string, reset = true) {
    if (openStatus === status) { setOpenStatus(null); return }
    setOpenStatus(status)
    setPieces([])
    setPiecesCursor(null)
    loadPieces(status, undefined, reset)
  }

  async function loadPieces(status: string, cursor?: string, replace = false) {
    setPiecesLoading(true)
    try {
      const page = await getInventoryPieces({ status, cursor, size: PIECE_PAGE_SIZE })
      setPieces(prev => replace ? page.items : [...prev, ...page.items])
      setPiecesCursor(page.nextCursor)
    } catch {
      // Silently leave whatever was already loaded — a failed "load more" isn't fatal.
    } finally {
      setPiecesLoading(false)
    }
  }

  if (loading) {
    return <div className="py-16 flex items-center justify-center"><Spinner /></div>
  }
  if (error || !counts || !statusCounts) {
    return <EmptyState icon="⚠" message={t('common.error')} />
  }

  const total = PHASE_ORDER.reduce((sum, k) => sum + counts[k], 0) || 1

  return (
    <div>
      {/* Segmented proportion bar */}
      <div className="flex h-2.5 rounded-md overflow-hidden mb-3">
        {PHASE_ORDER.map(k => (
          counts[k] > 0 && (
            <div key={k} className={PHASE_SEGBAR_COLOR[k]} style={{ width: `${(counts[k] / total) * 100}%` }} />
          )
        ))}
      </div>

      {/* Phase stat cards */}
      <div className="grid gap-2.5 mb-5" style={{ gridTemplateColumns: 'repeat(auto-fit, minmax(130px, 1fr))' }}>
        {PHASE_ORDER.map(k => {
          const Icon = PHASE_ICON[k]
          return (
            <div key={k} className={cn('bg-panel border border-line rounded-xl p-3', k === 'problem' && counts[k] > 0 && 'border-critical/30')}>
              <div className={cn('flex items-center gap-1.5 text-caption', k === 'problem' && counts[k] > 0 ? 'text-critical' : 'text-muted')}>
                <Icon size={14} strokeWidth={2} />
                {t(`inventory.breakdown.phase.${k}`)}
              </div>
              <div className={cn('text-h4 font-semibold mt-0.5', k === 'problem' && counts[k] > 0 ? 'text-critical' : 'text-primary')}>
                {counts[k].toLocaleString()}
              </div>
            </div>
          )
        })}
      </div>

      {/* Accordion: phase -> status -> pieces */}
      <div className="card overflow-hidden">
        {PHASE_ORDER.map(phaseKey => {
          const statuses = PHASE_STATUSES[phaseKey]
          const isPhaseOpen = openPhase === phaseKey
          const meta = statuses
            .map(s => `${t(`lookup.pieceStatus.${s}`, { defaultValue: s })} ${(statusCounts[s] ?? 0).toLocaleString()}`)
            .join(' · ')

          return (
            <div key={phaseKey} className="border-t border-line first:border-t-0">
              <button
                type="button"
                onClick={() => setOpenPhase(isPhaseOpen ? null : phaseKey)}
                className="w-full flex items-center gap-2.5 px-4 py-3 bg-elevated hover:bg-white/[0.03] transition-colors text-start"
              >
                <ChevronRight
                  size={14} strokeWidth={2} className="text-muted transition-transform flex-none rtl:rotate-180"
                  style={{ transform: isPhaseOpen ? 'rotate(90deg)' : undefined }}
                />
                <span className={cn('w-2 h-2 rounded-sm flex-none', PHASE_SEGBAR_COLOR[phaseKey])} />
                <span className="text-body font-medium text-primary">{t(`inventory.breakdown.phase.${phaseKey}`)}</span>
                <span className="text-caption text-muted ms-auto">{meta}</span>
              </button>

              {isPhaseOpen && statuses.map(status => {
                const isStatusOpen = openStatus === status
                return (
                  <Fragment key={status}>
                    <button
                      type="button"
                      onClick={() => toggleStatus(status)}
                      className="w-full flex items-center gap-2.5 py-2.5 px-4 ps-9 border-t border-line hover:bg-white/[0.03] transition-colors text-start"
                    >
                      <Badge status={status} />
                      <span className="text-small text-muted ms-auto">
                        {t('inventory.breakdown.pieceCount', { count: statusCounts[status] ?? 0 })}
                      </span>
                      <ChevronRight
                        size={13} strokeWidth={2} className="text-muted transition-transform flex-none rtl:rotate-180"
                        style={{ transform: isStatusOpen ? 'rotate(90deg)' : undefined }}
                      />
                    </button>

                    {isStatusOpen && (
                      <div className="border-t border-line bg-charcoal/40">
                        {pieces.length === 0 && !piecesLoading && (
                          <p className="px-4 py-3 ps-11 text-small text-muted">{t('inventory.breakdown.noPieces')}</p>
                        )}
                        {pieces.map(p => (
                          <div key={p.id} className="flex items-center gap-2.5 py-2 px-4 ps-11 border-t border-line first:border-t-0 text-small">
                            <span className="font-mono text-muted flex-none">{p.barcode}</span>
                            <span className="text-muted truncate flex-1 min-w-0">
                              {p.variantTitle}
                              {p.productTitle && ` — ${p.productTitle}`}
                              {p.orderNumber && ` · ${p.orderNumber}`}
                              {p.trackingNumber && ` · ${p.trackingNumber}`}
                            </span>
                            <Link
                              to={`/lookup?q=${encodeURIComponent(p.barcode)}`}
                              className="inline-flex items-center gap-1 text-trace-blue hover:text-trace-blue-hover flex-none"
                            >
                              {t('inventory.breakdown.timeline')}
                              <ArrowUpRight size={13} strokeWidth={2} />
                            </Link>
                          </div>
                        ))}
                        {piecesLoading && <div className="py-3 flex justify-center"><Spinner size={16} /></div>}
                        {piecesCursor && !piecesLoading && (
                          <button
                            type="button"
                            onClick={() => loadPieces(status, piecesCursor)}
                            className="w-full py-2 ps-11 text-start text-caption text-muted hover:text-primary border-t border-line"
                          >
                            {t('inventory.breakdown.loadMore')}
                          </button>
                        )}
                      </div>
                    )}
                  </Fragment>
                )
              })}
            </div>
          )
        })}
      </div>
    </div>
  )
}
