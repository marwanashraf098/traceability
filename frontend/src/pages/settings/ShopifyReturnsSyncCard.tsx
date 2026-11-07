import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { ShopifyReturnsSyncSettings, TransferCommandError, getShopifyReturnsSync, setShopifyReturnsSync } from '../../api'
import { Toggle, useToast } from '../../components/ui'
import { SavesRightAway } from './portal/ui'

/**
 * Shopify-native returns S5a — "Create returns in Shopify" (Settings › Returns portal). Shown only when the backend
 * says visible (the tenant is in the rollout list); saves right away; only the owner can switch it.
 */
export default function ShopifyReturnsSyncCard({ owner }: { owner: boolean }) {
  const { t, i18n } = useTranslation()
  const { toast } = useToast()
  const [s, setS] = useState<ShopifyReturnsSyncSettings | null>(null)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    let live = true
    getShopifyReturnsSync().then(r => { if (live) setS(r) }).catch(() => { if (live) setS(null) })
    return () => { live = false }
  }, [])

  if (!s || s.visible !== true) return null
  const k = 'settings.portal.shopifySync'

  async function change(v: boolean) {
    setBusy(true)
    try {
      setS(await setShopifyReturnsSync(v))
      toast({ tone: 'success', message: t(v ? `${k}.turnedOn` : `${k}.turnedOff`) })
    } catch (e) {
      toast({ tone: 'error', message: e instanceof TransferCommandError
        ? (i18n.language === 'ar' ? e.messageAr : e.messageEn) : t(`${k}.saveFailed`) })
    } finally {
      setBusy(false)
    }
  }

  return (
    <section className="rounded-2xl border border-line bg-surface p-5 space-y-2" data-testid="shopify-returns-sync-card">
      <p className="text-body font-semibold text-primary">{t(`${k}.title`)}{owner && <SavesRightAway />}</p>
      <p className="text-small text-muted">{t(`${k}.body`)}</p>
      <Toggle checked={!!s.enabled} onChange={change} disabled={!owner || busy} label={t(`${k}.toggle`)} />
      {s.enabled && s.permissionGranted === false && (
        <p className="text-small text-warning-text" data-testid="shopify-returns-sync-permission">{t(`${k}.permissionMissing`)}</p>
      )}
      {s.enabled && <p className="text-caption text-muted">{t(`${k}.fromNowOn`)}</p>}
    </section>
  )
}
