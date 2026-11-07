import type { TourId } from '../tours/tours'

/**
 * Onboarding O5 — the "?" of each page: route → its tour (replayed from the "?") and its Help article. O6 fills
 * `articleId` (Help center); until then every page has none.
 */
export interface HelpEntry {
  tourId: TourId | 'pick_pack_mode'
  articleId: string | null
}

export const HELP_REGISTRY: Record<string, HelpEntry> = {
  '/fulfill': { tourId: 'pick_pack_mode', articleId: null },
  '/pickups': { tourId: 'pickups', articleId: null },
  '/returns': { tourId: 'scan_returns', articleId: null },
  '/stock-take': { tourId: 'stock_take', articleId: null },
  '/transfers': { tourId: 'transfers', articleId: null },
}

/** The entry for a route, by its first path segment (/stock-take/:id/scan → /stock-take); null when none. */
export function helpFor(pathname: string): HelpEntry | null {
  const first = '/' + (pathname.split('/')[1] ?? '')
  return HELP_REGISTRY[first] ?? null
}
