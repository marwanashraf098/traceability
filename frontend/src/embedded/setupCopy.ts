/**
 * Local EN/AR copy for the embedded "Finish setup in Traced" card (onboarding gate, V186) — like
 * disconnectedCopy.ts, no dependency on the shared i18next tree. The address is never collected here.
 */
export interface SetupCopy {
  title: string
  bodyShopify: string
  bodyAddress: string
  cta: string
}

export const setupCopy: Record<'en' | 'ar', SetupCopy> = {
  en: {
    title: 'Finish setup in Traced',
    bodyShopify: 'Your store is almost ready. Open Traced to finish connecting it.',
    bodyAddress: 'Your store is connected. Open Traced and add your main warehouse address to start using it.',
    cta: 'Finish setup in Traced',
  },
  ar: {
    title: 'أكمل الإعداد في Traced',
    bodyShopify: 'متجرك على وشك أن يكون جاهزًا. افتح Traced لإكمال ربطه.',
    bodyAddress: 'تم ربط متجرك. افتح Traced وأضف عنوان مخزنك الرئيسي لتبدأ في استخدامه.',
    cta: 'أكمل الإعداد في Traced',
  },
}
