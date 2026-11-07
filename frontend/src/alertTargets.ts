/**
 * O6-fixes — where an alert's "Go →" lands. The server's actionUrl is used as-is when it is a real page; the ones
 * that aren't are mapped here, by pattern, to the page where the merchant can actually act:
 *
 *   /orders/<id>                 (no such route)  → /orders?order=<order> — the order's drawer on Orders (the alert's
 *                                                    own order_id when it has one, else the id in the URL)
 *   unmatched_delivery, bosta_discovery_failed, generic_bosta_conflict (their server link is /shipments/unlinked, which
 *   doesn't exist), and any /shipments/<id>       → the order's drawer when the alert names an order, else no button
 *                                                    (nothing in Traced to act on — the Help article says what to do)
 *   /fulfill/<id>  guided_unpack                   → /fulfill?guidedUnpack=<id> — Pick & Pack opens that cancelled order's
 *                                                    "unpack these pieces" panel (both packing modes)
 *   /fulfill/<id>  any other type                  → the order's drawer
 *   /onboarding    warehouse_address_push_failed   → /settings?tab=locations (where the address is saved again)
 *
 * null = no Go button. The guard test (alertTargets.test.tsx) derives every actionUrl the backend can build and checks
 * each result against App.tsx's routes.
 */
export interface AlertTargetInput {
  type: string
  actionUrl?: string | null
  order_id?: string | null
}

const ORDER_PAGE = /^\/orders\/([^/?#]+)$/
/** Alerts about a Bosta delivery Traced couldn't link: only the order they name (if any) is somewhere to act. */
const UNLINKED_DELIVERY = new Set(['unmatched_delivery', 'bosta_discovery_failed', 'generic_bosta_conflict'])
const FULFILL_ORDER = /^\/fulfill\/([^/?#]+)$/

export function goTargetFor(item: AlertTargetInput): string | null {
  const url = item.actionUrl
  if (!url) return null
  const order = (id: string | null | undefined) => (id ? `/orders?order=${encodeURIComponent(id)}` : null)

  if (UNLINKED_DELIVERY.has(item.type)) return order(item.order_id)

  const orderPage = ORDER_PAGE.exec(url)
  if (orderPage) return order(item.order_id ?? orderPage[1])

  if (url.startsWith('/shipments/')) return order(item.order_id)

  const fulfillOrder = FULFILL_ORDER.exec(url)
  if (fulfillOrder) {
    return item.type === 'guided_unpack'
      ? `/fulfill?guidedUnpack=${encodeURIComponent(fulfillOrder[1])}`
      : order(fulfillOrder[1])
  }

  if (url === '/onboarding' && item.type === 'warehouse_address_push_failed') return '/settings?tab=locations'

  return url
}

/**
 * The Overview alerts widget's destination for an alert — the SAME mapping as the Alerts page: an order target opens
 * that order's drawer in place (the widget has one); any other target is a link; no target → the Alerts page filtered
 * to this alert's type (a real page, never a dead link).
 */
export type AlertDestination = { kind: 'order'; orderId: string } | { kind: 'url'; url: string }

export function alertDestination(item: AlertTargetInput): AlertDestination {
  const target = goTargetFor(item)
  const order = target ? /^\/orders\?order=([^&]+)$/.exec(target) : null
  if (order) return { kind: 'order', orderId: decodeURIComponent(order[1]) }
  if (target) return { kind: 'url', url: target }
  return { kind: 'url', url: `/exceptions?type=${encodeURIComponent(item.type)}` }
}
