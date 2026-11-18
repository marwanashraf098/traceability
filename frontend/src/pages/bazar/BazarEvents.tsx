import { useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { Plus, Store } from 'lucide-react'
import { BazarError, listBazarEvents, type BazarEventRow, type BazarStatus } from '../../bazar/api'
import { shortDate } from '../../bazar/format'
import { Badge, Button, TableSkeleton, type BadgeTone } from '../../components/ui'

export const STATUS_TONE: Record<BazarStatus, BadgeTone> = { draft: 'warning', live: 'success', ended: 'neutral' }

/** Bazar POS B6 — /bazar: the tenant's events (newest first) + New event. Owner / manager, in-shell. */
export default function BazarEvents() {
  const { t, i18n } = useTranslation()
  const navigate = useNavigate()
  const [rows, setRows] = useState<BazarEventRow[] | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    listBazarEvents()
      .then(setRows)
      .catch(e => {
        if (e instanceof BazarError && e.code === 'BAZAR_NOT_ENABLED') { navigate('/overview', { replace: true }); return }
        setError(e instanceof BazarError ? e.message_(i18n.language) : t('bazar.loadFailed'))
      })
  }, [navigate, t, i18n.language])

  return (
    <div className="flex flex-col gap-5 max-w-[1080px]" data-testid="bazar-events">
      <div className="flex flex-wrap items-center gap-3">
        <h1 className="text-h1 text-primary me-auto">{t('bazar.title')}</h1>
        <Button iconStart={Plus} onClick={() => navigate('/bazar/new')}>{t('bazar.newEvent')}</Button>
      </div>
      <p className="text-body text-muted -mt-2">{t('bazar.listIntro')}</p>

      {error && <p role="alert" className="text-body text-critical">{error}</p>}
      {!rows && !error && <TableSkeleton rows={3} cols={4} />}
      {rows && rows.length === 0 && (
        <div className="card flex flex-col items-center text-center gap-3 py-12 px-6" data-testid="bazar-empty">
          <span className="w-12 h-12 rounded-xl bg-trace-blue/10 text-trace-blue flex items-center justify-center">
            <Store size={22} aria-hidden />
          </span>
          <p className="text-body font-semibold text-primary">{t('bazar.emptyTitle')}</p>
          <p className="text-body text-muted max-w-md">{t('bazar.emptyBody')}</p>
          <Button size="sm" iconStart={Plus} onClick={() => navigate('/bazar/new')}>{t('bazar.newEvent')}</Button>
        </div>
      )}
      {rows && rows.length > 0 && (
        <ul className="card p-0 divide-y divide-line overflow-hidden" data-testid="bazar-event-list">
          {rows.map(r => (
            <li key={r.id}>
              {/* B5: an ended event opens its report. */}
              <Link to={r.status === 'ended' ? `/bazar/${r.id}/report` : `/bazar/${r.id}`}
                className="flex flex-wrap items-center gap-x-4 gap-y-1 px-5 py-4 hover:bg-charcoal transition-colors">
                <span className="flex flex-col min-w-0 me-auto">
                  <span className="font-semibold text-primary">{r.name}</span>
                  <span className="text-small text-muted">
                    {shortDate(r.starts_on, i18n.language)} – {shortDate(r.ends_on, i18n.language)} · {r.location_name}
                  </span>
                </span>
                <Badge tone={STATUS_TONE[r.status]} label={t(`bazar.status.${r.status}`)} />
              </Link>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
