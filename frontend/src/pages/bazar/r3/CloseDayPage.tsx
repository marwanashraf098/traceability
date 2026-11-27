import { useCallback, useEffect, useState } from 'react'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { HelpLink } from '../../../help/HelpLink'
import { ArrowLeft } from 'lucide-react'
import {
  BazarError, getBazarEvent, listBazarDays, orderedMethods, type BazarDay, type BazarDays, type BazarEventDetail,
} from '../../../bazar/api'
import { Spinner, cn } from '../../../components/ui'
import { CloseDayForm, CloseResultView, DayStatusBadge, dayTitle } from '../CloseDayForm'
import { dayLabel } from '../r1/parts'
import { btnSecondary } from './parts'
import { defaultCloseDay } from '../closeDayDefault'

/**
 * Bazar R3 — /bazar/:id/close-day: Close the day as a page on the computer (design/bazar-v2 V2_CloseDay), in the normal
 * shell, owner / manager. Replaces the event page's modal; the phone keeps /bazar/:id/close. The day: ?day=, else the
 * oldest day that still needs closing, else today, else the last day (U1a — closeDayDefault). The form is R2's CloseDayForm, unchanged in behaviour (wide layout).
 */
export default function CloseDayPage() {
  const { id } = useParams<{ id: string }>()
  const [params, setParams] = useSearchParams()
  const { t, i18n } = useTranslation()
  const lang = i18n.language
  const navigate = useNavigate()
  const [ev, setEv] = useState<BazarEventDetail | null>(null)
  const [data, setData] = useState<BazarDays | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [closed, setClosed] = useState<BazarDay | null>(null)

  const loadDays = useCallback(() => {
    if (!id) return
    listBazarDays(id).then(setData).catch(e => setError(e instanceof BazarError ? e.message_(lang) : t('bazar.loadFailed')))
  }, [id, lang, t])
  useEffect(() => {
    if (!id) return
    getBazarEvent(id).then(setEv).catch(e => {
      if (e instanceof BazarError && e.code === 'BAZAR_NOT_ENABLED') { navigate('/overview', { replace: true }); return }
      setError(e instanceof BazarError ? e.message_(lang) : t('bazar.loadFailed'))
    })
    loadDays()
  }, [id, lang, t, navigate, loadDays])

  if (error && !(ev && data)) return <p role="alert" className="text-body text-critical">{error}</p>
  if (!ev || !data || !id) return <div className="py-16 flex justify-center"><Spinner size={28} /></div>

  const days = data.days
  const asked = params.get('day')
  const day = days.find(d => d.business_day === asked) ?? defaultCloseDay(days)
  const back = `/bazar/${id}?tab=days`

  return (
    <div className="flex flex-col gap-5 max-w-[1120px]" data-testid="bazar-close-day-page">
      <div className="flex items-end justify-between gap-4 flex-wrap">
        <div className="flex flex-col gap-1">
          <Link to={back} className="text-small text-muted hover:text-primary self-start inline-flex items-center gap-1">
            <ArrowLeft size={14} aria-hidden className="rtl:-scale-x-100" />{ev.name}
          </Link>
          <h1 className="text-[26px] leading-8 sm:text-[30px] sm:leading-9 font-bold tracking-[-0.02em] text-primary">{t('bazar.close.title')}</h1>
          <HelpLink screen="bazarCloseDay" className="self-start" />
        </div>
        {days.length > 1 && !closed && (
          <div className="flex gap-2 flex-wrap" role="group" aria-label={t('bazar.close.pickDay')} data-testid="close-day-picker">
            {days.map(d => {
              const on = day && d.business_day === day.business_day
              return (
                <button key={d.business_day} type="button" aria-pressed={!!on}
                  onClick={() => setParams({ day: d.business_day }, { replace: true })}
                  className={cn('min-h-[48px] px-3.5 rounded-[10px] border text-start flex flex-col justify-center',
                    on ? 'border-grey-900 bg-grey-900 text-white' : 'border-line bg-surface text-primary hover:bg-charcoal')}>
                  <span className="text-body font-semibold">{dayLabel(d.business_day, lang)}</span>
                  <span className={cn('text-[11px]', on ? 'text-grey-100' : 'text-muted')}>
                    {t(`bazar.r2.close.chip.${d.status}`)}{d.is_today ? ` · ${t('bazar.r2.close.chip.today')}` : ''}
                  </span>
                </button>
              )
            })}
          </div>
        )}
      </div>

      {!day ? (
        <p className="text-body text-muted">{t('bazar.close.noDays')}</p>
      ) : closed ? (
        <div className="flex flex-col gap-4 max-w-[640px]">
          <section role="status" className="rounded-2xl bg-[#16A34A] text-white px-4 py-3.5 flex flex-col gap-1" data-testid="close-done">
            <span className="text-small font-bold tracking-wider">{t('bazar.close.doneTitle')}</span>
            <span className="text-body-lg font-semibold">{dayTitle(t, closed, lang)}</span>
            <span className="text-small">{t('bazar.close.doneBody')}</span>
          </section>
          {closed.close && <CloseResultView result={closed.close.result} day={closed} />}
          <Link to={back} className={cn(btnSecondary, 'self-start')} data-testid="close-back-event">{t('bazar.r3.close.backToEvent')}</Link>
        </div>
      ) : (
        <>
          <div className="flex items-center gap-3 flex-wrap">
            <h2 className="text-h4 font-bold text-primary">{t('bazar.r2.close.closeDay', { day: dayTitle(t, day, lang) })}</h2>
            <DayStatusBadge day={day} />
          </div>
          {!asked && !day.is_today && day.needs_close && (
            <p className="text-body text-warning-text -mt-2" data-testid="close-day-oldest">
              {t('bazar.u1a.oldestOpen', { date: dayLabel(day.business_day, lang) })}
            </p>
          )}
          <CloseDayForm key={day.business_day + ':' + day.close_count} eventId={id} day={day} compact wide
                        methods={orderedMethods(data.payment_methods)}
                        onClosed={d => { setClosed(d); loadDays() }} />
        </>
      )}
    </div>
  )
}
