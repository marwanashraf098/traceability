import '@fontsource-variable/geist'
import '@fontsource/geist-mono'
import '@fontsource/cairo/300.css'
import '@fontsource/cairo/400.css'
import '@fontsource/cairo/500.css'
import '@fontsource/cairo/600.css'
import '@fontsource/cairo/700.css'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'
import './i18n'
import App from './App'
import ErrorBoundary from './components/ErrorBoundary'
import { listenForSessionChanges } from './session'

// Apply RTL direction from stored language preference
const lang = localStorage.getItem('lang') ?? 'en'
document.documentElement.dir  = lang === 'ar' ? 'rtl' : 'ltr'
document.documentElement.lang = lang

// B10: another tab changed this browser's session (a seller invite redeemed) → a signed-in tab starts over as that user.
listenForSessionChanges()

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <ErrorBoundary>
      <App />
    </ErrorBoundary>
  </StrictMode>,
)
