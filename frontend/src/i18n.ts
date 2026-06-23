import i18n from 'i18next'
import { initReactI18next } from 'react-i18next'
import en from './locales/en.json'
import ar from './locales/ar.json'

i18n.use(initReactI18next).init({
  resources: { en: { translation: en }, ar: { translation: ar } },
  // Guard for SSR / test environments where localStorage may not exist.
  lng: (typeof localStorage !== 'undefined' ? localStorage.getItem('lang') : null) ?? 'en',
  fallbackLng: 'en',
  interpolation: { escapeValue: false },
})

// Single authoritative dir/lang hook — fires for the startup load (via init's
// internal changeLanguage) AND every subsequent toggleLang() call.
i18n.on('languageChanged', (lng: string) => {
  document.documentElement.dir  = lng === 'ar' ? 'rtl' : 'ltr'
  document.documentElement.lang = lng
})

export default i18n
