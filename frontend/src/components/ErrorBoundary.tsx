import { Component, type ErrorInfo, type ReactNode } from 'react'

/**
 * Root-level render-error fallback — a thrown render error must never leave a blank page.
 * Wraps BOTH the standalone app (src/main.tsx) and the embedded Shopify app
 * (src/embedded/main.tsx).
 *
 * Deliberately self-contained: no i18next, no Tailwind classes, no Polaris. The embedded
 * bundle loads neither i18next nor index.css, and a fallback that depends on the thing
 * that may have just failed is no fallback. Styling is inline, using the DS v1.0 token
 * values from tailwind.config.js (panel/line/primary/muted/brand, e1 shadow, rounded-2xl
 * card, rounded-xl button). Language follows <html lang> — the standalone app's i18n hook
 * keeps it in sync; the embedded surface is always "en".
 */

const COPY = {
  en: {
    title: 'Something went wrong',
    body: 'This page hit an unexpected error. Reloading usually fixes it.',
    reload: 'Reload',
  },
  ar: {
    title: 'حدث خطأ ما',
    body: 'واجهت هذه الصفحة خطأً غير متوقع. إعادة التحميل تحل المشكلة عادةً.',
    reload: 'إعادة التحميل',
  },
} as const

function currentLang(): 'en' | 'ar' {
  return typeof document !== 'undefined' && document.documentElement.lang === 'ar' ? 'ar' : 'en'
}

interface Props { children: ReactNode }
interface State { hasError: boolean }

export default class ErrorBoundary extends Component<Props, State> {
  state: State = { hasError: false }

  static getDerivedStateFromError(): State {
    return { hasError: true }
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error('[ErrorBoundary] render error', error, info.componentStack)
  }

  render() {
    if (!this.state.hasError) return this.props.children

    const lang = currentLang()
    const copy = COPY[lang]
    const rtl = lang === 'ar'

    return (
      <div
        role="alert"
        dir={rtl ? 'rtl' : 'ltr'}
        lang={lang}
        data-testid="error-boundary-fallback"
        style={{
          minHeight: '100vh',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          padding: 16,
          background: '#F7F8FA',
          fontFamily: rtl
            ? 'Cairo, sans-serif'
            : '"Geist Variable", Inter, system-ui, sans-serif',
        }}
      >
        <div
          style={{
            width: '100%',
            maxWidth: 400,
            background: '#FFFFFF',
            border: '1px solid #E5E7EB',
            borderRadius: 16,
            boxShadow: '0 1px 2px 0 rgba(16,24,40,0.06), 0 1px 3px 0 rgba(16,24,40,0.10)',
            padding: 24,
            display: 'flex',
            flexDirection: 'column',
            gap: 12,
          }}
        >
          <h1 style={{ margin: 0, fontSize: 20, lineHeight: '28px', fontWeight: 600, color: '#111827' }}>
            {copy.title}
          </h1>
          <p style={{ margin: 0, fontSize: 14, lineHeight: '20px', color: '#5B6675' }}>
            {copy.body}
          </p>
          <button
            type="button"
            onClick={() => window.location.reload()}
            style={{
              alignSelf: 'flex-start',
              marginTop: 4,
              padding: '8px 16px',
              border: 'none',
              borderRadius: 12,
              background: '#2563EB',
              color: '#FFFFFF',
              fontSize: 14,
              lineHeight: '20px',
              fontWeight: 600,
              fontFamily: 'inherit',
              cursor: 'pointer',
            }}
          >
            {copy.reload}
          </button>
        </div>
      </div>
    )
  }
}
