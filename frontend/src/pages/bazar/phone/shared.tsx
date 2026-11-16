import { ReactNode, useCallback, useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { X } from 'lucide-react'
import { loadCapabilities, type BazarLive } from '../../../capabilities'
import { getRoleFromToken } from '../../../api'
import { listBazarSalesOfDay, type BazarSale } from '../../../bazar/api'
import { Button, Spinner, cn } from '../../../components/ui'
import { Logo } from '../../../components/Logo'
import { liveLabel } from '../../../bazar/format'

/** The live bazar this phone may sell at — GET /me bazarLive, only when it is the event in the URL. */
export function useBazarLive(eventId: string | undefined): { loading: boolean; live: BazarLive | null } {
  const [state, setState] = useState<{ loading: boolean; live: BazarLive | null }>({ loading: true, live: null })
  useEffect(() => {
    let cancelled = false
    loadCapabilities().then(c => {
      if (cancelled) return
      setState({ loading: false, live: c.bazarLive && c.bazarLive.eventId === eventId ? c.bazarLive : null })
    })
    return () => { cancelled = true }
  }, [eventId])
  return state
}

/** Today's sales of the event (newest first), reloaded on demand and every 30 s (other sellers' sales). */
export function useTodaySales(eventId: string, businessDay: string) {
  const [sales, setSales] = useState<BazarSale[] | null>(null)
  const [failed, setFailed] = useState(false)
  const reload = useCallback(() => {
    return listBazarSalesOfDay(eventId, businessDay)
      .then(s => { setSales(s); setFailed(false) })
      .catch(() => setFailed(true))
  }, [eventId, businessDay])
  useEffect(() => {
    reload()
    const id = window.setInterval(reload, 30_000)
    return () => window.clearInterval(id)
  }, [reload])
  return { sales, failed, reload, setSales }
}

/** Where "the rest of Traced" is for this role (never "/" — that would bring the phone straight back here). */
export function restOfTracedPath(): string {
  return getRoleFromToken() === 'worker' ? '/worker-home' : '/overview'
}

/** The phone screens' full-screen frame: 480 px column, dark header (the sidebar palette, as in the mockup). */
export function PhoneFrame({ header, children, footer, testId }: {
  header: ReactNode
  children: ReactNode
  footer?: ReactNode
  testId?: string
}) {
  return (
    <div className="min-h-[100dvh] bg-base flex justify-center" data-testid={testId}>
      <div className="w-full max-w-[480px] min-h-[100dvh] flex flex-col bg-base">
        <header className="bg-sidebar px-4 py-3 flex flex-col gap-2">{header}</header>
        <main className="flex-1 flex flex-col gap-3 px-4 py-4 min-h-0">{children}</main>
        {footer}
      </div>
    </div>
  )
}

export function PhoneBrandRow({ end }: { end?: ReactNode }) {
  return (
    <div className="flex items-center justify-between gap-2">
      <Logo variant="mark" size={18} className="text-sidebar-active" />
      {end}
    </div>
  )
}

export function LivePill({ live }: { live: BazarLive }) {
  const { t } = useTranslation()
  return (
    <span className="inline-flex self-start items-center gap-1.5 px-2.5 py-0.5 rounded-full bg-success/20 text-small font-semibold text-[#86EFAC]">
      <span className="w-1.5 h-1.5 rounded-full bg-current" aria-hidden />
      {liveLabel(t, live.day, live.dayCount)}
    </span>
  )
}

/** Shown while /me is loading, and when this event isn't live for the caller. */
export function NotLiveScreen({ loading }: { loading: boolean }) {
  const { t } = useTranslation()
  const navigate = useNavigate()
  if (loading) {
    return <div className="min-h-[100dvh] bg-base flex items-center justify-center"><Spinner size={32} /></div>
  }
  return (
    <div className="min-h-[100dvh] bg-base flex flex-col items-center justify-center gap-4 px-6 text-center"
         data-testid="bazar-not-live">
      <p className="text-h4 text-primary">{t('bazar.phone.notLiveTitle')}</p>
      <p className="text-body text-muted max-w-xs">{t('bazar.phone.notLiveBody')}</p>
      <Button variant="secondary" onClick={() => navigate(restOfTracedPath())}>{t('bazar.phone.restOfTraced')}</Button>
    </div>
  )
}

/**
 * A bottom sheet. Clicks inside stop at the sheet, so the scan screen's click-to-refocus listener never pulls focus
 * out of the sheet's fields (the same rule Modal follows).
 */
export function Sheet({ title, onClose, children, testId }: {
  title: string
  onClose: () => void
  children: ReactNode
  testId?: string
}) {
  const { t } = useTranslation()
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose() }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [onClose])
  return (
    <div className="fixed inset-0 z-overlay bg-black/50 flex items-end justify-center"
         onClick={e => { e.stopPropagation(); onClose() }}>
      <div role="dialog" aria-modal="true" aria-label={title} data-testid={testId}
           className={cn('w-full max-w-[480px] bg-surface rounded-t-2xl border-t border-line shadow-e4 animate-fadeIn',
             'max-h-[88dvh] overflow-y-auto pb-[max(16px,env(safe-area-inset-bottom))]')}
           onClick={e => e.stopPropagation()}>
        <div className="flex items-center justify-between gap-3 px-4 pt-4 pb-2">
          <h2 className="text-h4 text-primary">{title}</h2>
          <button type="button" onClick={onClose} aria-label={t('bazar.phone.close')}
                  className="w-11 h-11 -me-2 flex items-center justify-center text-muted hover:text-primary">
            <X size={20} />
          </button>
        </div>
        <div className="px-4 flex flex-col gap-3">{children}</div>
      </div>
    </div>
  )
}

/** Return / Exchange (B3), Close day (B4), End event (B5) — not in this slice. */
export function ComingSoonSheet({ what, onClose }: { what: string; onClose: () => void }) {
  const { t } = useTranslation()
  return (
    <Sheet title={what} onClose={onClose} testId="bazar-coming-soon">
      <p className="text-body text-muted">{t('bazar.phone.comingSoonBody')}</p>
      <Button variant="secondary" className="w-full" onClick={onClose}>{t('bazar.phone.ok')}</Button>
    </Sheet>
  )
}
