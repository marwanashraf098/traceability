import { useEffect } from 'react'
import { useTranslation } from 'react-i18next'
import { Smartphone, WifiOff } from 'lucide-react'
import { Button } from '../components/ui'
import { usePhoneStatus } from './PhoneScanProvider'

// Q1 — the floating phone control, on every authenticated page (RequireAuth renders it beside
// the page — full-screen scan screens included, which have no Layout): "Use phone" (the QR
// modal), "Waiting for the phone…" / "Phone connected · <device>" with Cancel / Unpair, and
// "Phone link reconnecting…" after 5 s down. Mounting it is what tells PhoneScanProvider an
// authenticated page is showing (load the pairing, open the stream).

export default function PhoneControl() {
  const { t } = useTranslation()
  const phone = usePhoneStatus()
  const setActive = phone?.setActive
  useEffect(() => {
    if (!setActive) return
    setActive(true)
    return () => setActive(false)
  }, [setActive])
  if (!phone || !phone.pairing) return null

  const status = phone.pairing.status
  const live = status === 'connected' || status === 'waiting'
  return (
    <div className="fixed bottom-4 end-4 z-40 print:hidden" data-testid="phone-control">
      {live ? (
        <span className="inline-flex items-center gap-1 rounded-full bg-panel border border-line shadow-e2 ps-3 pe-1 py-1"
          data-testid="phone-chip" data-state={phone.reconnecting ? 'reconnecting' : status}>
          <span className={'inline-flex items-center gap-1.5 text-small font-semibold ' +
            (phone.reconnecting ? 'text-warning-text' : status === 'connected' ? 'text-success-text' : 'text-muted')}>
            {phone.reconnecting ? <WifiOff size={14} /> : <Smartphone size={14} />}
            {phone.reconnecting ? t('fulfill.waybill.phone.reconnecting')
              : status === 'connected' ? t('fulfill.waybill.phone.connected', { device: phone.pairing.deviceLabel ?? '' })
              : t('fulfill.waybill.phone.waiting')}
          </span>
          <Button variant="ghost" size="sm" onClick={phone.unpair} disabled={phone.busy}>
            {status === 'connected' ? t('fulfill.waybill.phone.unpair') : t('fulfill.waybill.phone.cancel')}
          </Button>
        </span>
      ) : (
        <Button variant="secondary" size="sm" iconStart={Smartphone} onClick={phone.startPairing} disabled={phone.busy}
          className="shadow-e2">
          {t('fulfill.waybill.phone.usePhone')}
        </Button>
      )}
    </div>
  )
}
