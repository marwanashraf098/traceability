import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router-dom'
import { X } from 'lucide-react'
import {
  getExchangeDetail, getExchangeCandidates, attachExchange, acceptExchangeBareReturn,
  dismissExchange, ExchangeSummary, ExchangeCandidate,
} from '../../api'
import { Badge, Button, EmptyState, Input, LegStatusBadge, Skeleton, Spinner, cn, useToast } from '../../components/ui'
import { MergedRow } from './normalize'
import { exchangeStatusTone } from './statusTone'

/**
 * FR-EXCHANGE Step 4c — detail drawer. Same slide-in shell as VariantDrawer.tsx
 * (scrim + translate-x, ltr:/rtl: mirrored) — opens IN this tab, never navigates to
 * the existing /exchanges/:id mapping screen (that route is untouched, additive).
 *
 * SCOPE DECISION (build task item C): resolution actions (restock/damage) are NOT
 * reimplemented here. Neither GET /exchanges/{id} nor GET /refunds exposes a
 * per-piece list for either feed — there is no piece id to call the existing
 * disposition route with. Both branches below link out to the Returns scan flow
 * instead of faking a disposition UI with nothing behind it.
 */
export default function ExchangeRefundDrawer({
  row,
  onClose,
  onChanged,
}: {
  row: MergedRow | null
  onClose: () => void
  onChanged: () => void
}) {
  const { t } = useTranslation()
  const open = row != null

  return (
    <>
      <div
        className={cn(
          'fixed inset-0 bg-black/45 z-overlay transition-opacity duration-200',
          open ? 'opacity-100 pointer-events-auto' : 'opacity-0 pointer-events-none'
        )}
        onClick={onClose}
      />
      <aside
        aria-hidden={!open}
        data-testid="exchange-refund-drawer"
        className={cn(
          'fixed top-0 end-0 h-screen w-[440px] max-w-[92vw] bg-surface border-s border-line z-modal',
          'flex flex-col shadow-e4 transition-transform duration-200',
          open ? 'translate-x-0' : 'ltr:translate-x-full rtl:-translate-x-full'
        )}
      >
        {!row ? null : (
          <>
            <div className="p-5 pb-4 border-b border-line relative flex items-center gap-3">
              <div className="min-w-0">
                <div className="text-body-lg font-semibold text-primary truncate">
                  {row.kind === 'exchange' ? t('exchange.badge') : t('exchangesRefunds.type.refund')}
                </div>
                <div className="text-small font-mono text-muted">{row.trackingNumber}</div>
              </div>
              <button
                onClick={onClose}
                aria-label={t('exchangesRefunds.drawer.close')}
                className="absolute top-4 end-4 w-8 h-8 rounded-lg flex items-center justify-center text-muted hover:text-primary hover:bg-elevated transition-colors"
              >
                <X size={18} strokeWidth={2} />
              </button>
            </div>

            <div className="flex-1 overflow-y-auto p-5">
              {row.kind === 'refund'
                ? <RefundDrawerBody row={row} />
                : <ExchangeDrawerBody key={row.sourceId} exchangeId={row.sourceId} onChanged={onChanged} />}
            </div>
          </>
        )}
      </aside>
    </>
  )
}

// ── Refund body — everything it needs is already on the list row; no detail
// endpoint exists for a CRP leg (Step 4 diagnosis §2), so nothing more to fetch. ──

function RefundDrawerBody({ row }: { row: MergedRow }) {
  const { t } = useTranslation()
  return (
    <div className="space-y-5" data-testid="refund-drawer-body">
      <section>
        <h3 className="text-caption font-semibold text-muted uppercase tracking-wider mb-2">
          {t('exchangesRefunds.drawer.refund.originalOrder')}
        </h3>
        <div className="card p-3.5 space-y-1">
          <p className="text-body font-medium text-primary">{row.orderNumber ?? '—'}</p>
          <p className="text-small text-muted">{row.customerName ?? '—'}</p>
          {row.customerPhone && <p className="text-small font-mono text-muted">{row.customerPhone}</p>}
        </div>
      </section>

      <section>
        <h3 className="text-caption font-semibold text-muted uppercase tracking-wider mb-2">
          {t('exchangesRefunds.drawer.refund.lifecycle')}
        </h3>
        {row.legStatus
          ? <LegStatusBadge legStatus={row.legStatus} />
          : <span className="text-small text-muted">—</span>}
      </section>

      <section className="card border-line bg-elevated p-3.5">
        <p className="text-small text-muted mb-2">{t('exchangesRefunds.drawer.refund.resolutionNote')}</p>
        <Link to="/returns" className="text-small font-semibold text-trace-blue hover:underline">
          {t('exchangesRefunds.drawer.refund.goToReturns')}
        </Link>
      </section>
    </div>
  )
}

