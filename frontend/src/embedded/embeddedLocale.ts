/**
 * Build D: the embedded app's language. Shopify loads the app URL with a `locale` query parameter
 * (the admin user's language, e.g. "ar", "en", "en-US"); Arabic → 'ar', anything else → 'en'.
 */
export type EmbeddedLang = 'en' | 'ar'

export function embeddedLang(search: string): EmbeddedLang {
  const locale = new URLSearchParams(search).get('locale')?.trim().toLowerCase() ?? ''
  return locale === 'ar' || locale.startsWith('ar-') || locale.startsWith('ar_') ? 'ar' : 'en'
}

/** Sets <html lang dir> for the chosen language. */
export function applyLang(lang: EmbeddedLang) {
  document.documentElement.lang = lang
  document.documentElement.dir  = lang === 'ar' ? 'rtl' : 'ltr'
}
