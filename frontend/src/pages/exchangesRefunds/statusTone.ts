import type { BadgeTone } from '../../components/ui'

/**
 * Exchange status vocabulary tones (mapped/matched/needs_confirmation/unmatched/
 * bare_return/dismissed/return_received/...) — a page-local map, not added to ui.tsx's
 * shared STATUS_TONE (that map is keyed by piece/order/delivery states; exchange status
 * is a distinct vocabulary and would collide in meaning, not just in key name, e.g.
 * 'cancelled' already exists there for a different concept).
 */
const EXCHANGE_STATUS_TONE: Record<string, BadgeTone> = {
  needs_mapping: 'neutral',
  mapped: 'info',
  outbound_in_fulfillment: 'info',
  out_for_exchange: 'info',
  return_pending: 'warning',
  needs_confirmation: 'warning',
  unmatched: 'warning',
  matched: 'info',
  return_received: 'success',
  reconciled: 'success',
  bare_return: 'neutral',
  dismissed: 'neutral',
  cancelled: 'neutral',
}

export function exchangeStatusTone(status: string): BadgeTone {
  return EXCHANGE_STATUS_TONE[status] ?? 'neutral'
}

/** Step 4-close Part 2 — tone for a refund row's inspectionState facet. */
const INSPECTION_STATE_TONE: Record<string, BadgeTone> = {
  in_transit: 'info',
  needs_inspection: 'warning',
  resolved: 'success',
}

export function inspectionStateTone(state: string): BadgeTone {
  return INSPECTION_STATE_TONE[state] ?? 'neutral'
}
