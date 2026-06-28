import { useState, useEffect, useCallback } from 'react'
import { Page, Card, Text, Spinner, Badge, BlockStack, InlineStack } from '@shopify/polaris'

// CDN App Bridge global — injected by app-bridge.js before this module runs.
// shopify.idToken() returns a signed Shopify session JWT that our
// ShopifySessionTokenFilter validates as Authorization: Bearer <token>.
declare const shopify: { idToken(): Promise<string> }

interface StoreStatus {
  shop_domain: string
  status: string
  import_status: string | null
  last_sync_at: string | null
}

export default function EmbeddedApp() {
  const [stores, setStores] = useState<StoreStatus[]>([])
  const [loadState, setLoadState] = useState<'loading' | 'ok' | 'error'>('loading')

  const authenticatedFetch = useCallback(async (url: string) => {
    const token = await shopify.idToken()
    return fetch(url, { headers: { Authorization: `Bearer ${token}` } })
  }, [])

  useEffect(() => {
    authenticatedFetch('/api/v1/embedded/stores/status')
      .then(r => {
        if (!r.ok) throw new Error(`HTTP ${r.status}`)
        return r.json() as Promise<StoreStatus[]>
      })
      .then(data => { setStores(data); setLoadState('ok') })
      .catch(() => setLoadState('error'))
  }, [authenticatedFetch])

  if (loadState === 'loading') {
    return (
      <Page>
        <Card>
          <InlineStack align="center">
            <Spinner accessibilityLabel="Loading" />
          </InlineStack>
        </Card>
      </Page>
    )
  }

  if (loadState === 'error') {
    return (
      <Page title="Traced">
        <Card>
          <Text as="p" tone="critical">
            Could not connect to Traced. Check that the integration is configured correctly.
          </Text>
        </Card>
      </Page>
    )
  }

  return (
    <Page title="Traced — connected">
      <Card>
        <BlockStack gap="300">
          <Text as="h2" variant="headingMd">Connected stores</Text>
          {stores.length === 0 && (
            <Text as="p" tone="subdued">No stores found.</Text>
          )}
          {stores.map(s => (
            <InlineStack key={s.shop_domain} align="space-between" blockAlign="center">
              <Text as="span">{s.shop_domain}</Text>
              <Badge tone={s.status === 'connected' ? 'success' : 'attention'}>
                {s.status}
              </Badge>
            </InlineStack>
          ))}
        </BlockStack>
      </Card>
    </Page>
  )
}
