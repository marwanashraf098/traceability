import { useCallback, useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { ArrowLeft, ArrowRight } from 'lucide-react'
import { listBazarDays, managesBazar, orderedMethods, type BazarDay, type BazarDays } from '../../../bazar/api'
import { Spinner, cn } from '../../../components/ui'
import type { BazarLive } from '../../../capabilities'
import { CloseDayForm, CloseResultView, DayStatusBadge, dayTitle } from '../CloseDayForm'
import { NotLiveScreen, PhoneFrame, useBazarLive } from './shared'
import { amount } from '../r1/parts'

/**
 * Bazar POS B4 — CLOSE THE DAY on the phone (mockup page 1, step 8). Owner / manager only. Today by default; an
 * earlier day still open, or changed after its close, can be picked from the chips above the form.
 * R2 restyle (design/bazar-v2 V2_CloseDayPhone): the header carries today's money from the days list already loaded.
 */
export default function BazarCloseDay() {
  const { id } = useParams<{ id: string }>()
  const { loading, live } = useBazarLive(id)
  if (!live) return <NotLiveScreen loading={loading} />
  return <CloseScreen live={live} />
}

function CloseScreen({ live }: { live: BazarLive }) {
  const { t, i18n } = useTranslation()
  const navigate = useNavigate()
  const eventId = live.eventId
  const home = `/bazar/${encodeURIComponent(eventId)}/home`
  const [data, setData] = useState<BazarDays | null>(null)
  const [failed, setFailed] = useState(false)
  const [picked, setPicked] = useState<string | null>(null)
  const [closed, setClosed] = useState<BazarDay | null>(null)

  const load = useCallback(() => {
    listBazarDays(eventId).then(d => { setData(d); setFailed(false) }).catch(() => setFailed(true))
  }, [eventId])
  useEffect(() => { if (managesBazar()) load() }, [load])

  const days = data?.days ?? []
  const todayDay = days.find(d => d.is_today) ?? days[days.length - 1]
  // Days that still need the owner: open, or changed after their close (today always shows).
  const needing = days.filter(d => d.is_today || d.status === 'open' || d.status === 'changed_after_close')
  const day = days.find(d => d.business_day === picked) ?? todayDay
  const todayMoney = days.find(d => d.is_today)?.money

  const Back = i18n.dir() === 'rtl' ? ArrowRight : ArrowLeft
  const header = (
    <div className="flex items-center gap-3">
      <button type="button" onClick={() => navigate(home)} aria-label={t('bazar.sell.home')}
        className="min-h-[44px] min-w-[44px] -ms-2 flex items-center justify-center text-sidebar-text hover:text-sidebar-active">
        <Back size={20} aria-hidden />
      </button>
      <span className="flex flex-col min-w-0 flex-1">
        <span className="text-body-lg font-bold text-sidebar-active truncate">{live.name}</span>
        {todayMoney && (
          <span className="text-small text-sidebar-text" data-testid="close-header-today">
            {t('bazar.r2.close.headerToday')} <b dir="ltr" className="text-sidebar-active tabular-nums">{amount(todayMoney.total)}</b>
            {' · '}{t('bazar.r2.close.headerSales', { count: todayMoney.sales })}
          </span>
        )}
      </span>
      <h1 className="sr-only">{t('bazar.close.title')}</h1>
    </div>
  )

  if (!managesBazar()) {
    return (
      <PhoneFrame testId="bazar-close-day" header={header}>
        <p className="text-body text-muted">{t('bazar.sellError.notASeller')}</p>
      </PhoneFrame>
    )
  }

  return (
    <PhoneFrame testId="bazar-close-day" header={header}>
      {closed ? (
        <>
          <section role="status" className="rounded-2xl bg-[#16A34A] text-white px-4 py-3.5 flex flex-col gap-1" data-testid="close-done">
            <span className="text-small font-bold tracking-wider">{t('bazar.close.doneTitle')}</span>
            <span className="text-body-lg font-semibold">{dayTitle(t, closed, i18n.language)}</span>
            <span className="text-small">{t('bazar.close.doneBody')}</span>
          </section>
          {closed.close && <CloseResultView result={closed.close.result} day={closed} />}
          <button type="button" onClick={() => navigate(home)} data-testid="close-back-home"
            className="h-[52px] rounded-xl border border-line bg-surface font-semibold text-primary">
            {t('bazar.close.backHome')}
          </button>
        </>
      ) : !data ? (
        failed ? <p className="text-body text-critical-text">{t('bazar.phone.networkError')}</p>
               : <div className="flex justify-center py-8"><Spinner size={28} /></div>
      ) : !day ? (
        <p className="text-body text-muted">{t('bazar.close.noDays')}</p>
      ) : (
        <>
          {needing.length > 1 && (
            <div className="flex gap-2 overflow-x-auto -mx-1 px-1" role="group" aria-label={t('bazar.close.pickDay')}>
              {needing.map(d => (
                <button key={d.business_day} type="button" aria-pressed={d.business_day === day.business_day}
                  onClick={() => setPicked(d.business_day)}
                  className={cn('shrink-0 min-h-[44px] px-3 rounded-[10px] border text-start flex flex-col justify-center',
                    d.business_day === day.business_day ? 'border-grey-900 bg-grey-900 text-white' : 'border-line bg-surface text-primary')}>
                  <span className="text-small font-semibold">{dayTitle(t, d, i18n.language)}</span>
                  <span className={cn('text-[11px]', d.business_day === day.business_day ? 'text-grey-100' : 'text-muted')}>
                    {t(`bazar.r2.close.chip.${d.status}`)}{d.is_today ? ` · ${t('bazar.r2.close.chip.today')}` : ''}
                  </span>
                </button>
              ))}
            </div>
          )}
          <div className="flex items-center justify-between gap-2">
            <h2 className="text-[22px] leading-7 font-bold text-primary">
              {t('bazar.r2.close.closeDay', { day: dayTitle(t, day, i18n.language) })}
            </h2>
            <DayStatusBadge day={day} />
          </div>
          <CloseDayForm key={day.business_day + ':' + day.close_count} eventId={eventId} day={day}
                        methods={orderedMethods(data.payment_methods)}
                        onClosed={d => { setClosed(d); load() }} />
        </>
      )}
    </PhoneFrame>
  )
}
