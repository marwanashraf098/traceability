import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { Loader2, WifiOff, X } from 'lucide-react'
import {
  BazarError, changeBazarReceiptPayment, completeCartOnce, currentUserId, managesBazar, previewBazarCart,
  undoBazarReceipt, SELLER_UNDO_WINDOW_MS,
  type BazarCartLine, type BazarCartPreview, type BazarReceipt, type BazarSale, type PaymentMethod,
} from '../../../bazar/api'
import { useRetryingCommand } from '../../../bazar/useRetryingCommand'
import { sellTiming } from '../../../bazar/useQuickSell'
import { formatMoney, pieceTitle, saleTime, sellErrorText, toNumber } from '../../../bazar/format'
import { Button, Input, cn } from '../../../components/ui'
import { CustomerSheet, ErrorLine, MethodChips, SaleBadge } from './SaleSheets'
import { Sheet } from './shared'

// Bazar POS B6c — CART MODE (mockup step 6c): scan pieces into a list — nothing is sold or reserved until Complete.
// The list is checked by POST /cart/preview (read-only) after every change; Complete sells every piece in ONE
// transaction or none (the refused piece is marked and the cart stays as it was). Complete is sent like a sale: one
// request in flight, the SAME client_request_id on every retry until the server answers.

export const MAX_CART = 50

const norm = (c: string) => c.trim().toUpperCase()

/** The cart's codes and the server's read of them. */
export function useCart(eventId: string) {
  const [codes, setCodes] = useState<string[]>([])
  const [preview, setPreview] = useState<BazarCartPreview | null>(null)
  const [checking, setChecking] = useState(false)
  const [previewFailed, setPreviewFailed] = useState(false)
  const [notice, setNotice] = useState<{ key: 'alreadyIn' | 'tooMany'; code?: string } | null>(null)
  /** Complete's refusal of one line (index → error) — kept until the cart changes. */
  const [refused, setRefused] = useState<{ index: number; error: BazarError } | null>(null)
  const seq = useRef(0)

  useEffect(() => {
    setRefused(null)
    if (codes.length === 0) { setPreview(null); setChecking(false); setPreviewFailed(false); return }
    const mine = ++seq.current
    setChecking(true)
    previewBazarCart(eventId, codes)
      .then(p => { if (mine === seq.current) { setPreview(p); setPreviewFailed(false) } })
      .catch(() => { if (mine === seq.current) setPreviewFailed(true) })
      .finally(() => { if (mine === seq.current) setChecking(false) })
  }, [eventId, codes])

  const add = useCallback((raw: string) => {
    const code = raw.trim()
    if (!code) return
    setCodes(prev => {
      if (prev.some(c => norm(c) === norm(code))) { setNotice({ key: 'alreadyIn', code }); return prev }
      if (prev.length >= MAX_CART) { setNotice({ key: 'tooMany' }); return prev }
      setNotice(null)
      return [...prev, code]
    })
  }, [])
  const remove = useCallback((index: number) => {
    setNotice(null)
    setCodes(prev => prev.filter((_, i) => i !== index))
  }, [])
  const clear = useCallback(() => { setNotice(null); setCodes([]) }, [])

  // The preview of the CURRENT codes only (a stale answer is never shown against a changed list).
  const lines: BazarCartLine[] = preview && preview.lines.length === codes.length
    && preview.lines.every((l, i) => l.code === codes[i]) ? preview.lines : []
  return { codes, lines, preview, checking, previewFailed, notice, refused, setRefused, add, remove, clear }
}

export type Cart = ReturnType<typeof useCart>

function lineErrorText(t: ReturnType<typeof useTranslation>['t'], lang: string, l: BazarCartLine, refused?: BazarError) {
  const e = refused ?? (l.error ? new BazarError(409, l.error) : null)
  if (!e) return null
  const { title, detail } = sellErrorText(t, lang, e)
  return detail ? `${title} — ${detail}` : title
}

