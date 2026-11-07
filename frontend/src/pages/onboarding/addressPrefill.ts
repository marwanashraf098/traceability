import type { SetupGovernorate, ShopifyLocationOption } from '../../api'

/**
 * Onboarding O2b — a Shopify location's address mapped onto TODAY's setup fields only (governorate, city / area,
 * street, building). Never invents a value:
 *   - governorate: Shopify's province code when it is one of ours (they are Shopify's own Egypt codes); Shopify's
 *     two LEGACY codes map to the governorates they were merged into in 2011 — SU (6th of October) → Giza, HU
 *     (Helwan) → Cairo; else the province NAME matching one of ours exactly (EN or AR, case / spacing ignored), else
 *     empty for the merchant to choose; never for a location outside Egypt.
 *   - city: only together with a matched governorate (the city field belongs to the governorate — choosing one
 *     clears it), as written in Shopify — except a legacy code, whose own area ("6th of October" / "Helwan") is used
 *     when it is in that governorate's area list.
 *   - street = address1, building = address2, as written in Shopify.
 *   - zip and phone have no field today — not used.
 */
export interface PrefilledAddress {
  province: string
  city: string
  street: string
  building: string
}

const norm = (s: string) => s.trim().replace(/\s+/g, ' ').toLowerCase()

/** Shopify's legacy Egypt codes → the governorate they belong to since 2011, and their area's usual spellings. */
const LEGACY: Record<string, { code: string; area: string[] }> = {
  SU: { code: 'GZ', area: ['6th of october', '6th of october city', '6 october', '6th october', 'october', '6 أكتوبر', 'السادس من أكتوبر', 'مدينة 6 أكتوبر', 'مدينة السادس من أكتوبر'] },
  HU: { code: 'C', area: ['helwan', 'حلوان'] },
}

/** The legacy code's own area, when it is in the governorate's area list (in the page's language when known). */
function legacyArea(code: string | undefined, provinces: SetupGovernorate[], lang: string): string | null {
  const legacy = code ? LEGACY[code] : undefined
  if (!legacy) return null
  const areas = provinces.find(p => p.code === legacy.code)?.areas ?? []
  const hit = areas.find(a => legacy.area.includes(norm(a.nameEn)) || (!!a.nameAr && legacy.area.includes(norm(a.nameAr))))
  if (!hit) return null
  return lang.startsWith('ar') && hit.nameAr ? hit.nameAr : hit.nameEn
}

export function governorateFor(loc: ShopifyLocationOption, provinces: SetupGovernorate[]): string {
  if (loc.countryCode && loc.countryCode.toUpperCase() !== 'EG') return ''
  const code = loc.provinceCode?.trim().toUpperCase()
  if (code && provinces.some(p => p.code === code)) return code
  if (code && LEGACY[code] && provinces.some(p => p.code === LEGACY[code].code)) return LEGACY[code].code
  const name = loc.province ? norm(loc.province) : ''
  if (!name) return ''
  return provinces.find(p => norm(p.nameEn) === name || norm(p.nameAr) === name)?.code ?? ''
}

export function prefillFrom(loc: ShopifyLocationOption, provinces: SetupGovernorate[], lang = 'en'): PrefilledAddress {
  const province = governorateFor(loc, provinces)
  const legacy = province ? legacyArea(loc.provinceCode?.trim().toUpperCase(), provinces, lang) : null
  return {
    province,
    city: province ? (legacy ?? (loc.city ?? '').trim()) : '',
    street: (loc.address1 ?? '').trim(),
    building: (loc.address2 ?? '').trim(),
  }
}

/** Something was actually filled (the note shows only then). */
export function anyFilled(a: PrefilledAddress): boolean {
  return !!(a.province || a.city || a.street || a.building)
}

/** The read, capped: whatever hasn't answered within `ms` counts as nothing to offer (the form shows empty). */
export function withTimeout<T>(p: Promise<T>, ms: number, fallback: T): Promise<T> {
  return new Promise(resolve => {
    const timer = setTimeout(() => resolve(fallback), ms)
    p.then(v => { clearTimeout(timer); resolve(v) }, () => { clearTimeout(timer); resolve(fallback) })
  })
}
