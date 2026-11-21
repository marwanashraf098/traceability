import { useCallback, useEffect, useMemo, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { ArrowLeft, ArrowRight, Loader2 } from 'lucide-react'
import {
  BazarError, defaultMethod, exchangeOnce, orderedMethods,
  type BazarExchangeResult, type BazarPieceLookup, type BazarSale, type PaymentMethod,
} from '../../../bazar/api'
import { formatMoney, pieceTitle, toNumber } from '../../../bazar/format'
import { useRetryingCommand } from '../../../bazar/useRetryingCommand'
import type { BazarLive } from '../../../capabilities'
import { cn } from '../../../components/ui'
import { NotLiveScreen, PhoneFrame, useBazarLive } from './shared'
import { MethodChips } from './SaleSheets'
import { BigButton, CodeEntry, CommandStatus, ErrorCard, MoneyBanner, PhoneTabs, PieceRow, soldLine } from './ReturnParts'
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
  const { t, i18n } = useTranslation()
  const navigate = useNavigate()
  const eventId = live.eventId
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
  const diff = ready ? toNumber(newPrice ?? 0) - toNumber(oldSale.price) : 0

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

  const Back = i18n.dir() === 'rtl' ? ArrowRight : ArrowLeft
  const done = cmd.state.kind === 'done' ? cmd.state.result : null

  return (
    <PhoneFrame testId="bazar-exchange" header={
      <>
        <div className="flex items-center justify-between gap-3">
          <button type="button" onClick={() => navigate(`/bazar/${encodeURIComponent(eventId)}/home`)}
            className="min-h-[44px] -ms-2 px-2 flex items-center gap-1.5 text-sidebar-text hover:text-sidebar-active text-small font-semibold">
            <Back size={18} aria-hidden />{t('bazar.sell.home')}
          </button>
          <span className="text-small font-semibold text-sidebar-active truncate">{live.name}</span>
        </div>
        <PhoneTabs eventId={eventId} active="exchange" />
      </>
    } footer={ready && !done && (
      <footer className="sticky bottom-0 z-10 bg-surface border-t border-line px-4 pt-3 pb-[max(16px,env(safe-area-inset-bottom))]">
        <BigButton onClick={confirm} busy={cmd.busy} testId="exchange-confirm">
          {t('bazar.exch.confirm')}
        </BigButton>
      </footer>
    )}>
      {done ? (
        <ExchangeDone result={done} onNext={startOver} />
      ) : (
        <>
          {!ready && (
            <CodeEntry onCode={onCode} disabled={cmd.busy}
                       hint={target === 'old' ? t('bazar.exch.scanOldHint') : t('bazar.exch.scanNewHint')} />
          )}
          {!ready && (
            <button type="button" onClick={() => setSheet(target === 'old' ? 'find' : 'pick')} disabled={cmd.busy}
              data-testid={target === 'old' ? 'exchange-find' : 'exchange-pick'}
              className="h-11 rounded-lg border border-line bg-surface text-small font-semibold text-primary">
              {target === 'old' ? t('bazar.find.open') : t('bazar.pick.open')}
            </button>
          )}
          {sheet === 'find' && (
            <FindSaleSheet eventId={eventId} catalog={catalog} onClose={() => setSheet(null)}
              onPick={s => { setSheet(null); cmd.reset(); oldLook.clear(); setOldFound(s) }} />
          )}
          {sheet === 'pick' && (
            <PickSheet catalog={catalog} mode="exchange" onClose={() => setSheet(null)}
              onPick={(product, size) => { setSheet(null); cmd.reset(); newLook.clear(); setNewTapped({ product, size }) }} />
          )}

          <section className="flex flex-col gap-2">
            <span className={cn('text-small font-medium', target === 'old' ? 'text-primary' : 'text-muted')}>
              {t('bazar.exch.step1')}
            </span>
            {oldSale ? (
              <PieceRow testId="exchange-old" title={pieceTitle(oldSale)} line={soldLine(t, oldSale)} price={oldSale.price}
                        onClear={cmd.busy ? undefined : startOver} />
            ) : oldLook.looked?.loading ? <Looking /> : oldRefusal ? <ErrorCard error={oldRefusal} /> : null}
          </section>

          {oldSale && (
            <section className="flex flex-col gap-2">
              <span className={cn('text-small font-medium', target === 'new' ? 'text-primary' : 'text-muted')}>
                {t('bazar.exch.step2')}
              </span>
              {newTapped ? (
                <PieceRow testId="exchange-new" title={`${newTapped.product.title} · ${sizeName(newTapped.product, newTapped.size)}`}
                          line={t('bazar.pick.tappedLine')} price={newTapped.size.price ?? 0}
                          onClear={cmd.busy ? undefined : () => setNewTapped(null)} />
              ) : newPiece ? (
                <PieceRow testId="exchange-new" title={pieceTitle(newPiece)}
                          line={<span className="font-mono" dir="ltr">{newPiece.piece_code}</span>}
                          price={newPiece.price} onClear={cmd.busy ? undefined : newLook.clear} />
              ) : newLook.looked?.loading ? <Looking /> : newRefusal ? <ErrorCard error={newRefusal} /> : null}
            </section>
          )}

          {ready && (
            <>
              <MoneyBanner kind={diff > 0 ? 'pay' : diff < 0 ? 'giveBack' : 'even'} amount={Math.abs(diff)}
                           testId="exchange-difference" />
              {diff !== 0 && (
                <>
                  <span className="text-small font-medium text-muted">
                    {diff > 0 ? t('bazar.exch.paidBy') : t('bazar.ret.paidBackBy')}
                  </span>
                  <MethodChips methods={methods} value={method} busy={null} disabled={cmd.busy} onPick={setMethod} />
                </>
              )}
              <label className="flex items-center gap-2 min-h-[44px] text-body text-primary">
                <input type="checkbox" checked={damaged} disabled={cmd.busy} onChange={e => setDamaged(e.target.checked)}
                       className="w-5 h-5" data-testid="exchange-damaged" />
                {t('bazar.exch.damaged')}
              </label>
              <CommandStatus state={cmd.state} />
            </>
          )}
          <p className="text-small text-muted">{t('bazar.exch.returnOnly')}</p>
        </>
      )}
    </PhoneFrame>
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
  const m = result.money
  return (
    <>
      <section role="status" className="rounded-2xl bg-[#16A34A] text-white px-4 py-3.5 flex flex-col gap-1.5" data-testid="exchange-done">
        <span className="text-small font-bold tracking-wider">
          {t('bazar.exch.doneTitle', { old: result.old_sale.sale_number, number: result.new_sale.sale_number })}
        </span>
        <span className="text-body-lg font-semibold">
          <span className="line-through opacity-80">{pieceTitle(result.old_sale)}</span> → {pieceTitle(result.new_sale)}
        </span>
        <span className="text-h4 font-bold" data-testid="exchange-done-money">
          {!m ? t('bazar.exch.even')
            : m.kind === 'payment'
              ? t('bazar.exch.doneTook', { amount: formatMoney(m.amount), method: t(`bazar.method.${m.method}`) })
              : t('bazar.ret.doneGiveBack', { amount: formatMoney(m.amount), method: t(`bazar.method.${m.method}`) })}
        </span>
      </section>
      <BigButton variant="secondary" onClick={onNext} testId="exchange-next">{t('bazar.exch.next')}</BigButton>
    </>
  )
}
