import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { Loader2, ScanLine } from 'lucide-react'
import {
  BazarError, markBazarSent, orderedMethods, startBazarEvent, type BazarEventDetail,
} from '../../../bazar/api'
import { toNumber, transferCode } from '../../../bazar/format'
import { resetCapabilitiesCache } from '../../../capabilities'
import { Button, Modal, cn } from '../../../components/ui'
import { whenText } from '../BazarEvents'
import { cairoToday } from '../r1/overviewState'
import { Tag, amount, rangeLabel } from '../r1/parts'
import { Chip } from '../r2/parts'
import { PricesCard, priceTotals, usePriceData } from './PricesCard'
import { SellersCard } from './SellersCard'
import { Card, EventHeader, StepsStrip, btnPrimary, btnSecondary } from './parts'

/**
 * Bazar POS B6 — the draft event: Get ready (design/bazar-v2 V2_Setup). Same actions as before: scan pieces out (the
 * existing full-screen scanner — no scanner on this page), mark as sent (with its confirm), start the event (only once
 * sent), the price rule and per-variant prices. Sellers (B6b) sit under the scan card.
 */
export function SetupView({ ev, reload, onChanged }: {
  ev: BazarEventDetail
  reload: () => Promise<void>
  onChanged: (e: BazarEventDetail) => void
}) {
  const { t, i18n } = useTranslation()
  const lang = i18n.language
  const navigate = useNavigate()
  const [confirmSend, setConfirmSend] = useState(false)
  const [busy, setBusy] = useState<'send' | 'start' | null>(null)
  const [error, setError] = useState<string | null>(null)
  const prices = usePriceData(ev)
  const totals = priceTotals(prices)
  const preparing = ev.transfer_status === 'preparing'
  const sent = ev.transfer_status === 'sent'
  const pieces = ev.counts.sent
  const allPriced = prices.rows != null && prices.rows.length > 0 && totals.missing === 0

  async function act(kind: 'send' | 'start') {
    setBusy(kind)
    setError(null)
    try {
      const next = kind === 'send' ? await markBazarSent(ev.id) : await startBazarEvent(ev.id)
      if (kind === 'start') resetCapabilitiesCache()   // GET /me's bazarLive changes
      onChanged(next)
    } catch (e) {
      setError(e instanceof BazarError ? e.message_(lang) : t('bazar.saveFailed'))
      reload()
    } finally {
      setBusy(null)
      setConfirmSend(false)
    }
  }

  const methods = orderedMethods(ev.payment_methods).map(m => t(`bazar.method.${m}`)).join(', ')
  const rule = ev.discount_pct != null ? t('bazar.r1.overview.sub.rule', { pct: toNumber(ev.discount_pct) }) : t('bazar.r1.overview.sub.shopify')

  return (
    <div className="flex flex-col gap-5 pb-28" data-testid="bazar-setup">
      <EventHeader startsOn={ev.starts_on} lang={lang} name={ev.name}
        back={{ to: '/bazar', label: t('bazar.title') }}
        tag={<Tag tone="amber" testId="bazar-event-tag">{t('bazar.r3.setup.tag', { when: whenText(t, ev.starts_on, cairoToday()) })}</Tag>}
        meta={<>{ev.location_name} · <span>{rangeLabel(ev.starts_on, ev.ends_on, lang)}</span> · {methods}</>}
        actions={
          <button type="button" className={btnSecondary}
                  onClick={() => document.getElementById('bazar-sellers-card')?.scrollIntoView({ behavior: 'smooth', block: 'start' })}>
            {t('bazar.r3.header.inviteSellers')}
          </button>
        } />

      <StepsStrip testId="bazar-setup-steps" steps={[
        { title: t('bazar.r3.setup.steps.details'), sub: `${ev.location_name} · ${rangeLabel(ev.starts_on, ev.ends_on, lang)}`, state: 'done' },
        { title: t('bazar.r1.steps.s2'), sub: t('bazar.r3.setup.steps.piecesSoFar', { count: pieces }), state: sent ? 'done' : 'current' },
        { title: t('bazar.r1.steps.s3'), sub: rule, state: allPriced ? 'done' : sent || pieces > 0 ? 'current' : 'todo' },
        { title: t('bazar.r1.steps.s4'), sub: t('bazar.r1.overview.sub.starts', { date: rangeLabel(ev.starts_on, ev.starts_on, lang) }), state: 'todo' },
      ]} />

      {error && <p role="alert" className="text-body text-critical">{error}</p>}

      <div className="grid gap-5 lg:grid-cols-[minmax(0,0.8fr)_minmax(0,1.6fr)] items-start">
        <div className="flex flex-col gap-5 min-w-0">
          <Card testId="bazar-transfer-card" title={t('bazar.r3.setup.scanTitle')}
            end={<Chip tone={sent ? 'info' : 'neutral'}><span dir="ltr" className="font-mono">{transferCode(ev.transfer_id)}</span>
              {' · '}{sent ? t('bazar.transfer.sent') : t('bazar.transfer.preparing')}</Chip>}>
            <div className="grid grid-cols-3 gap-2">
              <Counter value={String(pieces)} label={t('bazar.r3.setup.piecesOut', { count: pieces })} testId="bazar-sent-count" />
              <Counter value={String(totals.products)} label={t('bazar.r3.setup.products', { count: totals.products })} />
              <Counter value={amount(totals.bazar)} label={t('bazar.r3.setup.egpAtBazar')} />
            </div>
            {preparing && (
              <button type="button" className={cn(btnPrimary, 'w-full')} onClick={() => navigate(`/bazar/${ev.id}/scan-out`)}>
                <ScanLine size={18} aria-hidden />
                {pieces > 0 ? t('bazar.transfer.scanMore') : t('bazar.transfer.scanOut')}
              </button>
            )}
            {preparing && pieces === 0 && <p className="text-small text-muted">{t('bazar.transfer.scanFirst')}</p>}
            <p className="text-small text-muted">{t('bazar.transfer.note')}</p>
          </Card>
          <SellersCard eventId={ev.id} />
        </div>
        <PricesCard ev={ev} data={prices} />
      </div>

      {/* The bottom bar: mark the pieces as sent, then start the event — same rules as before. */}
      <div className="sticky bottom-3 z-10 bg-grey-900 text-[#F2F4F7] rounded-2xl px-4 py-3.5 flex items-center gap-4 flex-wrap shadow-e4"
           data-testid="bazar-setup-bar">
        <span className="flex flex-col min-w-0 flex-[1_1_240px]">
          <span className="font-bold">
            {t('bazar.r3.setup.ready', { count: pieces })}
            {prices.rows && prices.rows.length > 0 && ` · ${totals.missing === 0 ? t('bazar.r3.setup.allPriced') : t('bazar.r1.overview.sub.missing', { count: totals.missing })}`}
          </span>
          <span className="text-small text-sidebar-text">{sent ? t('bazar.start.ready') : t('bazar.r3.setup.sendHint')}</span>
        </span>
        <span className="flex items-center gap-2.5 flex-wrap">
          {preparing && (
            <button type="button" className={btnPrimary} onClick={() => setConfirmSend(true)} disabled={pieces === 0 || busy === 'send'}
                    data-testid="bazar-mark-sent">
              {busy === 'send' && <Loader2 size={16} className="animate-spin" aria-hidden />}
              {t('bazar.r3.setup.markSent', { count: pieces })}
            </button>
          )}
          <button type="button" onClick={() => act('start')} disabled={!sent || busy === 'start'} data-testid="bazar-start"
            className={cn(sent ? btnPrimary : 'h-11 px-[18px] rounded-[10px] border border-grey-700 text-sidebar-text font-semibold disabled:opacity-100')}>
            {busy === 'start' && <Loader2 size={16} className="animate-spin" aria-hidden />}
            {sent ? t('bazar.start.button') : t('bazar.r3.setup.startAfter')}
          </button>
        </span>
      </div>

      {confirmSend && (
        <Modal title={t('bazar.transfer.confirmTitle')} onClose={() => setConfirmSend(false)}>
          <p className="text-body text-primary">{t('bazar.transfer.confirmBody', { count: pieces })}</p>
          <div className="flex justify-end gap-2 mt-5">
            <Button variant="secondary" onClick={() => setConfirmSend(false)}>{t('bazar.form.cancel')}</Button>
            <Button onClick={() => act('send')} loading={busy === 'send'}>{t('bazar.transfer.markSent')}</Button>
          </div>
        </Modal>
      )}
    </div>
  )
}

function Counter({ value, label, testId }: { value: string; label: string; testId?: string }) {
  return (
    <div className="rounded-[10px] border border-line px-3 py-2.5 flex flex-col min-w-0" data-testid={testId}>
      <span dir="ltr" className="text-h4 font-bold text-primary tabular-nums text-start truncate">{value}</span>
      <span className="text-small text-muted truncate">{label}</span>
    </div>
  )
}
