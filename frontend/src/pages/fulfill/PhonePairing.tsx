import { useTranslation } from 'react-i18next'
import { Smartphone, WifiOff } from 'lucide-react'
import { Button } from '../../components/ui'
import type { ScanPairingStatus } from '../../api'

// S6 — phone as scanner, the tablet's header control (PackSessionScreen): "Use phone" (opens
// PhonePairModal — the QR), or once the phone has claimed it, "Phone connected · <device>" with
// Unpair.

/** Header control: "Use phone", or the connected / waiting chip with Unpair. */
export function PhoneControl({ pairing, linkDown, busy, onUsePhone, onUnpair }: {
  pairing: ScanPairingStatus | null
  linkDown: boolean
  busy: boolean
  onUsePhone: () => void
  onUnpair: () => void
}) {
  const { t } = useTranslation()
  const status = pairing?.status
  if (status === 'connected' || status === 'waiting') {
    return (
      <span className="inline-flex items-center gap-2" data-testid="phone-chip">
        <span className={'inline-flex items-center gap-1.5 text-small font-semibold rounded-full px-3 py-1 ' +
          (linkDown ? 'bg-warning/[0.14] text-warning-text'
            : status === 'connected' ? 'bg-success/[0.12] text-success-text' : 'bg-elevated text-muted')}>
          {linkDown ? <WifiOff size={14} /> : <Smartphone size={14} />}
          {linkDown ? t('fulfill.waybill.phone.reconnecting')
            : status === 'connected' ? t('fulfill.waybill.phone.connected', { device: pairing?.deviceLabel ?? '' })
            : t('fulfill.waybill.phone.waiting')}
        </span>
        <Button variant="ghost" size="sm" onClick={onUnpair} disabled={busy}>
          {status === 'connected' ? t('fulfill.waybill.phone.unpair') : t('fulfill.waybill.phone.cancel')}
        </Button>
      </span>
    )
  }
  return (
    <Button variant="secondary" size="sm" iconStart={Smartphone} onClick={onUsePhone} disabled={busy}>
      {t('fulfill.waybill.phone.usePhone')}
    </Button>
  )
}
