import { useEffect, useState } from 'react'
import { Link, useLocation } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { ScanLine, X } from 'lucide-react'

/**
 * Bazar U1b — "Back to selling" on the phone. The sell screen remembers its event in this tab's session storage; any
 * other screen shows a bar back to it (phone widths only) until the seller closes it. No fetch: an event that has
 * ended since just opens the sell screen, which says so itself.
 */
const KEY = 'bazar-selling-event'

export function rememberSelling(eventId: string) {
  try { sessionStorage.setItem(KEY, eventId) } catch { /* private mode: no bar */ }
}

export function forgetSelling() {
  try { sessionStorage.removeItem(KEY) } catch { /* nothing kept */ }
}

function sellingEvent(): string | null {
  try { return sessionStorage.getItem(KEY) } catch { return null }
}

export function BackToSellingBar() {
  const { t } = useTranslation()
  const { pathname } = useLocation()
  const [eventId, setEventId] = useState<string | null>(() => sellingEvent())
  useEffect(() => { setEventId(sellingEvent()) }, [pathname])
  if (!eventId) return null
  const to = `/bazar/${encodeURIComponent(eventId)}/sell`
  if (pathname === to) return null
  return (
    <div className="lg:hidden fixed inset-x-3 bottom-3 z-40 flex items-center gap-2 rounded-2xl bg-grey-900 text-white shadow-e4 ps-4 pe-2 py-2"
         data-testid="back-to-selling">
      <ScanLine size={18} aria-hidden className="shrink-0" />
      <Link to={to} className="flex-1 min-h-[44px] flex items-center text-body font-semibold">{t('bazar.u1b.backToSelling')}</Link>
      <button type="button" aria-label={t('bazar.u1b.backToSellingClose')} onClick={() => { forgetSelling(); setEventId(null) }}
              className="h-11 w-11 rounded-xl flex items-center justify-center text-sidebar-text hover:text-white">
        <X size={18} aria-hidden />
      </button>
    </div>
  )
}
