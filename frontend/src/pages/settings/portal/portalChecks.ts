import { DEFAULT_BRAND, INK, palette } from '../../../portal/brand'

/**
 * Settings → Returns portal — two client-only checks shown under the Branding fields. Neither changes what is saved
 * or what the portal does; they only point out something the merchant probably wants to fix.
 */

const HEX = /^#[0-9A-Fa-f]{6}$/

/** A number of days written in the policy text: Latin or Arabic-Indic digits, then day / days / يوم / أيام. */
const DAYS = /([0-9٠-٩]+)[\s-]*(days?\b|يوم|أيام)/gi

function latinDigits(s: string): string {
  return s.replace(/[٠-٩]/g, d => String(d.charCodeAt(0) - 0x0660))
}

/**
 * The policy text names a number of days and none of them is the return window → the first such phrase, as the
 * merchant wrote it (e.g. "14 days", "١٤ يوم"). Null when the text names no days, one of them is the window, or the
 * window isn't a valid number yet.
 */
export function policyWindowMismatch(policy: string | null | undefined, windowDays: number | null): string | null {
  if (!policy || windowDays == null || !Number.isInteger(windowDays) || windowDays < 1) return null
  const found = [...policy.matchAll(DAYS)]
  if (found.length === 0) return null
  if (found.some(m => Number(latinDigits(m[1])) === windowDays)) return null
  return found[0][0].trim()
}

export type ColourNote = 'dark-text' | 'fallback' | null

/**
 * What the portal will actually do with this brand colour — the live portal's own rule (portal/brand.ts palette(),
 * unchanged): 'dark-text' when button text switches to dark, 'fallback' when neither white nor dark text is readable
 * and the default blue is used instead, null otherwise (white text, or no valid colour typed yet).
 */
export function brandColourNote(color: string | null | undefined): ColourNote {
  const c = (color ?? '').trim()
  if (!HEX.test(c)) return null
  const p = palette(c)
  if (p.brand === DEFAULT_BRAND && c.toUpperCase() !== DEFAULT_BRAND) return 'fallback'
  return p.onBrand === INK ? 'dark-text' : null
}
