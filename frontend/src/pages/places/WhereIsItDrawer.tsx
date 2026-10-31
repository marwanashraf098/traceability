import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { X } from 'lucide-react'
import { getWhereIsIt, ServerMessageError, WhereIsIt, WherePlace, WhereStatusCounts } from '../../api'
import { EmptyState, Skeleton } from '../../components/ui'

/**
 * Warehouse places S5 — "Where is it" for one variant (Inventory variant row, Lookup): per location, every place
 * holding its available / on-hold / damaged pieces (walking order) with counts, then "No place yet". One request.
 * Same slide-in shell as VariantDrawer; mounted only while open (nothing renders without places).
 */
export default function WhereIsItDrawer({ variantId, title, onClose }: {
  variantId: string
  title: string
  onClose: () => void
}) {
  const { t, i18n } = useTranslation()
  const [data, setData] = useState<WhereIsIt | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    setData(null)
    setError(null)
    getWhereIsIt(variantId)
      .then(d => { if (!cancelled) setData(d) })
      .catch(e => { if (!cancelled) setError(e instanceof ServerMessageError ? e.messageFor(i18n.language) : t('common.error')) })
    return () => { cancelled = true }
  }, [variantId]) // eslint-disable-line react-hooks/exhaustive-deps

  return (
    <>
      <div className="fixed inset-0 bg-black/45 z-overlay" onClick={onClose} />
      <aside role="dialog" aria-label={t('whereIsIt.title')} data-testid="where-is-it"
        className="fixed top-0 end-0 h-screen w-[440px] max-w-[92vw] bg-surface border-s border-line z-modal flex flex-col shadow-e4">
        <div className="p-5 pb-4 border-b border-line relative">
          <p className="text-caption uppercase tracking-wide font-semibold text-muted">{t('whereIsIt.title')}</p>
          <p className="text-body-lg font-semibold text-primary pe-10"><bdi>{title}</bdi></p>
          <button onClick={onClose} aria-label={t('inventory.drawer.close')}
            className="absolute top-4 end-4 w-8 h-8 rounded-lg flex items-center justify-center text-muted hover:text-primary hover:bg-elevated transition-colors">
            <X size={18} strokeWidth={2} />
          </button>
        </div>
        <div className="flex-1 overflow-y-auto p-5 space-y-5">
          {error ? <EmptyState icon="⚠" message={error} />
            : !data ? <div className="space-y-3"><Skeleton className="h-6 w-40 rounded-lg" /><Skeleton className="h-32 rounded-2xl" /></div>
            : data.locations.length === 0 ? <EmptyState icon="📦" message={t('whereIsIt.none')} />
            : data.locations.map(loc => (
              <section key={loc.locationId} className="space-y-2" data-testid="where-is-it-location">
                <div className="flex items-baseline justify-between gap-3">
                  <h3 className="text-body font-semibold text-primary">{loc.locationName}</h3>
                  <span className="text-small text-muted">{t('whereIsIt.pieces', { count: loc.total })}</span>
                </div>
                <ul className="divide-y divide-line border border-line rounded-xl">
                  {loc.places.map(p => <PlaceRow key={p.binId} place={p} />)}
                  {loc.noPlace.total > 0 && (
                    <li className="flex items-start justify-between gap-3 px-4 py-3" data-testid="where-is-it-no-place">
                      <span className="text-body text-muted">{t('whereIsIt.noPlace')}</span>
                      <Counts counts={loc.noPlace} />
                    </li>
                  )}
                </ul>
              </section>
            ))}
        </div>
      </aside>
    </>
  )
}

function PlaceRow({ place }: { place: WherePlace }) {
  return (
    <li className="flex items-start justify-between gap-3 px-4 py-3" data-testid="where-is-it-place">
      <span className="min-w-0">
        <span className="block font-mono text-body font-semibold text-primary" dir="ltr">{place.code}</span>
        <span className="block text-small text-muted" dir="ltr">{place.path.join(' › ')}</span>
      </span>
      <Counts counts={place.counts} />
    </li>
  )
}

function Counts({ counts }: { counts: WhereStatusCounts }) {
  const { t } = useTranslation()
  return (
    <span className="text-end">
      <span className="block text-body font-semibold text-primary tabular-nums">{counts.total}</span>
      {(counts.onHold > 0 || counts.damaged > 0) && (
        <span className="block text-caption text-muted">
          {[counts.available > 0 ? t('whereIsIt.available', { count: counts.available }) : null,
            counts.onHold > 0 ? t('whereIsIt.onHold', { count: counts.onHold }) : null,
            counts.damaged > 0 ? t('whereIsIt.damaged', { count: counts.damaged }) : null].filter(Boolean).join(' · ')}
        </span>
      )}
    </span>
  )
}
