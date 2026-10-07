/**
 * Local EN/AR copy for the embedded Disconnected empty state — no dependency on the shared
 * i18next/locale tree (it added ~96.5KB gzip to the embedded bundle to translate a single card).
 * The language comes from Shopify's `locale` parameter (embeddedLocale.ts, Build D).
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
