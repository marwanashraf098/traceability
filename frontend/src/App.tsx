import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import Layout from './components/Layout'
import Login from './pages/Login'
import Orders from './pages/Orders'
import OrderDetail from './pages/OrderDetail'
import Catalog from './pages/Catalog'
import Receiving from './pages/Receiving'
import Fulfill from './pages/Fulfill'
import LookupPage from './pages/Lookup'

function RequireAuth({ children }: { children: React.ReactNode }) {
  return localStorage.getItem('token') ? <>{children}</> : <Navigate to="/login" replace />
}

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/login" element={<Login />} />
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
        <Route path="*" element={<Navigate to="/orders" replace />} />
      </Routes>
    </BrowserRouter>
  )
}
