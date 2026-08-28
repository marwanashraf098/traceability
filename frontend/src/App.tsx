import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import { useState, useEffect, lazy, Suspense } from 'react'
import { getAccessToken, setAccessToken, clearAccessToken } from './auth'
import { getRoleFromToken } from './api'
import { ToastProvider } from './components/ui'
import Layout from './components/Layout'
import { StationProvider, useStation } from './components/StationProvider'
import StationGate from './components/StationGate'
// StyleGuide is DEV-only — lazy import ensures Rollup dead-code-eliminates
// the entire module when import.meta.env.DEV === false (production build).
const StyleGuide = import.meta.env.DEV
  ? lazy(() => import('./pages/StyleGuide'))
  : null
import Landing from './pages/Landing'
import Login from './pages/Login'
import Signup from './pages/Signup'
import Overview from './pages/Overview'
import Orders from './pages/Orders'
import Receiving from './pages/Receiving'
import Fulfill from './pages/Fulfill'
import GatherList from './pages/GatherList'
import StockTake from './pages/StockTake'
import StockTakeScan from './pages/StockTakeScan'
import StockTakeReview from './pages/StockTakeReview'
import Transfers from './pages/Transfers'
import TransferScanOut from './pages/TransferScanOut'
import TransferDetail from './pages/TransferDetail'
import TransferReconcile from './pages/TransferReconcile'
import LookupPage from './pages/Lookup'
import Returns from './pages/Returns'
import ExceptionsPage from './pages/Exceptions'
import ExchangeMapping from './pages/exchanges/ExchangeMapping'
import SettingsPage from './pages/settings/SettingsPage'
import Inventory from './pages/Inventory'
import PickupSessions from './pages/PickupSessions'
import WorkerHome from './pages/WorkerHome'
import Privacy from './pages/Privacy'
import Terms from './pages/Terms'

type AuthRefreshState = 'loading' | 'authenticated' | 'unauthenticated'

/**
 * Shared by RequireAuth and RootRoute — one refresh attempt per fresh load. On page
 * reload the in-memory access token is gone, so we call POST /api/v1/auth/refresh —
 * the browser sends the traced_refresh httpOnly cookie automatically. If the cookie
 * is valid we get a new access token and proceed; if not (expired, revoked, or
 * absent) callers treat 'unauthenticated' as their logged-out case.
 *
 * Fast path: if the access token is already in memory (in-session navigation) we skip
 * the refresh call entirely — no spinner, no extra RTT.
 */
function useAuthRefresh(): AuthRefreshState {
  const [state, setState] = useState<AuthRefreshState>(
    () => getAccessToken() !== null ? 'authenticated' : 'loading'
  )

  useEffect(() => {
    if (state !== 'loading') return
    fetch('/api/v1/auth/refresh', { method: 'POST', credentials: 'include' })
      .then(res => {
        if (!res.ok) { setState('unauthenticated'); return null }
        return res.json() as Promise<{ accessToken: string }>
      })
      .then(data => {
        if (data) { setAccessToken(data.accessToken); setState('authenticated') }
      })
      .catch(() => setState('unauthenticated'))
  }, []) // eslint-disable-line react-hooks/exhaustive-deps

  return state
}

function AuthLoadingSpinner() {
  return (
    <div className="min-h-screen bg-base flex items-center justify-center">
      <div className="w-8 h-8 rounded-full border-2 border-brand/30 border-t-brand animate-spin" />
    </div>
  )
}

export function RequireAuth({ children }: { children: React.ReactNode }) {
  const state = useAuthRefresh()
  const { stationMode, currentWorker } = useStation()

  if (state === 'loading') {
    return <AuthLoadingSpinner />
  }
  if (state === 'unauthenticated') {
    clearAccessToken()
    return <Navigate to="/login" replace />
  }
  // Worker Station Gate (Phase C): every fresh open (reload/reboot resets
  // currentWorker to null, in-memory only) lands on the gate whenever the
  // device is in station mode — never a silent fallback to whoever's access
  // token /auth/refresh happened to restore.
  if (stationMode && !currentWorker) {
    return <StationGate />
  }
  return <>{children}</>
}

