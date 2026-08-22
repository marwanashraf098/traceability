import { useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { Tabs } from '../components/ui'
import StockTab from './inventory/StockTab'
import BreakdownTab from './inventory/BreakdownTab'
import LedgerTab from './inventory/LedgerTab'

type TabKey = 'stock' | 'breakdown' | 'ledger'
const VALID_TABS: TabKey[] = ['stock', 'breakdown', 'ledger']

/**
 * The Inventory screen — one page, three tabs, frontend-only tab state (no route per
 * tab; ?tab= only seeds the initial tab on load, matching the mockup's single-page
 * panel-swap behavior). Absorbs three former standalone pages/routes:
 *   - Catalog.tsx's stock view      -> Tab 1 (Stock by location)
 *   - this page's old status drill  -> folded into Tab 2's accordion piece drill-down
 *   - ShopifyInventory.tsx          -> Tab 3 (Movement ledger)
 * /catalog and /shopify-inventory now redirect here (see App.tsx); their old page
 * files are deleted.
 */
export default function Inventory() {
  const { t } = useTranslation()
  const [searchParams] = useSearchParams()

  const initialTab = (() => {
    const fromUrl = searchParams.get('tab')
    return VALID_TABS.includes(fromUrl as TabKey) ? (fromUrl as TabKey) : 'stock'
  })()
  const [tab, setTab] = useState<TabKey>(initialTab)

  return (
    <div className="space-y-4">
      <h1 className="text-h1 text-primary">{t('inventory.title')}</h1>

      <Tabs
        tabs={[
          { key: 'stock',      label: t('inventory.tabs.stock') },
          { key: 'breakdown',  label: t('inventory.tabs.breakdown') },
          { key: 'ledger',     label: t('inventory.tabs.ledger') },
        ]}
        activeKey={tab}
        onChange={key => setTab(key as TabKey)}
      />

      {tab === 'stock' && <StockTab />}
      {tab === 'breakdown' && <BreakdownTab />}
      {tab === 'ledger' && <LedgerTab />}
    </div>
  )
}
