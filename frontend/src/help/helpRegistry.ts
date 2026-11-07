import type { TourId } from '../tours/tours'

/**
 * Onboarding O5 / O6 — the "?" of each page: route → its tour (replayed from the "?") and its Help article (the
 * TourCard's "Read more", owners / managers only). Plus every alert type → the article that explains it (the Alerts
 * page's "What does this mean?"). Guard tests: every articleId here exists in BOTH languages, every alert type the
 * Alerts page knows is mapped, and each article's own `alerts:` list agrees with this map.
 */
export interface HelpEntry {
  tourId: TourId | 'pick_pack_mode'
  articleId: string | null
}

export const HELP_REGISTRY: Record<string, HelpEntry> = {
  '/fulfill': { tourId: 'pick_pack_mode', articleId: 'pack-modes' },
  '/pickups': { tourId: 'pickups', articleId: 'hand-over-parcels' },
  '/returns': { tourId: 'scan_returns', articleId: 'scan-returns' },
  '/stock-take': { tourId: 'stock_take', articleId: 'stock-take' },
  '/transfers': { tourId: 'transfers', articleId: 'transfers' },
}

/** The entry for a route, by its first path segment (/stock-take/:id/scan → /stock-take); null when none. */
export function helpFor(pathname: string): HelpEntry | null {
  const first = '/' + (pathname.split('/')[1] ?? '')
  return HELP_REGISTRY[first] ?? null
}

/** The article of a tour (for the TourCard's "Read more"). */
export function articleForTour(tourId: TourId): string | null {
  return Object.values(HELP_REGISTRY).find(e => e.tourId === tourId)?.articleId ?? null
}

/** Alert groups: one article per group. Several alert types share an article. */
export const ALERT_GROUPS: Record<string, string[]> = {
  'alerts-shopify-sync': [
    'shopify_connection_lost', 'shopify_location_inactive', 'warehouse_address_push_failed',
    'inventory_increment_sync_failed', 'restocked_twice', 'transfer_stock_not_removed',
    'delivered_not_fulfilled_in_shopify', 'void_hold_sync_failed',
  ],
  'alerts-courier-shipments': [
    'lost', 'unmatched_delivery', 'bosta_discovery_failed', 'fulfillment_link_problem', 'stuck_shipment',
    'delivery_limbo', 'ndr_failed', 'high_attempts', 'waybill_deleted_in_bosta', 'unexpected_return',
    'generic_bosta_conflict', 'generic_multiple_parcels', 'generic_delivered_without_pickup',
    'missing_awb', 'missing_provider_id', 'exchange_needs_mapping', 'exchange_unmapped_state',
  ],
  'alerts-cancelled-orders': [
    'cancelled_live_shipment', 'cancelled_but_delivered', 'shopify_cancel_vs_inflight',
    'generic_cancelled_live_shipment', 'generic_fulfillment_cancelled',
  ],
  'alerts-packing': [
    'guided_unpack', 'pack_set_aside', 'pack_cancelled_after_print', 'blocked_customer', 'shopify_edit_conflict',
  ],
  'alerts-returns': [
    'never_received', 'return_leg_unscanned', 'return_in_transit_stuck', 'return_to_receive',
    'request_item_to_receive', 'return_link_ambiguous', 'pickup_booking_problem', 'refund_pending_overdue',
    'return_items_overdue', 'return_mismatch',
  ],
  'alerts-shopify-returns-exchanges': [
    'shopify_exchange_short', 'shopify_exchange_still_waiting', 'shopify_exchange_shipped_in_shopify',
    'shopify_return_open_after_intake', 'shopify_return_ended_after_intake',
  ],
  'alerts-tracking-changes': [
    'tracking_changed_after_custody', 'tracking_change_needs_review', 'tracking_changed_relabel',
    'superseded_waybill_moving',
  ],
}

export const ALERT_ARTICLES: Record<string, string> = Object.fromEntries(
  Object.entries(ALERT_GROUPS).flatMap(([article, types]) => types.map(t => [t, article])),
)

/** The article for an alert type; a type without a group opens the general alerts article. */
export function articleForAlert(type: string): string {
  return ALERT_ARTICLES[type] ?? 'alerts-overview'
}
