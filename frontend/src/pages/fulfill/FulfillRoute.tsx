import { useEffect, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { getPickPackMode, PickPackMode } from '../../api'
import Fulfill from '../Fulfill'
import WaybillPackPage from './WaybillPackPage'

// Pick & Pack S3 — /fulfill picks the page from the store's packing mode:
//   order_queue  → the existing Pick & Pack page, unchanged
//   waybill_scan → the waybill page; ?view=self-pickup → the existing queue, self-pickup only
// The mode lives in this wrapper (not inside Fulfill) so pages and tests that render <Fulfill />
// directly make exactly the requests they made before. If the mode can't be read, the queue page
// is the safe default.

export default function FulfillRoute() {
  const [mode, setMode] = useState<PickPackMode | null>(null)
  const [searchParams] = useSearchParams()

  useEffect(() => {
    let cancelled = false
    getPickPackMode()
      .then(r => { if (!cancelled) setMode(r?.mode ?? 'order_queue') })
      .catch(() => { if (!cancelled) setMode('order_queue') })
    return () => { cancelled = true }
  }, [])

  if (mode === null) return null
  if (mode === 'order_queue') return <Fulfill />
  if (searchParams.get('view') === 'self-pickup') return <Fulfill selfPickupOnly />
  return <WaybillPackPage />
}
