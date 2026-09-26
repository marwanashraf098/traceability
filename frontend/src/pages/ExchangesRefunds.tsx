import { ReactNode, useCallback, useEffect, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useSearchParams } from 'react-router-dom'
import { getExchanges, getRefunds, getReturnRequests, getRoleFromToken } from '../api'
import { Badge, DataTable, DataTableColumn, StatCard, Tabs } from '../components/ui'
import { MergedRow, FilterTab, normalizeExchange, normalizeRefund, matchesFilter } from './exchangesRefunds/normalize'
import { exchangeStatusTone, inspectionStateTone } from './exchangesRefunds/statusTone'
import ExchangeRefundDrawer from './exchangesRefunds/ExchangeRefundDrawer'
import RequestsPanel from './exchangesRefunds/RequestsPanel'

/** Returns portal Step 4e-A — the Requests tab sits beside the filter tabs, not among them. */
type PageTab = FilterTab | 'requests'

/**
 * FR-EXCHANGE Step 4c — the merchant-facing "Exchanges & Refunds" tab.
 *
 * Two structurally different backend sources, each read through its own RLS-scoped
 * query (GET /exchanges, GET /refunds) and merged to ONE display shape client-side
 * (normalize.ts) — never unioned in SQL (Step 4 diagnosis §3). Additive to the
 * existing /exchanges/:id mapping screen — this route never touches that one.
 *
 * PAGINATION SCOPE (stated per the build task's ask to report this): each feed is
 * fetched as a single page at size=100 (the backend's own per-request cap) with no
 * further "load more" UI this pass. Merging two independently offset-paginated feeds
 * into one further-paginated view is a real design problem the backend doesn't yet
 * support (their cursors don't line up) — rather than build a fake merged-page
 * control, this pass shows "the first 100 of each feed, newest first" and nothing
 * more. Flagged as a known scope limit, not silently hidden.
 */