/**
 * Root path "/". A logged-out visitor sees the Landing page exactly as before.
 * A logged-in device (valid traced_refresh cookie) is forwarded into the app
 * instead — a warehouse tablet reopening the bare domain should never see
 * marketing content it has to click through. Reuses the same one-shot
 * /auth/refresh probe as RequireAuth (no new auth mechanism) and the same
 * stationMode/role routing RequireAuth + OwnerOnlyRoute/WorkerOnlyRoute use
 * elsewhere, so a fresh open lands on the same screen App already sends that
 * user to post-login.
 */
export function RootRoute() {
  const state = useAuthRefresh()
  const { stationMode, currentWorker } = useStation()

  if (state === 'loading') {
    return <AuthLoadingSpinner />
  }
  if (state === 'unauthenticated') {
    return <Landing />
  }
  // Same precedence as RequireAuth: station-mode gate wins over role routing.
  if (stationMode && !currentWorker) {
    return <Navigate to="/overview" replace />
  }
  if (getRoleFromToken() === 'worker') {
    return <Navigate to="/worker-home" replace />
  }
  return <Navigate to="/overview" replace />
}

/**
 * Guards the owner/manager-only screens (Overview, Orders, Inventory, Receiving,
 * Stock Take, Transfers, Exceptions, Settings — the same set hidden from the
 * worker sidebar nav in Layout.tsx). A worker who reaches one of these routes
 * (typed URL, stale bookmark, kiosk default) is redirected to /worker-home
 * instead of rendering a screen whose API calls will 403.
 */
export function OwnerOnlyRoute({ children }: { children: React.ReactNode }) {
  if (getRoleFromToken() === 'worker') {
    return <Navigate to="/worker-home" replace />
  }
  return <>{children}</>
}

/** /worker-home is worker-only — owner/manager land on /overview instead. */
export function WorkerOnlyRoute({ children }: { children: React.ReactNode }) {
  const role = getRoleFromToken()
  if (role === 'owner' || role === 'manager') {
    return <Navigate to="/overview" replace />
  }
  return <>{children}</>
}

