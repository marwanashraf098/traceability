// Meta Pixel for the standalone signup page ONLY (pixel 1837033837461823, same as the
// marketing site). Imported by pages/Signup.tsx and nothing else: it must never reach the
// embedded Shopify bundle (embedded.html) or the returns portal (portal.html) —
// metaPixelEntries.test.ts walks both import graphs to prove it.
//
// No inline script: the fbq stub below is Meta's base code as bundled TS, and fbevents.js
// is added as an external <script src> (app CSP: script-src + https://connect.facebook.net).

const PIXEL_ID = '1837033837461823'
const SCRIPT_SRC = 'https://connect.facebook.net/en_US/fbevents.js'

type Fbq = ((...args: unknown[]) => void) & {
  callMethod?: (...args: unknown[]) => void
  queue: unknown[][]
  push: Fbq
  loaded: boolean
  version: string
  disablePushState?: boolean
}

declare global {
  interface Window { fbq?: Fbq; _fbq?: Fbq }
}

let initialised = false
const firedEventIds = new Set<string>()

/** Loads fbevents.js once, inits the pixel and records one PageView. Safe to call twice. */
export function loadMetaPixel(): void {
  if (initialised || typeof window === 'undefined') return
  initialised = true
  if (!window.fbq) {
    const n = function (...args: unknown[]) {
      if (n.callMethod) n.callMethod(...args)
      else n.queue.push(args)
    } as Fbq
    n.queue = []
    n.push = n
    n.loaded = true
    n.version = '2.0'
    window.fbq = n
    if (!window._fbq) window._fbq = n
    const s = document.createElement('script')
    s.async = true
    s.src = SCRIPT_SRC
    document.head.appendChild(s)
  }
  // The pixel must stay on the signup page only. fbevents.js otherwise (1) fires a PageView on
  // every SPA history change — so it kept tracking into /overview after signup — and (2) runs
  // automatic events / button-click detection. Both are switched off before init; the only
  // events sent are this explicit PageView and CompleteRegistration.
  window.fbq!.disablePushState = true
  window.fbq!('set', 'autoConfig', false, PIXEL_ID)
  window.fbq!('init', PIXEL_ID)
  window.fbq!('track', 'PageView')
}

/** CompleteRegistration, at most once per tenant per page load. No-op if the pixel never loaded. */
export function trackCompleteRegistration(tenantId: string): void {
  const eventID = `reg-${tenantId}`
  if (!window.fbq || firedEventIds.has(eventID)) return
  firedEventIds.add(eventID)
  window.fbq('track', 'CompleteRegistration', {}, { eventID })
}

export interface SignupAttribution {
  fbp?: string
  fbc?: string
  fbclid?: string
  utmSource?: string
  utmMedium?: string
  utmCampaign?: string
  utmTerm?: string
  utmContent?: string
}

function readCookie(name: string): string | undefined {
  const hit = document.cookie.split('; ').find(c => c.startsWith(name + '='))
  if (!hit) return undefined
  try { return decodeURIComponent(hit.slice(name.length + 1)) } catch { return undefined }
}

/** Meta cookies (set on .tracedtech.com) + fbclid/utm_* from the signup URL. Server validates. */
export function readSignupAttribution(): SignupAttribution {
  const q = new URLSearchParams(window.location.search)
  const get = (k: string) => q.get(k) || undefined
  return {
    fbp: readCookie('_fbp'),
    fbc: readCookie('_fbc'),
    fbclid: get('fbclid'),
    utmSource: get('utm_source'),
    utmMedium: get('utm_medium'),
    utmCampaign: get('utm_campaign'),
    utmTerm: get('utm_term'),
    utmContent: get('utm_content'),
  }
}

/** Our own team's accounts (App Store reviewers, internal tests) are never reported to Meta. */
export function isInternalEmail(email: string): boolean {
  return email.trim().toLowerCase().endsWith('@tracedtech.com')
}

/** Test-only: forget load/fire state between tests. */
export function __resetMetaPixelForTests(): void {
  initialised = false
  firedEventIds.clear()
}
