import { useMemo } from 'react'
import { useTranslation } from 'react-i18next'

// Number formatting for analytics, following the app's existing locale choice: Arabic text with
// Latin digits (ar-EG-u-nu-latn), English en-US grouping. Money is whole pounds:
// "EGP 1,234" in English, "1,234 ج.م" in Arabic. Rates arrive as 0–1 fractions and are shown as
// percentages to one decimal. A null figure is "—", never 0.

export const DASH = '—'

export function localeFor(lang: string): string {
  return lang === 'ar' ? 'ar-EG-u-nu-latn' : 'en-US'
}

export interface Fmt {
  lang: 'en' | 'ar'
  num: (n: number | null | undefined, digits?: number) => string
  money: (n: number | null | undefined) => string
  /** Compact money for chart labels and tight cells: EGP 12k, EGP 1.25M. */
  moneyK: (n: number | null | undefined) => string
  /** Axis ticks: 12k, 1.2M (no currency). */
  axis: (n: number) => string
  /** 0.8504 → "85.0%". */
  pct: (r: number | null | undefined, digits?: number) => string
  /** Percentage-point difference of two rates: "2.4 pts". */
  pts: (r: number) => string
  /** 'YYYY-MM-DD' → "5 Oct" / "5 أكتوبر". */
  day: (iso: string | null | undefined) => string
  /** 'YYYY-MM-DD' → "Mon 5 Oct". */
  dayLong: (iso: string | null | undefined) => string
}

export function makeFmt(lang: string): Fmt {
  const l: 'en' | 'ar' = lang === 'ar' ? 'ar' : 'en'
  const loc = localeFor(l)
  const nf = (digits: number) => new Intl.NumberFormat(loc, { minimumFractionDigits: digits, maximumFractionDigits: digits })
  const n0 = nf(0)
  const withCurrency = (s: string) => (l === 'ar' ? `${s} ج.م` : `EGP ${s}`)
  const num = (n: number | null | undefined, digits = 0) => (n == null || Number.isNaN(n) ? DASH : nf(digits).format(n))
  const money = (n: number | null | undefined) => {
    if (n == null || Number.isNaN(n)) return DASH
    const s = n0.format(Math.abs(Math.round(n)))
    return (n < 0 && Math.round(n) !== 0 ? '−' : '') + withCurrency(s)
  }
  const compact = (a: number) =>
    a >= 1e6 ? `${nf(2).format(a / 1e6)}M` : a >= 1e4 ? `${n0.format(Math.round(a / 1e3))}k` : n0.format(Math.round(a))
  const moneyK = (n: number | null | undefined) => {
    if (n == null || Number.isNaN(n)) return DASH
    return (n < 0 ? '−' : '') + withCurrency(compact(Math.abs(n)))
  }
  const axis = (n: number) => (n >= 1e6 ? `${nf(1).format(n / 1e6)}M` : n >= 1e3 ? `${n0.format(Math.round(n / 1e3))}k` : n0.format(Math.round(n)))
  const pct = (r: number | null | undefined, digits = 1) => (r == null || Number.isNaN(r) ? DASH : `${nf(digits).format(r * 100)}%`)
  const pts = (r: number) => `${nf(1).format(Math.abs(r * 100))} ${l === 'ar' ? 'نقطة' : 'pts'}`
  const dateFmt = new Intl.DateTimeFormat(loc, { day: 'numeric', month: 'short', timeZone: 'UTC' })
  const longFmt = new Intl.DateTimeFormat(loc, { weekday: 'short', day: 'numeric', month: 'short', timeZone: 'UTC' })
  const day = (iso: string | null | undefined) => (iso ? dateFmt.format(new Date(`${iso.slice(0, 10)}T00:00:00Z`)) : DASH)
  const dayLong = (iso: string | null | undefined) => (iso ? longFmt.format(new Date(`${iso.slice(0, 10)}T00:00:00Z`)) : DASH)
  return { lang: l, num, money, moneyK, axis, pct, pts, day, dayLong }
}

export function useFmt(): Fmt {
  const { i18n } = useTranslation()
  return useMemo(() => makeFmt(i18n.language), [i18n.language])
}

/** Relative change of a vs b, or null when there is no base to compare with. */
export function relChange(cur: number | null | undefined, prev: number | null | undefined): number | null {
  if (cur == null || prev == null || prev === 0) return null
  return (cur - prev) / Math.abs(prev)
}
