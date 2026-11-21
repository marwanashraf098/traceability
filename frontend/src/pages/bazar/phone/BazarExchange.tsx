import { useCallback, useEffect, useMemo, useState } from 'react'
import { useParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { Loader2 } from 'lucide-react'
import {
  BazarError, defaultMethod, exchangeOnce, orderedMethods,
  type BazarExchangeResult, type BazarPieceLookup, type BazarSale, type PaymentMethod,
} from '../../../bazar/api'
import { pieceTitle, toNumber } from '../../../bazar/format'
import { useRetryingCommand } from '../../../bazar/useRetryingCommand'
import type { BazarLive } from '../../../capabilities'
import { cn } from '../../../components/ui'
import { NotLiveScreen, useBazarLive, useBazarToday } from './shared'
import { MethodChips } from './SaleSheets'
import { BigButton, CommandStatus, ErrorCard, soldLine } from './ReturnParts'
import { amount } from '../r1/parts'
import { money } from '../r2/parts'
import {
  Columns, ConditionCards, ConfirmButton, KindSwitch, Label, MoneyBox, PieceCard, R4CodeEntry, R4Header, R4Screen,
  StepTick, StickyAction, TodayLine, TodayPill,
  useSentenceMoney,
} from '../r4/parts'
import { notReturnable, useLookup } from './BazarReturn'
import { sizeName, useBazarCatalog, type BazarCatalogProduct, type BazarCatalogSize } from '../../../bazar/catalog'
import { FindSaleSheet } from './FindSale'
import { PickSheet } from './PickSheet'

/**
 * Bazar POS B3 — EXCHANGE (mockup page 1, step 7): scan the piece coming back → scan the new piece → "Customer pays
 * X" / "Give back X" / "Even swap" → how (when money moves) → confirm. One request: the server returns the old sale and
 * sells the new piece in one transaction; only the difference moves as money. Same retry rules as a sale.
 */
export default function BazarExchange() {
  const { id } = useParams<{ id: string }>()
  const { loading, live } = useBazarLive(id)
  if (!live) return <NotLiveScreen loading={loading} />
  return <ExchangeScreen live={live} />
}

/** A new piece that can't be sold here → the refusal the server would give. */
function notForSale(p: BazarPieceLookup): BazarError | null {
  if (p.state === 'for_sale') return p.price == null ? new BazarError(409, { code: 'BAZAR_NO_PRICE' }) : null
  if (p.state === 'sold') {
    return new BazarError(409, { code: 'BAZAR_ALREADY_SOLD', details: {
      sale_number: p.sale?.sale_number, sold_at: p.sale?.sold_at, seller_name: p.sale?.seller_name } })
  }
  if (p.damaged) return new BazarError(409, { code: 'BAZAR_PIECE_DAMAGED' })
  return new BazarError(409, { code: 'BAZAR_PIECE_NOT_HERE' })
}

function ExchangeScreen({ live }: { live: BazarLive }) {
  const { t } = useTranslation()
  const eventId = live.eventId
  const { today } = useBazarToday(eventId)
  const sm = useSentenceMoney()
  const methods = useMemo(() => orderedMethods(live.paymentMethods), [live.paymentMethods])
  const oldLook = useLookup(eventId)
  const newLook = useLookup(eventId)
  const cmd = useRetryingCommand<BazarExchangeResult>()
  const [method, setMethod] = useState<PaymentMethod>(defaultMethod(live.paymentMethods))
  const [damaged, setDamaged] = useState(false)

  // B7c: no label — the piece coming back by its sale (found by product + size), the new one as a tapped size.
  const { catalog } = useBazarCatalog(eventId)
  const [sheet, setSheet] = useState<'find' | 'pick' | null>(null)
  const [oldFound, setOldFound] = useState<BazarSale | null>(null)
  const [newTapped, setNewTapped] = useState<{ product: BazarCatalogProduct; size: BazarCatalogSize } | null>(null)
  const oldSale = oldFound ?? (oldLook.looked?.piece?.state === 'sold' ? oldLook.looked.piece.sale : null)
  const oldRefusal = oldFound ? null : oldLook.looked?.piece ? notReturnable(oldLook.looked.piece) : oldLook.looked?.error ?? null
  const scanned = newTapped ? null : newLook.looked?.piece ?? null
  // Checked first: the piece coming back is "sold", but the right answer is "scan a different one".
  const samePiece = !!scanned && !!oldSale && scanned.piece_id === oldSale.piece_id
  const newPiece = scanned && !samePiece && !notForSale(scanned) ? scanned : null
  const newRefusal = newTapped ? null : samePiece ? new BazarError(400, { code: 'BAZAR_SAME_PIECE' })
    : newLook.looked?.piece ? notForSale(newLook.looked.piece) : newLook.looked?.error ?? null
  const target: 'old' | 'new' = oldSale ? 'new' : 'old'

  useEffect(() => {
    if (!oldSale) return
    setMethod(oldSale.method && methods.includes(oldSale.method) ? oldSale.method : defaultMethod(live.paymentMethods))
    setDamaged(false)
  }, [oldSale?.id]) // eslint-disable-line react-hooks/exhaustive-deps

  const onCode = useCallback((code: string) => {
    if (cmd.busy) return
    cmd.reset()
    if (target === 'old') { setOldFound(null); oldLook.look(code) }
    else { setNewTapped(null); newLook.look(code) }
  }, [cmd, target, oldLook, newLook])

  const newPrice = newTapped ? newTapped.size.price : newPiece?.price
  const ready = !!oldSale && (!!newPiece || !!newTapped)
  // What the server credits for the piece coming back: what it was paid — a cart line's net price (B6c), a quick
  // sale's price (its net price is its price).
  const credit = oldSale ? toNumber(oldSale.net_price ?? oldSale.price) : 0
  const diff = ready ? toNumber(newPrice ?? 0) - credit : 0

  function confirm() {
    if (!ready) return
    const old = oldFound ? { oldSaleId: oldFound.id } : oldLook.looked ? { oldCode: oldLook.looked.code } : null
    const next = newTapped ? { newVariantId: newTapped.size.variant_id }
      : newLook.looked ? { newCode: newLook.looked.code } : null
    if (!old || !next) return
    const m = diff === 0 ? null : method
    cmd.run((requestId, timeoutMs) => exchangeOnce(eventId,
      { ...old, ...next, method: m, condition: damaged ? 'damaged' : 'good' }, requestId, timeoutMs))
  }

  function startOver() { cmd.reset(); oldLook.clear(); newLook.clear(); setOldFound(null); setNewTapped(null) }

  const done = cmd.state.kind === 'done' ? cmd.state.result : null
  const exchanges = today ? t('bazar.phone.exchangesCount', { count: today.exchanges ?? 0 }) : ''
  const methodName = t(`bazar.method.${method}`)
  const linkBtn = 'min-h-[36px] px-1 text-small font-semibold text-trace-blue hover:underline shrink-0 disabled:opacity-60'

  return (
    <R4Screen testId="bazar-exchange" header={
      <R4Header live={live} back={{ to: `/bazar/${encodeURIComponent(eventId)}/sell`, label: t('bazar.tabs.sell') }}
        title={live.name} wideTitle={t('bazar.r4.returnTitle')}
        sub={today ? <TodayLine total={today.total} count={exchanges} /> : null}
        wideSub={t('bazar.r4.returnSub')}
        today={today ? <TodayPill total={today.total} count={exchanges} /> : null} />
    }>
      {done ? (
        <ExchangeDone result={done} onNext={startOver} />
      ) : (
        <Columns left={
          <>
            {!ready && (
              <R4CodeEntry onCode={onCode} disabled={cmd.busy}
                           hint={target === 'old' ? t('bazar.exch.scanOldHint') : t('bazar.exch.scanNewHint')} />
            )}
            <h2 className="lg:hidden text-h4 font-bold text-primary">{t('bazar.r4.returnTitle')}</h2>
            <KindSwitch eventId={eventId} active="exchange" />
            {sheet === 'find' && (
              <FindSaleSheet eventId={eventId} catalog={catalog} onClose={() => setSheet(null)}
                onPick={s => { setSheet(null); cmd.reset(); oldLook.clear(); setOldFound(s) }} />
            )}
            {sheet === 'pick' && (
              <PickSheet catalog={catalog} mode="exchange" onClose={() => setSheet(null)}
                onPick={(product, size) => { setSheet(null); cmd.reset(); newLook.clear(); setNewTapped({ product, size }) }} />
            )}

            <section className="flex flex-col gap-2.5">
              <div className="flex items-center gap-2.5">
                <StepTick done={!!oldSale} />
                <span className={cn('flex-1 text-body font-semibold', target === 'old' ? 'text-primary' : 'text-muted')}>
                  {t('bazar.r4.pieceBack')}
                </span>
                {target === 'old' && (
                  <button type="button" onClick={() => setSheet('find')} disabled={cmd.busy} data-testid="exchange-find" className={linkBtn}>
                    {t('bazar.find.open')}
                  </button>
                )}
              </div>
              {oldSale ? (
                <PieceCard testId="exchange-old" catalog={catalog} variantId={oldSale.variant_id} title={pieceTitle(oldSale)}
                           code={oldSale.piece_code} line={soldLine(t, oldSale)} price={oldSale.net_price ?? oldSale.price}
                           onClear={cmd.busy ? undefined : startOver} />
              ) : oldLook.looked?.loading ? <Looking /> : oldRefusal ? <ErrorCard error={oldRefusal} /> : null}
              {oldSale && (
                <>
                  <span className="text-body font-semibold text-primary">{t('bazar.r4.shape')}</span>
                  <ConditionCards damaged={damaged} disabled={cmd.busy} onChange={setDamaged} />
                </>
              )}
            </section>

            {oldSale && (
              <section className="flex flex-col gap-2.5">
                <div className="flex items-center gap-2.5">
                  <StepTick done={ready} />
                  <span className={cn('flex-1 text-body font-semibold', target === 'new' ? 'text-primary' : 'text-muted')}>
                    {t('bazar.r4.pieceInstead')}
                  </span>
                  {!ready && (
                    <button type="button" onClick={() => setSheet('pick')} disabled={cmd.busy} data-testid="exchange-pick" className={linkBtn}>
                      {t('bazar.r4.pickWithout')}
                    </button>
                  )}
                </div>
                {newTapped ? (
                  <PieceCard testId="exchange-new" tone="new" catalog={catalog} variantId={newTapped.size.variant_id}
                             title={`${newTapped.product.title} · ${sizeName(newTapped.product, newTapped.size)}`}
                             line={t('bazar.pick.tappedLine')} price={newTapped.size.price ?? 0}
                             onClear={cmd.busy ? undefined : () => setNewTapped(null)} />
                ) : newPiece ? (
                  <PieceCard testId="exchange-new" tone="new" catalog={catalog} variantId={null} title={pieceTitle(newPiece)}
                             code={newPiece.piece_code} line={t('bazar.r4.atBazar')}
                             price={newPiece.price} onClear={cmd.busy ? undefined : newLook.clear} />
                ) : newLook.looked?.loading ? <Looking /> : newRefusal ? <ErrorCard error={newRefusal} /> : (
                  <p className="rounded-xl border-[1.5px] border-dashed border-trace-blue/50 px-4 py-3.5 text-center text-body font-semibold text-trace-blue">
                    {t('bazar.exch.scanNewHint')}
                  </p>
                )}
              </section>
            )}
            <p className="text-small text-muted">{t('bazar.exch.returnOnly')}</p>
          </>
        } right={oldSale && (
          <>
            <h2 className="hidden lg:block text-body-lg font-bold text-primary">{t('bazar.r4.summary')}</h2>
            <div className="flex flex-col gap-1.5 text-body" data-testid="exchange-summary">
              <div className="flex justify-between gap-3">
                <span className="text-muted">{t('bazar.r4.comingBack')}</span>
                <span className="tabular-nums text-primary" dir="ltr">− {amount(credit)}</span>
              </div>
              <div className="flex justify-between gap-3">
                <span className="text-muted">{t('bazar.r4.goingOut')}</span>
                <span className="tabular-nums text-primary" dir="ltr">{ready ? `+ ${amount(newPrice ?? 0)}` : '—'}</span>
              </div>
            </div>
            {ready && (
              <MoneyBox testId="exchange-difference" big
                label={diff > 0 ? t('bazar.r4.customerPays') : diff < 0 ? t('bazar.r4.giveBack') : t('bazar.exch.noMoney')}
                amount={diff === 0 ? t('bazar.exch.even') : money(Math.abs(diff))}
                note={t('bazar.r4.diffNote')} />
            )}
            {ready && diff !== 0 && (
              <>
                <Label>{diff > 0 ? t('bazar.exch.paidBy') : t('bazar.ret.paidBackBy')}</Label>
                <MethodChips methods={methods} value={method} busy={null} disabled={cmd.busy} onPick={setMethod} />
              </>
            )}
            <CommandStatus state={cmd.state} />
            {ready && (
              <StickyAction>
                <ConfirmButton onClick={confirm} busy={cmd.busy} testId="exchange-confirm">
                  {diff > 0 ? t('bazar.r4.confirmTake', { amount: sm(diff), method: methodName })
                    : diff < 0 ? t('bazar.r4.confirmGive', { amount: sm(-diff), method: methodName })
                    : t('bazar.r4.confirmEven')}
                </ConfirmButton>
              </StickyAction>
            )}
            <p className="hidden lg:block text-small text-muted border-t border-line pt-3">{t('bazar.r4.explain')}</p>
          </>
        )} />
      )}
    </R4Screen>
  )
}

function Looking() {
  const { t } = useTranslation()
  return (
    <p className="flex items-center gap-2 text-small text-muted" role="status">
      <Loader2 size={14} className="animate-spin" aria-hidden />{t('bazar.ret.looking')}
    </p>
  )
}

function ExchangeDone({ result, onNext }: { result: BazarExchangeResult; onNext: () => void }) {
  const { t } = useTranslation()
  const sm = useSentenceMoney()
  const m = result.money
  return (
    <div className="flex flex-col gap-4 lg:max-w-[560px]">
      <section role="status" className="rounded-2xl bg-[#16A34A] text-white p-4 lg:p-5 flex flex-col gap-1.5" data-testid="exchange-done">
        <span className="text-[12px] font-bold tracking-[0.08em]">
          {t('bazar.exch.doneTitle', { old: result.old_sale.sale_number, number: result.new_sale.sale_number })}
        </span>
        <span className="text-[18px] leading-6 font-bold">
          <span className="line-through opacity-80">{pieceTitle(result.old_sale)}</span> → {pieceTitle(result.new_sale)}
        </span>
        <span className="text-h4 font-bold" data-testid="exchange-done-money">
          {!m ? t('bazar.exch.even')
            : m.kind === 'payment'
              ? t('bazar.exch.doneTook', { amount: sm(m.amount), method: t(`bazar.method.${m.method}`) })
              : t('bazar.ret.doneGiveBack', { amount: sm(m.amount), method: t(`bazar.method.${m.method}`) })}
        </span>
      </section>
      <BigButton variant="secondary" onClick={onNext} testId="exchange-next">{t('bazar.exch.next')}</BigButton>
    </div>
  )
}
