import type { PutAwayListSource } from '../../api'

// Warehouse places S4 — where a put-away list lives and where its Back goes (the screen its button was on).

export const PUT_AWAY_LIST_SOURCES: PutAwayListSource[] = ['receiving', 'tag_session', 'return_session', 'transfer']

/** The list page's address for a flow. */
export function putAwayListPath(source: PutAwayListSource, id: string): string {
  return `/put-away/list/${source}/${encodeURIComponent(id)}`
}

export function putAwayListBack(source: PutAwayListSource, id: string): string {
  switch (source) {
    case 'receiving': return `/receiving?session=${encodeURIComponent(id)}`
    case 'tag_session': return `/tag-stock/${encodeURIComponent(id)}/summary`
    case 'return_session': return '/returns'
    case 'transfer': return `/transfers/${encodeURIComponent(id)}`
  }
}
