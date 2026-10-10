/**
 * Returns portal P2 — the refund method step's rules, mirroring the backend's RefundDetails (which
 * re-checks everything). Egyptian mobile 01[0125] + 8 digits (+20 / 0020 / spaces normalised);
 * IBAN "EG" + 27 digits with mod-97 = 1, or a plain account number of 6–20 digits; InstaPay
 * name@instapay or a mobile; names 1–100 characters.
 */

export const REFUND_METHODS = ['bank_transfer', 'instapay', 'wallet', 'cash'] as const
export type RefundMethod = typeof REFUND_METHODS[number]
export const WALLET_PROVIDERS = ['vodafone_cash', 'orange_cash', 'e_and_cash', 'we_pay'] as const
export type WalletProvider = typeof WALLET_PROVIDERS[number]
const NAME_MAX = 100

export interface RefundFields {
  holderName: string
  bankName: string
  account: string
  instapay: string
  provider: WalletProvider | ''
  walletNumber: string
}

export const EMPTY_REFUND_FIELDS: RefundFields = {
  holderName: '', bankName: '', account: '', instapay: '', provider: '', walletNumber: '',
}

export type RefundFieldError =
  | 'holderMissing' | 'holderLong' | 'bankMissing' | 'bankLong'
  | 'accountMissing' | 'ibanInvalid' | 'accountInvalid'
  | 'instapayMissing' | 'instapayInvalid'
  | 'providerMissing' | 'walletMissing' | 'walletInvalid'

export function isRefundMethod(v: unknown): v is RefundMethod {
  return typeof v === 'string' && (REFUND_METHODS as readonly string[]).includes(v)
}

/** "01012345678" from 010 1234 5678, +20 10 1234 5678, 0020…; null when not an Egyptian mobile. */
export function normalizeMobile(raw: string): string | null {
  let s = raw.replace(/[\s\-.()]/g, '')
  if (s.startsWith('+20')) s = '0' + s.slice(3)
  else if (s.startsWith('0020')) s = '0' + s.slice(4)
  return /^01[0125]\d{8}$/.test(s) ? s : null
}

export function validEgyptianIban(raw: string): boolean {
  const s = raw.replace(/\s/g, '').toUpperCase()
  if (!/^EG\d{27}$/.test(s)) return false
  const rearranged = s.slice(4) + s.slice(0, 4)
  const digits = rearranged.replace(/[A-Z]/g, c => String(c.charCodeAt(0) - 55))
  return BigInt(digits) % 97n === 1n
}

/** Field errors for the chosen method (empty = valid). */
export function refundErrors(method: RefundMethod, f: RefundFields): Partial<Record<keyof RefundFields, RefundFieldError>> {
  const e: Partial<Record<keyof RefundFields, RefundFieldError>> = {}
  if (method === 'bank_transfer') {
    const holder = f.holderName.trim(), bank = f.bankName.trim()
    if (!holder) e.holderName = 'holderMissing'
    else if (holder.length > NAME_MAX) e.holderName = 'holderLong'
    if (!bank) e.bankName = 'bankMissing'
    else if (bank.length > NAME_MAX) e.bankName = 'bankLong'
    const acc = f.account.replace(/\s/g, '').toUpperCase()
    if (!acc) e.account = 'accountMissing'
    else if (acc.startsWith('EG')) { if (!validEgyptianIban(acc)) e.account = 'ibanInvalid' }
    else if (!/^\d{6,20}$/.test(acc)) e.account = 'accountInvalid'
  } else if (method === 'instapay') {
    const v = f.instapay.trim()
    if (!v) e.instapay = 'instapayMissing'
    else if (!/^[a-z0-9._-]{1,64}@instapay$/.test(v.toLowerCase()) && !normalizeMobile(v)) e.instapay = 'instapayInvalid'
  } else if (method === 'wallet') {
    if (!f.provider) e.provider = 'providerMissing'
    if (!f.walletNumber.trim()) e.walletNumber = 'walletMissing'
    else if (!normalizeMobile(f.walletNumber)) e.walletNumber = 'walletInvalid'
  }
  return e
}

/** The submit body's refundDetails for this method (cash: none). */
export function refundDetailsBody(method: RefundMethod, f: RefundFields): Record<string, string> | undefined {
  switch (method) {
    case 'bank_transfer': return { holderName: f.holderName.trim(), bankName: f.bankName.trim(), account: f.account.trim() }
    case 'instapay': return { instapay: f.instapay.trim() }
    case 'wallet': return { provider: f.provider, walletNumber: f.walletNumber.trim() }
    default: return undefined
  }
}

/** "••••4521" — the same rule as the backend's hint (P3 summary line). */
export function refundHint(method: RefundMethod, f: RefundFields): string | null {
  let v: string | null = null
  if (method === 'bank_transfer') v = f.account.replace(/\s/g, '').toUpperCase()
  else if (method === 'wallet') v = normalizeMobile(f.walletNumber)
  else if (method === 'instapay') {
    const s = f.instapay.trim().toLowerCase()
    v = s.endsWith('@instapay') ? s.slice(0, s.indexOf('@')) : normalizeMobile(s)
  }
  if (!v) return null
  return '••••' + (v.length <= 4 ? v : v.slice(-4))
}
