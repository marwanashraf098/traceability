import { ReactNode, useState } from 'react'
import { Navigate, useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { getRoleFromToken } from '../api'
import { useSetupState } from '../setupGate'
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
  if (state === undefined) return <GateSpinner />
  if (done || state.step === null) return <>{children}</>
  return <OnboardingGate step={state.step} onDone={() => setDone(true)} />
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
