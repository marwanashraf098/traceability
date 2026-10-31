import { useEffect, useState, useCallback, useRef, useMemo } from 'react'
import { useTranslation } from 'react-i18next'
import { useSearchParams } from 'react-router-dom'
import { ChevronRight } from 'lucide-react'
import {
  listOrders, OrderPage, listShopifyStores, syncShopifyStore,
  getOrdersSummary, OrderSummaryCounts,
  listGenericCarriers, markGenericDelivered, type GenericCarrierCount,
  type OrderTab, type OrderReasonGroup, type OrderReturnState,
} from '../api'
import {
  Alert, Avatar, Badge, Button, Checkbox, DataTable, type DataTableColumn,
  EmptyState, LegStatusBadge, Modal, ShippingBadge, Tabs, TableSkeleton, cn,
} from '../components/ui'
import { useCapabilities } from '../capabilities'
import OrderDrawer from '../components/OrderDrawer'
import OrderFiltersPanel, {
  DEFAULT_FILTERS, toApiFilters, type OrderFilterValues,
} from '../components/OrderFiltersPanel'

const PAGE_SIZE = 20
// Every /orders request runs the alert check (Needs attention), so typed filters wait this long before fetching.
const SEARCH_DEBOUNCE_MS = 300

// Infer item type from the API's OrderPage shape
type OrderItem = NonNullable<OrderPage['items']>[number]

// Tabs (2026-10-31, Orders tabs & filters): exclusive shipping tabs + All / Needs attention, each sent as
// tab=<key> (OrderListFilters on the server — a tab's count and its list share one predicate). Counts come from the
// same /orders?withCounts=true request as the page. Needs attention also sends attention=true (the same
// server-side set; its pill falls back to /orders/summary's needsAttention when the page carries no counts).
const TAB_KEYS: OrderTab[] = ['all', 'attention', 'toShip', 'inTransit', 'delivered', 'returned', 'cancelled']
const TAB_LABEL: Record<OrderTab, string> = {
  all: 'orders.tabs.all', attention: 'orders.tabs.needsAttention', toShip: 'orders.tabs.toShip',
  inTransit: 'orders.tabs.inTransit', delivered: 'orders.tabs.delivered', returned: 'orders.tabs.returned',
  cancelled: 'orders.tabs.cancelled',
}
const REASON_GROUPS: OrderReasonGroup[] =
  ['cant_ship', 'no_courier_update', 'delivery_failed', 'cancelled_shipped', 'return_to_handle', 'other']

function oneOf<T extends string>(v: string | null, allowed: readonly T[]): T | null {
  return v != null && (allowed as readonly string[]).includes(v) ? (v as T) : null
}

/** Every list setting lives in the URL (back / reload / shared links restore the view). Defaults are absent. */
function readUrl(sp: URLSearchParams) {
  const tab = oneOf(sp.get('tab'), TAB_KEYS) ?? 'all'
  const filters: OrderFilterValues = {
    traced:  oneOf(sp.get('traced'),  ['traced', 'not_traced'] as const) ?? '',
    returns: oneOf(sp.get('returns'), ['pending', 'inspected'] as const) ?? '',
    courier: oneOf(sp.get('courier'), ['bosta', 'other'] as const) ?? '',
    payment: oneOf(sp.get('payment'), ['cod', 'prepaid'] as const) ?? '',
    days:    oneOf(sp.get('days'),    ['7', '30', '90', 'all'] as const) ?? '30',
  }
  const reason = tab === 'attention' ? oneOf(sp.get('reason'), REASON_GROUPS) : null
  return { tab, filters, reason, q: sp.get('q') ?? '', tracking: sp.get('tracking') ?? '' }
}

/** The returnState line under the Delivery badge: "Pending inspection", or "Inspected · 2 restocked · 1 damaged". */
function returnLine(rs: OrderReturnState, t: (k: string, o?: Record<string, unknown>) => string): string {
  if (rs.state === 'pending') return t('orders.returnLine.pending')
  const kinds = (['restocked', 'damaged', 'backToGood', 'sellable'] as const).filter(k => (rs.grades[k] ?? 0) > 0)
  const mixed = kinds.length > 1
  const parts = kinds.map(k => {
    const count = rs.grades[k] ?? 0
    if (k === 'backToGood') return t(mixed ? 'orders.returnLine.backToGoodN' : 'orders.returnLine.backToGood', { count })
    if (k === 'sellable')   return t(mixed ? 'orders.returnLine.sellableN' : 'orders.returnLine.sellable', { count })
    return t(`orders.returnLine.${k}`, { count })
  })
  return [t('orders.returnLine.inspected'), ...parts].join(' · ')
}

