import { useCallback, useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { ArrowLeft, ArrowRight } from 'lucide-react'
import {
  BazarError, getBazarEvent, getBazarLeftovers, getBazarReport, managesBazar,
  type BazarEventDetail, type BazarLeftovers, type BazarReport,
} from '../../../bazar/api'
import { resetCapabilitiesCache } from '../../../capabilities'
import { Spinner } from '../../../components/ui'
import { EndEventPanel, LeftoversPanel } from '../Leftovers'
import { ReportKpis } from '../BazarReport'
import { PhoneFrame, restOfTracedPath } from './shared'
import { formatMoney, toNumber } from '../../../bazar/format'

/**
 * Bazar POS B5 — END EVENT on the phone (mockup page 1, step 9): confirm → leftovers (scan back, mark lost, close
 * the transfer) → the event summary. Owner / manager. Works on an ended event too (it is no longer "live" for /me),
 * so it reads the event itself instead of GET /me's bazarLive.
 */
export default function BazarEndEvent() {
  const { id } = useParams<{ id: string }>()
  const { t, i18n } = useTranslation()
  const navigate = useNavigate()
  const [ev, setEv] = useState<BazarEventDetail | null>(null)
  const [left, setLeft] = useState<BazarLeftovers | null>(null)
  const [report, setReport] = useState<BazarReport | null>(null)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(async () => {
    if (!id) return
    try {
      const [e, l] = await Promise.all([getBazarEvent(id), getBazarLeftovers(id)])
      setEv(e)
      setLeft(l)
      if (l.transfer_status === 'closed') setReport(await getBazarReport(id))
    } catch (e) {
      setError(e instanceof BazarError ? e.message_(i18n.language) : t('bazar.phone.networkError'))
    }
  }, [id, i18n.language, t])
  useEffect(() => { if (managesBazar()) load() }, [load])

  const Back = i18n.dir() === 'rtl' ? ArrowRight : ArrowLeft
  const live = ev?.status === 'live'
  const home = live ? `/bazar/${encodeURIComponent(id ?? '')}/home` : restOfTracedPath()
  const header = (
    <>
      <div className="flex items-center justify-between gap-3">
        <button type="button" onClick={() => navigate(home)}
          className="min-h-[44px] -ms-2 px-2 flex items-center gap-1.5 text-sidebar-text hover:text-sidebar-active text-small font-semibold">
          <Back size={18} aria-hidden />{t('bazar.sell.home')}
        </button>
        <span className="text-small font-semibold text-sidebar-active truncate">{ev?.name ?? ''}</span>
      </div>
      <h1 className="text-h4 text-sidebar-active">
        {report ? t('bazar.end.summary') : live ? t('bazar.end.title') : t('bazar.end.leftoversTitle')}
      </h1>
    </>
  )

  if (!managesBazar()) {
    return <PhoneFrame testId="bazar-end-event" header={header}><p className="text-body text-muted">{t('bazar.sellError.notASeller')}</p></PhoneFrame>
  }
  if (!ev || !left) {
    return (
      <PhoneFrame testId="bazar-end-event" header={header}>
        {error ? <p role="alert" className="text-body text-critical-text">{error}</p>
               : <div className="flex justify-center py-8"><Spinner size={28} /></div>}
      </PhoneFrame>
    )
  }

  return (
    <PhoneFrame testId="bazar-end-event" header={header}>
      {ev.status === 'draft' ? (
        <p className="text-body text-muted">{t('bazar.end.draft')}</p>
      ) : live ? (
        <EndEventPanel eventId={ev.id} name={ev.name} closeDaysTo={`/bazar/${encodeURIComponent(ev.id)}/close`}
          onEnded={l => { resetCapabilitiesCache(); setEv({ ...ev, status: 'ended' }); setLeft(l) }} />
      ) : report ? (
        <section className="flex flex-col gap-3" data-testid="end-summary">
          <ReportKpis r={report} />
          <div className="card p-4 flex flex-col gap-1">
            <span className="text-small text-muted">{t('bazar.report.notReceived')}</span>
            <span className={toNumber(report.money_not_received.total) > 0 ? 'text-h4 font-bold text-critical-text' : 'text-h4 font-bold text-primary'}>
              {formatMoney(report.money_not_received.total)}
            </span>
            <span className="text-small text-muted">
              {t('bazar.report.returns', { count: report.returns })} · {t('bazar.report.exchanges', { count: report.exchanges })}
              {' · '}{t('bazar.report.customersSaved', { count: report.customers_saved })}
            </span>
          </div>
          <button type="button" onClick={() => navigate(`/bazar/${encodeURIComponent(ev.id)}/report`)}
            className="h-[52px] rounded-xl bg-trace-blue text-white font-semibold" data-testid="end-full-report">
            {t('bazar.end.fullReport')}
          </button>
          <button type="button" onClick={() => navigate(restOfTracedPath())}
            className="h-[52px] rounded-xl border border-line bg-surface font-semibold text-primary">{t('bazar.end.backHome')}</button>
        </section>
      ) : (
        <LeftoversPanel eventId={ev.id} initial={left}
          onClosed={async () => { if (id) setReport(await getBazarReport(id)) }} />
      )}
    </PhoneFrame>
  )
}
