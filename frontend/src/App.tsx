import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import Layout from './components/Layout'
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
import Privacy from './pages/Privacy'
import Terms from './pages/Terms'

function RequireAuth({ children }: { children: React.ReactNode }) {
  return localStorage.getItem('token') ? <>{children}</> : <Navigate to="/login" replace />
}

export default function App() {
  return (
    <BrowserRouter>
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
        <Route path="*" element={<Navigate to="/overview" replace />} />
      </Routes>
    </BrowserRouter>
  )
}