export default function Orders() {
  const { t } = useTranslation()
  const [data,     setData]     = useState<OrderPage | null>(null)
  // ?order=<id> opens that order's drawer (links from Analytics); closing removes it from the URL.
  const [searchParams, setSearchParams] = useSearchParams()
  const url = readUrl(searchParams)
  const { tab: urlTab, reason } = url
  const filters = url.filters
  const filtersKey = JSON.stringify(filters)
  // q / tracking follow the inputs; the list (and the URL, alongside) takes the debounced copies SEARCH_DEBOUNCE_MS
  // after the last key. The copies are state, not read back from the URL, so the request goes out on the very render
  // the timer fires (the router's own update comes a render later); the URL still restores them (back / reload).
  const [q,        setQ]        = useState(url.q)
  const [tracking, setTracking] = useState(url.tracking)
  const [searchQ,        setSearchQ]        = useState(url.q)
  const [searchTracking, setSearchTracking] = useState(url.tracking)
  // The list request in flight — aborted when a newer one starts, so an older response never overwrites a newer one.
  const inFlight = useRef<AbortController | null>(null)
  // The page belongs to one view: any other change of the URL view starts again at page 1 (no extra request).
  const viewKey = [urlTab, filtersKey, reason, searchQ, searchTracking].join('|')
  const [pageState, setPageState] = useState({ view: viewKey, page: 0 })
  const page = pageState.view === viewKey ? pageState.page : 0
  const setPage = (fn: (p: number) => number) => setPageState({ view: viewKey, page: fn(page) })
  const [loading,  setLoading]  = useState(false)
  const [error,    setError]    = useState('')
  const [syncing,  setSyncing]  = useState(false)
  const [syncMsg,  setSyncMsg]  = useState('')
  const [summary,  setSummary]  = useState<OrderSummaryCounts | null>(null)
  // Distinct orders with an open alert — from /orders/summary, the same set the tab lists.
  // Not the shell bell's alert count (one order can have several alerts; some alerts name no order).
  const needsAttention = summary?.needsAttention ?? null
  // Non-Bosta couriers G3 — "With <carrier>": generic orders of one carrier now with the courier, which an
  // owner / manager can select and mark delivered (the carrier has no API; Shopify may also say so).
  const { genericCourier } = useCapabilities()
  const [carriers, setCarriers]         = useState<GenericCarrierCount[]>([])
  const [carrierFilter, setCarrierFilter] = useState<string | null>(null)
  const [selected, setSelected]         = useState<Set<string>>(new Set())
  const [confirmMark, setConfirmMark]   = useState(false)
  const [marking, setMarking]           = useState(false)
  const [markError, setMarkError]       = useState(false)
  const [markMsg, setMarkMsg]           = useState('')
  const loadCarriers = useCallback(() => {
    if (!genericCourier) return
    listGenericCarriers().then(setCarriers).catch(() => {})
  }, [genericCourier])
  useEffect(() => { loadCarriers() }, [loadCarriers])
  const carrierView = carrierFilter != null
  const tab: OrderTab = carrierView ? 'inTransit' : urlTab
  const [drawerOrderId, setDrawerOrderId]   = useState<string | null>(() => searchParams.get('order'))
  const closeDrawer = useCallback(() => {
    setDrawerOrderId(null)
    if (searchParams.has('order')) {
      setSearchParams(prev => { const n = new URLSearchParams(prev); n.delete('order'); return n }, { replace: true })
    }
  }, [searchParams, setSearchParams])

  // Independent, non-blocking — a failure here must never affect the table below (no
  // shared error state), and there's simply no tile row while it's unset.
  useEffect(() => {
    getOrdersSummary().then(setSummary).catch(() => {})
  }, [])

  const fetchOrders = useCallback(async () => {
    inFlight.current?.abort()
    const ctrl = new AbortController()
    inFlight.current = ctrl
    setLoading(true)
    setError('')
    try {
      const res = await listOrders({
        // G3 "With <carrier>": the legacy carrier filter, inside In transit.
        deliveryState: carrierFilter != null ? 'with_courier' : undefined,
        carrier:  carrierFilter ?? undefined,
        tab,
        attention: tab === 'attention' ? true : undefined,
        reason:   reason ?? undefined,
        filters:  toApiFilters(JSON.parse(filtersKey) as OrderFilterValues),
        q:        searchQ || undefined,
        tracking: searchTracking || undefined,
        page,
        size: PAGE_SIZE,
        withCounts: true,
      }, { signal: ctrl.signal })
      if (ctrl.signal.aborted) return   // a newer request owns the table now
      setData(res)
    } catch {
      if (ctrl.signal.aborted) return   // aborted on purpose — not an error
      setError(t('common.error'))
    } finally {
      if (inFlight.current === ctrl) { inFlight.current = null; setLoading(false) }
    }
  }, [tab, reason, filtersKey, carrierFilter, searchQ, searchTracking, page, t])

  useEffect(() => { fetchOrders() }, [fetchOrders])
  useEffect(() => () => inFlight.current?.abort(), [])

  // Debounce the typed filters: the URL (replace, not a new history entry) follows SEARCH_DEBOUNCE_MS after the last
  // keystroke; the list follows the URL, back on page 1.
  useEffect(() => {
    if (q === searchQ && tracking === searchTracking) return
    const timer = setTimeout(() => {
      setSearchQ(q)
      setSearchTracking(tracking)
      setSearchParams(prev => {
        const n = new URLSearchParams(prev)
        if (q) n.set('q', q); else n.delete('q')
        if (tracking) n.set('tracking', tracking); else n.delete('tracking')
        return n
      }, { replace: true })
      setSelected(new Set())
    }, SEARCH_DEBOUNCE_MS)
    return () => clearTimeout(timer)
  }, [q, tracking, searchQ, searchTracking, setSearchParams])

  // Back / forward: the inputs and the search follow the URL (our own debounced write is already equal — no-op).
  const lastUrlSearch = useRef({ q: url.q, tracking: url.tracking })
  useEffect(() => {
    if (lastUrlSearch.current.q !== url.q) { setQ(url.q); setSearchQ(url.q) }
    if (lastUrlSearch.current.tracking !== url.tracking) { setTracking(url.tracking); setSearchTracking(url.tracking) }
    lastUrlSearch.current = { q: url.q, tracking: url.tracking }
  }, [url.q, url.tracking])

  /** Writes view settings to the URL (a new history entry, so Back restores the previous view). */
  const updateView = useCallback((mut: (n: URLSearchParams) => void) => {
    setSearchParams(prev => { const n = new URLSearchParams(prev); mut(n); return n })
    setSelected(new Set())
  }, [setSearchParams])
  function applyFilters(next: OrderFilterValues) {
    updateView(n => {
      for (const k of ['traced', 'returns', 'courier', 'payment', 'days'] as const) {
        if (next[k] === DEFAULT_FILTERS[k]) n.delete(k); else n.set(k, next[k])
      }
    })
  }
  function toggleReason(group: OrderReasonGroup) {
    updateView(n => { if (reason === group) n.delete('reason'); else n.set('reason', group) })
  }

  const handleSync = useCallback(async () => {
    setSyncing(true)
    setSyncMsg('')
    try {
      const stores = await listShopifyStores()
      if (stores.length === 0) { setSyncMsg(t('orders.syncNoStore')); return }
      await Promise.all(stores.map(s => syncShopifyStore(s.id)))
      setSyncMsg(t('orders.syncSuccess'))
      fetchOrders()
    } catch {
      setSyncMsg(t('orders.syncError'))
    } finally {
      setSyncing(false)
    }
  }, [fetchOrders, t])

  function applyFilter(fn: () => void) { fn(); setPageState({ view: viewKey, page: 0 }); setSelected(new Set()) }

  async function markDelivered() {
    setMarking(true)
    setMarkError(false)
    try {
      const results = await markGenericDelivered([...selected])
      const delivered = results.filter(r => r.outcome === 'DELIVERED').length
      const skipped = results.length - delivered
      setMarkMsg(t('orders.markDelivered.done', { count: delivered })
        + (skipped > 0 ? ' ' + t('orders.markDelivered.skipped', { count: skipped }) : ''))
      setConfirmMark(false)
      setSelected(new Set())
      fetchOrders()
      loadCarriers()
      getOrdersSummary().then(setSummary).catch(() => {})
    } catch {
      setMarkError(true)
    } finally {
      setMarking(false)
    }
  }

  const total = data?.total ?? 0
  const from  = total === 0 ? 0 : page * PAGE_SIZE + 1
  const to    = Math.min((page + 1) * PAGE_SIZE, total)
  const rows  = data?.items ?? []

  // ── Fulfillment / Delivery cells ─────────────────────────────────────────────
  // Two facets of the single OrderStatusDeriver output — no client-side re-derivation.
  // Fulfillment reads derivedStatus.fulfillmentKey/.fulfillmentTone (new additive deriver
  // fields); Delivery reads derivedStatus.primaryKey/.tone (the existing composite facet,
  // untouched). The only branch here is presentational, not a re-derivation: no shipment
  // linked (row.deliveryState null, the same signal the LATERAL join already leaves null)
  // renders the "Not shipped" placeholder instead of echoing the pipeline stage — approved
  // in Step 0 review. onHold is a separate order-level flag, rendered alongside Fulfillment.
  function renderFulfillment(order: OrderItem) {
    return (
      <div className="flex flex-wrap items-start gap-1.5">
        <LegStatusBadge legStatus={{ primaryKey: order.derivedStatus.fulfillmentKey, tone: order.derivedStatus.fulfillmentTone }} />
        {order.onHold && <Badge tone="critical" label={t('orderDetail.onHold')} />}
      </div>
    )
  }

  // Under the Delivery badge: the Needs attention reason (that tab only, amber, "+N" for more), then what came back.
  function renderDelivery(order: OrderItem) {
    const ag = tab === 'attention' ? order.attentionGroup : undefined
    const rs = order.returnState
    if (!ag && !rs) return renderDeliveryBadge(order)
    return (
      <div className="flex flex-col items-start gap-1">
        {renderDeliveryBadge(order)}
        {ag && (
          <span className="text-small text-warning-text" data-testid={`orders-reason-line-${order.number}`}>
            {t(`orders.reason.${ag.reason}`, { defaultValue: ag.reason.replace(/_/g, ' ') })}
            {ag.extra > 0 && <span className="ms-1">{t('orders.reasonMore', { count: ag.extra })}</span>}
          </span>
        )}
        {rs && (
          <span className={cn('text-small', rs.state === 'pending' ? 'text-warning-text' : 'text-muted')}
                data-testid={`orders-return-line-${order.number}`}>
            {returnLine(rs, t)}
          </span>
        )}
      </div>
    )
  }

  function renderDeliveryBadge(order: OrderItem) {
    if (!order.deliveryState) {
      // V139: the server-derived shipping badge (awaiting booking / not booked for n days /
      // shipped with {carrier} / Bosta tracking not linked / cancelled) when there is one.
      if (order.shippingBadge && order.shippingBadge.state !== 'linked') {
        return <ShippingBadge badge={order.shippingBadge} />
      }
      return <LegStatusBadge legStatus={{ primaryKey: 'orders.delivery.notShipped', tone: 'NEUTRAL' }} />
    }
    // Cell-level label override ONLY — status.label_created itself is untouched (the
    // drawer's Current-state pill and the Overview funnel both still read that shared key
    // and must keep saying "Label created", a state). This list cell is stating an event
    // ("an AWB now exists for this order"), so it reads differently on purpose.
    const labelOverride = order.derivedStatus.primaryKey === 'status.label_created'
      ? t('orders.delivery.awbCreated')
      : undefined
    // V169: a generic (non-Bosta courier) forward leg also names its carrier ("Mylerz").
    const generic = order.shippingBadge?.state === 'generic_carrier' ? order.shippingBadge : null
    const status = (
      <LegStatusBadge
        legStatus={{ primaryKey: order.derivedStatus.primaryKey, tone: order.derivedStatus.tone }}
        labelOverride={labelOverride}
        carrier={generic?.carrier}
      />
    )
    if (!generic) return status
    return (
      <div className="flex flex-wrap items-start gap-1.5">
        {status}
        <ShippingBadge badge={generic} />
      </div>
    )
  }

  // ── Column definitions ─────────────────────────────────────────────────────
  const selectable = carrierFilter != null
  const pageIds = rows.map(r => r.id)
  const allSelected = pageIds.length > 0 && pageIds.every(id => selected.has(id))
  const columns: DataTableColumn<OrderItem>[] = [
    ...(selectable ? [{
      key: 'select',
      header: '',
      render: (row: OrderItem) => (
        <span onClick={e => e.stopPropagation()} data-testid={`orders-select-${row.number}`}>
          <Checkbox checked={selected.has(row.id)}
            label={<span className="sr-only">{t('orders.markDelivered.select', { number: row.number })}</span>}
            onChange={c => setSelected(prev => {
              const n = new Set(prev)
              if (c) n.add(row.id); else n.delete(row.id)
              return n
            })} />
        </span>
      ),
    } as DataTableColumn<OrderItem>] : []),
    {
      key: 'number',
      header: t('orders.columns.number', { defaultValue: 'Order' }),
      mono: true,
      render: row => (
        // Order number — mono, opens the same drawer the row click does (the full
        // OrderDetail.tsx page is retired/unrouted). Exchanges stay inline with normal
        // orders (no filter, no row restyling, no separate section) — the badge is
        // the only differentiator, same component QueueView/PickScreen already use.
        // stopPropagation is defensive here (this button's own click already does
        // exactly what the row click does) — kept so a future divergence between the
        // two can't accidentally double-fire.
        <div className="flex items-center gap-1.5" onClick={e => e.stopPropagation()}>
          <button
            type="button"
            onClick={() => setDrawerOrderId(row.id)}
            className="text-trace-blue hover:text-trace-blue-hover font-medium transition-colors tabular-nums"
          >
            {row.number ?? t('common.na')}
          </button>
          {row.isExchange && <Badge tone="info" label={t('exchange.badge')} />}
          {/* Quiet marker for fulfilled-outside-Traced orders (derivedStatus.notTraced), beside the number like
              the Exchange tag — grey, so it never shouts over the normal orders in the list. */}
          {row.derivedStatus.notTraced && <Badge tone="neutral" className="whitespace-nowrap font-sans" label={t('orders.badge.notTraced')} />}
        </div>
      ),
    },
    {
      key: 'customer',
      header: t('orders.columns.customer', { defaultValue: 'Customer' }),
      render: row => (
        <div className="flex items-center gap-2.5">
          <Avatar name={row.customerName ?? ''} size="sm" showText={false} />
          <div>
            <div className="text-primary">{row.customerName ?? t('common.na')}</div>
            {row.customerPhone && (
              <div className="text-small text-muted mt-0.5">{row.customerPhone}</div>
            )}
          </div>
        </div>
      ),
    },
    {
      key: 'fulfillment',
      header: t('orders.columns.fulfillment'),
      render: renderFulfillment,
    },
    {
      key: 'delivery',
      header: t('orders.columns.delivery'),
      render: renderDelivery,
    },
    {
      key: 'cod',
      header: t('orders.columns.cod', { defaultValue: 'Amount' }),
      align: 'end',
      render: row => row.codAmount != null
        ? <span className="font-mono tabular-nums text-primary">{row.codAmount.toLocaleString()} EGP</span>
        : <span className="text-muted">{t('common.na')}</span>,
    },
    {
      key: 'placedAt',
      header: t('orders.columns.placedAt', { defaultValue: 'Date' }),
      align: 'end',
      render: row => (
        <span className="text-small text-muted tabular-nums">
          {row.placedAt ? new Date(row.placedAt).toLocaleDateString() : t('common.na')}
        </span>
      ),
    },
    {
      // Visual hint only — NOT a separate click target. The whole row already opens
      // the drawer (DataTable's onRowClick); this chevron just signals that. Hidden
      // until row hover (DataTable's <tr> carries `group`), muted, and flips for RTL
      // via the same rtl:rotate-180 convention used in Fulfill.tsx.
      key: 'chevron',
      header: '',
      align: 'end',
      render: () => (
        <ChevronRight
          size={16}
          strokeWidth={2}
          className="text-muted opacity-0 group-hover:opacity-100 transition-opacity rtl:rotate-180"
        />
      ),
    },
  ]

  function handleTabClick(key: string) {
    const next = oneOf(key, TAB_KEYS)
    if (!next) return
    setCarrierFilter(null)
    updateView(n => {
      if (next === 'all') n.delete('tab'); else n.set('tab', next)
      n.delete('reason')
    })
  }
  const counts = data?.counts
  const tabCount = (key: OrderTab): number | undefined => {
    if (key === 'attention') return counts?.attention ?? needsAttention ?? undefined
    return counts?.[key]
  }
  const reasonCounts = tab === 'attention' ? counts?.reasonCounts ?? null : null
  // days is ignored while searching and on Needs attention — the panel says so.
  const daysApplied = !searchQ && !searchTracking && tab !== 'attention'
  const filterValues = useMemo(() => JSON.parse(filtersKey) as OrderFilterValues, [filtersKey])

  return (
    <div className="space-y-4">
      {/* Header */}
      <div className="flex items-center justify-between">
        <h1 className="text-h1 text-primary">{t('orders.title')}</h1>
        <div className="flex items-center gap-3">
          {syncMsg && <span className="text-small text-muted">{syncMsg}</span>}
          <Button
            variant="secondary"
            size="sm"
            loading={syncing}
            onClick={handleSync}
          >
            {t('orders.sync')}
          </Button>
        </div>
      </div>

      {/* Subheader — total + needs-attention count, calm and independent of table state */}
      <p className="text-small text-muted -mt-2" data-testid="orders-subheader">
        {t('orders.totalCount', { count: summary?.total ?? 0 })}
        {!!needsAttention && (
          <>
            <span className="mx-2 text-muted/50">•</span>
            <button
              type="button"
              data-testid="orders-subheader-attention"
              onClick={() => handleTabClick('attention')}
              className="text-trace-blue hover:text-trace-blue-hover font-medium transition-colors"
            >
              {t('orders.needAttentionSubheader', { count: needsAttention })}
            </button>
          </>
        )}
      </p>

      {/* Tabs — counts from the list's own withCounts (see TAB_KEYS); the row scrolls sideways on phones. */}
      <Tabs
        scrollable
        activeKey={tab}
        onChange={handleTabClick}
        tabs={TAB_KEYS.map(key => ({
          key,
          label: t(TAB_LABEL[key]),
          count: tabCount(key),
          countTone: key === 'attention' ? 'warning' as const : undefined,
        }))}
      />

      {/* Filter bar — raw inputs keep .input class; Input component can't hold fixed width without wrapper */}
      <div className="flex flex-wrap items-center gap-2">
        <input
          type="text"
          placeholder={t('orders.search')}
          value={q}
          onChange={e => setQ(e.target.value)}
          className="input w-64"
        />
        <input
          type="text"
          placeholder={t('orders.searchTracking')}
          value={tracking}
          onChange={e => setTracking(e.target.value)}
          className="input w-40"
        />
        <OrderFiltersPanel value={filterValues} onApply={applyFilters} tab={tab} daysApplied={daysApplied} />
        {/* G3: "With <carrier>" — only for a store that ships with other couriers and has parcels out. */}
        {genericCourier && carriers.length > 0 && (
          <select
            data-testid="orders-carrier-filter"
            aria-label={t('orders.carrierFilter.label')}
            value={carrierFilter ?? '__all__'}
            onChange={e => applyFilter(() => {
              setCarrierFilter(e.target.value === '__all__' ? null : e.target.value)
            })}
            className="input w-56"
          >
            <option value="__all__">{t('orders.carrierFilter.all')}</option>
            {carriers.map(c => (
              <option key={c.carrier} value={c.carrier}>
                {t('orders.carrierFilter.option', { carrier: c.carrier || t('status.another_carrier'), count: c.withCourier })}
              </option>
            ))}
          </select>
        )}
      </div>

      {/* Needs attention: one chip per reason group with its count; a click filters (reason=), again clears. */}
      {reasonCounts && (
        <div role="group" aria-label={t('orders.reasonGroupsLabel')} className="flex flex-wrap gap-1.5"
             data-testid="orders-reason-chips">
          {REASON_GROUPS.map(g => {
            const on = reason === g
            return (
              <button key={g} type="button" aria-pressed={on} data-testid={`orders-reason-${g}`}
                      onClick={() => toggleReason(g)}
                      className={cn(
                        'px-3 py-1.5 rounded-full border text-small transition-colors whitespace-nowrap inline-flex items-center gap-1.5',
                        on ? 'bg-brand/[0.14] border-trace-blue/40 text-trace-blue font-medium'
                           : 'border-line text-muted hover:text-primary hover:bg-elevated'
                      )}>
                {t(`orders.reasonGroup.${g}`)}
                <span className="tabular-nums font-semibold">{(reasonCounts[g] ?? 0).toLocaleString()}</span>
              </button>
            )
          })}
        </div>
      )}

      {markMsg && <Alert tone="success" title={markMsg} />}
      {selectable && (
        <div className="flex flex-wrap items-center gap-3" data-testid="orders-mark-bar">
          <Checkbox checked={allSelected} indeterminate={selected.size > 0 && !allSelected}
            label={t('orders.markDelivered.selectAll')}
            onChange={c => setSelected(c ? new Set(pageIds) : new Set())} />
          <span className="text-small text-muted">{t('orders.markDelivered.selected', { count: selected.size })}</span>
          <Button size="sm" disabled={selected.size === 0} onClick={() => { setMarkError(false); setConfirmMark(true) }}>
            {t('orders.markDelivered.button')}
          </Button>
        </div>
      )}
      {confirmMark && (
        <Modal title={t('orders.markDelivered.confirmTitle')} onClose={() => !marking && setConfirmMark(false)}>
          <div className="space-y-4" data-testid="orders-mark-confirm">
            <p className="text-body text-muted">
              {t('orders.markDelivered.confirmBody', {
                count: selected.size, carrier: carrierFilter || t('status.another_carrier'),
              })}
            </p>
            {markError && <Alert tone="critical" title={t('orders.markDelivered.error')} />}
            <div className="flex flex-wrap gap-3">
              <Button loading={marking} onClick={markDelivered}>
                {t('orders.markDelivered.confirm', { count: selected.size })}
              </Button>
              <Button variant="secondary" disabled={marking} onClick={() => setConfirmMark(false)}>
                {t('orders.markDelivered.cancel')}
              </Button>
            </div>
          </div>
        </Modal>
      )}

      {/* Table — loading/empty/error/data handled here so EmptyState can carry an action */}
      <div className="card overflow-hidden">
        {error ? (
          <div className="p-4">
            <Alert tone="critical" title={error} />
          </div>
        ) : loading ? (
          <TableSkeleton rows={5} cols={columns.length} />
        ) : rows.length === 0 ? (
          <EmptyState
            message={t('orders.empty')}
            icon="📦"
            action={{ label: t('orders.sync'), onClick: handleSync }}
          />
        ) : (
          <DataTable columns={columns} rows={rows} onRowClick={row => setDrawerOrderId(row.id)} />
        )}
      </div>

      {/* Pagination */}
      {!loading && !error && total > 0 && (
        <div className="flex items-center justify-between text-small text-muted">
          <span>{t('orders.showing', { from, to, total })}</span>
          <div className="flex gap-2">
            <Button
              variant="secondary"
              size="sm"
              disabled={page === 0}
              onClick={() => setPage(p => p - 1)}
            >
              {t('orders.prev')}
            </Button>
            <Button
              variant="secondary"
              size="sm"
              disabled={to >= total}
              onClick={() => setPage(p => p + 1)}
            >
              {t('orders.next')}
            </Button>
          </div>
        </div>
      )}

      {/* Slide-in overlay, not a route — Layout stays outside this view-switch. */}
      <OrderDrawer orderId={drawerOrderId} onClose={closeDrawer} />
    </div>
  )
}
