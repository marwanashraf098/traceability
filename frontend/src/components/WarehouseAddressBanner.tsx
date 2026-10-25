import { useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { MapPin } from 'lucide-react'
import type { Me } from '../api'
import { Button } from './ui'

// Onboarding gate (V186) — the soft banner for tenants that were connected before the gate existed
// (grandfathered: never gated, not even on the address). From /me's warehouseAddressNeeded; owner only
// (the address endpoint is owner-only). No inputs, no autofocus — it never takes focus from a scan input.
export default function WarehouseAddressBanner({ me }: { me: Me | null }) {
  const { t } = useTranslation()
  const navigate = useNavigate()
  if (!me || me.role !== 'owner' || me.warehouseAddressNeeded !== true) return null
  return (
    <div role="status" data-testid="warehouse-address-banner"
      className="flex-shrink-0 border-b border-trace-blue/25 bg-trace-blue/[0.06] px-[18px] max-[899px]:px-3 py-3 flex items-start gap-3 max-[640px]:flex-col">
      <MapPin size={18} strokeWidth={2} className="text-trace-blue flex-shrink-0 mt-0.5 max-[640px]:hidden" />
      <div className="flex-1 min-w-0">
        <p className="text-small font-semibold text-primary">{t('setupGate.banner.title')}</p>
        <p className="text-small text-muted">{t('setupGate.banner.body')}</p>
      </div>
      <Button variant="secondary" size="sm" className="flex-shrink-0" onClick={() => navigate('/onboarding')}>
        {t('setupGate.banner.cta')}
      </Button>
    </div>
  )
}
