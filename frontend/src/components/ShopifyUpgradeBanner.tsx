import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { AlertTriangle, ShieldAlert, ChevronDown } from 'lucide-react'
import { confirmCustomAppDeleted, type Me } from '../api'
import { Button, cn } from './ui'

// App-wide Shopify upgrade notice — rendered by Layout between the top bar and the page, from /me's
// shopifyUpgradeStage (owner / manager only; the server sends null for a worker):
//   'upgrade'        the store is still on the early-access custom app → upgrade (owner) / ask the owner (manager)
//   'delete_old_app' upgraded to the official app → delete the old custom app; the owner confirms it once
// The two stages can't show together (the server picks one), and the delete instruction never shows while the
// store is still a custom app. No inputs, no autofocus — it never takes focus from a scan input.

export const UPGRADE_FOCUS_URL = '/settings?tab=connections&focus=upgrade'

export default function ShopifyUpgradeBanner({ me, onConfirmed }: { me: Me | null; onConfirmed: () => void }) {
  const { t }    = useTranslation()
  const navigate = useNavigate()
  const [stepsOpen, setStepsOpen]   = useState(false)
  const [confirming, setConfirming] = useState(false)
  const [error, setError]           = useState('')

  const stage = me?.shopifyUpgradeStage ?? null
  if (!me || !stage) return null
  const isOwner = me.role === 'owner'

  async function handleConfirm() {
    setError('')
    setConfirming(true)
    try {
      await confirmCustomAppDeleted()
      onConfirmed()
    } catch {
      setError(t('shopifyUpgradeNotice.delete.confirmError'))
    } finally {
      setConfirming(false)
    }
  }

  if (stage === 'upgrade') {
    return (
      <div role="status" data-testid="shopify-upgrade-banner" data-stage="upgrade"
        className="flex-shrink-0 border-b border-warning/30 bg-warning/10 px-[18px] max-[899px]:px-3 py-3 flex items-start gap-3 max-[640px]:flex-col">
        <AlertTriangle size={18} strokeWidth={2} className="text-warning-text flex-shrink-0 mt-0.5 max-[640px]:hidden" />
        <div className="flex-1 min-w-0">
          <p className="text-small font-semibold text-primary">{t('shopifyUpgradeNotice.upgrade.title')}</p>
          <p className="text-small text-muted">{t('shopifyUpgradeNotice.upgrade.body')}</p>
        </div>
        {isOwner ? (
          <Button variant="primary" size="sm" className="flex-shrink-0" onClick={() => navigate(UPGRADE_FOCUS_URL)}>
            {t('shopifyUpgradeNotice.upgrade.cta')}
          </Button>
        ) : (
          <p className="text-small font-medium text-warning-text flex-shrink-0 self-center max-[640px]:self-start">
            {t('shopifyUpgradeNotice.upgrade.askOwner')}
          </p>
        )}
      </div>
    )
  }

  return (
    <div role="alert" data-testid="shopify-upgrade-banner" data-stage="delete_old_app"
      className="flex-shrink-0 border-b border-danger/30 bg-danger/10 px-[18px] max-[899px]:px-3 py-3 flex items-start gap-3 max-[640px]:flex-col">
      <ShieldAlert size={18} strokeWidth={2} className="text-danger flex-shrink-0 mt-0.5 max-[640px]:hidden" />
      <div className="flex-1 min-w-0">
        <p className="text-small font-semibold text-primary">{t('shopifyUpgradeNotice.delete.title')}</p>
        <p className="text-small text-muted">{t('shopifyUpgradeNotice.delete.body')}</p>
        <button type="button" onClick={() => setStepsOpen(o => !o)} aria-expanded={stepsOpen}
          aria-controls="shopify-delete-steps"
          className="mt-1 inline-flex items-center gap-1 text-small font-medium text-primary hover:underline">
          {t('shopifyUpgradeNotice.delete.howTo')}
          <ChevronDown size={14} strokeWidth={2} className={cn('transition-transform', stepsOpen && 'rotate-180')} />
        </button>
        {stepsOpen && (
          <ol id="shopify-delete-steps" className="mt-1 list-decimal ps-5 text-small text-muted space-y-0.5">
            <li>{t('shopifyUpgradeNotice.delete.step1')}</li>
            <li>{t('shopifyUpgradeNotice.delete.step2')}</li>
            <li>{t('shopifyUpgradeNotice.delete.step3')}</li>
          </ol>
        )}
        {error && <p role="alert" className="mt-1 text-small text-danger">{error}</p>}
      </div>
      {isOwner ? (
        <Button variant="danger" size="sm" className="flex-shrink-0" onClick={handleConfirm} disabled={confirming}>
          {confirming ? t('shopifyUpgradeNotice.delete.confirming') : t('shopifyUpgradeNotice.delete.cta')}
        </Button>
      ) : (
        <p className="text-small font-medium text-danger flex-shrink-0 self-center max-[640px]:self-start">
          {t('shopifyUpgradeNotice.delete.askOwner')}
        </p>
      )}
    </div>
  )
}
