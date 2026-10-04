import { useEffect, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import qrcode from 'qrcode-generator'
import { Alert, Button, Modal } from '../components/ui'
import type { ScanPairingCreated } from '../api'

// Phone as scanner (S6; per tablet since Q1): the QR the phone's camera opens (the pair URL), its
// countdown and Cancel. Its own file, loaded lazily by the floating phone control
// (PhoneScanProvider), so the QR library stays out of the main bundle until a worker taps
// "Use phone".

/** A QR code as an SVG drawn from qrcode-generator's module grid (pure JS — no canvas, no
 *  innerHTML, nothing CSP needs to allow). Quiet zone of 4 modules, error correction M. */
export function QrCode({ value, size = 260 }: { value: string; size?: number }) {
  const { n, d } = useMemo(() => {
    const qr = qrcode(0, 'M')
    qr.addData(value)
    qr.make()
    const count = qr.getModuleCount()
    let path = ''
    for (let r = 0; r < count; r++) {
      for (let c = 0; c < count; c++) {
        if (qr.isDark(r, c)) path += `M${c + 4},${r + 4}h1v1h-1z`
      }
    }
    return { n: count + 8, d: path }
  }, [value])
  return (
    <svg viewBox={`0 0 ${n} ${n}`} width={size} height={size} shapeRendering="crispEdges"
      role="img" aria-label="QR code" data-testid="pair-qr" data-value={value}>
      <rect width={n} height={n} fill="#ffffff" />
      <path d={d} fill="#000000" />
    </svg>
  )
}

function secondsLeft(iso: string | null, now: number) {
  if (!iso) return 0
  return Math.max(0, Math.ceil((new Date(iso).getTime() - now) / 1000))
}

export default function PhonePairModal({ offer, starting, error, onNewCode, onCancel }: {
  offer: ScanPairingCreated | null
  starting: boolean
  error: string | null
  onNewCode: () => void
  onCancel: () => void
}) {
  const { t } = useTranslation()
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    const id = window.setInterval(() => setNow(Date.now()), 1000)
    return () => window.clearInterval(id)
  }, [])
  const left = secondsLeft(offer?.pairCodeExpiresAt ?? null, now)
  const expired = !!offer && left === 0

  return (
    <Modal title={t('fulfill.waybill.phone.modalTitle')} onClose={onCancel}>
      <div className="space-y-4 text-center" data-testid="pair-modal">
        <p className="text-body text-muted">{t('fulfill.waybill.phone.modalBody')}</p>
        {error && <Alert tone="critical" title={error} />}
        <div className="flex justify-center">
          <div className="relative rounded-2xl bg-white p-3 border border-line">
            {offer && !expired ? (
              <QrCode value={offer.pairUrl} />
            ) : (
              <div className="w-[260px] h-[260px] flex items-center justify-center text-small text-muted"
                data-testid="pair-qr-placeholder">
                {starting ? t('fulfill.waybill.phone.starting') : expired ? t('fulfill.waybill.phone.expired') : ''}
              </div>
            )}
          </div>
        </div>
        {offer && !expired && (
          <p className="text-small text-muted font-mono" data-testid="pair-expires">
            {t('fulfill.waybill.phone.expiresIn', { seconds: left })}
          </p>
        )}
        <div className="flex gap-3">
          <Button variant="secondary" className="flex-1" onClick={onCancel}>{t('fulfill.waybill.phone.cancel')}</Button>
          {expired && (
            <Button className="flex-1" loading={starting} onClick={onNewCode}>{t('fulfill.waybill.phone.newCode')}</Button>
          )}
        </div>
      </div>
    </Modal>
  )
}
