import type { TFunction } from 'i18next'
import type { PickPlace } from '../../api'

// Warehouse places S3 — where to pick from, display only. The server sends, per variant, the places holding its
// available pieces at the main warehouse (largest first) and how many have no place; these helpers turn that into
// what the screens show. Nothing here assigns a piece: the picker scans whichever piece they take.

/** One part of a split: take `qty` from `place` (null = "No place yet"). `index` / `of` = "1 of 2 places". */
export interface SplitPart { place: PickPlace | null; qty: number; index: number; of: number }

const byWalk = (a: PickPlace, b: PickPlace) => (a.sortKey < b.sortKey ? -1 : a.sortKey > b.sortKey ? 1 : 0)

/**
 * Splits a needed quantity across places: the place with the most pieces first, then the next, until it's covered;
 * whatever no placed piece covers is one "No place yet" part. Nothing needed → one part of 0 at the biggest place
 * (or no place), so the item still shows once.
 */
export function splitAcrossPlaces(needed: number, places: PickPlace[]): SplitPart[] {
  const ordered = [...places].sort((a, b) => b.count - a.count || byWalk(a, b))
  const parts: { place: PickPlace | null; qty: number }[] = []
  if (needed <= 0) {
    parts.push({ place: ordered[0] ?? null, qty: 0 })
  } else {
    let left = needed
    for (const p of ordered) {
      if (left <= 0) break
      const take = Math.min(p.count, left)
      if (take <= 0) continue
      parts.push({ place: p, qty: take })
      left -= take
    }
    if (left > 0) parts.push({ place: null, qty: left })
  }
  return parts.map((p, i) => ({ ...p, index: i + 1, of: parts.length }))
}

/** The small line under a place code: "Box on shelf Z1-R01-S2" / "On the Z1 floor"; null for a shelf. */
export function placeNote(place: PickPlace, t: TFunction): string | null {
  if (place.kind !== 'box' || !place.parentCode) return null
  return place.parentKind === 'shelf'
    ? t('fulfill.places.boxOnShelf', { shelf: place.parentCode })
    : t('fulfill.places.onFloor', { zone: place.parentCode })
}

export const NO_PLACE = '__no_place__'

/** Zone of a split part for grouping ("No place yet" is its own group, always last). */
export function zoneOf(part: { place: PickPlace | null }): string {
  return part.place?.zoneCode ?? (part.place ? part.place.code : NO_PLACE)
}

/** Groups in walking order (each zone by its first place's key), "No place yet" last. */
export function groupByZone<T extends { place: PickPlace | null }>(rows: T[]): { zone: string; rows: T[] }[] {
  const groups = new Map<string, T[]>()
  for (const r of rows) {
    const z = zoneOf(r)
    if (!groups.has(z)) groups.set(z, [])
    groups.get(z)!.push(r)
  }
  const keyOf = (rs: T[]) => rs.reduce<string | null>(
    (min, r) => (r.place && (min == null || r.place.sortKey < min) ? r.place.sortKey : min), null)
  return [...groups.entries()]
    .map(([zone, rs]) => ({ zone, rows: rs, key: keyOf(rs) }))
    .sort((a, b) => {
      if (a.zone === NO_PLACE) return 1
      if (b.zone === NO_PLACE) return -1
      return (a.key ?? '') < (b.key ?? '') ? -1 : (a.key ?? '') > (b.key ?? '') ? 1 : 0
    })
    .map(({ zone, rows: rs }) => ({ zone, rows: rs }))
}

/** Rows in walking order by place (stable: equal places keep their incoming order); no place last. */
export function sortByPlace<T extends { place: PickPlace | null }>(rows: T[]): T[] {
  return rows
    .map((r, i) => ({ r, i }))
    .sort((a, b) => {
      if (!a.r.place || !b.r.place) return (a.r.place ? -1 : b.r.place ? 1 : 0) || a.i - b.i
      return byWalk(a.r.place, b.r.place) || a.i - b.i
    })
    .map(x => x.r)
}

/** An order's places, for a list of orders: every line's quantity split across places, the places in walking
 *  order (each once) and whether something has no place yet; `key` sorts the order by its first place. */
export function orderPlaceSummary(lines: { quantity: number; places: PickPlace[] }[]):
    { codes: string[]; noPlace: boolean; key: string | null } {
  const parts = lines.flatMap(l => splitAcrossPlaces(l.quantity, l.places)).filter(p => p.qty > 0)
  const placed = sortByPlace(parts).filter(p => p.place)
  const codes = [...new Set(placed.map(p => p.place!.code))]
  return { codes, noPlace: parts.some(p => !p.place), key: placed[0]?.place?.sortKey ?? null }
}
