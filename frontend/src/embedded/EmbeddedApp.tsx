/**
 * Traced embedded Shopify dashboard — READ-ONLY.
 * No mutating controls anywhere. All actionable operations deep-link out to
 * https://app.tracedtech.com (opens in a new tab, breaking out of the iframe).
 *
 * Data flows: four parallel GET calls via authenticatedFetch (App Bridge session
 * token → Bearer → ShopifySessionTokenFilter). Each section manages its own
 * loading/error state so the page populates progressively.
 */
import { useState, useEffect, useCallback } from 'react'
import {
  Page,
  Card,
  Text,
  Badge,
  Banner,
  SkeletonBodyText,
  BlockStack,
  InlineStack,
  InlineGrid,
  Link,
  Divider,
  Tabs,
  DataTable,
} from '@shopify/polaris'
import { notLinkedCopy } from './notLinkedCopy'
import { statusLabel, polarisTone, type DerivedTone } from './statusLabels'

// CDN App Bridge global — injected by the <script> in embedded.html before React mounts.
declare const shopify: { idToken(): Promise<string> }

const SaaS = 'https://app.tracedtech.com'

// ── Types ──────────────────────────────────────────────────────────────────

interface StoreRow {
  shop_domain: string
  status: string
  import_status: string | null
  last_sync_at: string | null
}

interface StatusCount { status: string; count: number }
interface InventorySummary { groupA: StatusCount[]; groupB: StatusCount[] }
interface DayCount { date: string; count: number }
interface ExceptionRow { type: string; severity: string; subjectKey: string }
interface ExceptionsData { count: number; exceptions: ExceptionRow[] }

// GET /api/v1/embedded/orders/funnel — mirrors OrderController.funnel()'s FunnelCounts shape.
interface FunnelCounts { newCount: number; picking: number; packed: number; courier: number; delivered: number }

// GET /api/v1/embedded/overview/late-to-pack — mirrors OverviewService.LateToPack.
interface LateToPack { overdue: number; over48: number }

// GET /api/v1/embedded/orders/list — one row per order, status facets pre-derived
// server-side by OrderStatusDeriver.derive(). Never re-derived client-side here.
interface EmbeddedOrderRow {
  id: string
  number: string
  isExchange: boolean
  notTraced: boolean
  customerName: string | null
  customerPhone: string | null
  codAmount: number | null
  placedAt: string | null
  primaryKey: string
  tone: DerivedTone
  fulfillmentKey: string
  fulfillmentTone: DerivedTone
}

type AsyncState<T> =
  | { status: 'loading' }
  | { status: 'ok'; data: T }
  | { status: 'err' }

const loading: AsyncState<never> = { status: 'loading' }

// ── Authenticated fetch ───────────────────────────────────────────────────

function useAuthFetch() {
  return useCallback(async (url: string, options?: RequestInit): Promise<Response> => {
    const token = await shopify.idToken()
    return fetch(url, {
      ...options,
      headers: { Authorization: `Bearer ${token}`, ...options?.headers },
    })
  }, [])
}

// ── Formatting helpers ────────────────────────────────────────────────────

function fmtLabel(s: string): string {
  return s.replace(/_/g, ' ').replace(/\b\w/g, c => c.toUpperCase())
}

/** "2026-01-15" → "Jan 15". Noon offset avoids UTC-midnight day shift. */
function fmtDay(iso: string): string {
  return new Date(iso + 'T12:00:00').toLocaleDateString('en', {
    month: 'short', day: 'numeric',
  })
}

function severityTone(
  sev: string,
): 'critical' | 'warning' | 'attention' | 'info' {
  switch (sev?.toUpperCase()) {
    case 'CRITICAL': return 'critical'
    case 'HIGH':     return 'warning'
    case 'MEDIUM':   return 'attention'
    default:         return 'info'
  }
}

