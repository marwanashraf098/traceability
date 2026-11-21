import { useCallback, useEffect, useMemo, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { ArrowLeft, ArrowRight, Loader2 } from 'lucide-react'
import {
  BazarError, defaultMethod, lookupBazarPiece, orderedMethods, returnOnce,
  type BazarPieceLookup, type BazarReturnResult, type BazarSale, type PaymentMethod, type ReturnCondition,
} from '../../../bazar/api'
import { formatMoney, pieceTitle, toNumber } from '../../../bazar/format'
import { useRetryingCommand } from '../../../bazar/useRetryingCommand'
import type { BazarLive } from '../../../capabilities'
import { NotLiveScreen, PhoneFrame, useBazarLive } from './shared'
import { useBazarCatalog } from '../../../bazar/catalog'
import { FindSaleSheet } from './FindSale'
import { MethodChips } from './SaleSheets'
import {
  BigButton, CodeEntry, CommandStatus, ConditionToggle, ErrorCard, MoneyBanner, PhoneTabs, PieceRow, soldLine,
} from './ReturnParts'

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
  const { t, i18n } = useTranslation()
  const navigate = useNavigate()
  const eventId = live.eventId
  const methods = useMemo(() => orderedMethods(live.paymentMethods), [live.paymentMethods])
  const { looked, look, clear } = useLookup(eventId)
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

  const Back = i18n.dir() === 'rtl' ? ArrowRight : ArrowLeft
  const done = cmd.state.kind === 'done' ? cmd.state.result : null

  return (
    <PhoneFrame testId="bazar-return" header={
      <>
        <div className="flex items-center justify-between gap-3">
          <button type="button" onClick={() => navigate(`/bazar/${encodeURIComponent(eventId)}/home`)}
            className="min-h-[44px] -ms-2 px-2 flex items-center gap-1.5 text-sidebar-text hover:text-sidebar-active text-small font-semibold">
            <Back size={18} aria-hidden />{t('bazar.sell.home')}
          </button>
          <span className="text-small font-semibold text-sidebar-active truncate">{live.name}</span>
        </div>
        <PhoneTabs eventId={eventId} active="return" />
      </>
    } footer={sale && !done && (
      <footer className="sticky bottom-0 z-10 bg-surface border-t border-line px-4 pt-3 pb-[max(16px,env(safe-area-inset-bottom))]">
        <BigButton onClick={confirm} busy={cmd.busy} testId="return-confirm">
          {t('bazar.ret.confirm', { amount: formatMoney(sale.net_price ?? sale.price) })}
        </BigButton>
      </footer>
    )}>
      {done ? (
        <ReturnDone result={done} onNext={next} />
      ) : (
        <>
          {!sale && <CodeEntry onCode={onCode} disabled={cmd.busy} hint={t('bazar.ret.scanHint')} />}
          {!sale && (
            <button type="button" onClick={() => setFinding(true)} disabled={cmd.busy} data-testid="return-find"
              className="h-11 rounded-lg border border-line bg-surface text-small font-semibold text-primary">
              {t('bazar.find.open')}
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
                <span className="text-small font-medium text-muted">{t('bazar.ret.coming')}</span>
                <PieceRow testId="return-sale" title={pieceTitle(sale)} line={soldLine(t, sale)} price={sale.net_price ?? sale.price}
                          onClear={cmd.busy ? undefined : next} />
              </section>
              <span className="text-small font-medium text-muted">{t('bazar.ret.condition')}</span>
              <ConditionToggle value={condition} onChange={setCondition} disabled={cmd.busy} />
              <MoneyBanner kind="giveBack" amount={toNumber(sale.net_price ?? sale.price)} testId="return-amount" label={t('bazar.ret.refund')} />
              <span className="text-small font-medium text-muted">{t('bazar.ret.paidBackBy')}</span>
              <MethodChips methods={methods} value={method} busy={null} disabled={cmd.busy} onPick={setMethod} />
              <CommandStatus state={cmd.state} />
            </>
          )}
          {!looked && !found && <p className="text-small text-muted">{t('bazar.ret.intro')}</p>}
        </>
      )}
    </PhoneFrame>
  )
}

function ReturnDone({ result, onNext }: { result: BazarReturnResult; onNext: () => void }) {
  const { t } = useTranslation()
  const s = result.sale
  return (
    <>
      <section role="status" className="rounded-2xl bg-[#16A34A] text-white px-4 py-3.5 flex flex-col gap-1.5" data-testid="return-done">
        <span className="text-small font-bold tracking-wider">{t('bazar.ret.doneTitle', { number: s.sale_number })}</span>
        <span className="text-body-lg font-semibold">{pieceTitle(s)}</span>
        {result.refund && (
          <span className="text-h4 font-bold" data-testid="return-done-refund">
            {t('bazar.ret.doneGiveBack', { amount: formatMoney(result.refund.amount),
                                           method: t(`bazar.method.${result.refund.method}`) })}
          </span>
        )}
        <span className="text-small">
          {s.return_condition === 'damaged' ? t('bazar.ret.doneDamaged') : t('bazar.ret.doneGood')}
        </span>
      </section>
      <BigButton variant="secondary" onClick={onNext} testId="return-next">{t('bazar.ret.next')}</BigButton>
    </>
  )
}
