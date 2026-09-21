import type { DerivedTone, ExchangeSummary, RefundLeg } from '../../api'

/**
 * FR-EXCHANGE Step 4c — the ONE display shape both feeds normalize to. Two
 * structurally different sources (Step 4 diagnosis §3) merged client-side, never
 * unioned in SQL — see ExchangesRefunds.tsx.
 *
 * LABELLING GUARD (non-negotiable, per the build task): mappedOrderId comes from
 * matched_order_id for an exchange and order_id for a refund — NEVER from an
 * exchange's outbound_order_id (the synthetic replacement order). ExchangeSummary
 * has no outbound_order_id field at all (see api.ts's own guard comment), so there is
 * nothing here to accidentally read — but normalizeExchange() below is still the one
 * place that decision is made, and exchangesRefundsNormalize.test.ts locks it with a
 * revert-to-confirm assertion.
 */
export type MergedRowKind = 'exchange' | 'refund'

export interface MergedRow {
  id: string
  kind: MergedRowKind
  sourceId: string
  trackingNumber: string
  customerName: string | null
  customerPhone: string | null
  /** The customer's ORIGINAL order — matched_order_id (exchange) / order_id (refund). */
  mappedOrderId: string | null
  /** Refund only — always present, so this doubles as a short reference. */
  orderNumber: string | null
  /** Real field, honest proxy for "last update" — null pre-match, and always null for
   *  refunds (neither GET /exchanges nor GET /refunds exposes a genuine last-update
   *  timestamp; not fabricated here). */
  matchedAt: string | null
  /** Exchange only — the status vocabulary (mapped/matched/needs_confirmation/...). */
  exchangeStatus?: ExchangeSummary['status']
  /** Exchange only — true when the outbound leg was auto-committed by the resolver
   *  (never set for a refund row). */
  autoMatched?: boolean
  /** Refund only — OrderStatusDeriver.deriveLegStatus(internal_state), rendered via
   *  <LegStatusBadge>. An exchange row NEVER carries this — see HONESTY CONSTRAINT 2. */
  legStatus?: { primaryKey: string; tone: DerivedTone }
  /** Refund only — Step 4-close Part 2's disposition rollup (backend: RefundLeg.
   *  inspection_state). ADDITIVE to legStatus, never a replacement for it — the row's
   *  STATUS column renders from this now (In transit / Needs inspection / Resolved),
   *  legStatus stays available for anything that still wants the raw courier badge. */
  inspectionState?: RefundLeg['inspection_state']
}

export function normalizeExchange(e: ExchangeSummary): MergedRow {
  return {
    id: `exchange:${e.id}`,
    kind: 'exchange',
    sourceId: e.id,
    trackingNumber: e.tracking_number,
    customerName: e.customer_name,
    customerPhone: e.customer_phone,
    mappedOrderId: e.matched_order_id,
    orderNumber: null,
    matchedAt: e.matched_at,
    exchangeStatus: e.status,
    autoMatched: e.auto_matched,
  }
}

export function normalizeRefund(r: RefundLeg): MergedRow {
  return {
    id: `refund:${r.id}`,
    kind: 'refund',
    sourceId: r.id,
    trackingNumber: r.tracking_number,
    customerName: r.customer_name,
    customerPhone: r.customer_phone,
    mappedOrderId: r.order_id,
    orderNumber: r.order_number,
    matchedAt: null,
    legStatus: r.leg_status,
    inspectionState: r.inspection_state,
  }
}

// ── Filters ───────────────────────────────────────────────────────────────────
// Every predicate is computed from a field the feeds actually carry — see HONESTY
// CONSTRAINT 2. inTransit in particular can only ever match a refund row: an
// exchange's inbound leg has no courier-progress signal (interpretReturnLeg is an
// intentionally-unimplemented hook on the backend), so it must yield zero exchange
// rows rather than a fabricated badge.
//
// Step 4-close Part 2 correction: the refund-side inTransit/needsAction/received
// predicates below now read inspectionState (piece-disposition-level), not
// legStatus.primaryKey — the diagnosis found the old legStatus-based mapping used
// 'status.delivered' for "received", a forward-leg concept that never actually applies
// to a CRP return leg (which reaches 'returned' on arrival, never 'delivered').

export type FilterTab = 'all' | 'needsAction' | 'refunds' | 'exchanges' | 'inTransit' | 'received'

const EXCHANGE_NEEDS_ACTION = new Set(['needs_confirmation', 'unmatched'])

export function matchesFilter(row: MergedRow, tab: FilterTab): boolean {
  switch (tab) {
    case 'all':
      return true
    case 'refunds':
      return row.kind === 'refund'
    case 'exchanges':
      return row.kind === 'exchange'
    case 'needsAction':
      return row.kind === 'exchange'
        ? EXCHANGE_NEEDS_ACTION.has(row.exchangeStatus ?? '')
        : row.inspectionState === 'needs_inspection'
    case 'inTransit':
      return row.kind === 'refund' && row.inspectionState === 'in_transit'
    case 'received':
      return row.kind === 'exchange'
        ? row.exchangeStatus === 'return_received'
        // internal_state='returned' (arrived) — both needs_inspection and resolved rows
        // are "received"; inspectionState is never 'in_transit' once internal_state has
        // reached 'returned', so this is exactly the complement of the inTransit check.
        : row.inspectionState === 'needs_inspection' || row.inspectionState === 'resolved'
  }
}
