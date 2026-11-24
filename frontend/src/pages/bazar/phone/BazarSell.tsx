import { useCallback, useEffect, useMemo, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { FileText, Loader2, ScanLine, Search, Undo2, WifiOff } from 'lucide-react'
import { useScanner, MAX_QUEUED_SCANS } from '../../../hooks/useScanner'
import CameraReader, { type CameraError } from '../../scanpair/CameraReader'
import {
  BazarError, defaultMethod, isReceipt, orderedMethods, undoBazarSale,
  type BazarReceipt, type BazarSale, type BazarSaleRow,
} from '../../../bazar/api'
import { sellTiming, useQuickSell, type SellCard } from '../../../bazar/useQuickSell'
import { pieceTitle, saleTime, sellErrorText } from '../../../bazar/format'
import { cn } from '../../../components/ui'
import { useCameraGate } from '../../../bazar/cameraGate'
import { useCameraShown } from '../../../bazar/cameraPref'
import type { BazarLive } from '../../../capabilities'
import { NotLiveScreen, useBazarLive, useBazarToday, useTodaySales } from './shared'
import {
  CustomerSheet, ErrorLine, MethodChips, SaleBadge, SaleSheet, canEditSale, canUndoSale, usePaymentChange,
} from './SaleSheets'
import { CartPanel, ReceiptSheet, useCart } from './CartMode'
import {
  findVariant, getPriceSheet, productNumber, sizeName, tapRef, tappedVariant, useBazarCatalog,
  type BazarCatalogProduct, type BazarCatalogSize,
} from '../../../bazar/catalog'
import { PickSheet } from './PickSheet'
import { ProductGrid } from './ProductGrid'
import { amount } from '../r1/parts'
import {
  CameraChips, CameraGateOverlay, CameraHiddenBar, Columns, Label, ModeTabs, R4Header, R4Screen, ReadyPill, SCAN_FIELD, StickyAction, Thumb, TodayLine,
  TodayPill, useSellerName,
} from '../r4/parts'

type Mode = 'quick' | 'cart'

/** The seller's last mode on this phone, per event (a convenience: storage may be unavailable). */
function savedMode(eventId: string): Mode {
  try { return sessionStorage.getItem(`bazar-sell-mode:${eventId}`) === 'cart' ? 'cart' : 'quick' } catch { return 'quick' }
}
function saveMode(eventId: string, m: Mode) {
  try { sessionStorage.setItem(`bazar-sell-mode:${eventId}`, m) } catch { /* private mode */ }
}

/**
 * Bazar POS B6 — QUICK MODE (mockup step 6): every scan is a sale. B6c — CART MODE (step 6c): scan several pieces,
 * one discount, one method, one customer, Complete. Quick is the default; the switch sits under the tabs.
 * Full-screen, phone-first; on a computer the cart's summary sits beside its list.
 */
export default function BazarSell() {
  const { id } = useParams<{ id: string }>()
  const { loading, live } = useBazarLive(id)
  if (!live) return <NotLiveScreen loading={loading} />
  return <Sell live={live} />
}

function Sell({ live }: { live: BazarLive }) {
  const { t, i18n } = useTranslation()
  const eventId = live.eventId
  const methods = useMemo(() => orderedMethods(live.paymentMethods), [live.paymentMethods])
  const method = defaultMethod(live.paymentMethods)
  const { sales, setSales, reload } = useTodaySales(eventId, live.businessDay)
  const { today, reload: reloadToday } = useBazarToday(eventId)

  const upsert = useCallback((s: BazarSaleRow) => {
    setSales(prev => {
      const list = prev ?? []
      return list.some(x => x.id === s.id) ? list.map(x => (x.id === s.id ? s : x)) : [s, ...list]
    })
  }, [setSales])
  const [mode, setModeState] = useState<Mode>(() => savedMode(eventId))
  const setMode = (m: Mode) => { setModeState(m); saveMode(eventId, m) }
  const cart = useCart(eventId)

  // B7a: what's for sale without a barcode (numbers, sizes, what's left) — reloaded after every sale.
  const { catalog, reload: reloadCatalog } = useBazarCatalog(eventId)
  const quick = useQuickSell({ eventId, method, onSold: s => { upsert(s); reload(); reloadToday(); reloadCatalog() } })
  const [sheet, setSheet] = useState<
    { kind: 'sale' | 'customer'; sale: BazarSale } | { kind: 'receipt'; receipt: BazarReceipt }
    | { kind: 'pick'; number: number | null } | null>(null)
  const [typing, setTyping] = useState(false)
  const [cameraShown, setCameraShown] = useCameraShown()
  const [cameraError, setCameraError] = useState<CameraError | null>(null)
  const [dropped, setDropped] = useState<string | null>(null)

  const changed = useCallback((s: BazarSale) => { upsert(s); quick.updateSale(s); reloadToday(); reloadCatalog() },
    [upsert, quick, reloadToday, reloadCatalog])
  const receiptChanged = useCallback((r: BazarReceipt) => { upsert(r); reloadToday() }, [upsert, reloadToday])

  const scanner = useScanner({
    onScan: quick.sell,
    focusPaused: sheet !== null || typing,
  })

  /** Quick mode: one code (or a B7a tapped-size ref) → one sale, through the scanner's single-flight queue. True = sent. */
  const sendQuick = useCallback((code: string): boolean => {
    if (scanner.pending >= MAX_QUEUED_SCANS) { scanner.handleScan(code); return false }   // refused loudly by the scanner
    if (!quick.enqueue(code)) {
      if (scanner.inputRef.current) scanner.inputRef.current.value = ''
      setDropped(code)
      return false
    }
    setDropped(null)
    scanner.handleScan(code)
    return true
  }, [scanner, quick])

  /** Every way a code arrives (camera, HID scanner, typed) goes through here. 'await' = a quick sale was started. */
  const submit = useCallback((raw: string): 'await' | void => {
    const code = raw.trim()
    if (!code) return
    // B7a: "12" / "#12" — the price sheet's product number (never a piece code: those have 6+ digits). It opens
    // that product's sizes; it never sells.
    const n = productNumber(code)
    if (n !== null) {
      if (scanner.inputRef.current) scanner.inputRef.current.value = ''
      setSheet({ kind: 'pick', number: n })
      return
    }
    if (mode === 'cart') {
      // Cart: the code joins the list — nothing is sold until Complete.
      cart.add(code)
      if (scanner.inputRef.current) scanner.inputRef.current.value = ''
      return
    }
    return sendQuick(code) ? 'await' : undefined
  }, [scanner, mode, cart, sendQuick])

  /** B7a: a tapped size — the same path as a scan (quick: a sale; cart: a line); the server picks the piece. */
  const pick = useCallback((_p: BazarCatalogProduct, size: BazarCatalogSize) => {
    setSheet(null)
    const ref = tapRef(size.variant_id)
    if (mode === 'cart') cart.add(ref)
    else sendQuick(ref)
  }, [mode, cart, sendQuick])

  /** What the sending / error card names: the scanned code, or a tapped size's product + size. */
  const labelFor = useCallback((code: string) => {
    const v = tappedVariant(code)
    if (!v) return code
    const f = findVariant(catalog, v)
    return f ? `${f.product.title} · ${sizeName(f.product, f.size)}` : ''
  }, [catalog])

  const [sheetBusy, setSheetBusy] = useState(false)
  const priceSheet = useCallback(async () => {
    setSheetBusy(true)
    try { await getPriceSheet(eventId, i18n.language, false) } catch { /* the button stays; try again */ }
    finally { setSheetBusy(false) }
  }, [eventId, i18n.language])

  // C1: one aim of the camera = one code. Quick mode: no camera code while a sale is in flight (from any source), and
  // after a camera sale the camera pauses until its SOLD / error card is shown and the view is clear (or Scan next).
  const gate = useCameraGate({
    mode: 'pause',
    onCode: submit,
    busy: mode === 'quick' && (quick.busy || scanner.scanning || scanner.pending > 0),
  })
  const { resultShown } = gate
  useEffect(() => {
    if (quick.card.kind === 'sold' || quick.card.kind === 'error' || quick.card.kind === 'undone') resultShown()
  }, [quick.card, resultShown])
  const onCameraError = useCallback((e: CameraError) => setCameraError(e), [])

  const sellerName = useSellerName()
  const pickButton = (
    <button type="button" onClick={() => setSheet({ kind: 'pick', number: null })} data-testid="open-pick"
      className="lg:hidden w-full h-12 rounded-xl border border-line bg-surface flex items-center justify-center gap-2 text-body font-semibold text-primary">
      <Search size={18} aria-hidden />{t('bazar.pick.open')}
    </button>
  )
  const recent = (sales ?? []).slice(0, 20)
  // R4: "Scanner ready" is display only — no sheet open and not typing (no focus listener).
  const scannerReady = sheet === null && !typing
  const cameraLive = cameraShown && !cameraError
  const onType = () => {
    const next = !typing
    setTyping(next)
    if (next) setTimeout(() => scanner.inputRef.current?.focus(), 0)
  }
  const scanBlock = (
    <>
        {/* The camera band (phone: dark, under the top bar) and the one scan input. */}
        <div className="-mx-4 px-4 pb-4 bg-sidebar flex flex-col gap-2.5 lg:mx-0 lg:p-0 lg:bg-transparent lg:gap-3">
        {/* The phone's camera is its scanner (no pairing). C1: hidden on a computer unless "Use camera". */}
        {cameraShown ? (
        <div className="relative h-44 lg:h-24 lg:order-2 rounded-2xl bg-[#1A1F27] overflow-hidden flex items-center" data-testid="bazar-camera-box">
          {cameraLive
            ? <div className="w-full"><CameraReader active onRead={gate.onRead} onFrame={gate.onFrame} onError={onCameraError} /></div>
            : (
              <p className="w-full px-6 text-center text-small text-[#D1D5DB]">
                {cameraError ? t(`bazar.sell.camera.${cameraError}`) : t('bazar.sell.camera.off')}
              </p>
            )}
          <span className="absolute bottom-2 inset-x-0 text-center text-small text-[#D1D5DB] pointer-events-none">
            {mode === 'cart' ? t('bazar.cart.scanHint') : t('bazar.sell.scanHint')}
          </span>
          {cameraLive && <CameraGateOverlay notice={gate.notice} waitingForClear={gate.waitingForClear} onScanNext={gate.scanNext} />}
          <CameraChips state={cameraLive ? t('bazar.r4.cameraReady') : t('bazar.r4.cameraOff')} typing={typing}
            onHide={() => { setCameraError(null); setCameraShown(false) }} onType={onType} />
        </div>
        ) : (
          <CameraHiddenBar onUse={() => { setCameraError(null); setCameraShown(true) }} typing={typing} onType={onType} />
        )}

        {/* HID scanner / typed code: the one scan input (its props as before). It opens no keyboard until "Type". */}
        <div className={cn(SCAN_FIELD, scannerReady && 'lg:[&_input::placeholder]:text-transparent')}>
          <ScanLine size={20} aria-hidden className="absolute start-4 top-1/2 -translate-y-1/2 text-trace-blue pointer-events-none z-[1]" />
          <input
            ref={scanner.inputRef}
            type="text"
            inputMode={typing ? 'text' : 'none'}
            autoComplete="off"
            autoCapitalize="characters"
            spellCheck={false}
            dir="auto"
            aria-label={t('bazar.sell.codeLabel')}
            placeholder={typing ? t('bazar.sell.typePlaceholder') : t('bazar.sell.hidPlaceholder')}
            className={cn('input w-full font-mono placeholder:font-sans', typing ? 'h-11 text-body-lg' : 'h-10 text-small')}
            onKeyDown={e => { if (e.key === 'Enter') submit((e.target as HTMLInputElement).value) }}
            data-testid="bazar-scan-input"
          />
          {scannerReady && (
            <span className="hidden lg:flex absolute end-3 top-1/2 -translate-y-1/2 pointer-events-none">
              <ReadyPill>{t('bazar.r4.scannerReady')}</ReadyPill>
            </span>
          )}
        </div>
        </div>
        {/* B7a: no barcode — pick a product (phone: a sheet, at the bottom in Quick; computer: the grid below). */}
        {mode === 'cart' && pickButton}
        <div className="hidden lg:block"><ProductGrid catalog={catalog} onPick={pick} /></div>
        {dropped && <p className="text-small text-muted" role="status">{t('bazar.sell.alreadySending', { code: labelFor(dropped) })}</p>}
        {scanner.queueFull && <p className="text-small text-critical" role="alert">{t('bazar.sell.queueFull')}</p>}
        {scanner.pending > 0 && <p className="text-small text-muted">{t('bazar.sell.waiting', { count: scanner.pending })}</p>}
    </>
  )
  const recentList = (
      <section className="flex flex-col gap-1 min-h-0" aria-label={t('bazar.r4.earlier')}>
        <Label>{t('bazar.r4.earlier')}</Label>
        {sales && recent.length === 0 && <p className="text-small text-muted py-2">{t('bazar.sell.noSales')}</p>}
        <ul className="flex flex-col divide-y divide-line" data-testid="bazar-recent">
          {recent.map(s => isReceipt(s) ? (
            <li key={s.id}>
              <button type="button" onClick={() => setSheet({ kind: 'receipt', receipt: s })} data-testid="recent-receipt"
                className="w-full flex items-center gap-3 py-2.5 text-start min-h-[56px]">
                <span aria-hidden className="w-10 h-10 rounded-lg bg-charcoal text-muted font-bold flex items-center justify-center shrink-0 tabular-nums">
                  {s.line_count}
                </span>
                <span className="flex-1 min-w-0 flex flex-col">
                  <span className={cn('font-semibold text-primary truncate', s.status !== 'active' && 'line-through text-muted')}>
                    {t('bazar.cart.row', { count: s.line_count })}
                  </span>
                  <span className="text-small text-muted truncate">
                    <span dir="ltr">{saleTime(s.sold_at)}</span>
                    {' · '}{s.status === 'undone' ? t('bazar.sell.undoneShort') : s.method ? t(`bazar.method.${s.method}`) : ''}
                  </span>
                </span>
                <span className={cn('font-bold tabular-nums shrink-0', s.status !== 'active' && 'line-through text-muted')} dir="ltr">
                  {amount(s.total)}
                </span>
                <span className="text-small font-semibold text-muted shrink-0">{t('bazar.r4.edit')}</span>
              </button>
            </li>
          ) : (
            <li key={s.id}>
              <button type="button" onClick={() => setSheet({ kind: 'sale', sale: s })}
                className="w-full flex items-center gap-3 py-2.5 text-start min-h-[56px]">
                <Thumb catalog={catalog} variantId={s.variant_id} title={s.product_title} size={40} />
                <span className="flex-1 min-w-0 flex flex-col">
                  <span className={cn('font-semibold text-primary truncate', s.status !== 'active' && 'line-through text-muted')}>
                    {pieceTitle(s)}
                  </span>
                  <span className="text-small text-muted truncate flex items-center gap-1.5">
                    <span dir="ltr">{saleTime(s.sold_at)}</span>
                    {s.status !== 'active' || s.exchange_of_sale_number
                      ? <SaleBadge sale={s} />
                      : s.method ? <span>· {t(`bazar.method.${s.method}`)}</span> : null}
                  </span>
                </span>
                <span className={cn('font-bold tabular-nums shrink-0', s.status !== 'active' && 'line-through text-muted')} dir="ltr">
                  {amount(s.price)}
                </span>
                <span className="text-small font-semibold text-muted shrink-0">{t('bazar.r4.edit')}</span>
              </button>
            </li>
          ))}
        </ul>
      </section>
  )
  const homePath = `/bazar/${encodeURIComponent(eventId)}/home`
  const salesCount = today ? t('bazar.sell.salesCount', { count: today.sale_count }) : ''
  const modeTabs = <ModeTabs mode={mode} onChange={setMode} />

  return (
    <R4Screen testId="bazar-sell" header={
      <R4Header testId="bazar-sell-header" live={live}
        back={{ to: homePath, label: t('bazar.sell.home') }}
        title={live.name}
        wideTitle={t('bazar.r4.sellTitle', { name: live.name })}
        sub={today ? <TodayLine total={today.total} count={salesCount} /> : null}
        wideSub={sellerName ? t('bazar.r4.sellingAs', { name: sellerName }) : null}
        today={today ? <TodayPill total={today.total} count={salesCount} /> : null}
        action={
          <span className="flex items-center gap-2 shrink-0">
            {/* B7b: the A4 price sheet (computer: print it; the phone has it in the bazar menu). */}
            <button type="button" onClick={priceSheet} disabled={sheetBusy} data-testid="sell-price-sheet"
              className="hidden lg:flex h-10 px-3 rounded-[10px] border border-line bg-surface text-small font-semibold text-primary items-center gap-1.5 shrink-0 disabled:opacity-60 hover:bg-charcoal">
              {sheetBusy ? <Loader2 size={14} className="animate-spin" aria-hidden /> : <FileText size={14} aria-hidden />}
              {t('bazar.pick.priceSheet')}
            </button>
            <Link to={`/bazar/${encodeURIComponent(eventId)}/return`} data-testid="sell-return"
              className="h-9 px-3 rounded-md bg-white/10 text-small font-semibold text-sidebar-active flex items-center shrink-0 lg:h-10 lg:px-4 lg:rounded-[10px] lg:bg-surface lg:border lg:border-line lg:text-primary lg:text-body lg:hover:bg-charcoal">
              <span className="lg:hidden">{t('bazar.phone.return')}</span>
              <span className="hidden lg:inline">{t('bazar.r4.returnExchange')}</span>
            </Link>
          </span>
        } />
    }>
      {mode === 'cart' ? (
        <CartPanel eventId={eventId} cart={cart} methods={methods} defaultMethod={method} scanner={scanBlock}
          catalog={catalog} modeSwitch={modeTabs} extra={recentList}
          onCompleted={r => { upsert(r); reload(); reloadToday(); reloadCatalog() }} onChanged={receiptChanged} />
      ) : (
        <>
          <Columns left={scanBlock} right={
            <>
              {modeTabs}
              <p className="text-small text-muted">{t('bazar.r4.quickHint', { method: t(`bazar.method.${method}`) })}</p>
              <StatusCard card={quick.card} eventId={eventId} methods={methods} labelFor={labelFor}
                onChanged={changed} onCustomer={s => setSheet({ kind: 'customer', sale: s })} />
              {(quick.card.kind === 'sold' || quick.card.kind === 'undone') && (
                <p className="rounded-xl border border-line bg-base px-3.5 py-3 text-small text-muted" data-testid="sell-ready-next">
                  {t('bazar.r4.readyNext')}
                </p>
              )}
              {recentList}
            </>
          } />
          <div className="contents lg:hidden"><StickyAction>{pickButton}</StickyAction></div>
        </>
      )}

      {sheet?.kind === 'pick' && (
        <PickSheet catalog={catalog} startNumber={sheet.number} mode={mode} onPick={pick}
                   onClose={() => setSheet(null)} onScanBurst={submit} />
      )}

      {sheet?.kind === 'sale' && (
        <SaleSheet eventId={eventId} sale={sheet.sale} methods={methods} onChanged={changed}
                   onClose={() => setSheet(null)} onScanBurst={submit} />
      )}
      {sheet?.kind === 'receipt' && (
        <ReceiptSheet eventId={eventId} receipt={sheet.receipt} methods={methods} onChanged={receiptChanged}
                      onClose={() => setSheet(null)} onScanBurst={submit} />
      )}
      {sheet?.kind === 'customer' && (
        <CustomerSheet eventId={eventId} sale={sheet.sale} onSaved={changed} onClose={() => setSheet(null)}
                       onScanBurst={submit} />
      )}
    </R4Screen>
  )
}

function StatusCard({ card, eventId, methods, labelFor, onChanged, onCustomer }: {
  card: SellCard
  eventId: string
  methods: ReturnType<typeof orderedMethods>
  /** B7a: a tapped size's product + size instead of its internal ref. */
  labelFor: (code: string) => string
  onChanged: (s: BazarSale) => void
  onCustomer: (s: BazarSale) => void
}) {
  const { t, i18n } = useTranslation()
  const pay = usePaymentChange(eventId, onChanged)
  const [now, setNow] = useState(Date.now())
  const [undoing, setUndoing] = useState(false)
  const [undoError, setUndoError] = useState<BazarError | null>(null)
  const soldAt = card.kind === 'sold' ? card.shownAt : null
  useEffect(() => {
    if (soldAt === null) return
    setNow(Date.now())
    const id = window.setInterval(() => setNow(Date.now()), 250)
    const stop = window.setTimeout(() => window.clearInterval(id), sellTiming.undoCardMs + 500)
    return () => { window.clearInterval(id); window.clearTimeout(stop) }
  }, [soldAt])
  useEffect(() => { setUndoError(null) }, [card])

  if (card.kind === 'idle') {
    return (
      <section className="rounded-2xl border border-dashed border-line px-4 py-6 text-center text-small text-muted" data-testid="sell-card-idle">
        {t('bazar.sell.idle')}
      </section>
    )
  }
  if (card.kind === 'sending') {
    return (
      <section role="status" className="rounded-2xl bg-trace-blue/10 border border-trace-blue/30 px-4 py-5 flex items-center gap-3" data-testid="sell-card-sending">
        <Loader2 size={20} className="animate-spin text-trace-blue" aria-hidden />
        <span className="font-semibold text-primary">{t('bazar.sell.sending')}</span>
        <span className="font-mono text-small text-muted ms-auto truncate" dir="ltr">{labelFor(card.code)}</span>
      </section>
    )
  }
  if (card.kind === 'checking') {
    return (
      <section role="status" className="rounded-2xl bg-warning/10 border border-warning/30 px-4 py-4 flex flex-col gap-1" data-testid="sell-card-checking">
        <span className="flex items-center gap-2 font-semibold text-warning-text">
          <WifiOff size={18} aria-hidden />{t('bazar.sell.checking')}
          <Loader2 size={16} className="animate-spin ms-auto" aria-hidden />
        </span>
        <span className="text-small text-primary">{t('bazar.sell.checkingBody')}</span>
        <span className="font-mono text-small text-muted" dir="ltr">{labelFor(card.code)}</span>
      </section>
    )
  }
  if (card.kind === 'error') {
    const { title, detail } = sellErrorText(t, i18n.language, card.error)
    return (
      <section role="alert" className="rounded-2xl bg-critical/10 border border-critical/30 px-4 py-4 flex flex-col gap-1" data-testid="sell-card-error">
        <span className="font-semibold text-critical-text">{title}</span>
        {detail && <span className="text-small text-primary">{detail}</span>}
        <span className="font-mono text-small text-muted" dir="ltr">{labelFor(card.code)}</span>
      </section>
    )
  }
  const sale = card.sale
  if (card.kind === 'undone') {
    return (
      <section role="status" className="rounded-2xl bg-charcoal border border-line px-4 py-4 flex flex-col gap-1" data-testid="sell-card-undone">
        <span className="text-[12px] font-bold tracking-[0.08em] text-muted">{t('bazar.sell.undoneTitle', { number: sale.sale_number })}</span>
        <span className="font-semibold text-primary line-through">{pieceTitle(sale)}</span>
        <span className="text-small text-muted">{t('bazar.sell.undoneBody')}</span>
      </section>
    )
  }

  const left = Math.max(0, Math.ceil((card.shownAt + sellTiming.undoCardMs - now) / 1000))
  const showUndo = left > 0 && canUndoSale(sale)
  const editable = canEditSale(sale)

  async function undo() {
    setUndoing(true)
    setUndoError(null)
    try {
      onChanged(await undoBazarSale(eventId, sale.id))
    } catch (e) {
      setUndoError(e instanceof BazarError ? e : new BazarError(0, { code: 'NETWORK' }))
    } finally {
      setUndoing(false)
    }
  }

  const box = 'min-h-[44px] px-3 py-1.5 rounded-[10px] bg-white/[0.16] text-start text-small font-medium leading-tight break-words hover:bg-white/[0.22]'
  return (
    <section role="status" className="rounded-2xl bg-[#16A34A] text-white p-4 lg:p-[18px] flex flex-col gap-3" data-testid="sell-card-sold">
      <div className="flex items-start justify-between gap-3">
        <span className="flex flex-col gap-0.5 min-w-0">
          <span className="text-[12px] font-bold tracking-[0.08em]">
            {t('bazar.sell.soldTitle', { number: sale.sale_number })}
            <span dir="ltr"> · {saleTime(sale.sold_at)}</span>
          </span>
          <span className="text-[20px] leading-6 font-bold truncate">{pieceTitle(sale)}</span>
          {sale.piece_code && <span className="font-mono text-[12px] opacity-90" dir="ltr">{sale.piece_code}</span>}
        </span>
        <span className="text-[38px] leading-[44px] font-bold tabular-nums shrink-0" dir="ltr" data-testid="sold-price">{amount(sale.price)}</span>
      </div>
      <MethodChips methods={methods} value={sale.method} busy={pay.busy} tone="onGreen" disabled={!editable}
                   onPick={m => pay.pick(sale, m)} />
      {pay.error && <div className="rounded-md bg-white px-2 py-1"><ErrorLine error={pay.error} /></div>}
      {(editable || showUndo) && (
        <div className="grid grid-cols-[minmax(0,1fr)_minmax(0,1fr)_auto] gap-2">
          {editable ? (
            <>
              {/* R4: two boxes that open the customer sheet — nothing is saved from here. */}
              <button type="button" onClick={() => onCustomer(sale)} className={box} data-testid="sold-customer-name">
                {sale.customer_name || t('bazar.r4.customerName')}
              </button>
              <button type="button" onClick={() => onCustomer(sale)} className={box} data-testid="sold-customer-phone">
                <span dir={sale.customer_phone ? 'ltr' : undefined}>{sale.customer_phone || t('bazar.r4.customerPhone')}</span>
              </button>
            </>
          ) : <span className="col-span-2" />}
          {showUndo ? (
            <button type="button" onClick={undo} disabled={undoing} data-testid="sold-undo"
              className="min-h-[44px] px-3 rounded-[10px] bg-[#15803D] hover:bg-[#166534] text-small font-semibold flex items-center gap-1.5 shrink-0 whitespace-nowrap">
              {undoing ? <Loader2 size={14} className="animate-spin" aria-hidden /> : <Undo2 size={15} aria-hidden />}
              {t('bazar.sell.undoCountdown', { seconds: left })}
            </button>
          ) : <span />}
        </div>
      )}
      {undoError && <div className="rounded-md bg-white px-2 py-1"><ErrorLine error={undoError} /></div>}
    </section>
  )
}
