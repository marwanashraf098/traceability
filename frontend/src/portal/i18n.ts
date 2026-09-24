import i18next, { type i18n as I18n } from 'i18next'
import { initReactI18next } from 'react-i18next'
import en from './locales/en.json'
import ar from './locales/ar.json'

/**
 * The portal's own i18n instance — portal-only strings, never the app's locale files or
 * src/i18n.ts. Language: the saved choice on this origin → else a browser language starting
 * with "ar" → Arabic → else English.
 */

export type PortalLang = 'en' | 'ar'
export const LANG_KEY = 'traced.portal.lang'

function readSaved(): PortalLang | null {
  try {
    const v = localStorage.getItem(LANG_KEY)
    return v === 'en' || v === 'ar' ? v : null
  } catch {
    return null
  }
}

export function detectLanguage(): PortalLang {
  const saved = readSaved()
  if (saved) return saved
  const langs = typeof navigator !== 'undefined'
    ? (navigator.languages?.length ? navigator.languages : [navigator.language])
    : []
  return langs.some(l => (l ?? '').toLowerCase().startsWith('ar')) ? 'ar' : 'en'
}

export function applyDocumentLanguage(lang: PortalLang): void {
  document.documentElement.lang = lang
  document.documentElement.dir = lang === 'ar' ? 'rtl' : 'ltr'
}

export function saveLanguage(lang: PortalLang): void {
  try { localStorage.setItem(LANG_KEY, lang) } catch { /* private mode — still switches for this visit */ }
}

export function createPortalI18n(lang: PortalLang = detectLanguage()): I18n {
  const instance = i18next.createInstance()
  instance.use(initReactI18next).init({
    resources: { en: { translation: en }, ar: { translation: ar } },
    lng: lang,
    fallbackLng: 'en',
    initAsync: false,
    interpolation: { escapeValue: false },
  })
  return instance
}
