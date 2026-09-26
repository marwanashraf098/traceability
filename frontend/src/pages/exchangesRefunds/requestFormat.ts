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
