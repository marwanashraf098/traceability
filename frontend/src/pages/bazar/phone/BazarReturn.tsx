import { useCallback, useEffect, useMemo, useState } from 'react'
import { useParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { Loader2, Search } from 'lucide-react'
import {
  BazarError, defaultMethod, lookupBazarPiece, orderedMethods, returnOnce,
  type BazarPieceLookup, type BazarReturnResult, type BazarSale, type PaymentMethod, type ReturnCondition,
} from '../../../bazar/api'
import { pieceTitle } from '../../../bazar/format'
import { useRetryingCommand } from '../../../bazar/useRetryingCommand'
import type { BazarLive } from '../../../capabilities'
import { NotLiveScreen, useBazarLive, useBazarToday } from './shared'
import { useBazarCatalog } from '../../../bazar/catalog'
import { FindSaleSheet } from './FindSale'
import { MethodChips } from './SaleSheets'
import { BigButton, CommandStatus, ErrorCard, soldLine } from './ReturnParts'
import { money } from '../r2/parts'
import {
  Columns, ConditionCards, ConfirmButton, KindSwitch, Label, MoneyBox, PieceCard, R4CodeEntry, R4Header, R4Screen,
  StickyAction, TodayLine, TodayPill,
  useSentenceMoney,
} from '../r4/parts'

/**
 * Bazar POS B3 — RETURN (mockup page 1, step 7): scan → the sale (piece, price, when / by whom, customer) →
 * good / damaged → how the money goes back (default: how they paid) → confirm. Sent like a sale: the same request id
 * on every retry, success only once the server confirms.
 */
export default function BazarReturn() {
  const { id } = useParams<{ id: string }>()
  const { loading, live } = useBazarLive(id)
  if (!live) return <NotLiveScreen loading={loading} />
  return <ReturnScreen live={live} />
}

/** A scan the server answered: the piece, or why it can't be used here. */
export type Looked = { code: string; piece: BazarPieceLookup | null; error: BazarError | null; loading: boolean }

export function useLookup(eventId: string) {
  const [looked, setLooked] = useState<Looked | null>(null)
  const look = useCallback(async (code: string) => {
    setLooked({ code, piece: null, error: null, loading: true })
    try {
      const piece = await lookupBazarPiece(eventId, code)
      setLooked(cur => (cur?.code === code ? { code, piece, error: null, loading: false } : cur))
    } catch (e) {
      const error = e instanceof BazarError ? e : new BazarError(0, { code: 'NETWORK' })
      setLooked(cur => (cur?.code === code ? { code, piece: null, error, loading: false } : cur))
    }
  }, [eventId])
  return { looked, look, clear: () => setLooked(null) }
}

/** A looked-up piece that can't be returned here → the same refusal the server would give. */
export function notReturnable(p: BazarPieceLookup): BazarError | null {
  if (p.state === 'sold') return null
  if (p.state === 'returned') {
    return new BazarError(409, { code: 'BAZAR_ALREADY_RETURNED',
      details: { sale_number: p.sale?.sale_number, returned_by_name: p.sale?.returned_by_name } })
  }
  return new BazarError(409, { code: 'BAZAR_NOT_SOLD_HERE' })
}

function ReturnScreen({ live }: { live: BazarLive }) {
  const { t } = useTranslation()
  const eventId = live.eventId
  const methods = useMemo(() => orderedMethods(live.paymentMethods), [live.paymentMethods])
  const { looked, look, clear } = useLookup(eventId)
  const { today } = useBazarToday(eventId)
  const sm = useSentenceMoney()
  const cmd = useRetryingCommand<BazarReturnResult>()
  const [condition, setCondition] = useState<ReturnCondition>('good')
  const [method, setMethod] = useState<PaymentMethod>(defaultMethod(live.paymentMethods))

  // B7c: no label — the sale found by product + size (sent as sale_id); a scan still sends its code.
  const { catalog } = useBazarCatalog(eventId)
  const [finding, setFinding] = useState(false)
  const [found, setFound] = useState<BazarSale | null>(null)
  const sale = found ?? (looked?.piece?.state === 'sold' ? looked.piece.sale : null)
  const refusal = found ? null : looked?.piece ? notReturnable(looked.piece) : looked?.error ?? null

  // A new sale on screen: good, and the money goes back the way it came in.
  useEffect(() => {
    if (!sale) return
    setCondition('good')
    setMethod(sale.method && methods.includes(sale.method) ? sale.method : defaultMethod(live.paymentMethods))
  }, [sale?.id]) // eslint-disable-line react-hooks/exhaustive-deps

  const onCode = useCallback((code: string) => {
    if (cmd.busy) return
    cmd.reset()
    setFound(null)
    look(code)
  }, [cmd, look])

  function confirm() {
    if (!sale) return
    if (found) {
      const saleId = found.id
      cmd.run((requestId, timeoutMs) => returnOnce(eventId, { saleId, condition, method }, requestId, timeoutMs))
      return
    }
    if (!looked) return
    const code = looked.code
    cmd.run((requestId, timeoutMs) => returnOnce(eventId, { code, condition, method }, requestId, timeoutMs))
  }

  function next() { cmd.reset(); clear(); setFound(null) }

  const done = cmd.state.kind === 'done' ? cmd.state.result : null
  const returns = today ? t('bazar.phone.returnsCount', { count: today.returns ?? 0 }) : ''

  return (
    <R4Screen testId="bazar-return" header={
      <R4Header live={live} back={{ to: `/bazar/${encodeURIComponent(eventId)}/sell`, label: t('bazar.tabs.sell') }}
        title={live.name} wideTitle={t('bazar.r4.returnTitle')}
        sub={today ? <TodayLine total={today.total} count={returns} /> : null}
        wideSub={t('bazar.r4.returnSub')}
        today={today ? <TodayPill total={today.total} count={returns} /> : null} />
    }>
      {done ? (
        <ReturnDone result={done} onNext={next} />
      ) : (
        <Columns left={
          <>
            {!sale && <R4CodeEntry onCode={onCode} disabled={cmd.busy} hint={t('bazar.ret.scanHint')} />}
            <h2 className="lg:hidden text-h4 font-bold text-primary">{t('bazar.r4.returnTitle')}</h2>
            <KindSwitch eventId={eventId} active="return" />
            {!sale && (
              <button type="button" onClick={() => setFinding(true)} disabled={cmd.busy} data-testid="return-find"
                className="h-12 rounded-xl border border-line bg-surface text-body font-semibold text-primary flex items-center justify-center gap-2 hover:bg-charcoal">
                <Search size={17} aria-hidden />{t('bazar.find.open')}
              </button>
            )}
            {finding && (
              <FindSaleSheet eventId={eventId} catalog={catalog} onClose={() => setFinding(false)}
                onPick={s => { setFinding(false); cmd.reset(); clear(); setFound(s) }} />
            )}
            {looked?.loading && (
              <p className="flex items-center gap-2 text-small text-muted" role="status">
                <Loader2 size={14} className="animate-spin" aria-hidden />{t('bazar.ret.looking')}
              </p>
            )}
            {refusal && <ErrorCard error={refusal} />}
            {sale && (
              <>
                <section className="flex flex-col gap-2">
                  <Label>{t('bazar.ret.coming')}</Label>
                  <PieceCard testId="return-sale" catalog={catalog} variantId={sale.variant_id} title={pieceTitle(sale)}
                             code={sale.piece_code} line={soldLine(t, sale)} price={sale.price}
                             onClear={cmd.busy ? undefined : next} />
                </section>
                <section className="flex flex-col gap-2">
                  <span className="text-body font-semibold text-primary">{t('bazar.r4.shape')}</span>
                  <ConditionCards damaged={condition === 'damaged'} disabled={cmd.busy}
                                  onChange={d => setCondition(d ? 'damaged' : 'good')} />
                </section>
              </>
            )}
            {!looked && !found && <p className="text-small text-muted">{t('bazar.ret.intro')}</p>}
          </>
        } right={sale && (
          <>
            <h2 className="hidden lg:block text-body-lg font-bold text-primary">{t('bazar.r4.refundSummary')}</h2>
            <MoneyBox testId="return-amount" label={t('bazar.r4.giveBack')} amount={money(sale.price)}
                      note={t('bazar.r4.giveBackNote')} />
            <Label>{t('bazar.r4.refundWith')} · <span className="normal-case tracking-normal font-semibold">{t('bazar.r4.sameAsSale')}</span></Label>
            <MethodChips methods={methods} value={method} busy={null} disabled={cmd.busy} onPick={setMethod} />
            <CommandStatus state={cmd.state} />
            <StickyAction>
              <ConfirmButton onClick={confirm} busy={cmd.busy} testId="return-confirm">
                {t('bazar.r4.confirmRefund', { amount: sm(sale.price), method: t(`bazar.method.${method}`) })}
              </ConfirmButton>
            </StickyAction>
          </>
        )} />
      )}
    </R4Screen>
  )
}

function ReturnDone({ result, onNext }: { result: BazarReturnResult; onNext: () => void }) {
  const { t } = useTranslation()
  const sm = useSentenceMoney()
  const s = result.sale
  return (
    <div className="flex flex-col gap-4 lg:max-w-[560px]">
      <section role="status" className="rounded-2xl bg-[#16A34A] text-white p-4 lg:p-5 flex flex-col gap-1.5" data-testid="return-done">
        <span className="text-[12px] font-bold tracking-[0.08em]">{t('bazar.ret.doneTitle', { number: s.sale_number })}</span>
        <span className="text-[20px] leading-6 font-bold">{pieceTitle(s)}</span>
        {result.refund && (
          <span className="text-h4 font-bold" data-testid="return-done-refund">
            {t('bazar.ret.doneGiveBack', { amount: sm(result.refund.amount),
                                           method: t(`bazar.method.${result.refund.method}`) })}
          </span>
        )}
        <span className="text-small">
          {s.return_condition === 'damaged' ? t('bazar.ret.doneDamaged') : t('bazar.ret.doneGood')}
        </span>
      </section>
      <BigButton variant="secondary" onClick={onNext} testId="return-next">{t('bazar.ret.next')}</BigButton>
    </div>
  )
}