const STATUS_LABELS: Record<string, string> = {
  available:                 'Available',
  reserved:                  'Reserved',
  packed:                    'Packed',
  awaiting_pickup:           'Awaiting Pickup',
  with_courier:              'With Courier',
  return_pending_inspection: 'Pending Inspection',
  delivered:                 'Delivered (30d)',
  damaged:                   'Damaged (30d)',
  lost:                      'Lost (30d)',
}

// ── Skeleton placeholder ──────────────────────────────────────────────────

function Skeleton({ lines = 4 }: { lines?: number }) {
  return <Card><SkeletonBodyText lines={lines} /></Card>
}

// ── Section: Connection Status ────────────────────────────────────────────

function ConnectionSection({ state }: { state: AsyncState<StoreRow[]> }) {
  if (state.status === 'loading') return <Skeleton lines={2} />

  if (state.status === 'err') {
    return (
      <Banner tone="critical" title="Could not load connection status">
        <Text as="p">
          Check that the Traced integration is configured for this store.{' '}
          <Link url={SaaS} external>Open Traced →</Link>
        </Text>
      </Banner>
    )
  }

  const connected = state.data.find(s => s.status === 'connected')
  const first = state.data[0]
  const needsReauth = !connected && first?.status === 'needs_reauth'

  if (!connected) {
    return (
      <Banner
        tone={needsReauth ? 'warning' : 'info'}
        title={
          needsReauth
            ? 'Store connection needs to be refreshed'
            : 'Connect this store to Traced'
        }
      >
        <BlockStack gap="300">
          <Text as="p">
            {needsReauth
              ? 'The access token for this store has expired. Reconnect in Traced to resume inventory tracking and fulfillment automation.'
              : 'This store is not yet linked to a Traced account. Set up your account in Traced, then return here to see live inventory and fulfilment data.'}
          </Text>
          <Link url={SaaS} external>Open Traced to connect →</Link>
        </BlockStack>
      </Banner>
    )
  }

  return (
    <Card>
      <InlineStack align="space-between" blockAlign="center">
        <BlockStack gap="100">
          <Text as="h2" variant="headingMd">Connection</Text>
          <Text as="p" variant="bodySm" tone="subdued">{connected.shop_domain}</Text>
          {connected.last_sync_at && (
            <Text as="p" variant="bodySm" tone="subdued">
              Last synced {new Date(connected.last_sync_at).toLocaleString()}
            </Text>
          )}
        </BlockStack>
        <InlineStack gap="200" blockAlign="center">
          {connected.import_status && connected.import_status !== 'idle' && (
            <Badge tone="attention">{fmtLabel(connected.import_status)}</Badge>
          )}
          <Badge tone="success">Connected</Badge>
        </InlineStack>
      </InlineStack>
    </Card>
  )
}

// ── Section: Inventory Summary ────────────────────────────────────────────

function MetricTile({ label, count }: { label: string; count: number }) {
  return (
    <Card>
      <BlockStack gap="100">
        <Text as="p" variant="bodySm" tone="subdued">{label}</Text>
        <Text as="p" variant="headingXl">{count.toLocaleString()}</Text>
      </BlockStack>
    </Card>
  )
}

function InventorySection({ state }: { state: AsyncState<InventorySummary> }) {
  if (state.status === 'loading') return <Skeleton lines={6} />
  if (state.status === 'err') {
    return (
      <Card>
        <Banner tone="critical" title="Could not load inventory summary" />
      </Card>
    )
  }

  const { groupA, groupB } = state.data

  return (
    <Card>
      <BlockStack gap="400">
        <Text as="h2" variant="headingMd">Inventory</Text>

        <InlineGrid columns={{ xs: 2, sm: 3 }} gap="300">
          {groupA.map(s => (
            <MetricTile
              key={s.status}
              label={STATUS_LABELS[s.status] ?? fmtLabel(s.status)}
              count={s.count}
            />
          ))}
        </InlineGrid>

        <Divider />

        <BlockStack gap="300">
          <Text as="p" variant="bodySm" tone="subdued">Last 30 days</Text>
          <InlineGrid columns={{ xs: 3, sm: 3 }} gap="300">
            {groupB.map(s => (
              <MetricTile
                key={s.status}
                label={STATUS_LABELS[s.status] ?? fmtLabel(s.status)}
                count={s.count}
              />
            ))}
          </InlineGrid>
        </BlockStack>
      </BlockStack>
    </Card>
  )
}

