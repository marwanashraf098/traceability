import type { PutAwayListPiece, PutAwayListPlace } from '../../api'

// Warehouse places S4 — a put-away list as the screens show it: the gather list in reverse. Pieces are grouped into
// rows by place × variant (a waiting piece by its suggested place, a placed one by where it is now, so a piece put
// where it was suggested ticks off its own row), rows in walking order (S3's sortKey), grouped by zone, "No
// suggestion" last. Nothing here decides a place: the server sends the suggestion (S2's rule) and the done state.

export interface PutAwayListRow {
  key: string
  place: PutAwayListPlace | null
  /** The waiting pieces' reason (last_place | most_of_variant); 'placed' when every piece is done; null: none. */
  reason: PutAwayListPiece['placeReason']
  variantId: string
  productTitle: string
  variantTitle: string | null
  sku: string | null
  pieces: PutAwayListPiece[]
  qty: number
  done: number
}

export const NO_SUGGESTION = '__no_suggestion__'

const byKey = (a: string, b: string) => (a < b ? -1 : a > b ? 1 : 0)

/** Rows in walking order (place sortKey; equal places by product, variant), no place last. */
export function putAwayRows(pieces: PutAwayListPiece[]): PutAwayListRow[] {
  const rows = new Map<string, PutAwayListRow>()
  for (const p of pieces) {
    const key = `${p.place?.id ?? NO_SUGGESTION}:${p.variantId}`
    let row = rows.get(key)
    if (!row) {
      row = { key, place: p.place, reason: null, variantId: p.variantId, productTitle: p.productTitle,
        variantTitle: p.variantTitle, sku: p.sku, pieces: [], qty: 0, done: 0 }
      rows.set(key, row)
    }
    row.pieces.push(p)
    row.qty += 1
    if (p.done) row.done += 1
  }
  for (const r of rows.values()) {
    // waiting pieces first, then the ones already placed; codes in order inside each
    r.pieces.sort((a, b) => Number(a.done) - Number(b.done) || byKey(a.code, b.code))
    const waiting = r.pieces.find(p => !p.done)
    r.reason = waiting ? waiting.placeReason : 'placed'
  }
  return [...rows.values()].sort((a, b) => {
    if (!a.place || !b.place) return (a.place ? -1 : b.place ? 1 : 0) || byKey(a.productTitle, b.productTitle)
    return byKey(a.place.sortKey, b.place.sortKey) || byKey(a.productTitle, b.productTitle)
      || byKey(a.variantTitle ?? '', b.variantTitle ?? '')
  })
}

/** Rows grouped by zone in walking order (each zone by its first row's key); "No suggestion" (no place) last. */
export function groupPutAwayRows(rows: PutAwayListRow[]): { zone: string; rows: PutAwayListRow[] }[] {
  const groups = new Map<string, PutAwayListRow[]>()
  for (const r of rows) {
    const zone = r.place?.zoneCode ?? (r.place ? r.place.code : NO_SUGGESTION)
    if (!groups.has(zone)) groups.set(zone, [])
    groups.get(zone)!.push(r)
  }
  const first = (rs: PutAwayListRow[]) => rs.find(r => r.place)?.place?.sortKey ?? ''
  return [...groups.entries()]
    .map(([zone, rs]) => ({ zone, rows: rs }))
    .sort((a, b) => {
      if (a.zone === NO_SUGGESTION) return 1
      if (b.zone === NO_SUGGESTION) return -1
      return byKey(first(a.rows), first(b.rows))
    })
}
