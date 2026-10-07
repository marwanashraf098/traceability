import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { AppProvider as PolarisProvider } from '@shopify/polaris'
import enTranslations from '@shopify/polaris/locales/en.json'
import '@shopify/polaris/build/esm/styles.css'
import EmbeddedApp from './EmbeddedApp'
import ErrorBoundary from '../components/ErrorBoundary'

// App Bridge is initialized by the CDN script in embedded.html <head> — no Provider needed.
// The CDN script reads <meta name="shopify-api-key"> and establishes the admin frame bridge
// before this module runs. React components call window.shopify.idToken() directly.
//
// No i18next here (reverted 2026-09-04): it added ~96.5KB gzip to the embedded bundle. The
// embedded screens carry their own EN/AR copy; the language comes from Shopify's `locale`
// parameter (embeddedLocale.ts, Build D). Polaris ships no Arabic locale, so its own built-in
// strings stay English.

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    {/* Outside PolarisProvider so the fallback still renders if Polaris itself throws. */}
    <ErrorBoundary>
      <PolarisProvider i18n={enTranslations}>
        <EmbeddedApp />
      </PolarisProvider>
    </ErrorBoundary>
  </StrictMode>,
)
