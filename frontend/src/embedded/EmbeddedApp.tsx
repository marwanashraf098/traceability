import { useState, useEffect } from 'react'
import { useAuthenticatedFetch } from '@shopify/app-bridge-react'
import { Page, Card, Text, Spinner, Badge, BlockStack, InlineStack } from '@shopify/polaris'

interface StoreStatus {
  shop_domain: string
  status: string
  import_status: string | null
  last_sync_at: string | null
}

export default function EmbeddedApp() {
  const fetch = useAuthenticatedFetch()
  const [stores, setStores] = useState<StoreStatus[]>([])
  const [loadState, setLoadState] = useState<'loading' | 'ok' | 'error'>('loading')

  useEffect(() => {
    fetch('/api/v1/embedded/stores/status')
      .then(r => {
        if (!r.ok) throw new Error(`HTTP ${r.status}`)
        return r.json() as Promise<StoreStatus[]>
      })
      .then(data => { setStores(data); setLoadState('ok') })
      .catch(() => setLoadState('error'))
  }, [fetch])

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
