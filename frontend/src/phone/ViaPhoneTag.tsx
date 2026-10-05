import { useTranslation } from 'react-i18next'
import { Smartphone } from 'lucide-react'

// Q1b — the small "via phone" marker on a scan (or a line of scans) that came from a paired
// phone, as decided server-side. `count` (optional) shows how many.
export function ViaPhoneTag({ count }: { count?: number }) {
  const { t } = useTranslation()
  return (
    <span data-testid="via-phone"
      className="inline-flex items-center gap-1 rounded-full border border-info/30 bg-info/10 px-2 py-0.5 text-caption font-semibold text-info whitespace-nowrap">
      <Smartphone size={12} />
      {count === undefined ? t('phone.viaPhone') : t('phone.linePhoneScans', { count })}
    </span>
  )
}
