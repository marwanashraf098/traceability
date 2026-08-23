import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import { useState, useEffect, lazy, Suspense } from 'react'
import { getAccessToken, setAccessToken, clearAccessToken } from './auth'
import { ToastProvider } from './components/ui'
import Layout from './components/Layout'
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
import Privacy from './pages/Privacy'
import Terms from './pages/Terms'

/**
 * Async auth gate. On page reload the in-memory access token is gone, so we call
 * POST /api/v1/auth/refresh — the browser sends the traced_refresh httpOnly cookie
 * automatically. If the cookie is valid we get a new access token and proceed; if not
 * (expired, revoked, or absent) we redirect to /login.
 *
 * Fast path: if the access token is already in memory (in-session navigation) we skip
 * the refresh call entirely — no spinner, no extra RTT.
 */
function RequireAuth({ children }: { children: React.ReactNode }) {
  const [state, setState] = useState<'loading' | 'authenticated' | 'unauthenticated'>(
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

  if (state === 'loading') {
    return (
      <div className="min-h-screen bg-base flex items-center justify-center">
        <div className="w-8 h-8 rounded-full border-2 border-brand/30 border-t-brand animate-spin" />
      </div>
    )
  }
  if (state === 'unauthenticated') {
    clearAccessToken()
    return <Navigate to="/login" replace />
  }
  return <>{children}</>
}

export default function App() {
  return (
    <BrowserRouter>
      <ToastProvider>
      <Routes>
        <Route path="/"        element={<Landing />} />
        <Route path="/privacy" element={<Privacy />} />
        <Route path="/terms"   element={<Terms />} />
        <Route path="/login"   element={<Login />} />
        <Route path="/signup" element={<Signup />} />
        <Route
          path="/overview"
          element={
            <RequireAuth>
              <Layout><Overview /></Layout>
            </RequireAuth>
          }
        />
        <Route
          path="/orders"
          element={
            <RequireAuth>
              <Layout><Orders /></Layout>
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
              <Layout><ExchangeMapping /></Layout>
            </RequireAuth>
          }
        />
        {/* Absorbed into /inventory Tab 1 (Stock by location) — Phase B consolidation. */}
        <Route path="/catalog" element={<Navigate to="/inventory" replace />} />
        <Route
          path="/receiving"
          element={
            <RequireAuth>
              <Layout><Receiving /></Layout>
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
              <Layout><StockTake /></Layout>
            </RequireAuth>
          }
        />
        {/* NOT Layout-wrapped — full-screen, per the /fulfill precedent */}
        <Route
          path="/stock-take/:id/scan"
          element={
            <RequireAuth>
              <StockTakeScan />
            </RequireAuth>
          }
        />
        <Route
          path="/stock-take/:id/review"
          element={
            <RequireAuth>
              <Layout><StockTakeReview /></Layout>
            </RequireAuth>
          }
        />
        <Route
          path="/transfers"
          element={
            <RequireAuth>
              <Layout><Transfers /></Layout>
            </RequireAuth>
          }
        />
        {/* NOT Layout-wrapped — full-screen, per the /stock-take/:id/scan precedent */}
        <Route
          path="/transfers/:id/scan-out"
          element={
            <RequireAuth>
              <TransferScanOut />
            </RequireAuth>
          }
        />
        <Route
          path="/transfers/:id"
          element={
            <RequireAuth>
              <Layout><TransferDetail /></Layout>
            </RequireAuth>
          }
        />
        <Route
          path="/transfers/:id/reconcile"
          element={
            <RequireAuth>
              <Layout><TransferReconcile /></Layout>
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
              <Layout><ExceptionsPage /></Layout>
            </RequireAuth>
          }
        />
        <Route
          path="/inventory"
          element={
            <RequireAuth>
              <Layout><Inventory /></Layout>
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
          path="/settings"
          element={
            <RequireAuth>
              <Layout><SettingsPage /></Layout>
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
  )
}
