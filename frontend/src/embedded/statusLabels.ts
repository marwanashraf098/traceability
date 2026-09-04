/**
 * Local EN label map for OrderStatusDeriver's primaryKey + fulfillmentKey vocabulary.
 * The embedded bundle carries no i18next (see main.tsx) — these strings are copied
 * VERBATIM from src/locales/en.json's status.* keys so the embedded Orders tab reads
 * identically to the standalone app's EN labels. Not a subset/rewrite: every key the
 * deriver can produce for primaryKey OR fulfillmentKey is present. Keep in sync by hand
 * if src/locales/en.json's status.* entries change — there is no shared import between
 * the two bundles (i18next isn't loaded here at all).
 *
 * Source of truth checked 2026-09-04: src/locales/en.json.
 */
export const STATUS_LABELS: Record<string, string> = {
  'status.new':                 'New',
  'status.confirmed':           'Confirmed',
  'status.ready_to_pick':       'Ready to pick',
  'status.picking':             'Picking',
  'status.packed':              'Packed',
  'status.awaiting_courier':    'Awaiting courier',
  'status.label_created':       'Label created',
  'status.in_transit':          'In transit',
  'status.delivery_failed':     'Delivery failed — retrying',
  'status.delivered':           'Delivered',
  'status.fulfilled':           'Fulfilled',
  'status.returning':           'Returning',
  'status.returned':            'Returned',
  'status.lost':                'Lost',
  'status.terminated':          'Terminated',
  'status.cancelled':           'Cancelled',
  'status.self_pickup_pending': 'Ready for pickup',
  'status.needs_attention':     'Needs attention',
}

/** Falls back to a de-namespaced/underscore-stripped rendering of an unknown key — never a blank badge. */
export function statusLabel(key: string): string {
  return STATUS_LABELS[key] ?? key.replace(/^status\./, '').replace(/_/g, ' ')
}

// Backend Tone enum (NEUTRAL/INFO/SUCCESS/WARN/DANGER, see OrderStatusDeriver.Tone) ->
// Polaris Badge tone. NEUTRAL has no Polaris equivalent — undefined renders Polaris's
// default (neutral-looking) badge, same as omitting the tone prop entirely.
export type DerivedTone = 'NEUTRAL' | 'INFO' | 'SUCCESS' | 'WARN' | 'DANGER'

const POLARIS_TONE: Record<DerivedTone, 'info' | 'success' | 'warning' | 'critical' | undefined> = {
  NEUTRAL: undefined,
  INFO:    'info',
  SUCCESS: 'success',
  WARN:    'warning',
  DANGER:  'critical',
}

export function polarisTone(tone: DerivedTone): 'info' | 'success' | 'warning' | 'critical' | undefined {
  return POLARIS_TONE[tone]
}
