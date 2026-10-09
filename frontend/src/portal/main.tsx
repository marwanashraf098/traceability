import '@fontsource-variable/geist'
import '@fontsource/geist-mono/600.css'
// P1: the store's own font (Cairo by default) is loaded on demand by PortalApp (fonts.ts) —
// only the chosen family. Geist stays for the language switch's "English"; Geist Mono as today.
import './portal.css'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { I18nextProvider } from 'react-i18next'
import PortalApp, { slugFromPath } from './PortalApp'
import { applyDocumentLanguage, createPortalI18n, detectLanguage } from './i18n'

// Returns portal entry (portal.html → returns.tracedtech.com/{slug}). Deliberately imports
// nothing from the merchant app: no api.ts, no src/i18n.ts, no router, no index.css.
const lang = detectLanguage()
applyDocumentLanguage(lang)
const i18n = createPortalI18n(lang)

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <I18nextProvider i18n={i18n}>
      <PortalApp slug={slugFromPath(window.location.pathname)} />
    </I18nextProvider>
  </StrictMode>,
)
