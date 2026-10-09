import { Navigate, useLocation, useParams } from 'react-router-dom'
import { isReadyPage } from '../../analytics/flag'
import SummaryPage from './SummaryPage'

/**
 * /analytics/:page — lazy-loaded from App.tsx so analytics code stays out of the main bundle.
 * A page that isn't built yet (or a typo) lands on Summary, keeping the period in the URL.
 */
export default function AnalyticsRoute() {
  const { page } = useParams()
  const { search } = useLocation()
  if (!page || !isReadyPage(page)) return <Navigate to={`/analytics/summary${search}`} replace />
  switch (page) {
    case 'summary':
      return <SummaryPage />
    default:
      return <Navigate to={`/analytics/summary${search}`} replace />
  }
}
