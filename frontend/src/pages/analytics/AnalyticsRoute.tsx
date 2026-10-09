import { Navigate, useLocation, useParams } from 'react-router-dom'
import { isReadyPage } from '../../analytics/flag'
import SummaryPage from './SummaryPage'
import RevenuePage from './RevenuePage'
import DeliveryPage from './DeliveryPage'
import MoneyPage from './MoneyPage'
import OrdersPage from './OrdersPage'
import { AnalyticsDrawers } from './drawers'

/**
 * /analytics/:page — lazy-loaded from App.tsx so analytics code stays out of the main bundle.
 * A page that isn't built yet (or a typo) lands on Summary, keeping the period in the URL.
 */
export default function AnalyticsRoute() {
  const { page } = useParams()
  const { search } = useLocation()
  if (!page || !isReadyPage(page)) return <Navigate to={`/analytics/summary${search}`} replace />
  const body = page === 'summary' ? <SummaryPage />
    : page === 'revenue' ? <RevenuePage />
    : page === 'delivery' ? <DeliveryPage />
    : page === 'money' ? <MoneyPage />
    : page === 'orders' ? <OrdersPage />
    : null
  if (!body) return <Navigate to={`/analytics/summary${search}`} replace />
  // The SKU drawer and the drill-down open from the URL on any page (?sku=, ?drill=).
  return <>{body}<AnalyticsDrawers /></>
}
