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
import OrderDetail from './pages/OrderDetail'
import Catalog from './pages/Catalog'
import Receiving from './pages/Receiving'
import Fulfill from './pages/Fulfill'
import LookupPage from './pages/Lookup'
import Returns from './pages/Returns'
import ExceptionsPage from './pages/Exceptions'
import Connections from './pages/Connections'
import Settings from './pages/Settings'
import Users from './pages/Users'
import Onboarding from './pages/Onboarding'
import Inventory from './pages/Inventory'
import PickupSessions from './pages/PickupSessions'
import ShopifyInventory from './pages/ShopifyInventory'
import Locations from './pages/Locations'
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
        <Route
          path="/orders/:id"
          element={
            <RequireAuth>
              <Layout><OrderDetail /></Layout>
            </RequireAuth>
          }
        />
        <Route
          path="/catalog"
          element={
            <RequireAuth>
              <Layout><Catalog /></Layout>
            </RequireAuth>
          }
        />
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
          path="/lookup"
          element={
            <RequireAuth>
              <Layout><LookupPage /></Layout>
            </RequireAuth>
          }
        />
        <Route
          path="/returns"
          element={
            <RequireAuth>
              <Layout><Returns /></Layout>
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
          path="/connections"
          element={
            <RequireAuth>
              <Layout><Connections /></Layout>
            </RequireAuth>
          }
        />
        <Route
          path="/onboarding"
          element={
            <RequireAuth>
              <Layout><Onboarding /></Layout>
            </RequireAuth>
          }
        />
        <Route
          path="/settings"
          element={
            <RequireAuth>
              <Layout><Settings /></Layout>
            </RequireAuth>
          }
        />
        <Route
          path="/users"
          element={
            <RequireAuth>
              <Layout><Users /></Layout>
            </RequireAuth>
          }
        />
        <Route
          path="/shopify-inventory"
          element={
            <RequireAuth>
              <Layout><ShopifyInventory /></Layout>
            </RequireAuth>
          }
        />
        <Route
          path="/locations"
          element={
            <RequireAuth>
              <Layout><Locations /></Layout>
            </RequireAuth>
          }
        />
        {import.meta.env.DEV && StyleGuide && (
          <Route path="/_styleguide" element={<Suspense fallback={null}><StyleGuide /></Suspense>} />
        )}
        <Route path="*" element={<Navigate to="/overview" replace />} />
      </Routes>
      </ToastProvider>
    </BrowserRouter>
  )
}
