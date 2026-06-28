import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { Provider as AppBridgeProvider } from '@shopify/app-bridge-react'
import { AppProvider as PolarisProvider } from '@shopify/polaris'
import enTranslations from '@shopify/polaris/locales/en.json'
import '@shopify/polaris/build/esm/styles.css'
import EmbeddedApp from './EmbeddedApp'

// apiKey = Shopify app client ID (injected at build time from VITE_SHOPIFY_API_KEY).
const apiKey = import.meta.env.VITE_SHOPIFY_API_KEY as string ?? ''
// host = base64(shopOrigin) — Shopify passes this as a URL query param on every load.
const host = new URLSearchParams(window.location.search).get('host') ?? ''

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <AppBridgeProvider config={{ apiKey, host, forceRedirect: false }}>
      <PolarisProvider i18n={enTranslations}>
        <EmbeddedApp />
      </PolarisProvider>
    </AppBridgeProvider>
  </StrictMode>,
)
