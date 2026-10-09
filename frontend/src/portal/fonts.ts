/**
 * Returns portal P1 — the merchant's portal font (tenants.portal_font, V159). Self-hosted through
 * @fontsource only (CSP font-src 'self'); each family is its own lazy chunk, so a page loads ONLY
 * the family it shows. Used by the portal (portal/main → PortalApp) and by the settings preview.
 *
 * All five ship Latin + Arabic subsets (unicode-range — the browser fetches only the subsets the
 * text needs). Cairo and Readex Pro are variable fonts; Tajawal and Almarai have no 600, so their
 * semibold is 700 — set through --pp-w600 (portal.css), never a browser-synthesized bold.
 */

export const PORTAL_FONTS = ['cairo', 'tajawal', 'ibm-plex-sans-arabic', 'almarai', 'readex-pro'] as const
export type PortalFont = typeof PORTAL_FONTS[number]
export const DEFAULT_PORTAL_FONT: PortalFont = 'cairo'

interface FontSpec {
  /** The CSS family name @fontsource registers. */
  family: string
  /** Shown in the picker (a proper name — not translated). */
  label: string
  /** The weight the portal's "600" uses with this family. */
  semibold: 600 | 700
  /** Every weight the portal uses (variable: one file per subset). */
  load: () => Promise<unknown>
  /** Regular weight only — the settings picker's sample line. */
  loadRegular: () => Promise<unknown>
}

const SPECS: Record<PortalFont, FontSpec> = {
  cairo: {
    family: 'Cairo Variable', label: 'Cairo', semibold: 600,
    load: () => import('@fontsource-variable/cairo/wght.css'),
    loadRegular: () => import('@fontsource-variable/cairo/wght.css'),
  },
  tajawal: {
    family: 'Tajawal', label: 'Tajawal', semibold: 700,
    load: () => Promise.all([import('@fontsource/tajawal/400.css'), import('@fontsource/tajawal/700.css')]),
    loadRegular: () => import('@fontsource/tajawal/400.css'),
  },
  'ibm-plex-sans-arabic': {
    family: 'IBM Plex Sans Arabic', label: 'IBM Plex Sans Arabic', semibold: 600,
    load: () => Promise.all([
      import('@fontsource/ibm-plex-sans-arabic/400.css'),
      import('@fontsource/ibm-plex-sans-arabic/600.css'),
      import('@fontsource/ibm-plex-sans-arabic/700.css'),
    ]),
    loadRegular: () => import('@fontsource/ibm-plex-sans-arabic/400.css'),
  },
  almarai: {
    family: 'Almarai', label: 'Almarai', semibold: 700,
    load: () => Promise.all([import('@fontsource/almarai/400.css'), import('@fontsource/almarai/700.css')]),
    loadRegular: () => import('@fontsource/almarai/400.css'),
  },
  'readex-pro': {
    family: 'Readex Pro Variable', label: 'Readex Pro', semibold: 600,
    load: () => import('@fontsource-variable/readex-pro/wght.css'),
    loadRegular: () => import('@fontsource-variable/readex-pro/wght.css'),
  },
}

export function isPortalFont(v: unknown): v is PortalFont {
  return typeof v === 'string' && (PORTAL_FONTS as readonly string[]).includes(v)
}

/** Unknown / missing → the default, so an older backend still gets a working page. */
export function portalFontOrDefault(v: unknown): PortalFont {
  return isPortalFont(v) ? v : DEFAULT_PORTAL_FONT
}

export function fontLabel(font: PortalFont): string {
  return SPECS[font].label
}

/** The font-family value: the family, then system fallbacks (Latin and Arabic) while it loads. */
export function fontStack(font: PortalFont): string {
  return `'${SPECS[font].family}', system-ui, -apple-system, 'Segoe UI', Tahoma, sans-serif`
}

/** CSS variables for a root element: --pp-font and --pp-w600 (portal.css reads both). */
export function fontVars(font: PortalFont): Record<'--pp-font' | '--pp-w600', string> {
  return { '--pp-font': fontStack(font), '--pp-w600': String(SPECS[font].semibold) }
}

const loaded = new Map<string, Promise<void>>()

function once(key: string, load: () => Promise<unknown>): Promise<void> {
  let p = loaded.get(key)
  if (!p) {
    // A failed chunk load is not fatal: the stack's fallbacks render the text. Forget it so a
    // later call can retry.
    p = load().then(() => undefined, () => { loaded.delete(key) })
    loaded.set(key, p)
  }
  return p
}

/** Loads every weight the portal uses for this family (once per page). */
export function loadPortalFont(font: PortalFont): Promise<void> {
  return once(`full:${font}`, SPECS[font].load)
}

/** Loads the regular weight only (the settings picker's samples). */
export function loadPortalFontRegular(font: PortalFont): Promise<void> {
  return once(`regular:${font}`, SPECS[font].loadRegular)
}