/**
 * The cart: the lines (piece, size, bazar price, ×), subtotal, discount, method, customer, Complete. {@code scanner}
 * is the screen's own scan block (camera + the one scan input), placed above the lines; on a computer the summary
 * sits beside the list.
 */
export function CartPanel({ eventId, cart, methods, defaultMethod, scanner, onCompleted, onChanged }: {
  eventId: string
  cart: Cart
  methods: PaymentMethod[]
  defaultMethod: PaymentMethod
  scanner: ReactNode
  onCompleted: (r: BazarReceipt) => void
  onChanged: (r: BazarReceipt) => void
}) {
  const { t, i18n } = useTranslation()
  const cmd = useRetryingCommand<BazarReceipt>()
  const [discount, setDiscount] = useState('')
  const [method, setMethod] = useState<PaymentMethod>(defaultMethod)
  const [name, setName] = useState('')
  const [phone, setPhone] = useState('')
  const [customerOpen, setCustomerOpen] = useState(false)
  const [done, setDone] = useState<{ receipt: BazarReceipt; shownAt: number } | null>(null)
  const summaryRef = useRef<HTMLElement>(null)

  // The Sell screen's scanner re-focuses its scan input on every click (SAFETY-CRITICAL, useScanner — not touched).
  // The cart's own fields (discount, customer) must keep the focus they were clicked into: the same click-refocus
  // pattern, deferred so it always runs after the scanner's, whichever listener was attached first.
  useEffect(() => {
    const keep = (e: MouseEvent) => {
      const el = e.target as HTMLElement | null
      if (!(el instanceof HTMLInputElement) || !summaryRef.current?.contains(el)) return
      window.setTimeout(() => { if (el.isConnected && !el.disabled) el.focus() }, 0)
    }
    document.addEventListener('click', keep)
    return () => document.removeEventListener('click', keep)
  }, [])

  const subtotal = cart.lines.reduce((s, l) => s + (l.error ? 0 : toNumber(l.price)), 0)
  const off = discount.trim() === '' ? 0 : Number(discount)
  const discountOk = Number.isInteger(off) && off >= 0 && off <= subtotal
  const total = discountOk ? subtotal - off : subtotal
  const anyError = cart.lines.some(l => l.error) || cart.refused !== null
  const ready = cart.codes.length > 0 && cart.lines.length === cart.codes.length && !cart.checking && !anyError
    && discountOk && !cart.previewFailed

  // A finished command: the receipt card, an empty cart for the next customer.
  useEffect(() => {
    if (cmd.state.kind === 'done') {
      const r = cmd.state.result
      setDone({ receipt: r, shownAt: Date.now() })
      onCompleted(r)
      cart.clear()
      setDiscount('')
      setName('')
      setPhone('')
      setCustomerOpen(false)
      setMethod(defaultMethod)
      cmd.reset()
    } else if (cmd.state.kind === 'error') {
      const idx = cmd.state.error.details?.line_index
      if (typeof idx === 'number') cart.setRefused({ index: idx, error: cmd.state.error })
    }
  }, [cmd.state]) // eslint-disable-line react-hooks/exhaustive-deps

  // A new scan replaces the last receipt's card.
  useEffect(() => { if (cart.codes.length > 0) setDone(null) }, [cart.codes.length])

  function complete() {
    if (!ready || cmd.busy) return
    const req = {
      codes: cart.codes, discount: off, method,
      customer: name.trim() || phone.trim() ? { name: name.trim() || null, phone: phone.trim() || null } : null,
    }
    cmd.run((requestId, timeoutMs) => completeCartOnce(eventId, req, requestId, timeoutMs))
  }

  const commandError = cmd.state.kind === 'error' && typeof cmd.state.error.details?.line_index !== 'number'
    ? cmd.state.error : null

  return (
    <div className="flex flex-col gap-3 lg:grid lg:grid-cols-[minmax(0,1fr)_360px] lg:items-start lg:gap-5" data-testid="bazar-cart">
      <div className="flex flex-col gap-3 min-w-0">
        {scanner}
        {cart.notice && (
          <p role="status" className="text-small text-muted" data-testid="cart-notice">
            {t(`bazar.cart.${cart.notice.key}`, { code: cart.notice.code ?? '' })}
          </p>
        )}
        {done && <ReceiptCard eventId={eventId} done={done} onChanged={r => { setDone({ ...done, receipt: r }); onChanged(r) }} />}

        <section className="flex flex-col gap-1.5" aria-label={t('bazar.cart.cart')}>
          <div className="flex items-center justify-between gap-2">
            <span className="text-small font-medium text-muted">
              {t('bazar.cart.pieces', { count: cart.codes.length })}
              {cart.checking && <Loader2 size={12} className="inline animate-spin ms-1.5" aria-label={t('bazar.cart.checkingPieces')} />}
            </span>
            {cart.codes.length > 0 && (
              <button type="button" onClick={cart.clear} disabled={cmd.busy}
                className="min-h-[36px] px-2 text-small font-semibold text-muted hover:text-primary">{t('bazar.cart.clear')}</button>
            )}
          </div>
          {cart.codes.length === 0 ? (
            <p className="rounded-2xl border border-dashed border-line px-4 py-5 text-center text-small text-muted" data-testid="cart-empty">
              {t('bazar.cart.empty')}
            </p>
          ) : (
            <ul className="flex flex-col gap-1.5" data-testid="cart-lines">
              {cart.codes.map((code, i) => {
                const l = cart.lines[i]
                const refused = cart.refused?.index === i ? cart.refused.error : undefined
                const err = l ? lineErrorText(t, i18n.language, l, refused) : null
                const title = l?.product_title ? pieceTitle({ product_title: l.product_title, variant_title: l.variant_title ?? null }) : code
                return (
                  <li key={`${code}-${i}`} data-testid="cart-line"
                      className={cn('flex items-start gap-2.5 px-3 py-2.5 rounded-xl border bg-surface min-h-[52px]',
                        err ? 'border-critical/40 bg-critical/5' : 'border-line')}>
                    <span className="flex-1 min-w-0 flex flex-col">
                      <span className={cn('font-medium truncate', err ? 'text-critical-text' : 'text-primary')}>{title}</span>
                      <span className="text-small text-muted font-mono" dir="ltr">{l?.piece_code ?? code}</span>
                      {err && <span role="alert" className="text-small text-critical-text" data-testid="cart-line-error">{err}</span>}
                    </span>
                    <span className="font-semibold tabular-nums shrink-0 pt-0.5">
                      {l && !l.error ? formatMoney(l.price ?? 0).replace(/^EGP /, '') : ''}
                    </span>
                    <button type="button" onClick={() => cart.remove(i)} disabled={cmd.busy}
                      aria-label={t('bazar.cart.remove', { title })}
                      className="w-10 h-10 -me-1.5 -mt-1.5 rounded-full flex items-center justify-center text-muted hover:text-primary hover:bg-charcoal shrink-0">
                      <X size={18} aria-hidden />
                    </button>
                  </li>
                )
              })}
            </ul>
          )}
          {cart.previewFailed && <p role="alert" className="text-small text-critical">{t('bazar.cart.previewFailed')}</p>}
        </section>
      </div>

      <section ref={summaryRef} className="card p-4 flex flex-col gap-3 lg:sticky lg:top-4" data-testid="cart-summary">
        <div className="flex items-center justify-between text-body">
          <span className="text-muted">{t('bazar.cart.subtotal')}</span>
          <span className="tabular-nums font-semibold" dir="ltr" data-testid="cart-subtotal">{formatMoney(subtotal)}</span>
        </div>
        <label className="flex flex-col gap-1">
          <span className="text-small font-medium text-muted">{t('bazar.cart.discount')}</span>
          <Input value={discount} inputMode="numeric" dir="ltr" placeholder="0" disabled={cmd.busy}
                 onChange={e => setDiscount(e.target.value.replace(/[^\d٠-٩]/g, '').replace(/[٠-٩]/g, d => String('٠١٢٣٤٥٦٧٨٩'.indexOf(d))))}
                 data-testid="cart-discount" aria-invalid={!discountOk} />
          <span className={cn('text-small', discountOk ? 'text-muted' : 'text-critical')}>
            {discountOk ? t('bazar.cart.discountHint') : t('bazar.cart.discountInvalid', { max: Math.floor(subtotal) })}
          </span>
        </label>
        <div className="flex items-center justify-between">
          <span className="text-body font-semibold text-primary">{t('bazar.cart.total')}</span>
          <span className="text-h3 font-bold tabular-nums text-primary" dir="ltr" data-testid="cart-total">{formatMoney(total)}</span>
        </div>
        <span className="text-small font-medium text-muted">{t('bazar.sell.paidBy')}</span>
        <MethodChips methods={methods} value={method} busy={null} disabled={cmd.busy} onPick={setMethod} />

        {customerOpen || name || phone ? (
          <fieldset className="flex flex-col gap-2" disabled={cmd.busy}>
            <legend className="text-small font-medium text-muted mb-1">{t('bazar.cart.customer')}</legend>
            <Input value={name} maxLength={80} autoComplete="off" placeholder={t('bazar.customer.namePlaceholder')}
                   aria-label={t('bazar.customer.name')} onChange={e => setName(e.target.value)} />
            <Input value={phone} inputMode="tel" autoComplete="off" dir="ltr" placeholder="01012345678"
                   aria-label={t('bazar.customer.phone')} onChange={e => setPhone(e.target.value)} />
          </fieldset>
        ) : (
          <button type="button" onClick={() => setCustomerOpen(true)}
            className="min-h-[44px] self-start font-semibold text-trace-blue underline underline-offset-2">
            {t('bazar.customer.addShort')}
          </button>
        )}

        {cmd.state.kind === 'checking' && (
          <p role="status" className="rounded-lg bg-warning/10 border border-warning/30 px-3 py-2 text-small text-primary flex items-start gap-2" data-testid="cart-checking">
            <WifiOff size={16} className="text-warning-text shrink-0 mt-0.5" aria-hidden />{t('bazar.cart.checking')}
          </p>
        )}
        {anyError && cart.codes.length > 0 && <p className="text-small text-critical-text">{t('bazar.cart.fixLines')}</p>}
        <ErrorLine error={commandError} />
        <button type="button" onClick={complete} disabled={!ready || cmd.busy} data-testid="cart-complete"
          className="h-[52px] rounded-xl bg-[#16A34A] text-white font-semibold flex items-center justify-center gap-2 disabled:opacity-50">
          {cmd.busy && <Loader2 size={18} className="animate-spin" aria-hidden />}
          {cmd.busy ? t('bazar.cart.completing') : t('bazar.cart.complete', { total: formatMoney(total) })}
        </button>
      </section>
    </div>
  )
}

