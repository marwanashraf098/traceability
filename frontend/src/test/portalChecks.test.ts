import { describe, expect, test } from 'vitest'
import { DEFAULT_BRAND, INK, palette } from '../portal/brand'
import { brandColourNote, policyWindowMismatch } from '../pages/settings/portal/portalChecks'

// Returns portal redesign Pass A — the two client-only notes under Branding & policy.
//   mismatch — the policy text names days (Latin or Arabic-Indic digits; day / days / يوم / أيام) and none is the window
//   colour   — what the LIVE portal does (portal/brand.ts palette(), unchanged): dark text / default blue / nothing

describe('policy text vs return window', () => {
  test.each([
    ['Return after 14 days', 90, '14 days'],
    ['Returns within 1 day only.', 14, '1 day'],
    ['A 30-day return policy', 14, '30-day'],
    ['RETURNS WITHIN 7 DAYS', 14, '7 DAYS'],
    ['الإرجاع خلال 14 يوم', 30, '14 يوم'],
    ['الإرجاع خلال ١٤ يوم من الاستلام', 30, '١٤ يوم'],
    ['يمكنك الإرجاع خلال ٧ أيام', 14, '٧ أيام'],
    ['خلال 10 أيام', 14, '10 أيام'],
  ])('“%s” with a %i-day window → warns about “%s”', (text, window, quote) => {
    expect(policyWindowMismatch(text, window)).toBe(quote)
  })

  test.each([
    ['Return after 14 days', 14],
    ['الإرجاع خلال ١٤ يوم', 14],          // Arabic-Indic digits read as 14
    ['Return within 30 days; refunds take 7 days', 30],   // one of the numbers is the window
    ['Unworn items with tags only.', 14],  // no number of days
    ['Call us on 0100 123 4567', 14],      // a number that isn't days
    ['', 14],
  ])('“%s” with a %i-day window → no warning', (text, window) => {
    expect(policyWindowMismatch(text, window)).toBeNull()
  })

  test('no warning while the window field is not a valid number', () => {
    expect(policyWindowMismatch('Return after 14 days', null)).toBeNull()
    expect(policyWindowMismatch('Return after 14 days', 0)).toBeNull()
    expect(policyWindowMismatch(null, 14)).toBeNull()
  })

  test('the first mismatching phrase is quoted as written', () => {
    expect(policyWindowMismatch('Within 7 days, or 10 days for sale items', 14)).toBe('7 days')
  })
})

describe('brand colour note — follows the live portal palette()', () => {
  test.each([
    // prod colours (2026-11-06): light ones already get dark text in the portal
    ['#FF9D98', 'dark-text'],
    ['#FFF700', 'dark-text'],
    ['#FFFFFF', 'dark-text'],
    ['#121212', null],
    ['#164C4A', null],
    ['#452023', null],
    ['#0F766E', null],
    // mid-tones where neither white (3.96) nor dark (4.49) reaches 4.5:1 → the portal uses the default blue
    ['#E0457B', 'fallback'],
    ['#808080', 'fallback'],
    // the default blue itself is not a fallback
    [DEFAULT_BRAND, null],
    // not a colour yet → nothing
    ['#12', null],
    ['', null],
  ] as const)('%s → %s', (hex, note) => {
    expect(brandColourNote(hex)).toBe(note)
  })

  test('agrees with palette() for every note', () => {
    expect(palette('#FF9D98').onBrand).toBe(INK)
    expect(palette('#E0457B').brand).toBe(DEFAULT_BRAND)
    expect(palette('#121212').onBrand).toBe('#FFFFFF')
  })
})
