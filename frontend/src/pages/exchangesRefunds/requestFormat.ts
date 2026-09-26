import type { TFunction } from 'i18next'
import type { BadgeTone } from '../../components/ui'
import type { ReturnRequestStatus } from '../../api'

/** Returns portal Step 4e-A — shared formatting for the Requests tab and its drawer. */

/** M1 colours: Requested = amber, Approved = blue, Rejected = grey. */
const REQUEST_STATUS_TONE: Record<ReturnRequestStatus, BadgeTone> = {
  requested: 'warning',
  approved: 'info',
  rejected: 'neutral',
  pickup_booked: 'info',
  received: 'success',
  refund_pending: 'warning',
  refunded: 'success',
  cancelled: 'neutral',
  closed: 'neutral',
}

export function requestStatusTone(status: ReturnRequestStatus): BadgeTone {
  return REQUEST_STATUS_TONE[status] ?? 'neutral'
}

/** "Mariam Saleh" → "Mariam S." — first name + last initial; one word stays as is. */
export function shortCustomerName(name: string | null): string {
  if (!name || !name.trim()) return '—'
  const parts = name.trim().split(/\s+/)
  if (parts.length === 1) return parts[0]
  return `${parts[0]} ${Array.from(parts[parts.length - 1])[0]}.`
}

export function reasonLabel(t: TFunction, code: string): string {
  return t(`exchangesRefunds.requests.reasons.${code}`, { defaultValue: code.replace(/_/g, ' ') })
}

function sameDay(a: Date, b: Date): boolean {
  return a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate()
}

/** M1 "Sent": "Today, 2:14 PM" / "Yesterday" / "21 Sep". */
export function sentLabel(t: TFunction, locale: string, iso: string, now: Date = new Date()): string {
  const d = new Date(iso)
  if (sameDay(d, now)) {
    const time = d.toLocaleTimeString(locale, { hour: 'numeric', minute: '2-digit' })
    return t('exchangesRefunds.requests.sentToday', { time })
  }
  const yesterday = new Date(now)
  yesterday.setDate(now.getDate() - 1)
  if (sameDay(d, yesterday)) return t('exchangesRefunds.requests.sentYesterday')
  return shortDate(locale, iso)
}

export function shortDate(locale: string, iso: string): string {
  return new Date(iso).toLocaleDateString(locale, { day: 'numeric', month: 'short' })
}

// ── Step 4d-2 ─────────────────────────────────────────────────────────────────

/**
 * R3/R6 — "Partly received" is a DISPLAY label only: an approved / courier-booked request of
 * which some items already arrived. The stored status is unchanged.
 */
export type RequestDisplayStatus = ReturnRequestStatus | 'partly_received'

export function displayStatus(status: ReturnRequestStatus, arrivedCount: number): RequestDisplayStatus {
  return (status === 'approved' || status === 'pickup_booked') && arrivedCount > 0 ? 'partly_received' : status
}

export function displayStatusTone(status: RequestDisplayStatus): BadgeTone {
  return status === 'partly_received' ? 'info' : requestStatusTone(status)
}

/** "EGP 1,150" / "EGP 350.50" — whole amounts without decimals, Latin digits in both languages. */
export function formatMoney(amount: string | number | null | undefined, currency = 'EGP'): string {
  if (amount == null || amount === '') return ''
  const n = typeof amount === 'number' ? amount : Number(amount)
  if (!Number.isFinite(n)) return ''
  const whole = Math.abs(n % 1) < 0.005
  return `${currency} ${n.toLocaleString('en-US', {
    minimumFractionDigits: whole ? 0 : 2, maximumFractionDigits: whole ? 0 : 2,
  })}`
}

/** R2 history: "26 Sep, 10:05 AM". */
export function dateTimeLabel(locale: string, iso: string): string {
  const d = new Date(iso)
  return `${d.toLocaleDateString(locale, { day: 'numeric', month: 'short' })}, ${d.toLocaleTimeString(locale, { hour: 'numeric', minute: '2-digit' })}`
}

/** Today in the business time zone as YYYY-MM-DD (refund dates can't be later). */
export function todayIso(now: Date = new Date()): string {
  const parts = new Intl.DateTimeFormat('en-CA', { timeZone: 'Africa/Cairo', year: 'numeric', month: '2-digit', day: '2-digit' })
    .format(now)
  return parts
}
