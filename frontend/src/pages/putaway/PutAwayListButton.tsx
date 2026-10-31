import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { Layers } from 'lucide-react'
import { Button } from '../../components/ui'
import { useCapabilities } from '../../capabilities'
import { getPutAwayList, PutAwayListSource } from '../../api'
import { putAwayListPath } from './putAwayListPaths'

/**
 * Warehouse places S4 — the one button that opens a flow's put-away list (Receiving, Tag stock summary, the closed
 * Scan returns summary, the transfer reconcile end screen). Without places it renders nothing and asks nothing, so
 * the screen is exactly today's. With places it reads the list in the background (never blocking the screen) and
 * shows only while a piece on it is still waiting at a location that has shelves or boxes — a receipt that went in
 * count-only (untagged), a return session with nothing restocked, or a list already put away shows no button.
 */
export default function PutAwayListButton({ source, sourceId, label, variant = 'secondary', size = 'sm' }: {
  source: PutAwayListSource
  sourceId: string
  label: string
  variant?: 'primary' | 'secondary'
  size?: 'sm' | 'md' | 'lg'
}) {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const { places } = useCapabilities()
  const [waiting, setWaiting] = useState(0)

  useEffect(() => {
    if (!places || !sourceId) return
    let cancelled = false
    getPutAwayList(source, sourceId)
      .then(l => { if (!cancelled) setWaiting(l.hasPlaces ? l.total - l.placed : 0) })
      .catch(() => { /* no button */ })
    return () => { cancelled = true }
  }, [places, source, sourceId])

  if (!places || waiting <= 0) return null
  return (
    <Button variant={variant} size={size} iconStart={Layers} data-testid={`put-away-list-button-${source}`}
      onClick={() => navigate(putAwayListPath(source, sourceId))}>
      {label}{' · '}{t('putAway.list.waitingCount', { count: waiting })}
    </Button>
  )
}
