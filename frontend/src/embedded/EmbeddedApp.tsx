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
} from '@shopify/polaris'

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

type AsyncState<T> =
  | { status: 'loading' }
  | { status: 'ok'; data: T }
  | { status: 'err' }

const loading: AsyncState<never> = { status: 'loading' }

// ── Authenticated fetch ───────────────────────────────────────────────────

function useAuthFetch() {
  return useCallback(async (url: string): Promise<Response> => {
    const token = await shopify.idToken()
    return fetch(url, { headers: { Authorization: `Bearer ${token}` } })
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

// ── Root dashboard ────────────────────────────────────────────────────────

export default function EmbeddedApp() {
  const authFetch = useAuthFetch()

  const [storesState, setStoresState]     = useState<AsyncState<StoreRow[]>>(loading)
  const [invState,    setInvState]        = useState<AsyncState<InventorySummary>>(loading)
  const [actState,    setActState]        = useState<AsyncState<DayCount[]>>(loading)
  const [excState,    setExcState]        = useState<AsyncState<ExceptionsData>>(loading)

  useEffect(() => {
    // All four requests fire in parallel — each section populates independently.
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
  }, [authFetch])

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

        {/* 1 — Connection status (full width, always first) */}
        <ConnectionSection state={storesState} />

        {/* 2 — Inventory summary (full width) */}
        <InventorySection state={invState} />

        {/* 3 + 4 — Activity chart + Exceptions side by side on wider screens */}
        <InlineGrid columns={{ xs: 1, sm: 2 }} gap="400">
          <ActivitySection state={actState} />
          <ExceptionsSection state={excState} />
        </InlineGrid>

        {/* Footer deep-link — reinforces that resolution happens in the SaaS */}
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
