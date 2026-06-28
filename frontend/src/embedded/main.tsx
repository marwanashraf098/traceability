import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { AppProvider as PolarisProvider } from '@shopify/polaris'
import enTranslations from '@shopify/polaris/locales/en.json'
import '@shopify/polaris/build/esm/styles.css'
import EmbeddedApp from './EmbeddedApp'

// App Bridge is initialized by the CDN script in embedded.html <head> — no Provider needed.
// The CDN script reads <meta name="shopify-api-key"> and establishes the admin frame bridge
// before this module runs. React components call window.shopify.idToken() directly.

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <PolarisProvider i18n={enTranslations}>
      <EmbeddedApp />
    </PolarisProvider>
  </StrictMode>,
)