export function isMyReceipt(r: BazarReceipt): boolean {
  const me = currentUserId()
  return !!me && r.seller_id === me
}

export function canEditReceipt(r: BazarReceipt): boolean {
  return r.status === 'active' && (managesBazar() || isMyReceipt(r))
}

/** Undo the whole cart: none of its pieces returned / exchanged; owner / manager, or its seller within 2 minutes. */
export function canUndoReceipt(r: BazarReceipt, now = Date.now()): boolean {
  if (r.status !== 'active' || r.lines.some(l => l.status !== 'active')) return false
  if (managesBazar()) return true
  return isMyReceipt(r) && now - new Date(r.sold_at).getTime() < SELLER_UNDO_WINDOW_MS
}

/** The green card after Complete: number, pieces, total, and Undo while the seller's window is open. */
function ReceiptCard({ eventId, done, onChanged }: {
  eventId: string
  done: { receipt: BazarReceipt; shownAt: number }
  onChanged: (r: BazarReceipt) => void
}) {
  const { t } = useTranslation()
  const r = done.receipt
  const [now, setNow] = useState(Date.now())
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<BazarError | null>(null)
  useEffect(() => {
    const id = window.setInterval(() => setNow(Date.now()), 500)
    const stop = window.setTimeout(() => window.clearInterval(id), sellTiming.undoCardMs + 500)
    return () => { window.clearInterval(id); window.clearTimeout(stop) }
  }, [done.shownAt])

  if (r.status === 'undone') {
    return (
      <section role="status" className="rounded-2xl bg-charcoal border border-line px-4 py-4 flex flex-col gap-1" data-testid="cart-card-undone">
        <span className="text-small font-bold tracking-wider text-muted">{t('bazar.cart.undoneTitle', { number: r.sale_number })}</span>
        <span className="text-small text-muted">{t('bazar.cart.undoneBody')}</span>
      </section>
    )
  }
  const left = Math.max(0, Math.ceil((done.shownAt + sellTiming.undoCardMs - now) / 1000))
  async function undo() {
    setBusy(true)
    setError(null)
    try { onChanged(await undoBazarReceipt(eventId, r.id)) }
    catch (e) { setError(e instanceof BazarError ? e : new BazarError(0, { code: 'NETWORK' })) }
    finally { setBusy(false) }
  }
  return (
    <section role="status" className="rounded-2xl bg-[#16A34A] text-white px-4 py-3.5 flex flex-col gap-2" data-testid="cart-card-sold">
      <div className="flex items-start justify-between gap-3">
        <span className="flex flex-col gap-0.5 min-w-0">
          <span className="text-small font-bold tracking-wider">{t('bazar.cart.doneTitle', { number: r.sale_number })}</span>
          <span className="text-body font-semibold">
            {t('bazar.cart.pieces', { count: r.line_count })}
            {toNumber(r.discount) > 0 && <> · {t('bazar.cart.discountLine', { amount: formatMoney(r.discount) })}</>}
          </span>
        </span>
        <span className="text-h3 font-bold tabular-nums shrink-0" dir="ltr">{formatMoney(r.total).replace(/^EGP /, '')}</span>
      </div>
      {left > 0 && canUndoReceipt(r) && (
        <button type="button" onClick={undo} disabled={busy} data-testid="cart-undo"
          className="self-end min-h-[44px] px-3 rounded-lg border border-white/60 font-semibold flex items-center gap-1.5">
          {busy && <Loader2 size={14} className="animate-spin" aria-hidden />}
          {t('bazar.cart.undoCountdown', { seconds: left })}
        </button>
      )}
      {error && <div className="rounded-md bg-white px-2 py-1"><ErrorLine error={error} /></div>}
    </section>
  )
}

