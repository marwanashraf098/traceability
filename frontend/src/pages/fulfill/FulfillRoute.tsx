import { useEffect, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { getPickPackMode, getRoleFromToken, PickPackMode } from '../../api'
import Layout from '../../components/Layout'
import { useStation } from '../../components/StationProvider'
import { useOnboardingProgress } from '../../onboardingProgress'
import { endTour, useActiveTour } from '../../tours/tourState'
import Fulfill from '../Fulfill'
import WaybillPackPage from './WaybillPackPage'
import PickPackChooser from './PickPackChooser'

// Pick & Pack S3 — /fulfill picks the page from the store's packing mode:
//   order_queue  → the existing Pick & Pack page, unchanged
//   waybill_scan → the waybill page; ?view=self-pickup → the existing queue, self-pickup only
// The mode lives in this wrapper (not inside Fulfill) so pages and tests that render <Fulfill />
// directly make exactly the requests they made before. If the mode can't be read, the queue page
// is the safe default.
//
// Onboarding O5 — the first-time "How do you want to pack?" chooser renders HERE, before the page, only when ALL
// hold: the user is the owner, not station mode, the mode is order_queue, progress says pickPackChosen is not set
// AND the tenant has never packed (task 'pack'). Only that case waits for GET /onboarding/progress; everyone else
// gets the page exactly as before (no extra request). The page's "?" (TourHelpButton 'pick_pack_mode') replays the
// chooser for any role — read-only unless owner.

export default function FulfillRoute() {
  const [mode, setMode] = useState<PickPackMode | null>(null)
  const [searchParams] = useSearchParams()
  const { stationMode } = useStation()
  const owner = getRoleFromToken() === 'owner'
  const mayChoose = owner && !stationMode && mode === 'order_queue'
  const [chosen, setChosen] = useState(false)
  const tour = useActiveTour()
  const replay = tour?.id === 'pick_pack_mode'
  // Progress (owner / manager only): the first-time decision, and "Recommended for Bosta" on a replay.
  const progress = useOnboardingProgress(mayChoose || (replay && getRoleFromToken() !== 'worker'))

  useEffect(() => {
    let cancelled = false
    getPickPackMode()
      .then(r => { if (!cancelled) setMode(r?.mode ?? 'order_queue') })
      .catch(() => { if (!cancelled) setMode('order_queue') })
    return () => { cancelled = true }
  }, [])

  if (mode === null) return null
  if (mayChoose && progress === undefined) return null
  const firstTime = mayChoose && !chosen && !!progress && !progress.pickPackChosen
    && progress.tasks.some(t => t.key === 'pack' && !t.tried)
  if (firstTime || replay) {
    return (
      <Layout>
        <PickPackChooser current={mode} bostaConnected={!!progress?.bostaConnected} canChange={owner && !stationMode}
          replay={!firstTime} onDone={m => { setChosen(true); setMode(m); if (replay) endTour(false) }} />
      </Layout>
    )
  }
  // O6-fixes: the Unpack Required alert's Go → opens that cancelled order's unpack panel, whatever the mode.
  // (Its own param — `?order=<id>&unpack=1` is the Packed list's edit-conflict dialog and must stay as it was.)
  const guidedUnpack = searchParams.get('guidedUnpack')
  if (guidedUnpack) return <Fulfill unpackOrderId={guidedUnpack} />
  if (mode === 'order_queue') return <Fulfill />
  if (searchParams.get('view') === 'self-pickup') return <Fulfill selfPickupOnly />
  return <WaybillPackPage />
}
