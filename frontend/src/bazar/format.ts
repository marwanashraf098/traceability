import type { TFunction } from 'i18next'
import { BazarError, type BazarSale } from './api'
import { formatMoney } from '../pages/exchangesRefunds/requestFormat'

export { formatMoney }

/** "18:42" — the sale's time in Cairo (Latin digits in both languages, like the rest of the app). */
export function saleTime(iso: string | null | undefined): string {
  if (!iso) return ''
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return ''
  return d.toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit', hour12: false, timeZone: 'Africa/Cairo' })
}

/** "20 Nov" — a YYYY-MM-DD date, no time-zone shift. */
export function shortDate(ymd: string | null | undefined, lang: string): string {
  if (!ymd) return ''
  const [y, m, d] = ymd.split('-').map(Number)
  if (!y || !m || !d) return ymd
  return new Date(Date.UTC(y, m - 1, d)).toLocaleDateString(lang === 'ar' ? 'ar-EG-u-nu-latn' : 'en-GB',
    { day: 'numeric', month: 'short', timeZone: 'UTC' })
}

/** Which day of the event a YYYY-MM-DD date is (1-based), and how many days it has. */
export function eventDay(startsOn: string, endsOn: string, today: string): { day: number; count: number } {
  const ms = (s: string) => Date.UTC(+s.slice(0, 4), +s.slice(5, 7) - 1, +s.slice(8, 10))
  return { day: Math.round((ms(today) - ms(startsOn)) / 86_400_000) + 1,
           count: Math.round((ms(endsOn) - ms(startsOn)) / 86_400_000) + 1 }
}

/** "Live · Day 2 of 3", or just "Live" when today is outside the event's dates. */
export function liveLabel(t: TFunction, day: number, count: number): string {
  return day >= 1 && day <= count ? t('bazar.status.liveDay', { day, count }) : t('bazar.status.live')
}

export function pieceTitle(s: Pick<BazarSale, 'product_title' | 'variant_title'>): string {
  return s.variant_title ? `${s.product_title} · ${s.variant_title}` : s.product_title
}

/** "TR-1A2B3C" — a short handle for the event's transfer (transfers have no number of their own). */
export function transferCode(transferId: string): string {
  return 'TR-' + transferId.replace(/-/g, '').slice(0, 6).toUpperCase()
}

export function toNumber(v: string | number | null | undefined): number {
  if (v == null || v === '') return 0
  const n = typeof v === 'number' ? v : Number(v)
  return Number.isFinite(n) ? n : 0
}

/**
 * The phone's sell errors — short, clear copy per B2 code (EN + AR in the locales); anything else shows the server's
 * own message. Already sold names the sale, its time and the seller.
 */