/** A cart from the recent list: its pieces, the totals, how it was paid, the customer, undo — as the API allows. */
export function ReceiptSheet({ eventId, receipt, methods, onChanged, onClose }: {
  eventId: string
  receipt: BazarReceipt
  methods: PaymentMethod[]
  onChanged: (r: BazarReceipt) => void
  onClose: () => void
}) {
  const { t } = useTranslation()
  const [current, setCurrent] = useState(receipt)
  const [busy, setBusy] = useState<PaymentMethod | null>(null)
  const [error, setError] = useState<BazarError | null>(null)
  const [undoing, setUndoing] = useState(false)
  const [customerOpen, setCustomerOpen] = useState(false)
  const changed = (r: BazarReceipt) => { setCurrent(r); onChanged(r) }
  const editable = canEditReceipt(current)

  async function act(fn: () => Promise<BazarReceipt>) {
    setError(null)
    try { changed(await fn()) }
    catch (e) { setError(e instanceof BazarError ? e : new BazarError(0, { code: 'NETWORK' })) }
  }

  if (customerOpen && current.lines[0]) {
    return (
      <CustomerSheet eventId={eventId} sale={{ ...current.lines[0], customer_name: current.customer_name, customer_phone: current.customer_phone }}
        onSaved={(s: BazarSale) => changed({ ...current, customer_name: s.customer_name, customer_phone: s.customer_phone,
          lines: current.lines.map(l => ({ ...l, customer_name: s.customer_name, customer_phone: s.customer_phone })) })}
        onClose={() => setCustomerOpen(false)} />
    )
  }

  return (
    <Sheet title={current.sale_number} onClose={onClose} testId="bazar-receipt-sheet">
      <span className="text-small text-muted">
        {saleTime(current.sold_at)} · {isMyReceipt(current) ? t('bazar.sell.byYou') : current.seller_name}
        {' · '}{t('bazar.cart.pieces', { count: current.line_count })}
      </span>
      {current.status === 'undone' && <p className="text-small font-semibold text-muted">{t('bazar.sell.undoneLabel')}</p>}
      <ul className="flex flex-col divide-y divide-line border border-line rounded-xl" data-testid="receipt-lines">
        {current.lines.map(l => (
          <li key={l.id} className="flex items-center gap-2 px-3 py-2">
            <span className={cn('flex-1 min-w-0 truncate', l.status !== 'active' && 'line-through text-muted')}>{pieceTitle(l)}</span>
            {l.status !== 'active' && <SaleBadge sale={l} />}
            <span className="tabular-nums text-small text-muted shrink-0" dir="ltr">
              {toNumber(l.discount) > 0 ? <><s>{formatMoney(l.price).replace(/^EGP /, '')}</s>{' '}</> : null}
              <span className="text-primary font-semibold">{formatMoney(l.net_price ?? l.price).replace(/^EGP /, '')}</span>
            </span>
          </li>
        ))}
      </ul>
      <div className="flex flex-col gap-1 text-body">
        <div className="flex justify-between"><span className="text-muted">{t('bazar.cart.subtotal')}</span><span className="tabular-nums" dir="ltr">{formatMoney(current.subtotal)}</span></div>
        {toNumber(current.discount) > 0 && (
          <div className="flex justify-between"><span className="text-muted">{t('bazar.cart.discount')}</span><span className="tabular-nums" dir="ltr">−{formatMoney(current.discount)}</span></div>
        )}
        <div className="flex justify-between font-bold"><span>{t('bazar.cart.total')}</span><span className={cn('tabular-nums', current.status !== 'active' && 'line-through text-muted')} dir="ltr">{formatMoney(current.total)}</span></div>
      </div>
      {current.status === 'active' && (
        <>
          <span className="text-small font-medium text-muted">{t('bazar.sell.paidBy')}</span>
          <MethodChips methods={methods} value={current.method} busy={busy} disabled={!editable}
            onPick={async m => { setBusy(m); await act(() => changeBazarReceiptPayment(eventId, current.id, m)); setBusy(null) }} />
          <div className="flex items-center justify-between gap-2 py-1">
            <span className="text-body text-primary min-w-0 truncate">
              {current.customer_name || current.customer_phone
                ? [current.customer_name, current.customer_phone].filter(Boolean).join(' · ')
                : <span className="text-muted">{t('bazar.customer.none')}</span>}
            </span>
            {editable && (
              <Button variant="secondary" size="sm" onClick={() => setCustomerOpen(true)}>
                {current.customer_name || current.customer_phone ? t('bazar.customer.edit') : t('bazar.customer.add')}
              </Button>
            )}
          </div>
          {canUndoReceipt(current) && (
            <Button variant="secondary" className="w-full" loading={undoing}
              onClick={async () => { setUndoing(true); await act(() => undoBazarReceipt(eventId, current.id)); setUndoing(false) }}>
              {t('bazar.cart.undoCart')}
            </Button>
          )}
          {!editable && <p className="text-small text-muted">{t('bazar.cart.notYours')}</p>}
        </>
      )}
      <ErrorLine error={error} />
    </Sheet>
  )
}
