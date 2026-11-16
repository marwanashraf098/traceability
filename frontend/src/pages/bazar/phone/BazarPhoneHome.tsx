import { useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { ScanLine } from 'lucide-react'
import { currentUserId, managesBazar } from '../../../bazar/api'
import { formatMoney, summarize } from '../../../bazar/format'
import { Spinner } from '../../../components/ui'
import {
  ComingSoonSheet, LivePill, NotLiveScreen, PhoneBrandRow, PhoneFrame, restOfTracedPath, useBazarLive, useTodaySales,
} from './shared'
import type { BazarLive } from '../../../capabilities'

/** Bazar POS B6 — the phone's landing on a live bazar (mockup step 5). Full-screen, every seller role. */
export default function BazarPhoneHome() {
  const { id } = useParams<{ id: string }>()
  const { loading, live } = useBazarLive(id)
  if (!live) return <NotLiveScreen loading={loading} />
  return <Home live={live} />
}

function Home({ live }: { live: BazarLive }) {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const { sales } = useTodaySales(live.eventId, live.businessDay)
  const [soon, setSoon] = useState<string | null>(null)
  const manager = managesBazar()
  const me = currentUserId()
  const sum = sales ? summarize(sales) : null

  const sellers = sum?.sellers.map(s => (s.id === me
    ? t('bazar.phone.sellerYou', { count: s.count })
    : t('bazar.phone.sellerCount', { name: s.name, count: s.count }))) ?? []

  const secondary = 'h-[52px] rounded-xl border border-line bg-surface font-semibold text-primary flex items-center justify-center'

  return (
    <PhoneFrame testId="bazar-phone-home" header={
      <>
        <PhoneBrandRow />
        <LivePill live={live} />
        <h1 className="text-h4 text-sidebar-active">{live.name}</h1>
      </>
    }>
      <button type="button" onClick={() => navigate(`/bazar/${encodeURIComponent(live.eventId)}/sell`)}
        className="h-[84px] rounded-2xl bg-trace-blue hover:bg-trace-blue-hover text-white text-h4 flex items-center justify-center gap-3 shadow-elevated">
        <ScanLine size={26} aria-hidden />{t('bazar.phone.startSelling')}
      </button>
      <div className="grid grid-cols-2 gap-2.5">
        <button type="button" className={secondary} onClick={() => setSoon(t('bazar.phone.return'))}>{t('bazar.phone.return')}</button>
        <button type="button" className={secondary} onClick={() => setSoon(t('bazar.phone.exchange'))}>{t('bazar.phone.exchange')}</button>
      </div>

      <section className="card p-3.5 flex flex-col gap-2" data-testid="bazar-today">
        <div className="flex items-baseline justify-between gap-2">
          <span className="font-semibold text-primary">{t('bazar.phone.today')}</span>
          {sum
            ? <span className="text-h4 font-bold text-primary tabular-nums" data-testid="bazar-today-total">{formatMoney(sum.total)}</span>
            : <Spinner size={18} />}
        </div>
        {sum && (
          <span className="text-small text-muted">
            {[t('bazar.phone.pieces', { count: sum.count }), ...sellers].join(' · ')}
          </span>
        )}
      </section>

      <div className="flex-1" />
      {manager && (
        <div className="grid grid-cols-2 gap-2.5">
          <button type="button" className={secondary.replace('52px', '46px')}
            onClick={() => setSoon(t('bazar.phone.closeDay'))}>{t('bazar.phone.closeDay')}</button>
          <button type="button" className={secondary.replace('52px', '46px')}
            onClick={() => setSoon(t('bazar.phone.endEvent'))}>{t('bazar.phone.endEvent')}</button>
        </div>
      )}
      <button type="button" onClick={() => navigate(restOfTracedPath())}
        className="text-small font-semibold text-trace-blue py-2 min-h-[44px]">
        {t('bazar.phone.restOfTraced')}
      </button>
      {soon && <ComingSoonSheet what={soon} onClose={() => setSoon(null)} />}
    </PhoneFrame>
  )
}