// ── Section: Order Activity ───────────────────────────────────────────────

function ActivitySection({ state }: { state: AsyncState<DayCount[]> }) {
  if (state.status === 'loading') return <Skeleton lines={8} />
  if (state.status === 'err') {
    return (
      <Card>
        <Banner tone="critical" title="Could not load order activity" />
      </Card>
    )
  }

  const days = state.data.slice(-14)
  const maxCount = Math.max(...days.map(d => d.count), 1)
  const allZero = days.every(d => d.count === 0)

  return (
    <Card>
      <BlockStack gap="400">
        <Text as="h2" variant="headingMd">Order Activity — last 14 days</Text>

        {allZero ? (
          <Text as="p" tone="subdued">No orders placed in this period.</Text>
        ) : (
          <BlockStack gap="200">
            {days.map(d => (
              <InlineStack key={d.date} blockAlign="center" gap="300">
                {/* Date label — fixed width for alignment */}
                <span style={{ width: 48, flexShrink: 0 }}>
                  <Text as="span" variant="bodySm" tone="subdued">{fmtDay(d.date)}</Text>
                </span>

                {/* Bar track */}
                <span style={{ flex: 1, height: 8, background: '#f1f2f3', borderRadius: 4, display: 'block' }}>
                  <span style={{
                    display: 'block',
                    width: `${(d.count / maxCount) * 100}%`,
                    height: '100%',
                    background: '#008060',
                    borderRadius: 4,
                    minWidth: d.count > 0 ? 4 : 0,
                    transition: 'width 0.3s ease',
                  }} />
                </span>

                {/* Count */}
                <span style={{ width: 28, textAlign: 'right', flexShrink: 0 }}>
                  <Text as="span" variant="bodySm">{d.count}</Text>
                </span>
              </InlineStack>
            ))}
          </BlockStack>
        )}
      </BlockStack>
    </Card>
  )
}

// ── Section: Open Exceptions ──────────────────────────────────────────────

function ExceptionsSection({ state }: { state: AsyncState<ExceptionsData> }) {
  if (state.status === 'loading') return <Skeleton lines={5} />
  if (state.status === 'err') {
    return (
      <Card>
        <Banner tone="critical" title="Could not load exceptions" />
      </Card>
    )
  }

  const { count, exceptions } = state.data

  return (
    <Card>
      <BlockStack gap="400">
        <InlineStack align="space-between" blockAlign="center">
          <Text as="h2" variant="headingMd">Open Exceptions</Text>
          {count > 0 && (
            <Badge tone={exceptions.some(e => e.severity === 'CRITICAL') ? 'critical' : 'warning'}>
              {count.toString()}
            </Badge>
          )}
        </InlineStack>

        {count === 0 ? (
          <Text as="p" tone="subdued">No open exceptions — all clear.</Text>
        ) : (
          <BlockStack gap="200">
            {exceptions.map((ex, i) => (
              <InlineStack key={i} align="space-between" blockAlign="center" wrap={false} gap="200">
                <InlineStack gap="200" blockAlign="center" wrap={false}>
                  <Badge tone={severityTone(ex.severity)}>{ex.severity}</Badge>
                  <Text as="span" variant="bodySm">{fmtLabel(ex.type)}</Text>
                </InlineStack>
                <InlineStack gap="200" blockAlign="center" wrap={false}>
                  {ex.subjectKey && (
                    <Text as="span" variant="bodySm" tone="subdued">{ex.subjectKey}</Text>
                  )}
                  {/* Deep-link to Traced exceptions page — no inline resolve action */}
                  <Link url={`${SaaS}/exceptions`} external>View</Link>
                </InlineStack>
              </InlineStack>
            ))}

            {count > exceptions.length && (
              <>
                <Divider />
                <Text as="p" variant="bodySm" tone="subdued">
                  Showing {exceptions.length} of {count}.{' '}
                  <Link url={`${SaaS}/exceptions`} external>View all in Traced →</Link>
                </Text>
              </>
            )}
          </BlockStack>
        )}
      </BlockStack>
    </Card>
  )
}

