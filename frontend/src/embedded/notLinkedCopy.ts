/**
 * Local EN/AR copy for the embedded NotLinked empty state — no dependency on the shared
 * i18next/locale tree (see EmbeddedApp.tsx's NotLinked component for why: the global i18n
 * system added ~96.5KB gzip to the embedded bundle to translate a single card).
 *
 * No signal in the embedded surface selects a language — confirmed at HEAD: embedded.html
 * hardcodes lang="en", no dir attribute, no locale meta tag, and PolarisProvider is hardcoded
 * to enTranslations. NotLinked therefore always renders 'en' in production; 'ar' exists here
 * for direct, explicit use (tests, visual verification) — never auto-detected.
 */
export interface NotLinkedCopy {
  pageTitle: string
  heading: string
  body: string
  newAccount: string
  existingAccount: string
  openTraced: string
}

export const notLinkedCopy: Record<'en' | 'ar', NotLinkedCopy> = {
  en: {
    pageTitle: 'Traced',
    heading: "This store isn't connected to Traced",
    body: "We couldn't find a Traced account linked to this store.",
    newAccount: 'New to Traced? Create your account at',
    existingAccount: 'Already have a Traced account? Open Traced and connect this store from Settings → Connections.',
    openTraced: 'Open Traced →',
  },
  ar: {
    pageTitle: 'Traced',
    heading: 'هذا المتجر غير مرتبط بحساب Traced',
    body: 'لم نتمكن من العثور على حساب Traced مرتبط بهذا المتجر.',
    newAccount: 'جديد على Traced؟ أنشئ حسابك على',
    existingAccount: 'لديك حساب Traced بالفعل؟ افتح Traced واربط هذا المتجر من الإعدادات ← الاتصالات.',
    openTraced: 'افتح Traced ←',
  },
}