export default function App() {
  return (
    // StationProvider sits ABOVE the router so currentWorker survives route
    // navigation between worker screens — only a true reload resets it.
    <StationProvider>
    <BrowserRouter>
      <ToastProvider>
      <Routes>
        <Route path="/"        element={<RootRoute />} />
        <Route path="/privacy" element={<Privacy />} />
        <Route path="/terms"   element={<Terms />} />
        <Route path="/login"   element={<Login />} />
        <Route path="/signup" element={<Signup />} />
        <Route
          path="/overview"
          element={
            <RequireAuth>
              <OwnerOnlyRoute>
                <Layout><Overview /></Layout>
              </OwnerOnlyRoute>
            </RequireAuth>
          }
        />
        <Route
          path="/orders"
          element={
            <RequireAuth>
              <OwnerOnlyRoute>
                <Layout><Orders /></Layout>
              </OwnerOnlyRoute>
            </RequireAuth>
          }
        />
        {/* /orders/:id (OrderDetail.tsx) intentionally unrouted — Orders fix pass:
            order-number link + row click both open OrderDrawer instead. OrderDetail.tsx
            is left in place (not deleted) since OrderDrawer still imports ShipmentCard/
            groupHistory/toRawDisplay from it. */}
        <Route
          path="/exchanges/:id"
          element={
            <RequireAuth>
              <OwnerOnlyRoute>
                <Layout><ExchangeMapping /></Layout>
              </OwnerOnlyRoute>
            </RequireAuth>
          }
        />
        {/* Absorbed into /inventory Tab 1 (Stock by location) — Phase B consolidation. */}
        <Route path="/catalog" element={<Navigate to="/inventory" replace />} />
        <Route
          path="/receiving"
          element={
            <RequireAuth>
              <OwnerOnlyRoute>
                <Layout><Receiving /></Layout>
              </OwnerOnlyRoute>
            </RequireAuth>
          }
        />
        <Route
          path="/fulfill"
          element={
            <RequireAuth>
              <Fulfill />
            </RequireAuth>
          }
        />
        <Route
          path="/fulfill/gather"
          element={
            <RequireAuth>
              <GatherList />
            </RequireAuth>
          }
        />
        <Route
          path="/stock-take"
          element={
            <RequireAuth>
              <OwnerOnlyRoute>
                <Layout><StockTake /></Layout>
              </OwnerOnlyRoute>
            </RequireAuth>
          }
        />
        {/* NOT Layout-wrapped — full-screen, per the /fulfill precedent */}
        <Route
          path="/stock-take/:id/scan"
          element={
            <RequireAuth>
              <OwnerOnlyRoute>
                <StockTakeScan />
              </OwnerOnlyRoute>
            </RequireAuth>
          }
        />
        <Route
          path="/stock-take/:id/review"
          element={
            <RequireAuth>
              <OwnerOnlyRoute>
                <Layout><StockTakeReview /></Layout>
              </OwnerOnlyRoute>
            </RequireAuth>
          }
        />
        <Route
          path="/transfers"
          element={
            <RequireAuth>
              <OwnerOnlyRoute>
                <Layout><Transfers /></Layout>
              </OwnerOnlyRoute>
            </RequireAuth>
          }
        />
        {/* NOT Layout-wrapped — full-screen, per the /stock-take/:id/scan precedent */}
        <Route
          path="/transfers/:id/scan-out"
          element={
            <RequireAuth>
              <OwnerOnlyRoute>
                <TransferScanOut />
              </OwnerOnlyRoute>
            </RequireAuth>
          }
        />
        <Route
          path="/transfers/:id"
          element={
            <RequireAuth>
              <OwnerOnlyRoute>
                <Layout><TransferDetail /></Layout>
              </OwnerOnlyRoute>
            </RequireAuth>
          }
        />
        <Route
          path="/transfers/:id/reconcile"
          element={
            <RequireAuth>
              <OwnerOnlyRoute>
                <Layout><TransferReconcile /></Layout>
              </OwnerOnlyRoute>
            </RequireAuth>
          }
        />
        <Route
          path="/lookup"
          element={
            <RequireAuth>
              <Layout><LookupPage /></Layout>
            </RequireAuth>
          }
        />
        {/* NOT Layout-wrapped — full-screen open session, per the /fulfill precedent.
            Returns applies <Layout> internally around its landing (list) view only. */}
        <Route
          path="/returns"
          element={
            <RequireAuth>
              <Returns />
            </RequireAuth>
          }
        />
        <Route
          path="/exceptions"
          element={
            <RequireAuth>
              <OwnerOnlyRoute>
                <Layout><ExceptionsPage /></Layout>
              </OwnerOnlyRoute>
            </RequireAuth>
          }
        />
        <Route
          path="/inventory"
          element={
            <RequireAuth>
              <OwnerOnlyRoute>
                <Layout><Inventory /></Layout>
              </OwnerOnlyRoute>
            </RequireAuth>
          }
        />
        <Route
          path="/pickups"
          element={
            <RequireAuth>
              <Layout><PickupSessions /></Layout>
            </RequireAuth>
          }
        />
        <Route
          path="/worker-home"
          element={
            <RequireAuth>
              <WorkerOnlyRoute>
                <Layout><WorkerHome /></Layout>
              </WorkerOnlyRoute>
            </RequireAuth>
          }
        />
        <Route
          path="/settings"
          element={
            <RequireAuth>
              <OwnerOnlyRoute>
                <Layout><SettingsPage /></Layout>
              </OwnerOnlyRoute>
            </RequireAuth>
          }
        />
        {/* Absorbed into /settings — Settings consolidation. Old direct links keep resolving. */}
        <Route path="/connections" element={<Navigate to="/settings?tab=connections" replace />} />
        <Route path="/users"       element={<Navigate to="/settings?tab=users" replace />} />
        <Route path="/locations"   element={<Navigate to="/settings?tab=locations" replace />} />
        {/* Folded into the Overview onboarding card — Settings consolidation. */}
        <Route path="/onboarding" element={<Navigate to="/overview" replace />} />
        {/* Absorbed into /inventory Tab 3 (Movement ledger) — Phase B consolidation. */}
        <Route path="/shopify-inventory" element={<Navigate to="/inventory" replace />} />
        {import.meta.env.DEV && StyleGuide && (
          <Route path="/_styleguide" element={<Suspense fallback={null}><StyleGuide /></Suspense>} />
        )}
        <Route path="*" element={<Navigate to="/overview" replace />} />
      </Routes>
      </ToastProvider>
    </BrowserRouter>
    </StationProvider>
  )
}
