import { ReactNode, useEffect, useState } from 'react'
import { Navigate, useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { getRoleFromToken } from '../api'
import { loadSetupState, takeIntroRedirect, useSetupState, type SetupIntro } from '../setupGate'
import { useStation } from './StationProvider'
import OnboardingGate, { AddressStep, GateFrame } from '../pages/onboarding/OnboardingGate'

function GateSpinner() {
  return (
    <div className="min-h-screen bg-bg flex items-center justify-center" data-testid="setup-gate-loading">
      <div className="w-8 h-8 rounded-full border-2 border-brand/30 border-t-brand animate-spin" />
    </div>
  )
}

/**
 * Onboarding gate (V186) — frontend only. Wraps an owner / manager screen OUTSIDE its Layout: while
 * GET /me's setupStep is not null the full-screen gate renders instead of the screen. Workers and a
 * station device (StationGate already ran in RequireAuth) are never gated; a failed /me reads as not
 * gated. The server decides who is gated (SetupStatus: demo / review tenants never are).
 */
export function SetupGate({ children }: { children: ReactNode }) {
  const role = getRoleFromToken()
  const { stationMode } = useStation()
  if (stationMode || (role !== 'owner' && role !== 'manager')) return <>{children}</>
  return <SetupGateInner>{children}</SetupGateInner>
}

function SetupGateInner({ children }: { children: ReactNode }) {
  const state = useSetupState()
  const [done, setDone] = useState(false)
  // After the gate is finished here: the intro to go to next (undefined while /me is asked again).
  const [after, setAfter] = useState<SetupIntro | undefined>(undefined)
  if (state === undefined) return <GateSpinner />
  if (done) return after === undefined ? <GateSpinner /> : <IntroRedirect intro={after}>{children}</IntroRedirect>
  if (state.step === null) return <IntroRedirect intro={state.intro}>{children}</IntroRedirect>
  return <OnboardingGate step={state.step} name={state.name} onDone={() => {
    setDone(true)
    loadSetupState().then(s => setAfter(s.intro))
  }} />
}

/**
 * Onboarding O2 — once the gate is done, an OWNER goes to the setup intro once (GET /me setupIntro:
 * Flow-3 courier, then Flow-4). Those screens live outside SetupGate, so they can never lock anyone in:
 * the redirect happens at most once per screen per page load (takeIntroRedirect), managers, workers and
 * station mode never get here (SetupGate), and the backend never sends an intro for the demo / review
 * tenants, a hidden card or a tenant that has seen it.
 */
function IntroRedirect({ intro, children }: { intro: SetupIntro; children: ReactNode }) {
  const navigate = useNavigate()
  const go = getRoleFromToken() === 'owner' ? intro : null
  const [checked, setChecked] = useState(go === null)
  useEffect(() => {
    if (go === null) return
    if (takeIntroRedirect(go)) navigate(go === 'courier' ? '/setup/courier' : '/setup/ready', { replace: true })
    else setChecked(true)
  }, [go, navigate])
  if (!checked) return <GateSpinner />
  return <>{children}</>
}

/**
 * /onboarding — the gate when setupStep is not null (via the route's SetupGate); otherwise, for an owner
 * whose main warehouse still has no address (grandfathered — the soft banner's target), the address form
 * alone; anyone else goes to Overview.
 */
export function OnboardingRoute() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const state = useSetupState()
  if (state === undefined) return <GateSpinner />
  if (getRoleFromToken() !== 'owner' || !state.warehouseAddressNeeded) return <Navigate to="/overview" replace />
  return (
    <GateFrame onExit={() => navigate('/overview')} exitLabel={t('setupGate.backToTraced')}>
      <AddressStep mode="banner" onSaved={() => navigate('/overview', { replace: true })} />
    </GateFrame>
  )
}
