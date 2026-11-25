import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { Loader2, Undo2, WifiOff, X } from 'lucide-react'
import {
  BazarError, changeBazarReceiptPayment, completeCartOnce, currentUserId, managesBazar, previewBazarCart,
  undoBazarReceipt, SELLER_UNDO_WINDOW_MS,
  type BazarCartLine, type BazarCartPreview, type BazarReceipt, type BazarSale, type PaymentMethod,
} from '../../../bazar/api'
import { useRetryingCommand } from '../../../bazar/useRetryingCommand'
import { useScanBurst } from '../../../bazar/useScanBurst'
import { sellTiming } from '../../../bazar/useQuickSell'
import { pieceTitle, saleTime, sellErrorText, toNumber } from '../../../bazar/format'
import { tappedVariant, type BazarCatalog } from '../../../bazar/catalog'
import { Button, Input, cn } from '../../../components/ui'
import { CustomerSheet, ErrorLine, MethodChips, SaleBadge } from './SaleSheets'
import { Sheet } from './shared'
import { amount } from '../r1/parts'
import { money } from '../r2/parts'
import { Columns, Label, StickyAction, Thumb, useSentenceMoney } from '../r4/parts'

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
    && preview.lines.every((l, i) => {
      const tapped = tappedVariant(codes[i])
      return tapped ? l.code === null && l.variant_id === tapped : l.code === codes[i]
    }) ? preview.lines : []
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
export function CartPanel({ eventId, cart, methods, defaultMethod, scanner, catalog, modeSwitch, extra, onCompleted, onChanged }: {
  eventId: string
  cart: Cart
  methods: PaymentMethod[]
  defaultMethod: PaymentMethod
  scanner: ReactNode
  /** R4: the catalog (line pictures), the Quick / Cart switch and the recent sales, placed in the panel. */
  catalog: BazarCatalog | null
  modeSwitch: ReactNode
  extra?: ReactNode
  onCompleted: (r: BazarReceipt) => void
  onChanged: (r: BazarReceipt) => void
}) {
  const { t, i18n } = useTranslation()
  const cmd = useRetryingCommand<BazarReceipt>()
  const [discount, setDiscount] = useState('')
  const sm = useSentenceMoney()
  const [method, setMethod] = useState<PaymentMethod>(defaultMethod)
  const [name, setName] = useState('')
  const [phone, setPhone] = useState('')
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
  // …and a HID scan typed into one of those fields still goes into the cart (the field is put back as it was).
  useScanBurst(summaryRef, cart.add)

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
    <div className="contents" data-testid="bazar-cart">
      <Columns left={
        <>
          {scanner}
          {cart.notice && (
            <p role="status" className="text-small text-muted" data-testid="cart-notice">
              {t(`bazar.cart.${cart.notice.key}`, { code: cart.notice.code ?? '' })}
            </p>
          )}
        </>
      } right={
        <>
          {modeSwitch}
          <section className="flex flex-col rounded-2xl border border-line bg-surface p-3.5 lg:border-0 lg:p-0" aria-label={t('bazar.cart.cart')}>
            <div className="flex items-center justify-between gap-2 pb-2">
              <span className="text-body-lg font-bold text-primary">
                {t('bazar.r4.cartTitle', { count: cart.codes.length })}
                {cart.checking && <Loader2 size={13} className="inline animate-spin ms-1.5 text-muted" aria-label={t('bazar.cart.checkingPieces')} />}
              </span>
              {cart.codes.length > 0 && (
                <button type="button" onClick={cart.clear} disabled={cmd.busy} aria-label={t('bazar.cart.clear')}
                  className="min-h-[36px] px-1 text-small font-semibold text-muted hover:text-primary">{t('bazar.r4.clear')}</button>
              )}
            </div>
            {cart.codes.length === 0 ? (
              <p className="rounded-xl border border-dashed border-line px-4 py-5 text-center text-small text-muted" data-testid="cart-empty">
                {t('bazar.cart.empty')}
              </p>
            ) : (
              <ul className="flex flex-col divide-y divide-line border-t border-line" data-testid="cart-lines">
                {cart.codes.map((code, i) => {
                  const l = cart.lines[i]
                  const refused = cart.refused?.index === i ? cart.refused.error : undefined
                  const err = l ? lineErrorText(t, i18n.language, l, refused) : null
                  const tapped = tappedVariant(code) !== null
                  const title = l?.product_title ? pieceTitle({ product_title: l.product_title, variant_title: l.variant_title ?? null })
                    : tapped ? '…' : code
                  return (
                    <li key={`${code}-${i}`} data-testid="cart-line"
                        className={cn('flex items-center gap-3 py-2.5 min-h-[60px]', err && 'bg-critical/5 -mx-2 px-2 rounded-lg')}>
                      <Thumb catalog={catalog} variantId={l?.variant_id ?? tappedVariant(code)} title={l?.product_title ?? code} size={40} />
                      <span className="flex-1 min-w-0 flex flex-col">
                        <span className={cn('font-semibold truncate', err ? 'text-critical-text' : 'text-primary')}>{title}</span>
                        {tapped
                          ? <span className="text-small text-muted whitespace-normal break-words" data-testid="cart-line-tapped">{t('bazar.pick.tappedLine')}</span>
                          : <span className="text-small text-muted font-mono" dir="ltr">{l?.piece_code ?? code}</span>}
                        {err && <span role="alert" className="text-small text-critical-text" data-testid="cart-line-error">{err}</span>}
                      </span>
                      <span className="font-bold tabular-nums shrink-0" dir="ltr">
                        {l && !l.error ? amount(l.price ?? 0) : ''}
                      </span>
                      <button type="button" onClick={() => cart.remove(i)} disabled={cmd.busy}
                        aria-label={t('bazar.cart.remove', { title })}
                        className="w-9 h-9 rounded-lg border border-line flex items-center justify-center text-muted hover:text-primary hover:bg-charcoal shrink-0">
                        <X size={16} aria-hidden />
                      </button>
                    </li>
                  )
                })}
              </ul>
            )}
            {cart.previewFailed && <p role="alert" className="text-small text-critical pt-2">{t('bazar.cart.previewFailed')}</p>}
          </section>

          <section ref={summaryRef} className="contents lg:flex lg:flex-col lg:gap-4" data-testid="cart-summary">
            <div className="rounded-xl border border-line bg-base px-3.5 py-3 flex flex-col gap-2.5">
              <div className="flex items-center justify-between text-body">
                <span className="text-muted">{t('bazar.cart.subtotal')}</span>
                <span className="tabular-nums text-primary" dir="ltr" data-testid="cart-subtotal">{amount(subtotal)}</span>
              </div>
              <label className="flex items-center justify-between gap-3">
                <span className="text-body text-muted">{t('bazar.r4.discount')}</span>
                <span className="flex items-center gap-1.5">
                  <Input value={discount} inputMode="numeric" dir="ltr" placeholder="0" disabled={cmd.busy}
                         className="w-24 h-10 text-end tabular-nums"
                         onChange={e => setDiscount(e.target.value.replace(/[^\d٠-٩]/g, '').replace(/[٠-٩]/g, d => String('٠١٢٣٤٥٦٧٨٩'.indexOf(d))))}
                         data-testid="cart-discount" aria-invalid={!discountOk} />
                  <span className="h-8 px-2.5 rounded-md bg-surface border border-line text-small font-semibold text-primary flex items-center">EGP</span>
                </span>
              </label>
              {!discountOk && (
                <span className="text-small text-critical">{t('bazar.cart.discountInvalid', { max: Math.floor(subtotal) })}</span>
              )}
              <div className="flex items-center justify-between border-t border-line pt-2.5">
                <span className="text-body-lg font-bold text-primary">{t('bazar.cart.total')}</span>
                <span className="text-[22px] leading-7 font-bold tabular-nums text-primary" dir="ltr" data-testid="cart-total">{money(total)}</span>
              </div>
            </div>
            <Label>{t('bazar.r4.paidWith')}</Label>
            <MethodChips methods={methods} value={method} busy={null} disabled={cmd.busy} onPick={setMethod} />

            {/* R4: the customer's two boxes, always shown (still optional, still sent with Complete). */}
            {/* U1b: one box per row — side by side the placeholders were cut ("Customer name (option…"). */}
            <fieldset className="grid grid-cols-1 gap-2" disabled={cmd.busy} data-testid="cart-customer">
              <legend className="sr-only">{t('bazar.cart.customer')}</legend>
              <Input value={name} maxLength={80} autoComplete="off" placeholder={t('bazar.r4.customerNameOpt')}
                     aria-label={t('bazar.customer.name')} onChange={e => setName(e.target.value)} />
              <Input value={phone} inputMode="tel" autoComplete="off" dir="ltr" placeholder={t('bazar.r4.customerPhoneOpt')}
                     aria-label={t('bazar.customer.phone')} onChange={e => setPhone(e.target.value)} />
            </fieldset>

            {cmd.state.kind === 'checking' && (
              <p role="status" className="rounded-lg bg-warning/10 border border-warning/30 px-3 py-2 text-small text-primary flex items-start gap-2" data-testid="cart-checking">
                <WifiOff size={16} className="text-warning-text shrink-0 mt-0.5" aria-hidden />{t('bazar.cart.checking')}
              </p>
            )}
            {anyError && cart.codes.length > 0 && <p className="text-small text-critical-text">{t('bazar.cart.fixLines')}</p>}
            <ErrorLine error={commandError} />
            <StickyAction>
              <button type="button" onClick={complete} disabled={!ready || cmd.busy} data-testid="cart-complete"
                className="w-full min-h-[56px] rounded-xl bg-[#16A34A] hover:bg-[#15803D] text-white text-body-lg font-bold flex items-center justify-center gap-2 disabled:opacity-50">
                {cmd.busy && <Loader2 size={18} className="animate-spin" aria-hidden />}
                {cmd.busy ? t('bazar.cart.completing')
                  : t('bazar.r4.charge', { total: sm(total), method: t(`bazar.method.${method}`) })}
              </button>
            </StickyAction>
          </section>
          {done && <ReceiptCard eventId={eventId} done={done} onChanged={r => { setDone({ ...done, receipt: r }); onChanged(r) }} />}
          {extra}
        </>
      } />
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
  const sm = useSentenceMoney()
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
    <section role="status" data-testid="cart-card-sold"
      className="rounded-xl border border-success/30 bg-success/[0.06] px-3.5 py-2.5 flex flex-col gap-1.5">
      <div className="flex items-center gap-3">
        <span className="flex-1 min-w-0 flex flex-col">
          <span className="text-[12px] font-bold tracking-[0.08em] text-success-text">{t('bazar.cart.doneTitle', { number: r.sale_number })}</span>
          <span className="text-small text-muted truncate">
            {t('bazar.r4.lastSale')} · {t('bazar.cart.pieces', { count: r.line_count })}
            {toNumber(r.discount) > 0 && <> · {t('bazar.cart.discountLine', { amount: sm(r.discount) })}</>}
          </span>
        </span>
        <span className="font-bold tabular-nums text-primary shrink-0" dir="ltr">{money(r.total)}</span>
        {left > 0 && canUndoReceipt(r) && (
          <button type="button" onClick={undo} disabled={busy} data-testid="cart-undo"
            className="min-h-[40px] px-2 rounded-lg font-semibold text-trace-blue flex items-center gap-1.5 shrink-0">
            {busy ? <Loader2 size={14} className="animate-spin" aria-hidden /> : <Undo2 size={15} aria-hidden />}
            {t('bazar.cart.undoCountdown', { seconds: left })}
          </button>
        )}
      </div>
      {error && <ErrorLine error={error} />}
    </section>
  )
}

