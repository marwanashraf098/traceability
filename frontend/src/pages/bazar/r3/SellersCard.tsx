import { useTranslation } from 'react-i18next'
import { SellersPanel } from '../SellersPanel'

/** B6b — who sells at this event, and their one-time sign-in links (moved out of BazarEventPage in R3). */
export function SellersCard({ eventId }: { eventId: string }) {
  const { t } = useTranslation()
  return (
    <section id="bazar-sellers-card" className="card p-4 sm:p-5 flex flex-col gap-3 min-w-0 scroll-mt-6" data-testid="bazar-sellers-card">
      <div className="flex flex-col">
        <h2 className="text-body-lg font-semibold text-primary">{t('bazar.sellers.title')}</h2>
        <p className="text-small text-muted">{t('bazar.sellers.subtitle')}</p>
      </div>
      <SellersPanel eventId={eventId} />
    </section>
  )
}