export default function ExchangesRefunds() {
  const { t } = useTranslation()
  const [rows, setRows] = useState<MergedRow[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(false)
  // Step 4c-3: /exchanges?tab=requests&request=<id> (the pickup_booking_problem exception's link).
  const [searchParams] = useSearchParams()
  const linkedRequest = searchParams.get('tab') === 'requests' ? searchParams.get('request') : null
  const [tab, setTab] = useState<PageTab>(searchParams.get('tab') === 'requests' ? 'requests' : 'all')
  const [selected, setSelected] = useState<MergedRow | null>(null)

  // Returns portal Step 4e-A — customer return requests, owner and manager only (the API
  // answers 403 to a worker). Its own fetches; the exchange/refund feeds above are untouched.
  const role = getRoleFromToken()
  const canSeeRequests = role === 'owner' || role === 'manager'
  const [newRequests, setNewRequests] = useState(0)
  // Step 4d-2 (R6): "{n} awaiting refund" beside the "{n} new" badge.
  const [awaitingRefund, setAwaitingRefund] = useState(0)
  const linkedParcel = linkedRequest ? searchParams.get('parcel') : null

  const loadNewRequests = useCallback(async () => {
    if (!canSeeRequests) return
    try {
      const [fresh, refunds] = await Promise.all([
        getReturnRequests(0, 1, 'requested'),
        getReturnRequests(0, 1, 'refund_pending'),
      ])
      setNewRequests(fresh.total)
      setAwaitingRefund(refunds.total)
    } catch {
      // The badges are a hint only — the tab itself shows its own load error.
    }
  }, [canSeeRequests])

  useEffect(() => { loadNewRequests() }, [loadNewRequests])

  const load = useCallback(async () => {
    setLoading(true)
    setError(false)
    try {
      const [exchanges, refunds] = await Promise.all([getExchanges(), getRefunds()])
      // Both feeds already come back newest-first (created_at DESC, id DESC) — no
      // re-sort needed; the two lists are simply concatenated exchanges-then-refunds,
      // each internally still newest-first. A true merged chronological order would
      // need a shared timestamp on both rows, which the refund feed doesn't expose
      // (see normalize.ts's matchedAt comment) — not fabricated here either.
      setRows([...exchanges.map(normalizeExchange), ...refunds.map(normalizeRefund)])
    } catch {
      setError(true)
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { load() }, [load])

  const counts = useMemo(() => ({
    all: rows.length,
    needsAction: rows.filter(r => matchesFilter(r, 'needsAction')).length,
    refunds: rows.filter(r => matchesFilter(r, 'refunds')).length,
    exchanges: rows.filter(r => matchesFilter(r, 'exchanges')).length,
    inTransit: rows.filter(r => matchesFilter(r, 'inTransit')).length,
    received: rows.filter(r => matchesFilter(r, 'received')).length,
  }), [rows])

  const filteredRows = useMemo(
    () => (tab === 'requests' ? [] : rows.filter(r => matchesFilter(r, tab))),
    [rows, tab],
  )

  const columns: DataTableColumn<MergedRow>[] = [
    {
      key: 'type', header: t('exchangesRefunds.columns.type'),
      render: row => (
        <Badge
          tone={row.kind === 'exchange' ? 'info' : 'neutral'}
          label={row.kind === 'exchange' ? t('exchange.badge') : t('exchangesRefunds.type.refund')}
        />
      ),
    },
    {
      key: 'customer', header: t('exchangesRefunds.columns.customer'),
      render: row => (
        <div className="min-w-0">
          <p className="text-body font-medium text-primary truncate">{row.customerName ?? '—'}</p>
          {row.customerPhone && <p className="text-caption font-mono text-muted">{row.customerPhone}</p>}
        </div>
      ),
    },
    {
      key: 'tracking', header: t('exchangesRefunds.columns.tracking'), mono: true,
      render: row => row.trackingNumber,
    },
    {
      key: 'mappedOrder', header: t('exchangesRefunds.columns.mappedOrder'),
      render: row => row.kind === 'refund'
        ? <span className="text-primary">{row.orderNumber}</span>
        : row.mappedOrderId
          ? <span className="font-mono text-primary">{shortId(row.mappedOrderId)}</span>
          : <span className="text-muted">{t('exchangesRefunds.noMappedOrder')}</span>,
    },
    {
      // Step 4-close Part 2: a refund row's STATUS renders from inspectionState (the
      // piece-disposition facet — In transit / Needs inspection / Resolved), not the raw
      // courier leg_status badge — deriveLegStatus stays the single display-status path
      // for the courier state itself, this is an additive facet layered on top of it
      // (still readable via row.legStatus if ever needed elsewhere).
      key: 'status', header: t('exchangesRefunds.columns.status'),
      render: row => row.kind === 'refund'
        ? (row.inspectionState
            ? (
              <span className="inline-flex flex-col items-start gap-1" data-testid="refund-inspection-badge">
                <Badge
                  tone={inspectionStateTone(row.inspectionState)}
                  label={t(`exchangesRefunds.inspectionState.${row.inspectionState}`)}
                />
                {row.inspectionState === 'received_untracked' && row.awaitingReceiving && (
                  <span className="text-caption text-muted" data-testid="refund-awaiting-receiving">
                    {t('exchangesRefunds.awaitingReceiving')}
                  </span>
                )}
              </span>
            )
            : <span className="text-muted">—</span>)
        : (
          <span className="inline-flex items-center gap-1.5" data-testid="exchange-status-badge">
            <Badge
              tone={exchangeStatusTone(row.exchangeStatus ?? '')}
              label={t(`exchangesRefunds.status.${row.exchangeStatus}`, { defaultValue: (row.exchangeStatus ?? '').replace(/_/g, ' ') })}
            />
            {row.autoMatched && (
              <span data-testid="exchange-auto-matched-row-badge">
                <Badge tone="warning" label={t('exchangesRefunds.autoMatchedRowBadge')} />
              </span>
            )}
          </span>
        ),
    },
    {
      key: 'lastUpdate', header: t('exchangesRefunds.columns.lastUpdate'),
      render: row => row.matchedAt ? fmtDate(row.matchedAt) : <span className="text-muted">—</span>,
    },
  ]

  const tabs: Array<{ key: PageTab; label: string; count?: number; badge?: ReactNode }> = [
    { key: 'all', label: t('exchangesRefunds.tabs.all'), count: counts.all },
    ...(canSeeRequests ? [{
      key: 'requests' as const,
      label: t('exchangesRefunds.tabs.requests'),
      badge: newRequests > 0 || awaitingRefund > 0
        ? (
          <>
            {newRequests > 0 && (
              <span data-testid="requests-new-badge">
                <Badge tone="warning" label={t('exchangesRefunds.requests.newBadge', { count: newRequests })} />
              </span>
            )}
            {awaitingRefund > 0 && (
              <span data-testid="requests-refund-badge">
                <Badge tone="warning" label={t('exchangesRefunds.requests.awaitingRefundBadge', { count: awaitingRefund })} />
              </span>
            )}
          </>
        )
        : undefined,
    }] : []),
    { key: 'needsAction', label: t('exchangesRefunds.tabs.needsAction'), count: counts.needsAction },
    { key: 'refunds', label: t('exchangesRefunds.tabs.refunds'), count: counts.refunds },
    { key: 'exchanges', label: t('exchangesRefunds.tabs.exchanges'), count: counts.exchanges },
    { key: 'inTransit', label: t('exchangesRefunds.tabs.inTransit'), count: counts.inTransit },
    { key: 'received', label: t('exchangesRefunds.tabs.received'), count: counts.received },
  ]

  return (
    <>
      <div className="space-y-4" data-testid="exchanges-refunds-page">
        <h1 className="text-h1 text-primary">{t('exchangesRefunds.title')}</h1>

        {!loading && !error && tab !== 'requests' && (
          <div className="grid grid-cols-2 sm:grid-cols-4 gap-3" data-testid="summary-chips">
            <StatCard label={t('exchangesRefunds.chips.total')} value={counts.all} tone="neutral" />
            <StatCard label={t('exchangesRefunds.chips.needsAction')} value={counts.needsAction} tone="warning" />
            <StatCard label={t('exchangesRefunds.chips.inTransit')} value={counts.inTransit} tone="info" />
            <StatCard label={t('exchangesRefunds.chips.received')} value={counts.received} tone="success" />
          </div>
        )}

        <Tabs tabs={tabs} activeKey={tab} onChange={key => setTab(key as PageTab)} />

        {tab === 'requests' ? (
          <RequestsPanel onDecided={loadNewRequests} initialRequestId={linkedRequest} initialParcelId={linkedParcel} />
        ) : error ? (
          <div className="card p-10 flex flex-col items-center gap-3 text-center" data-testid="load-error">
            <p className="text-body font-semibold text-primary">{t('exchangesRefunds.errorTitle')}</p>
            <button className="btn-outline" onClick={load}>{t('exchangesRefunds.retry')}</button>
          </div>
        ) : (
          <div className="card overflow-hidden">
            <DataTable
              columns={columns}
              rows={filteredRows}
              loading={loading}
              emptyMessage={t('exchangesRefunds.emptyTitle')}
              onRowClick={setSelected}
            />
          </div>
        )}
      </div>

      <ExchangeRefundDrawer
        row={selected}
        onClose={() => setSelected(null)}
        onChanged={load}
      />
    </>
  )
}

function shortId(id: string): string {
  return id.replace(/-/g, '').slice(0, 8).toUpperCase()
}

function fmtDate(iso: string): string {
  return new Date(iso).toLocaleString(undefined, { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' })
}