export function sellErrorText(t: TFunction, lang: string, e: BazarError): { title: string; detail?: string } {
  const d = e.details ?? {}
  switch (e.code) {
    case 'BAZAR_ALREADY_SOLD':
      // B5 scan-back: a piece sold at the bazar never comes back — the server's message, no sale details.
      if (!d.sale_number) return { title: t('bazar.sellError.alreadySold'), detail: e.message_(lang) }
      return {
        title: t('bazar.sellError.alreadySold'),
        detail: t('bazar.sellError.alreadySoldDetail', {
          number: String(d.sale_number ?? ''), time: saleTime(d.sold_at as string | undefined),
          seller: String(d.seller_name ?? ''),
        }),
      }
    case 'BAZAR_NO_PRICE':
      return { title: t('bazar.sellError.noPrice'), detail: t('bazar.sellError.noPriceDetail') }
    case 'BAZAR_PIECE_NOT_HERE':
      return { title: t('bazar.sellError.notHere'), detail: t('bazar.sellError.notHereDetail') }
    case 'BAZAR_PIECE_NOT_FOUND':
      return { title: t('bazar.sellError.notFound'), detail: t('bazar.sellError.notFoundDetail') }
    case 'BAZAR_PIECE_UNTAGGED':
      return { title: t('bazar.sellError.untagged'), detail: t('bazar.sellError.untaggedDetail') }
    case 'BAZAR_EVENT_NOT_LIVE':
      return { title: t('bazar.sellError.notLive'), detail: t('bazar.sellError.notLiveDetail') }
    case 'BAZAR_NOT_A_SELLER':
      return { title: t('bazar.sellError.notASeller') }
    case 'BAZAR_PAYMENT_METHOD_NOT_ALLOWED':
    case 'BAZAR_PAYMENT_METHOD_REQUIRED':
      return { title: t('bazar.sellError.method') }
    case 'BAZAR_EVENT_NOT_FOUND':
    case 'BAZAR_NOT_ENABLED':
      return { title: t('bazar.sellError.eventGone') }
    case 'BAZAR_UNDO_NOT_ALLOWED':
      // B6c: a cart line is undone / re-paid through its cart.
      if (d.receipt_id) {
        return { title: t('bazar.sellError.cartLine'),
                 detail: t('bazar.sellError.cartLineDetail', { number: String(d.sale_number ?? '') }) }
      }
      // A cart with a returned piece: the server says so.
      if (e.messageEn.includes('cart')) return { title: t('bazar.sellError.undoNotAllowed'), detail: e.message_(lang) }
      return { title: t('bazar.sellError.undoNotAllowed') }
    case 'BAZAR_SOLD_OUT':
      // B7a: no piece of the tapped size left at this bazar (another seller may have just sold the last one).
      return { title: t('bazar.sellError.soldOut'), detail: e.message_(lang) }
    case 'BAZAR_PRODUCT_NUMBER':
      return { title: t('bazar.sellError.productNumber'), detail: t('bazar.sellError.productNumberDetail') }
    case 'BAZAR_CART_DUPLICATE_PIECE':
      return { title: t('bazar.sellError.cartDuplicate') }
    case 'BAZAR_INVALID_DISCOUNT':
      return { title: t('bazar.sellError.invalidDiscount'), detail: t('bazar.sellError.invalidDiscountDetail') }
    case 'BAZAR_SALE_UNDONE':
      return { title: t('bazar.sellError.saleUndone') }
    case 'BAZAR_INVALID_PHONE':
      return { title: t('bazar.sellError.invalidPhone') }
    case 'BAZAR_NOT_SOLD_HERE':
      return { title: t('bazar.sellError.notSoldHere'), detail: t('bazar.sellError.notSoldHereDetail') }
    case 'BAZAR_ALREADY_RETURNED':
      return {
        title: t('bazar.sellError.alreadyReturned'),
        detail: d.sale_number ? t('bazar.sellError.alreadyReturnedDetail', {
          number: String(d.sale_number), name: String(d.returned_by_name ?? '') }) : undefined,
      }
    case 'BAZAR_PIECE_DAMAGED':
      return { title: t('bazar.sellError.pieceDamaged'), detail: t('bazar.sellError.pieceDamagedDetail') }
    case 'BAZAR_ALREADY_BACK':
      return { title: t('bazar.sellError.alreadyBack'), detail: t('bazar.sellError.alreadyBackDetail') }
    case 'BAZAR_PIECE_MARKED_LOST':
      return { title: t('bazar.sellError.markedLost') }
    case 'BAZAR_EVENT_ENDED':
      return { title: t('bazar.sellError.eventEnded'), detail: t('bazar.sellError.eventEndedDetail') }
    case 'BAZAR_EVENT_NOT_ENDED':
      return { title: t('bazar.sellError.notEnded') }
    case 'BAZAR_NOT_OUTSTANDING':
      return { title: t('bazar.sellError.notOutstanding') }
    case 'BAZAR_LEFTOVERS_OUTSTANDING':
      return { title: t('bazar.sellError.leftoversOut') }
    case 'BAZAR_DAY_CLOSED':
      return { title: t('bazar.sellError.dayClosed'), detail: t('bazar.sellError.dayClosedDetail') }
    case 'BAZAR_SAME_PIECE':
      return { title: t('bazar.sellError.samePiece') }
    case 'NETWORK':
      return { title: t('bazar.phone.networkError') }
    default:
      return { title: e.message_(lang) }
  }
}
