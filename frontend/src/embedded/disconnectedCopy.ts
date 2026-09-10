/**
 * Local EN/AR copy for the embedded Disconnected empty state — same rationale as
 * notLinkedCopy.ts: no dependency on the shared i18next/locale tree (it added ~96.5KB
 * gzip to the embedded bundle to translate a single card).
 *
 * No signal in the embedded surface selects a language — embedded.html hardcodes
 * lang="en", no dir attribute, no locale meta tag, and PolarisProvider is hardcoded to
 * enTranslations. Disconnected therefore always renders 'en' in production; 'ar' exists
 * here for direct, explicit use (tests, visual verification) — never auto-detected.
 */
export interface DisconnectedCopy {
  pageTitle: string
  heading: string
  body: string
  reconnectPrompt: string
  openTraced: string
}

export const disconnectedCopy: Record<'en' | 'ar', DisconnectedCopy> = {
  en: {
    pageTitle: 'Traced',
    heading: 'This store is disconnected from Traced',
    body: 'Sync is paused — Traced is not reading or writing orders, inventory, or fulfilment for this store right now.',
    reconnectPrompt: 'To resume syncing, reconnect this store from Traced:',
    openTraced: 'Reconnect in Traced →',
  },
  ar: {
    pageTitle: 'Traced',
    heading: 'هذا المتجر غير متصل بـ Traced',
    body: 'المزامنة متوقفة مؤقتًا — لا يقوم Traced حاليًا بقراءة أو كتابة الطلبات أو المخزون أو التنفيذ لهذا المتجر.',
    reconnectPrompt: 'لاستئناف المزامنة، أعد الاتصال بهذا المتجر من Traced:',
    openTraced: 'إعادة الاتصال في Traced ←',
  },
}
