import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Search } from 'lucide-react'
import { Modal, Spinner, cn } from './ui'
import {
  LookupExchangeCreated, LookupExchangeOrder, LookupReturnable, TransferCommandError,
  createLookupExchange, getLookupReturnables, searchLookupExchangeOrders,
} from '../api'

/** The portal's return reasons (PortalService.REASON_CODES) — optional here, 'other' when left alone. */
const REASONS = ['other', 'wrong_size', 'damaged', 'not_as_pictured', 'wrong_item', 'changed_mind'] as const

function returnableKey(r: LookupReturnable): string {
  return r.kind === 'piece' ? `p:${r.pieceId}` : `u:${r.orderItemId}:${r.unitNo}`
}

/**
 * C2 Lookup Exchange — "Exchange this piece" (mockup screen 4). Three steps on one card: 1. find the customer's
 * original order, 2. pick what comes back (a delivered piece, or a unit of an untracked line), 3. optionally the
 * courier and its waybills (the outbound one hands this piece to the courier at once). Every refusal shows the
 * server's message in the merchant's language.
 */
export default function LookupExchangeDialog({ piece, onClose, onCreated }: {
  piece: { id: string; shortCode: string | null; productTitle: string; variantTitle: string }
  onClose: () => void
  onCreated: (created: LookupExchangeCreated) => void
}) {
  const { t, i18n } = useTranslation()
  const [q, setQ] = useState('')
  const [orders, setOrders] = useState<LookupExchangeOrder[] | null>(null)
  const [searching, setSearching] = useState(false)
  const [order, setOrder] = useState<LookupExchangeOrder | null>(null)
  const [returnables, setReturnables] = useState<LookupReturnable[] | null>(null)
  const [returning, setReturning] = useState<string | null>(null)
  const [reason, setReason] = useState<string>('other')
  const [carrier, setCarrier] = useState('')
  const [outbound, setOutbound] = useState('')
  const [back, setBack] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  function message(e: unknown): string {
    return e instanceof TransferCommandError
      ? (i18n.language === 'ar' ? e.messageAr : e.messageEn)
      : t('lookupExchange.dialog.failed')
  }

  useEffect(() => {
    const term = q.trim()
    if (term.length < 2) { setOrders(null); return }
    let cancelled = false
    const timer = setTimeout(() => {
      setSearching(true)
      searchLookupExchangeOrders(term)
        .then(list => { if (!cancelled) setOrders(list) })
        .catch(e => { if (!cancelled) { setOrders([]); setError(message(e)) } })
        .finally(() => { if (!cancelled) setSearching(false) })
    }, 300)
    return () => { cancelled = true; clearTimeout(timer) }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [q])

  async function chooseOrder(o: LookupExchangeOrder) {
    setOrder(o)
    setReturning(null)
    setReturnables(null)
    setError(null)
    try {
      const list = await getLookupReturnables(o.id)
      setReturnables(list)
      if (list.length === 1) setReturning(returnableKey(list[0]))
    } catch (e) {
      setReturnables([])
      setError(message(e))
    }
  }

  async function submit() {
    const chosen = returnables?.find(r => returnableKey(r) === returning)
    if (!order || !chosen) { setError(t('lookupExchange.dialog.pickReturning')); return }
    setBusy(true)
    setError(null)
    try {
      const created = await createLookupExchange(piece.id, {
        orderId: order.id,
        ...(chosen.kind === 'piece'
          ? { returningPieceId: chosen.pieceId }
          : { returningOrderItemId: chosen.orderItemId, returningUnitNo: chosen.unitNo }),
        reasonCode: reason,
        carrier: carrier.trim() || undefined,
        outboundTrackingNumber: outbound.trim() || undefined,
        returnTrackingNumber: back.trim() || undefined,
      })
      onCreated(created)
    } catch (e) {
      setError(message(e))
    } finally {
      setBusy(false)
    }
  }

  const step = 'text-caption font-semibold text-muted uppercase tracking-wider'

  return (
    <Modal title={t('lookupExchange.dialog.title')} onClose={onClose}>
      <div className="space-y-5 max-h-[70vh] overflow-y-auto -mx-1 px-1" data-testid="lookup-exchange-dialog">
        <p className="text-small text-muted">
          {t('lookupExchange.dialog.goingOut')}{' '}
          <span className="text-primary"><bdi>{piece.productTitle} — {piece.variantTitle}</bdi></span>
          {piece.shortCode && <>{' · '}<bdi className="font-mono" dir="ltr">{piece.shortCode}</bdi></>}
        </p>

        {/* 1. The original order */}
        <section className="space-y-2">
          <h4 className={step}>{t('lookupExchange.dialog.step1')}</h4>
          {order ? (
            <div className="flex items-center justify-between gap-3 rounded-lg border border-trace-blue/40 bg-elevated px-3 py-2">
              <OrderLine o={order} />
              <button type="button" className="text-small font-medium text-trace-blue hover:underline flex-shrink-0"
                onClick={() => { setOrder(null); setReturnables(null); setReturning(null) }}>
                {t('lookupExchange.dialog.change')}
              </button>
            </div>
          ) : (
            <>
              <div className="relative">
                <Search size={14} className="absolute top-1/2 -translate-y-1/2 start-3 text-muted pointer-events-none" />
                <input className="input w-full ps-8" autoFocus value={q} data-testid="lookup-exchange-search"
                  placeholder={t('lookupExchange.dialog.searchPlaceholder')} aria-label={t('lookupExchange.dialog.step1')}
                  onChange={e => { setQ(e.target.value); setError(null) }} />
              </div>
              {searching && <div className="flex justify-center py-2"><Spinner size={16} /></div>}
              {!searching && orders && orders.length === 0 && (
                <p className="text-small text-muted" data-testid="lookup-exchange-no-orders">{t('lookupExchange.dialog.noOrders')}</p>
              )}
              {!searching && orders && orders.length > 0 && (
                <ul className="space-y-1.5" data-testid="lookup-exchange-orders">
                  {orders.map(o => (
                    <li key={o.id}>
                      <button type="button" onClick={() => chooseOrder(o)}
                        className="w-full text-start rounded-lg border border-line px-3 py-2 hover:border-trace-blue/40 hover:bg-elevated transition-colors">
                        <OrderLine o={o} />
                      </button>
                    </li>
                  ))}
                </ul>
              )}
            </>
          )}
        </section>

        {/* 2. What comes back */}
        {order && (
          <section className="space-y-2">
            <h4 className={step}>{t('lookupExchange.dialog.step2')}</h4>
            {returnables === null ? (
              <div className="flex justify-center py-2"><Spinner size={16} /></div>
            ) : returnables.length === 0 ? (
              <p className="text-small text-muted">{t('lookupExchange.dialog.nothingToReturn')}</p>
            ) : (
              <div className="space-y-1.5" role="radiogroup" aria-label={t('lookupExchange.dialog.step2')}
                data-testid="lookup-exchange-returnables">
                {returnables.map(r => {
                  const key = returnableKey(r)
                  const on = key === returning
                  return (
                    <label key={key}
                      className={cn('flex items-center gap-3 rounded-lg border px-3 py-2 cursor-pointer transition-colors',
                        on ? 'border-trace-blue/60 bg-elevated' : 'border-line hover:bg-elevated')}>
                      <input type="radio" name="lookup-exchange-returning" checked={on} onChange={() => setReturning(key)}
                        className="accent-trace-blue" />
                      <span className="min-w-0 flex-1 text-small text-primary">
                        <bdi>{r.productTitle} — {r.variantTitle}</bdi>
                        <span className="text-muted">
                          {' · '}
                          {r.kind === 'piece'
                            ? <bdi className="font-mono" dir="ltr">{r.shortCode}</bdi>
                            : t('lookupExchange.dialog.unit', { n: r.unitNo })}
                        </span>
                      </span>
                    </label>
                  )
                })}
              </div>
            )}
            <div className="space-y-1">
              <label htmlFor="lookup-exchange-reason" className="text-small font-medium text-primary block">
                {t('lookupExchange.dialog.reason')}{' '}
                <span className="font-normal text-muted">{t('exchangesRefunds.requests.drawer.manual.optional')}</span>
              </label>
              <select id="lookup-exchange-reason" className="input w-full" value={reason} onChange={e => setReason(e.target.value)}>
                {REASONS.map(r => <option key={r} value={r}>{t(`exchangesRefunds.requests.reasons.${r}`)}</option>)}
              </select>
            </div>
          </section>
        )}

        {/* 3. Courier and waybills — optional, can be added later in the request */}
        {order && (
          <section className="space-y-2">
            <h4 className={step}>{t('lookupExchange.dialog.step3')}</h4>
            <div className="grid gap-3 sm:grid-cols-2">
              <div className="min-w-0 space-y-1">
                <label htmlFor="lookup-exchange-carrier" className="text-small font-medium text-primary block">
                  {t('exchangesRefunds.requests.drawer.manual.carrierLabel')}
                </label>
                <input id="lookup-exchange-carrier" className="input w-full" maxLength={80} autoComplete="off"
                  placeholder={order.carrierName ?? ''} value={carrier} onChange={e => setCarrier(e.target.value)} />
              </div>
              <div className="min-w-0 space-y-1">
                <label htmlFor="lookup-exchange-outbound" className="text-small font-medium text-primary block">
                  {t('lookupExchange.dialog.outbound')}
                </label>
                <input id="lookup-exchange-outbound" className="input w-full font-mono" dir="ltr" maxLength={100}
                  autoComplete="off" value={outbound} data-testid="lookup-exchange-outbound"
                  onChange={e => { setOutbound(e.target.value); setError(null) }} />
              </div>
              <div className="min-w-0 space-y-1 sm:col-span-2">
                <label htmlFor="lookup-exchange-return" className="text-small font-medium text-primary block">
                  {t('exchangesRefunds.requests.drawer.manual.returnLabel')}
                </label>
                <input id="lookup-exchange-return" className="input w-full font-mono" dir="ltr" maxLength={100}
                  autoComplete="off" value={back} onChange={e => { setBack(e.target.value); setError(null) }} />
              </div>
            </div>
            <p className="text-small text-muted" data-testid="lookup-exchange-summary">
              {t(outbound.trim() ? 'lookupExchange.dialog.summaryNow' : 'lookupExchange.dialog.summaryLater')}
            </p>
          </section>
        )}

        {error && <p className="text-small text-critical" role="alert" data-testid="lookup-exchange-error">{error}</p>}

        <div className="flex gap-2 justify-end pt-1">
          <button type="button" className="btn-outline text-small" onClick={onClose}>{t('common.cancel')}</button>
          <button type="button" className="btn-brand text-small" data-testid="lookup-exchange-submit"
            disabled={busy || !order || !returning} onClick={submit}>
            {busy ? <Spinner size={14} /> : t('lookupExchange.dialog.submit')}
          </button>
        </div>
      </div>
    </Modal>
  )
}

function OrderLine({ o }: { o: LookupExchangeOrder }) {
  const { t, i18n } = useTranslation()
  const date = o.deliveredAt
    ? new Date(o.deliveredAt).toLocaleDateString(i18n.language === 'ar' ? 'ar-EG' : 'en-GB', { day: 'numeric', month: 'short' })
    : null
  return (
    <span className="min-w-0 block">
      <span className="text-small font-medium text-primary block">
        <bdi className="font-mono" dir="ltr">{o.number}</bdi>
        {o.customerName && <>{' · '}<bdi>{o.customerName}</bdi></>}
      </span>
      <span className="text-caption text-muted block">
        {[date ? t('lookupExchange.dialog.delivered', { date }) : null,
          t('lookupExchange.dialog.items', { count: o.itemCount }),
          o.carrierName].filter(Boolean).join(' · ')}
      </span>
    </span>
  )
}
