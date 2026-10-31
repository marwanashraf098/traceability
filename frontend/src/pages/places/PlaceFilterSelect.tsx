import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { getPlaces, PlaceNode } from '../../api'
import { Select } from '../../components/ui'

/** Warehouse places S5 — the Inventory place filter's value: '' every place, NO_PLACE, or a place id. */
export const NO_PLACE = '__no_place__'

/**
 * A tree picker over one location's places (zone → rack → shelf / box, indented in tree order) plus "No place yet".
 * A zone or rack means everything under it; a shelf or box means only itself (the server's PlaceScope).
 */
export default function PlaceFilterSelect({ locationId, value, onChange }: {
  locationId: string
  value: string
  onChange: (v: string) => void
}) {
  const { t } = useTranslation()
  const [nodes, setNodes] = useState<PlaceNode[]>([])

  useEffect(() => {
    let cancelled = false
    getPlaces(locationId).then(tree => { if (!cancelled) setNodes(tree.nodes.filter(n => !n.archived)) }).catch(() => setNodes([]))
    return () => { cancelled = true }
  }, [locationId])

  const options = [
    { value: '', label: t('placeFilter.all') },
    { value: NO_PLACE, label: t('placeFilter.noPlace') },
    ...nodes.map(n => ({
      value: n.id,
      label: '   '.repeat(n.depth) + (n.kind === 'box' ? `${n.code} (${t('places.kind.box')})` : n.label),
    })),
  ]
  return <Select value={value} onChange={onChange} options={options} className="w-56" />
}
