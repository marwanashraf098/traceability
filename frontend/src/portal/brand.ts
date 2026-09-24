/**
 * Brand colours for the portal. The merchant's brand colour drives buttons, links and
 * accents; text on a brand-coloured button is white or #141821, whichever reaches 4.5:1.
 * If neither does, the default blue (#3656E0) with white text is used instead.
 */

export const DEFAULT_BRAND = '#3656E0'
export const INK = '#141821'
const WHITE = '#FFFFFF'
const HEX = /^#[0-9A-Fa-f]{6}$/

function channel(c: number): number {
  const s = c / 255
  return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4)
}

export function luminance(hex: string): number {
  const n = parseInt(hex.slice(1), 16)
  return 0.2126 * channel((n >> 16) & 255) + 0.7152 * channel((n >> 8) & 255) + 0.0722 * channel(n & 255)
}

export function contrast(a: string, b: string): number {
  const [l1, l2] = [luminance(a), luminance(b)].sort((x, y) => y - x)
  return (l1 + 0.05) / (l2 + 0.05)
}

/** Mix a colour with white; weight = share of the colour (0..1). */
function tint(hex: string, weight: number): string {
  const n = parseInt(hex.slice(1), 16)
  const mix = (c: number) => Math.round(c * weight + 255 * (1 - weight))
  const r = mix((n >> 16) & 255), g = mix((n >> 8) & 255), b = mix(n & 255)
  return '#' + [r, g, b].map(v => v.toString(16).padStart(2, '0')).join('').toUpperCase()
}

export interface BrandPalette {
  /** Button background. */
  brand: string
  /** Text on a brand-coloured button. */
  onBrand: string
  /** Links, wordmark and accent text on white/near-white — the brand if it reads at 4.5:1, else ink. */
  accentText: string
  /** Pale background for accent chips (step numbers, success icon). */
  accentSoft: string
}

export function palette(brandColor: string | null | undefined): BrandPalette {
  let brand = brandColor && HEX.test(brandColor) ? brandColor.toUpperCase() : DEFAULT_BRAND
  let onBrand: string
  const white = contrast(brand, WHITE)
  const ink = contrast(brand, INK)
  if (white >= 4.5 || ink >= 4.5) {
    onBrand = white >= ink ? WHITE : INK
  } else {
    brand = DEFAULT_BRAND
    onBrand = WHITE
  }
  const accentText = contrast(brand, '#F7F7F5') >= 4.5 ? brand : INK
  return { brand, onBrand, accentText, accentSoft: tint(brand, 0.12) }
}
