/**
 * Onboarding O6 — the help center's topics (in display order; labels `helpCenter.topics.<key>`) and the "Start here"
 * path (Connect → Receive → Pack → Pickup → Returns), each step opening its article.
 */
export const TOPICS = [
  'start', 'receive', 'pack', 'pickup', 'returns', 'alerts', 'exchange', 'portal', 'stock', 'places', 'orders',
  'team', 'trouble', 'analytics', 'account',
] as const
export type HelpTopic = (typeof TOPICS)[number]

export const START_HERE: { key: 'connect' | 'receive' | 'pack' | 'pickup' | 'returns'; articleId: string }[] = [
  { key: 'connect', articleId: 'connect-shopify' },
  { key: 'receive', articleId: 'receive-and-label' },
  { key: 'pack', articleId: 'pack-modes' },
  { key: 'pickup', articleId: 'hand-over-parcels' },
  { key: 'returns', articleId: 'scan-returns' },
]

/** Support links (also on the article page's "No" answer). */
export const HELP_WHATSAPP = 'https://wa.me/201037371204'

/** The order articles are listed in inside their topic (unknown ids go last). */
export const ARTICLE_ORDER: string[] = [
  'connect-shopify', 'connect-bosta', 'other-couriers', 'old-location-zero', 'orders-before-connect', 'test-scanner', 'phone-scanner',
  'receiving-modes', 'receive-and-label', 'reprint-labels', 'receiving-excel-import', 'label-runs', 'tag-stock',
  'pack-modes', 'batch-waybills', 'gather-list', 'order-not-in-pick-pack', 'set-aside', 'unpack-after-edit', 'cancelled-after-print', 'pack-other-couriers',
  'hand-over-parcels', 'cancelled-waybills-pickup',
  'scan-returns', 'grade-returns', 'untracked-returns', 'return-to-receive',
  'alerts-overview', 'alerts-shopify-sync', 'alerts-courier-shipments', 'alerts-cancelled-orders', 'alerts-packing', 'alerts-returns',
  'alerts-shopify-returns-exchanges', 'alerts-tracking-changes',
  'exchanges-bosta', 'exchanges-other-couriers', 'lookup-sold-exchange', 'shopify-native-exchanges',
  'portal-setup', 'portal-branding', 'portal-refund-methods', 'portal-photos', 'portal-requests', 'portal-who-can-return',
  'transfers', 'transfers-shopify', 'stock-take', 'piece-statuses', 'back-to-good', 'shopify-stock-mismatch',
  'places-setup', 'put-away', 'place-stickers', 'find-piece',
  'orders-tabs', 'orders-edited-in-shopify', 'tracked-vs-not-tracked',
  'roles', 'tablet-setup', 'pin-switch', 'logout-devices',
  'scanner-wrong-letters', 'missed-scans', 'phone-wont-pair', 'emails-junk',
  'booked-vs-realized', 'bosta-owes', 'inventory-value',
  'setup-call', 'contact-support', 'delete-account',
]