// ── Exchange body — its own fetch (GET /exchanges/{id}), own loading/error, never
// blocks the table behind it. ────────────────────────────────────────────────────

function ExchangeDrawerBody({ exchangeId, onChanged }: { exchangeId: string; onChanged: () => void }) {
  const { t } = useTranslation()
  const [detail, setDetail] = useState<ExchangeSummary | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(false)

  const load = () => {
    setLoading(true)
    setError(false)
    getExchangeDetail(exchangeId)
      .then(setDetail)
      .catch(() => setError(true))
      .finally(() => setLoading(false))
  }

  useEffect(load, [exchangeId]) // eslint-disable-line react-hooks/exhaustive-deps

  if (loading) {
    return (
      <div className="space-y-4">
        <Skeleton className="h-6 w-32 rounded-lg" />
        <Skeleton className="h-24 rounded-2xl" />
        <Skeleton className="h-6 w-32 rounded-lg" />
        <Skeleton className="h-32 rounded-2xl" />
      </div>
    )
  }
  if (error || !detail) {
    return <EmptyState icon="⚠" message={t('exchangesRefunds.drawer.error')} />
  }

  const refresh = () => { load(); onChanged() }

  return (
    <div className="space-y-5" data-testid="exchange-drawer-body">
      <section>
        <h3 className="text-caption font-semibold text-muted uppercase tracking-wider mb-2">
          {t('exchangesRefunds.drawer.exchange.replacementSection')}
        </h3>
        <div className="card p-3.5 space-y-1">
          <p className="text-body text-primary">{detail.outbound_description ?? '—'}</p>
          {detail.outbound_items_count != null && (
            <p className="text-small text-muted">
              {t('exchangesRefunds.drawer.exchange.itemsCount', { count: detail.outbound_items_count })}
            </p>
          )}
        </div>
      </section>

      <section>
        <h3 className="text-caption font-semibold text-muted uppercase tracking-wider mb-2">
          {t('exchangesRefunds.drawer.exchange.statusLabel')}
        </h3>
        <Badge tone={exchangeStatusTone(detail.status)} label={t(`exchangesRefunds.status.${detail.status}`, { defaultValue: detail.status.replace(/_/g, ' ') })} />
      </section>

      {detail.status === 'needs_confirmation' && (
        <CandidatePicker exchangeId={exchangeId} onConfirmed={refresh} />
      )}

      {detail.status === 'unmatched' && (
        <UnmatchedActions exchangeId={exchangeId} onDone={refresh} />
      )}

      {(detail.status === 'matched' || detail.status === 'return_received') && (
        <section className="card border-line bg-elevated p-3.5 space-y-2" data-testid="exchange-matched-readonly">
          <p className="text-small text-muted">{t('exchangesRefunds.drawer.exchange.readOnlyNote')}</p>
          <div className="text-small">
            <span className="text-muted">{t('exchangesRefunds.drawer.exchange.matchedOrder')}: </span>
            <span className="font-mono text-primary">{shortId(detail.matched_order_id)}</span>
          </div>
          {detail.match_method && (
            <div className="text-small">
              <span className="text-muted">{t('exchangesRefunds.drawer.exchange.matchMethod')}: </span>
              <span className="text-primary">{t(`exchangesRefunds.matchMethod.${detail.match_method}`)}</span>
            </div>
          )}
        </section>
      )}

      {detail.status === 'bare_return' && (
        <p className="text-small text-muted">{t('exchangesRefunds.drawer.exchange.bareReturnNote')}</p>
      )}
      {detail.status === 'dismissed' && (
        <p className="text-small text-muted">{t('exchangesRefunds.drawer.exchange.dismissedNote')}</p>
      )}
    </div>
  )

  function shortId(id: string | null): string {
    return id ? id.replace(/-/g, '').slice(0, 8).toUpperCase() : '—'
  }
}