// ── Section: Order flow (funnel) ──────────────────────────────────────────
// Pure display — mirrors the standalone Overview's flow-strip node vocabulary
// (New/Picking/Packed/Courier/Delivered), today's orders only, no interaction.

function FlowStripSection({ state }: { state: AsyncState<FunnelCounts> }) {
  if (state.status === 'loading') return <Skeleton lines={3} />
  if (state.status === 'err') {
    return (
      <Card>
        <Banner tone="critical" title="Could not load order flow" />
      </Card>
    )
  }

  const { newCount, picking, packed, courier, delivered } = state.data
  const nodes: { label: string; value: number }[] = [
    { label: 'New',       value: newCount },
    { label: 'Picking',   value: picking },
    { label: 'Packed',    value: packed },
    { label: 'Courier',   value: courier },
    { label: 'Delivered', value: delivered },
  ]
  const allZero = nodes.every(n => n.value === 0)

  return (
    <Card>
      <BlockStack gap="300">
        <Text as="h2" variant="headingMd">Today's order flow</Text>
        {allZero ? (
          <Text as="p" tone="subdued">No orders placed yet today.</Text>
        ) : (
          <InlineStack gap="300" blockAlign="center" wrap>
            {nodes.map((n, i) => (
              <InlineStack key={n.label} gap="300" blockAlign="center">
                <BlockStack gap="050">
                  <Text as="p" variant="headingLg">{n.value.toLocaleString()}</Text>
                  <Text as="p" variant="bodySm" tone="subdued">{n.label}</Text>
                </BlockStack>
                {i < nodes.length - 1 && <Text as="span" tone="subdued">→</Text>}
              </InlineStack>
            ))}
          </InlineStack>
        )}
      </BlockStack>
    </Card>
  )
}

// ── Section: Late to pack ──────────────────────────────────────────────────
// Live state (not date-range scoped) — mirrors the standalone Overview's
// Late-to-pack card. Pure display.

function LateToPackSection({ state }: { state: AsyncState<LateToPack> }) {
  if (state.status === 'loading') return <Skeleton lines={2} />
  if (state.status === 'err') {
    return (
      <Card>
        <Banner tone="critical" title="Could not load late-to-pack" />
      </Card>
    )
  }

  const { overdue, over48 } = state.data
  const calm = overdue === 0

  return (
    <Card>
      <BlockStack gap="100">
        <Text as="p" variant="bodySm" tone="subdued">Late to pack</Text>
        <Text as="p" variant="headingXl">{overdue.toLocaleString()}</Text>
        {calm ? (
          <Text as="p" variant="bodySm" tone="subdued">All caught up</Text>
        ) : (
          <>
            <Text as="p" variant="bodySm" tone="subdued">as of now</Text>
            {over48 > 0 && (
              <Text as="p" variant="bodySm" fontWeight="semibold" tone="critical">
                {over48} over 48h
              </Text>
            )}
          </>
        )}
      </BlockStack>
    </Card>
  )
}

// ── Orders tab: read-only table ─────────────────────────────────────────────
// GET /orders/list — 50 most recent orders, no pagination/search/filters, no row
// click, no drawer. Every status badge renders a server-derived key straight
// through the local statusLabels map — no client-side re-derivation.

