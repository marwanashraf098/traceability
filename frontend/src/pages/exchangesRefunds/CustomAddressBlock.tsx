import { useTranslation } from 'react-i18next'
import type { ReturnRequestDetail } from '../../api'

/**
 * V117 — the pickup address the customer typed in the returns portal ("A different address"),
 * shown in full to the merchant under the Pickup / Area field, with a "Customer entered a new
 * address" label. Nothing for requests that use the delivery address. After a privacy request
 * the street is gone and only the label + "removed" note remain.
 */
export default function CustomAddressBlock({ detail }: { detail: ReturnRequestDetail }) {
  const { t } = useTranslation()
  const a = detail.customAddress
  if (detail.pickupAddressSource !== 'custom' || !a) return null
  const parts = [
    a.buildingNumber && t('exchangesRefunds.requests.drawer.customBuilding', { value: a.buildingNumber }),
    a.floor && t('exchangesRefunds.requests.drawer.customFloor', { value: a.floor }),
    a.apartment && t('exchangesRefunds.requests.drawer.customApartment', { value: a.apartment }),
  ].filter(Boolean)
  return (
    <div className="mt-1 space-y-0.5" data-testid="custom-address">
      <span className="inline-block text-caption font-medium text-trace-blue">
        {t('exchangesRefunds.requests.drawer.customAddressLabel')}
      </span>
      {a.redacted ? (
        <p className="text-small text-muted">{t('exchangesRefunds.requests.drawer.customAddressRedacted')}</p>
      ) : (
        <>
          {a.firstLine && <p className="text-body text-primary"><bdi>{a.firstLine}</bdi></p>}
          {parts.length > 0 && <p className="text-small text-secondary"><bdi>{parts.join(' · ')}</bdi></p>}
          {a.secondLine && <p className="text-small text-muted"><bdi>{a.secondLine}</bdi></p>}
        </>
      )}
    </div>
  )
}