function CandidatePicker({ exchangeId, onConfirmed }: { exchangeId: string; onConfirmed: () => void }) {
  const { t } = useTranslation()
  const { toast } = useToast()
  const [candidates, setCandidates] = useState<ExchangeCandidate[] | null>(null)
  const [error, setError] = useState(false)
  const [confirming, setConfirming] = useState<string | null>(null)

  useEffect(() => {
    getExchangeCandidates(exchangeId).then(setCandidates).catch(() => setError(true))
  }, [exchangeId])

  const confirm = async (orderId: string) => {
    setConfirming(orderId)
    try {
      await attachExchange(exchangeId, orderId)
      toast({ tone: 'success', message: t('exchangesRefunds.drawer.exchange.confirmed') })
      onConfirmed()
    } catch {
      toast({ tone: 'error', message: t('exchangesRefunds.drawer.exchange.actionFailed') })
    } finally {
      setConfirming(null)
    }
  }

  return (
    <section data-testid="candidate-picker">
      <h3 className="text-caption font-semibold text-muted uppercase tracking-wider mb-2">
        {t('exchangesRefunds.drawer.exchange.candidatesTitle')}
      </h3>
      {error ? (
        <p className="text-small text-critical">{t('exchangesRefunds.drawer.exchange.actionFailed')}</p>
      ) : candidates == null ? (
        <Skeleton className="h-16 rounded-xl" />
      ) : candidates.length === 0 ? (
        <p className="text-small text-muted">{t('exchangesRefunds.drawer.exchange.candidatesEmpty')}</p>
      ) : (
        <div className="space-y-2">
          {candidates.map(c => (
            <div
              key={c.pieceId}
              className="card p-3 flex items-center justify-between gap-3"
              data-testid={`candidate-${c.pieceId}`}
            >
              <div className="min-w-0">
                <p className="text-small font-semibold text-primary truncate">
                  {c.variantTitle ?? c.productTitle ?? '—'}
                </p>
                {c.productTitle && c.variantTitle && (
                  <p className="text-caption text-muted truncate">{c.productTitle}</p>
                )}
              </div>
              <Button
                variant="secondary" size="sm"
                loading={confirming === c.orderId}
                onClick={() => confirm(c.orderId)}
              >
                {t('exchangesRefunds.drawer.exchange.confirmMatch')}
              </Button>
            </div>
          ))}
        </div>
      )}
    </section>
  )
}

function UnmatchedActions({ exchangeId, onDone }: { exchangeId: string; onDone: () => void }) {
  const { t } = useTranslation()
  const { toast } = useToast()
  const [orderId, setOrderId] = useState('')
  const [attaching, setAttaching] = useState(false)
  const [bareReturning, setBareReturning] = useState(false)
  const [dismissing, setDismissing] = useState(false)

  const run = async (action: 'attach' | 'bare' | 'dismiss') => {
    try {
      if (action === 'attach') {
        if (!orderId.trim()) return
        setAttaching(true)
        await attachExchange(exchangeId, orderId.trim())
      } else if (action === 'bare') {
        setBareReturning(true)
        await acceptExchangeBareReturn(exchangeId)
      } else {
        setDismissing(true)
        await dismissExchange(exchangeId)
      }
      toast({ tone: 'success', message: t('exchangesRefunds.drawer.exchange.confirmed') })
      onDone()
    } catch {
      toast({ tone: 'error', message: t('exchangesRefunds.drawer.exchange.actionFailed') })
    } finally {
      setAttaching(false); setBareReturning(false); setDismissing(false)
    }
  }

  return (
    <section className="space-y-4" data-testid="unmatched-actions">
      <div>
        <h3 className="text-caption font-semibold text-muted uppercase tracking-wider mb-2">
          {t('exchangesRefunds.drawer.exchange.searchAttachTitle')}
        </h3>
        <div className="flex gap-2">
          <Input
            value={orderId}
            onChange={e => setOrderId(e.target.value)}
            placeholder={t('exchangesRefunds.drawer.exchange.orderIdPlaceholder')}
            data-testid="attach-order-id-input"
          />
          <Button variant="secondary" loading={attaching} disabled={!orderId.trim()} onClick={() => run('attach')}>
            {t('exchangesRefunds.drawer.exchange.attachButton')}
          </Button>
        </div>
      </div>

      <div className="flex gap-2">
        {/* raw <button> — Button doesn't spread data-testid */}
        <button
          className="btn-outline"
          disabled={bareReturning}
          onClick={() => run('bare')}
          data-testid="bare-return-button"
        >
          {bareReturning && <Spinner size={16} />}
          {t('exchangesRefunds.drawer.exchange.bareReturnButton')}
        </button>
        <button
          className="btn-danger"
          disabled={dismissing}
          onClick={() => run('dismiss')}
          data-testid="dismiss-button"
        >
          {dismissing && <Spinner size={16} />}
          {t('exchangesRefunds.drawer.exchange.dismissButton')}
        </button>
      </div>
    </section>
  )
}
