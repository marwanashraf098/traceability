import { useEffect, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { Tabs } from '../components/ui'
import StockTab from './inventory/StockTab'
import BreakdownTab from './inventory/BreakdownTab'
import LedgerTab from './inventory/LedgerTab'
import UntaggedTab from './inventory/UntaggedTab'
import { useCapabilities } from '../capabilities'
import { getTagStock } from '../api'

type TabKey = 'stock' | 'breakdown' | 'ledger' | 'untagged'
const VALID_TABS: TabKey[] = ['stock', 'breakdown', 'ledger', 'untagged']

/**
 * The Inventory screen — one page, three tabs, frontend-only tab state (no route per
 * tab; ?tab= only seeds the initial tab on load, matching the mockup's single-page
 * panel-swap behavior). Absorbs three former standalone pages/routes:
 *   - Catalog.tsx's stock view      -> Tab 1 (Stock by location)
 *   - this page's old status drill  -> folded into Tab 2's accordion piece drill-down
 *   - ShopifyInventory.tsx          -> Tab 3 (Movement ledger)
 * /catalog and /shopify-inventory now redirect here (see App.tsx); their old page
 * files are deleted.
 * Flexible labeling (UI pass 2026-10-25): a fourth tab, "Untagged (N)" — Tag stock, moved here from its own page
 * (the old /tag-stock URL redirects to ?tab=untagged). Switched-on tenants only; the other tabs are unchanged.
 */
export default function Inventory() {
  const { t } = useTranslation()
  const [searchParams] = useSearchParams()
  const { flexibleLabeling } = useCapabilities()
  const fromUrl = searchParams.get('tab')
  const initialTab = (() => {
    return VALID_TABS.includes(fromUrl as TabKey) ? (fromUrl as TabKey) : 'stock'
  })()
  const [tab, setTab] = useState<TabKey>(initialTab)
  const [untagged, setUntagged] = useState<number | null>(null)

  // The tab's count — only for a switched-on tenant (no request otherwise).
  useEffect(() => {
    if (!flexibleLabeling) return
    let cancelled = false
    getTagStock().then(d => { if (!cancelled) setUntagged(d.untagged) }).catch(() => { /* count stays hidden */ })
    return () => { cancelled = true }
  }, [flexibleLabeling])

  // ?tab=untagged without the switch (or before /me says so) falls back to the first tab.
  const active: TabKey = tab === 'untagged' && !flexibleLabeling ? 'stock' : tab

  return (
    <div className="space-y-4">
      <h1 className="text-h1 text-primary">{t('inventory.title')}</h1>
      <Tabs
        tabs={[
          { key: 'stock',      label: t('inventory.tabs.stock') },
          { key: 'breakdown',  label: t('inventory.tabs.breakdown') },
          { key: 'ledger',     label: t('inventory.tabs.ledger') },
          ...(flexibleLabeling ? [{ key: 'untagged', label: untagged === null
            ? t('inventory.tabs.untaggedPlain') : t('inventory.tabs.untagged', { count: untagged }) }] : []),
        ]}
        activeKey={active}
        onChange={key => setTab(key as TabKey)}
      />

      {active === 'stock' && <StockTab />}
      {active === 'breakdown' && <BreakdownTab />}
      {active === 'ledger' && <LedgerTab />}
      {active === 'untagged' && <UntaggedTab onTotal={setUntagged} />}
    </div>
  )
}
