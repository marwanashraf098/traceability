import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { getPlaces, PlaceNode } from '../../api'
import { Select } from '../../components/ui'

/** Warehouse places S5 — the Inventory place filter's value: '' every place, NO_PLACE, or a place id. */
export const NO_PLACE = '__no_place__'

/**
 * A tree picker over one location's places (zone → rack → shelf / box, indented in tree order) plus "No place yet".
 * A zone or rack means everything under it; a shelf or box means only itself (the server's PlaceScope).
 * Places rollout: nothing at all — exactly today's toolbar — until the location's places are known and it is set up
 * (a live shelf or box).
 */
export default function PlaceFilterSelect({ locationId, value, onChange }: {
  locationId: string
  value: string
  onChange: (v: string) => void
}) {
  const { t } = useTranslation()
  const [loaded, setLoaded] = useState<{ locationId: string; nodes: PlaceNode[] } | null>(null)

  useEffect(() => {
    let cancelled = false
    getPlaces(locationId)
      .then(tree => { if (!cancelled) setLoaded({ locationId, nodes: tree.nodes.filter(n => !n.archived) }) })
      .catch(() => { if (!cancelled) setLoaded({ locationId, nodes: [] }) })
    return () => { cancelled = true }
  }, [locationId])

  const nodes = loaded?.locationId === locationId ? loaded.nodes : []
  if (!nodes.some(n => n.kind === 'shelf' || n.kind === 'box')) return null

  const options = [
    { value: '', label: t('placeFilter.all') },
    { value: NO_PLACE, label: t('placeFilter.noPlace') },
    ...nodes.map(n => ({
      value: n.id,
      label: '   '.repeat(n.depth) + (n.kind === 'box' ? `${n.code} (${t('places.kind.box')})` : n.label),
    })),
  ]
  // A shelf means that shelf only — its boxes are places of their own (the server's PlaceScope).
  const shelf = nodes.find(n => n.id === value)?.kind === 'shelf'
  return (
    <>
      <Select value={value} onChange={onChange} options={options} className="w-56" />
      {shelf && <span className="text-caption text-muted" data-testid="place-filter-shelf-hint">{t('placeFilter.shelfHint')}</span>}
    </>
  )
}