/** A cart from the recent list: its pieces, the totals, how it was paid, the customer, undo — as the API allows. */
export function ReceiptSheet({ eventId, receipt, methods, onChanged, onClose, onScanBurst }: {
  eventId: string
  receipt: BazarReceipt
  methods: PaymentMethod[]
  onChanged: (r: BazarReceipt) => void
  onClose: () => void
  /** A HID scan typed into the customer sheet's boxes (see useScanBurst). */
  onScanBurst?: (code: string) => void
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
        onClose={() => setCustomerOpen(false)} onScanBurst={onScanBurst} />
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
              {toNumber(l.discount) > 0 ? <><s>{amount(l.price)}</s>{' '}</> : null}
              <span className="text-primary font-semibold">{amount(l.net_price ?? l.price)}</span>
            </span>
          </li>
        ))}
      </ul>
      <div className="flex flex-col gap-1 text-body">
        <div className="flex justify-between"><span className="text-muted">{t('bazar.cart.subtotal')}</span><span className="tabular-nums" dir="ltr">{money(current.subtotal)}</span></div>
        {toNumber(current.discount) > 0 && (
          <div className="flex justify-between"><span className="text-muted">{t('bazar.cart.discount')}</span><span className="tabular-nums" dir="ltr">−{money(current.discount)}</span></div>
        )}
        <div className="flex justify-between font-bold"><span>{t('bazar.cart.total')}</span><span className={cn('tabular-nums', current.status !== 'active' && 'line-through text-muted')} dir="ltr">{money(current.total)}</span></div>
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
