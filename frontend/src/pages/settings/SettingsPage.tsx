import { Navigate, useSearchParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { getRoleFromToken } from '../../api'
import { Tabs } from '../../components/ui'
import BusinessTab from './BusinessTab'
import ConnectionsTab from './ConnectionsTab'
import UsersTab from './UsersTab'
import LocationsTab from './LocationsTab'

type TabKey = 'business' | 'connections' | 'users' | 'locations'
const ALL_TABS: TabKey[] = ['business', 'connections', 'users', 'locations']

/**
 * The consolidated Settings screen — one page, four tabs, replacing the four
 * former standalone routes (/connections /users /locations /settings). Each
 * tab's body is only mounted while active, so it lazy-calls its own existing
 * endpoint on activation — no combined endpoint, no data lifted up here.
 *
 * Client-side gating only (cosmetic) — every tab body still goes through its
 * own server @PreAuthorize on every call:
 *   worker  — sees no tabs at all (Business + Connections excluded per spec;
 *             Users/Locations excluded too since GET /users is OWNER/MANAGER-only
 *             server-side and this nav item was never worker-visible before this
 *             consolidation) -> redirected to /overview before any tab renders.
 *   manager — all 4 tabs; Business read-only (banner, disabled fields, no Save);
 *             Connections read-only (status visible, actions disabled).
 *   owner   — full access to all 4 tabs.
 */
export default function SettingsPage() {
  const { t } = useTranslation()
  const role = getRoleFromToken()
  const isOwner = role === 'owner'
  const isWorker = role === 'worker'

  const visibleTabs: TabKey[] = isWorker ? [] : ALL_TABS

  const [searchParams, setSearchParams] = useSearchParams()

  if (visibleTabs.length === 0) {
    return <Navigate to="/overview" replace />
  }

  const requested = searchParams.get('tab') as TabKey | null
  const activeTab = requested && visibleTabs.includes(requested) ? requested : visibleTabs[0]

  function selectTab(key: string) {
    setSearchParams({ tab: key })
  }

  return (
    <div className="space-y-4">
      <h1 className="text-h1 text-primary">{t('settings.title')}</h1>

      <Tabs
        tabs={visibleTabs.map(key => ({ key, label: t(`settings.tabs.${key}`) }))}
        activeKey={activeTab}
        onChange={selectTab}
      />

      <div className="pt-2">
        {activeTab === 'business' && <BusinessTab isOwner={isOwner} />}
        {activeTab === 'connections' && <ConnectionsTab readOnly={!isOwner} />}
        {activeTab === 'users' && <UsersTab />}
        {activeTab === 'locations' && <LocationsTab />}
      </div>
    </div>
  )
}
