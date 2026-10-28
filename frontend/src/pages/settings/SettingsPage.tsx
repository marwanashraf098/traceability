import { Navigate, useSearchParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { getRoleFromToken } from '../../api'
import { Tabs } from '../../components/ui'
import BusinessTab from './BusinessTab'
import ConnectionsTab from './ConnectionsTab'
import UsersTab from './UsersTab'
import LocationsTab from './LocationsTab'
import ReturnsPortalTab from './ReturnsPortalTab'
import PickPackTab from './PickPackTab'
import PrivacyTab from './PrivacyTab'
import CouriersTab from './CouriersTab'
import LabelsTab from './LabelsTab'
import PlacesTab from './PlacesTab'
import { useCapabilities } from '../../capabilities'

type TabKey = 'business' | 'connections' | 'users' | 'locations' | 'places' | 'pickpack' | 'labels' | 'couriers' | 'portal' | 'privacy'
const ALL_TABS: TabKey[] = ['business', 'connections', 'users', 'locations', 'pickpack', 'couriers', 'portal']

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
 *
 * Returns portal Step 4e-A adds a fifth tab, "Returns portal" (?tab=portal) — owner and
 * manager, both editable (PUT /tenant/portal-settings allows both); never a worker.
 *
 * Pick & Pack S3 adds "Pick & Pack" (?tab=pickpack) — the packing mode. Owner edits; manager
 * read-only (PUT /tenant/settings is owner-only server-side); never a worker.
 *
 * Non-Bosta couriers G2 adds "Couriers" (?tab=couriers) — the "I ship with other couriers" switch and
 * default carrier (owner edits, manager read-only) and "Test a waybill" (owner + manager).
 *
 * GDPR build A adds "Privacy" (?tab=privacy) — Shopify customer data requests to download.
 * Owner only (every /privacy endpoint is owner-only server-side); managers don't see the tab.
 *
 * Flexible labeling slice 8 adds "Labels" (?tab=labels) — the default receiving mode. Owner and manager edit; shown
 * only while the tenant has flexible labeling (the switch itself is admin-only and not here).
 *
 * Warehouse places S1 adds "Places" (?tab=places, after Locations) — zones / racks / shelves / boxes and their
 * stickers. Owner and manager; shown only while the tenant has places (set by ops, no merchant switch).
 */
export default function SettingsPage() {
  const { t } = useTranslation()
  const role = getRoleFromToken()
  const isOwner = role === 'owner'
  const isWorker = role === 'worker'
  const { flexibleLabeling, places } = useCapabilities()

  const base: TabKey[] = ALL_TABS.flatMap(k =>
    k === 'pickpack' && flexibleLabeling ? ['pickpack', 'labels'] as TabKey[]
      : k === 'locations' && places ? ['locations', 'places'] as TabKey[]
      : [k])
  const visibleTabs: TabKey[] = isWorker ? [] : isOwner ? [...base, 'privacy'] : base

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
        {activeTab === 'places' && <PlacesTab />}
        {activeTab === 'pickpack' && <PickPackTab isOwner={isOwner} />}
        {activeTab === 'labels' && <LabelsTab />}
        {activeTab === 'couriers' && <CouriersTab isOwner={isOwner} />}
        {activeTab === 'portal' && <ReturnsPortalTab />}
        {activeTab === 'privacy' && <PrivacyTab />}
      </div>
    </div>
  )
}
