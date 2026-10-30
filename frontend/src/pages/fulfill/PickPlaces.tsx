import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { MapPin } from 'lucide-react'
import { getOrderPickPlaces, LinePickPlaces } from '../../api'
import { useCapabilities } from '../../capabilities'
import { placeNote, splitAcrossPlaces } from './placeSplit'

// Warehouse places S3 — place chips under each pack line (queue mode and waybill mode). Display only: the order
// detail / order card is fetched and shown exactly as before; places are fetched AFTER it, on their own, and never
// awaited by the screen or its scan handling. Places off, a failed or slow fetch → no chips, nothing else changes.

/**
 * The open order's places per order line (by order-line id), or null. `refreshKey` (the order's packed count)
 * re-reads them after a scan, in the background; the last answer stays on screen meanwhile.
 */
export function useOrderPickPlaces(orderId: string | null, refreshKey: number): Map<string, LinePickPlaces> | null {
  const { places: enabled } = useCapabilities()
  const [state, setState] = useState<{ orderId: string; lines: Map<string, LinePickPlaces> } | null>(null)

  useEffect(() => {
    if (!enabled || !orderId) return
    let cancelled = false
    getOrderPickPlaces(orderId)
      .then(r => {
        if (cancelled || !r || !Array.isArray(r.lines)) return
        setState({ orderId, lines: new Map(r.lines.map(l => [l.orderItemId, l])) })
      })
      .catch(() => { /* no chips — the screen works exactly as without places */ })
    return () => { cancelled = true }
  }, [enabled, orderId, refreshKey])

  return enabled && state && state.orderId === orderId ? state.lines : null
}

/** "Z1-R01-S2 · 3", "BOX-07 · 2", "No place yet · 1" — the line's still-needed quantity split across places. */
export function LinePlaceChips({ places, needed }: { places: LinePickPlaces | undefined; needed: number }) {
  const { t } = useTranslation()
  if (!places || needed <= 0) return null
  const parts = splitAcrossPlaces(needed, places.places)
  return (
    <div className="flex flex-wrap gap-1.5 mt-2" data-testid={`line-places-${places.orderItemId}`}>
      {parts.map(p => {
        const note = p.place ? placeNote(p.place, t) : null
        return (
          <span key={p.place?.binId ?? 'none'} data-testid="place-chip" title={note ?? undefined}
            className={p.place
              ? 'inline-flex items-center gap-1 rounded-full border border-trace-blue/30 bg-trace-blue/[0.08] px-2 py-0.5 text-caption text-primary'
              : 'inline-flex items-center gap-1 rounded-full border border-line bg-elevated px-2 py-0.5 text-caption text-muted'}>
            <MapPin size={11} aria-hidden className="flex-shrink-0" />
            <bdi className={p.place ? 'font-mono font-semibold' : undefined}>
              {p.place ? p.place.code : t('fulfill.places.noPlace')}
            </bdi>
            <span aria-hidden>·</span>
            <span className="font-mono">{p.qty}</span>
          </span>
        )
      })}
    </div>
  )
}