function OrdersTable({ state }: { state: AsyncState<EmbeddedOrderRow[]> }) {
  if (state.status === 'loading') return <Skeleton lines={8} />
  if (state.status === 'err') {
    return (
      <Card>
        <Banner tone="critical" title="Could not load orders" />
      </Card>
    )
  }

  const rows = state.data
  if (rows.length === 0) {
    return (
      <Card>
        <Text as="p" tone="subdued">No orders yet.</Text>
      </Card>
    )
  }

  return (
    <Card padding="0">
      <DataTable
        columnContentTypes={['text', 'text', 'text', 'text', 'numeric', 'text']}
        headings={['Order', 'Customer', 'Fulfillment', 'Delivery', 'Amount', 'Date']}
        rows={rows.map(o => [
          <InlineStack key="number" gap="150" blockAlign="center" wrap={false}>
            <Text as="span" fontWeight="medium">{o.number}</Text>
            {o.isExchange && <Badge tone="info">Exchange</Badge>}
            {o.notTraced && <Badge>Not Traced</Badge>}
          </InlineStack>,
          <BlockStack key="customer" gap="0">
            <Text as="span">{o.customerName ?? '—'}</Text>
            {o.customerPhone && (
              <Text as="span" variant="bodySm" tone="subdued">{o.customerPhone}</Text>
            )}
          </BlockStack>,
          <Badge key="fulfillment" tone={polarisTone(o.fulfillmentTone)}>
            {statusLabel(o.fulfillmentKey)}
          </Badge>,
          <Badge key="delivery" tone={polarisTone(o.tone)}>
            {statusLabel(o.primaryKey)}
          </Badge>,
          o.codAmount != null ? `${o.codAmount.toLocaleString()} EGP` : '—',
          o.placedAt ? new Date(o.placedAt).toLocaleDateString() : '—',
        ])}
      />
    </Card>
  )
}

// ── Section: Not linked to any Traced account ─────────────────────────────

/**
 * Option A (2026-09-04): a cold Shopify-side install no longer auto-provisions a tenant
 * (see ShopifyOAuthService.path2()). This renders instead of the dashboard whenever the
 * embedded token-exchange call reports NOT_PROVISIONED — a neutral empty state, not a
 * redirect and not a paywall. Deliberately carries no pricing/payment copy: billing lives
 * entirely off-platform at tracedtech.com and must never be presented as a gate here.
 *
 * `lang` defaults to 'en' and is never auto-detected — no locale signal exists anywhere in
 * the embedded surface (embedded.html hardcodes lang="en", no dir attribute, no locale meta
 * tag). 'ar' is reachable only by an explicit caller (tests, visual verification), matching
 * the instruction not to invent a new locale-detection mechanism.
 */
export function NotLinked({ lang = 'en' }: { lang?: 'en' | 'ar' }) {
  const copy = notLinkedCopy[lang]

  useEffect(() => {
    document.documentElement.dir  = lang === 'ar' ? 'rtl' : 'ltr'
    document.documentElement.lang = lang
  }, [lang])

  return (
    <Page title={copy.pageTitle}>
      <Card>
        <BlockStack gap="400">
          <Text as="h2" variant="headingMd">{copy.heading}</Text>
          <Text as="p">{copy.body}</Text>
          <BlockStack gap="200">
            <Text as="p">
              {copy.newAccount}{' '}
              <Link url="https://tracedtech.com" external>tracedtech.com</Link>
            </Text>
            <Text as="p">
              {copy.existingAccount}{' '}
              <Link url={SaaS} external>{copy.openTraced}</Link>
            </Text>
          </BlockStack>
        </BlockStack>
      </Card>
    </Page>
  )
}

// ── Root dashboard ────────────────────────────────────────────────────────

type LinkStatus = 'checking' | 'linked' | 'not_linked'

