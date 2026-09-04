import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { AppProvider as PolarisProvider } from '@shopify/polaris'
import enTranslations from '@shopify/polaris/locales/en.json'
import '@shopify/polaris/build/esm/styles.css'
import EmbeddedApp from './EmbeddedApp'

// App Bridge is initialized by the CDN script in embedded.html <head> — no Provider needed.
// The CDN script reads <meta name="shopify-api-key"> and establishes the admin frame bridge
// before this module runs. React components call window.shopify.idToken() directly.
//
// No i18next here (reverted 2026-09-04 — see notLinkedCopy.ts): no locale signal exists
// anywhere in the embedded surface (embedded.html hardcodes lang="en", no dir attribute),
// so there is nothing to detect. NotLinked's dir/lang handling lives locally in
// EmbeddedApp.tsx's own component now, scoped to when it actually renders.

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <PolarisProvider i18n={enTranslations}>
      <EmbeddedApp />
    </PolarisProvider>
  </StrictMode>,
)
