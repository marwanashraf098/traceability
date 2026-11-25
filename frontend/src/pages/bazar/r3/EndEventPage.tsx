import { useCallback, useEffect, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { ArrowLeft } from 'lucide-react'
import {
  BazarError, getBazarEvent, getBazarLeftovers, type BazarEventDetail, type BazarLeftovers,
} from '../../../bazar/api'
import { resetCapabilitiesCache } from '../../../capabilities'
import { Spinner } from '../../../components/ui'
import { EndEventPanel, LeftoversPanel } from '../Leftovers'
import { amount } from '../r1/parts'
import { Chip } from '../r2/parts'
import { StepsStrip, cairoDateTime } from './parts'

/**
 * Bazar R3 — /bazar/:id/end-event: End the event as a page on the computer (design/bazar-v2 V2_EndEvent), in the
 * normal shell, owner / manager. Replaces the event page's modal; the phone keeps /bazar/:id/end. Live → R2's
 * EndEventPanel (confirm); ended → R2's LeftoversPanel (wide); transfer closed → the report. Behaviour unchanged.
 */
export default function EndEventPage() {
  const { id } = useParams<{ id: string }>()
  const { t, i18n } = useTranslation()
  const lang = i18n.language
  const navigate = useNavigate()
  const [ev, setEv] = useState<BazarEventDetail | null>(null)
  const [left, setLeft] = useState<BazarLeftovers | null>(null)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(async () => {
    if (!id) return
    try {
      const [e, l] = await Promise.all([getBazarEvent(id), getBazarLeftovers(id)])
      setEv(e)
      setLeft(l)
    } catch (e) {
      if (e instanceof BazarError && e.code === 'BAZAR_NOT_ENABLED') { navigate('/overview', { replace: true }); return }
      setError(e instanceof BazarError ? e.message_(lang) : t('bazar.loadFailed'))
    }
  }, [id, lang, t, navigate])
  useEffect(() => { load() }, [load])

  if (error && !ev) return <p role="alert" className="text-body text-critical">{error}</p>
  if (!ev || !left || !id) return <div className="py-16 flex justify-center"><Spinner size={28} /></div>

  const live = ev.status === 'live'
  const ended = ev.status === 'ended'
  const closed = left.transfer_status === 'closed'
  const c = left.counts
  const expected = Math.max(c.sent - c.sold, 0)
  const scanned = c.back + c.damaged

  return (
    <div className="flex flex-col gap-5 max-w-[1120px]" data-testid="bazar-end-event-page">
      <div className="flex items-end justify-between gap-4 flex-wrap">
        <div className="flex flex-col gap-1">
          <Link to={`/bazar/${id}`} className="text-small text-muted hover:text-primary self-start inline-flex items-center gap-1">
            <ArrowLeft size={14} aria-hidden className="rtl:-scale-x-100" />{ev.name}
          </Link>
          <div className="flex items-center gap-3 flex-wrap">
            <h1 className="text-[26px] leading-8 sm:text-[30px] sm:leading-9 font-bold tracking-[-0.02em] text-primary">{t('bazar.end.title')}</h1>
            {ended && ev.ended_at && <Chip tone="neutral" testId="end-stopped">{t('bazar.r2.end.sellingStopped', { time: cairoDateTime(ev.ended_at, lang) })}</Chip>}
          </div>
        </div>
        <dl className="flex gap-6" data-testid="end-header-totals">
          {ev.totals?.revenue != null && <Total value={amount(ev.totals.revenue)} label={t('bazar.r3.end.egp')} />}
          <Total value={String(c.sold)} label={t('bazar.r2.end.piecesSold')} />
          <Total value={String(expected)} label={t('bazar.r2.end.leftOver')} />
        </dl>
      </div>

      {ev.status !== 'draft' && (
        <StepsStrip testId="end-steps" steps={[
          { title: t('bazar.r3.end.steps.days'), sub: ended ? t('bazar.r3.end.steps.daysDone') : t('bazar.r3.end.steps.daysTodo'), state: ended ? 'done' : 'current' },
          { title: t('bazar.r3.end.steps.back'), sub: t('bazar.r3.end.steps.backSub', { scanned, count: expected }),
            state: !ended ? 'todo' : c.outstanding === 0 ? 'done' : 'current' },
          { title: t('bazar.r3.end.steps.close'), sub: t('bazar.r3.end.steps.closeSub'), state: closed ? 'done' : ended && c.outstanding === 0 ? 'current' : 'todo' },
          { title: t('bazar.r3.end.steps.report'), sub: t('bazar.r3.end.steps.reportSub'), state: closed ? 'current' : 'todo' },
        ]} />
      )}

      {ev.status === 'draft' ? (
        <p className="text-body text-muted">{t('bazar.end.draft')}</p>
      ) : live ? (
        <section className="card p-5 max-w-[560px]">
          <EndEventPanel eventId={ev.id} name={ev.name} closeDaysTo={`/bazar/${id}/close-day`}
            onEnded={l => { resetCapabilitiesCache(); setEv({ ...ev, status: 'ended', ended_at: new Date().toISOString() }); setLeft(l) }} />
        </section>
      ) : (
        <>
          <LeftoversPanel eventId={ev.id} initial={left} noCamera wide reportTo={`/bazar/${id}/report`} onChange={setLeft}
                          onClosed={l => { setLeft(l); load() }} />
        </>
      )}
    </div>
  )
}

function Total({ value, label }: { value: string; label: string }) {
  return (
    <div className="flex flex-col-reverse items-end">
      <dt className="text-small text-muted">{label}</dt>
      <dd dir="ltr" className="text-[22px] leading-7 font-bold text-primary tabular-nums">{value}</dd>
    </div>
  )
}