export default function EmbeddedApp() {
  const authFetch = useAuthFetch()

  const [linkStatus,  setLinkStatus]      = useState<LinkStatus>('checking')
  const [storesState, setStoresState]     = useState<AsyncState<StoreRow[]>>(loading)
  const [invState,    setInvState]        = useState<AsyncState<InventorySummary>>(loading)
  const [actState,    setActState]        = useState<AsyncState<DayCount[]>>(loading)
  const [excState,    setExcState]        = useState<AsyncState<ExceptionsData>>(loading)
  const [funnelState, setFunnelState]     = useState<AsyncState<FunnelCounts>>(loading)
  const [ltpState,    setLtpState]        = useState<AsyncState<LateToPack>>(loading)
  const [ordersState, setOrdersState]     = useState<AsyncState<EmbeddedOrderRow[]>>(loading)

  // In-page tab state only — NOT App Bridge navigation (no shopify.app / NavMenu use
  // anywhere in this bundle). Selection resets to Overview on remount; not persisted.
  const [selectedTab, setSelectedTab] = useState(0)
  const TABS = [
    { id: 'overview', content: 'Overview' },
    { id: 'orders',   content: 'Orders' },
  ]

  useEffect(() => {
    // Token exchange fires in parallel with the four data fetches below (same round-trip
    // cost as before — no added latency on the happy path). What changed (Option A,
    // 2026-09-04) is what happens on NOT_PROVISIONED: previously a top-level redirect into
    // the legacy install flow, which auto-provisioned a tenant with no human/payment step.
    // Now: render the NotLinked empty state in place of the dashboard. linkStatus starts
    // 'checking' so the four sections never mount (and never flash their error Banners)
    // until we know which case we're in — the four fetches still run underneath and
    // populate their state regardless, so once linkStatus resolves to 'linked' the
    // dashboard renders immediately with whatever those fetches have resolved to by then.
    authFetch('/api/v1/embedded/token-exchange', { method: 'POST' })
      .then(async r => {
        if (r.status === 401) {
          const body = await r.json().catch(() => ({})) as { error?: string }
          if (body?.error === 'NOT_PROVISIONED') {
            setLinkStatus('not_linked')
            return
          }
          // Other 401s (bad session token) → fall through to 'linked' below; the
          // data-fetch calls will also fail and show their error Banners as before.
        }
        // 204 (success/skip), 502, 503 → dashboard renders normally.
        setLinkStatus('linked')
      })
      .catch(() => setLinkStatus('linked')) // network error on token-exchange itself → don't block the dashboard

    // All four data requests fire in parallel — each section populates independently.
    authFetch('/api/v1/embedded/stores/status')
      .then(r => r.ok ? r.json() as Promise<StoreRow[]> : Promise.reject(r.status))
      .then(d  => setStoresState({ status: 'ok', data: d }))
      .catch(() => setStoresState({ status: 'err' }))

    authFetch('/api/v1/embedded/inventory/summary')
      .then(r => r.ok ? r.json() as Promise<InventorySummary> : Promise.reject(r.status))
      .then(d  => setInvState({ status: 'ok', data: d }))
      .catch(() => setInvState({ status: 'err' }))

    authFetch('/api/v1/embedded/orders/daily-counts?days=14')
      .then(r => r.ok ? r.json() as Promise<DayCount[]> : Promise.reject(r.status))
      .then(d  => setActState({ status: 'ok', data: d }))
      .catch(() => setActState({ status: 'err' }))

    authFetch('/api/v1/embedded/exceptions?limit=10')
      .then(r => r.ok ? r.json() as Promise<ExceptionsData> : Promise.reject(r.status))
      .then(d  => setExcState({ status: 'ok', data: d }))
      .catch(() => setExcState({ status: 'err' }))

    authFetch('/api/v1/embedded/orders/funnel')
      .then(r => r.ok ? r.json() as Promise<FunnelCounts> : Promise.reject(r.status))
      .then(d  => setFunnelState({ status: 'ok', data: d }))
      .catch(() => setFunnelState({ status: 'err' }))

    authFetch('/api/v1/embedded/overview/late-to-pack')
      .then(r => r.ok ? r.json() as Promise<LateToPack> : Promise.reject(r.status))
      .then(d  => setLtpState({ status: 'ok', data: d }))
      .catch(() => setLtpState({ status: 'err' }))

    authFetch('/api/v1/embedded/orders/list')
      .then(r => r.ok ? r.json() as Promise<EmbeddedOrderRow[]> : Promise.reject(r.status))
      .then(d  => setOrdersState({ status: 'ok', data: d }))
      .catch(() => setOrdersState({ status: 'err' }))
  }, [authFetch])

  // 'checking': render one shared skeleton, not four independent section skeletons — avoids
  // flashing four "Could not load..." Banners in the not-linked case (item 6). The four data
  // fetches above still fire and populate their own state regardless of linkStatus, so once
  // it resolves to 'linked' the dashboard below renders immediately with whatever they've
  // already resolved to — no added round-trip versus the pre-Option-A behavior.
  if (linkStatus === 'checking') {
    return (
      <Page title="Traced">
        <BlockStack gap="500">
          <Skeleton lines={2} />
          <Skeleton lines={6} />
        </BlockStack>
      </Page>
    )
  }

  if (linkStatus === 'not_linked') {
    return <NotLinked />
  }

  return (
    <Page
      title="Traced"
      subtitle="Inventory &amp; fulfilment overview — read-only"
      primaryAction={{
        content: 'Open Traced',
        url: SaaS,
        external: true,
      }}
    >
      <BlockStack gap="500">

        {/* In-page tabs (Polaris Tabs, plain React state) — NOT App Bridge navigation.
            No history/URL involvement; selecting a tab does not change the iframe route.
            Tabs is used WITHOUT its own `children`/Panel prop deliberately: Polaris's
            <Tabs> mounts one internal Panel per configured tab (see
            Tabs/components/Panel/Panel.js) and wraps the SAME children element in
            every one of them, CSS-hiding all but the selected index rather than
            omitting the others from the DOM — passing dynamic, per-tab content through
            that prop produces one correct panel and one inert-but-present duplicate.
            Rendering the tab bar and the single active panel separately below sidesteps
            that entirely — one real content block, not two. */}
        <Tabs tabs={TABS} selected={selectedTab} onSelect={setSelectedTab} />
        <div role="tabpanel" aria-labelledby={TABS[selectedTab].id} style={{ paddingTop: 16 }}>
          {selectedTab === 0 ? (
            <BlockStack gap="500">
              {/* 1 — Connection status (full width, always first) */}
              <ConnectionSection state={storesState} />

              {/* 2 — Inventory summary (full width) */}
              <InventorySection state={invState} />

              {/* 3 + 4 — Activity chart + Exceptions side by side on wider screens */}
              <InlineGrid columns={{ xs: 1, sm: 2 }} gap="400">
                <ActivitySection state={actState} />
                <ExceptionsSection state={excState} />
              </InlineGrid>

              {/* 5 + 6 — Order flow + Late-to-pack side by side on wider screens */}
              <InlineGrid columns={{ xs: 1, sm: 2 }} gap="400">
                <FlowStripSection state={funnelState} />
                <LateToPackSection state={ltpState} />
              </InlineGrid>
            </BlockStack>
          ) : (
            <OrdersTable state={ordersState} />
          )}
        </div>

        {/* Footer deep-link — reinforces that resolution happens in the SaaS, visible on
            both tabs since it applies to the whole read-only panel, not one view. */}
        <Card>
          <InlineStack align="space-between" blockAlign="center">
            <BlockStack gap="100">
              <Text as="p" variant="bodyMd" fontWeight="semibold">
                Manage inventory, resolve exceptions, and run fulfilment
              </Text>
              <Text as="p" variant="bodySm" tone="subdued">
                This panel is a read-only view. All actions happen in the Traced app.
              </Text>
            </BlockStack>
            {/* Opens in a new browser tab — correct escape from the Shopify iframe */}
            <a href={SaaS} target="_blank" rel="noopener noreferrer"
               style={{ textDecoration: 'none' }}>
              <span style={{
                display: 'inline-flex',
                alignItems: 'center',
                gap: 6,
                padding: '8px 16px',
                background: '#008060',
                color: '#fff',
                borderRadius: 6,
                fontSize: 14,
                fontWeight: 600,
                cursor: 'pointer',
                whiteSpace: 'nowrap',
              }}>
                Open Traced ↗
              </span>
            </a>
          </InlineStack>
        </Card>

      </BlockStack>
    </Page>
  )
}
