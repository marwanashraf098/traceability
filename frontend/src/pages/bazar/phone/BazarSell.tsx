import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { ArrowLeft, ArrowRight, Camera, CameraOff, FileText, Keyboard, LayoutGrid, Loader2, WifiOff } from 'lucide-react'
import { useScanner, MAX_QUEUED_SCANS } from '../../../hooks/useScanner'
import CameraReader, { type CameraError } from '../../scanpair/CameraReader'
import {
  BazarError, defaultMethod, isReceipt, orderedMethods, undoBazarSale,
  type BazarReceipt, type BazarSale, type BazarSaleRow,
} from '../../../bazar/api'
import { sellTiming, useQuickSell, type SellCard } from '../../../bazar/useQuickSell'
import { formatMoney, pieceTitle, saleTime, sellErrorText } from '../../../bazar/format'
import { cn } from '../../../components/ui'
import type { BazarLive } from '../../../capabilities'
import { NotLiveScreen, PhoneFrame, useBazarLive, useBazarToday, useTodaySales } from './shared'
import { PhoneTabs } from './ReturnParts'
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

type Mode = 'quick' | 'cart'

/** The seller's last mode on this phone, per event (a convenience: storage may be unavailable). */
function savedMode(eventId: string): Mode {
  try { return sessionStorage.getItem(`bazar-sell-mode:${eventId}`) === 'cart' ? 'cart' : 'quick' } catch { return 'quick' }
}
function saveMode(eventId: string, m: Mode) {
  try { sessionStorage.setItem(`bazar-sell-mode:${eventId}`, m) } catch { /* private mode */ }
}

/** The camera reports the same code many times a second: one read per code per 2 s (ScanPairPage's rule). */
const DUPLICATE_MS = 2_000

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
  const navigate = useNavigate()
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
  const [cameraOn, setCameraOn] = useState(true)
  const [cameraError, setCameraError] = useState<CameraError | null>(null)
  const [dropped, setDropped] = useState<string | null>(null)

  const changed = useCallback((s: BazarSale) => { upsert(s); quick.updateSale(s); reloadToday(); reloadCatalog() },
    [upsert, quick, reloadToday, reloadCatalog])
  const receiptChanged = useCallback((r: BazarReceipt) => { upsert(r); reloadToday() }, [upsert, reloadToday])

  const scanner = useScanner({
    onScan: quick.sell,
    focusPaused: sheet !== null || typing,
  })

  /** Quick mode: one code (or a B7a tapped-size ref) → one sale, through the scanner's single-flight queue. */
  const sendQuick = useCallback((code: string) => {
    if (scanner.pending >= MAX_QUEUED_SCANS) { scanner.handleScan(code); return }   // refused loudly by the scanner
    if (!quick.enqueue(code)) {
      if (scanner.inputRef.current) scanner.inputRef.current.value = ''
      setDropped(code)
      return
    }
    setDropped(null)
    scanner.handleScan(code)
  }, [scanner, quick])

  /** Every way a code arrives (camera, HID scanner, typed) goes through here. */
  const submit = useCallback((raw: string) => {
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
    sendQuick(code)
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

  const lastRead = useRef<{ code: string; at: number } | null>(null)
  const onCameraRead = useCallback((raw: string) => {
    const code = raw.trim()
    const now = Date.now()
    const last = lastRead.current
    if (!code || (last && last.code === code && now - last.at < DUPLICATE_MS)) return
    lastRead.current = { code, at: now }
    submit(code)
  }, [submit])
  const onCameraError = useCallback((e: CameraError) => setCameraError(e), [])

  const recent = (sales ?? []).slice(0, 20)
  const scanBlock = (
    <>
        {/* The phone's camera is its scanner (no pairing). */}
        <div className="relative h-44 rounded-2xl bg-[#1A1F27] overflow-hidden flex items-center" data-testid="bazar-camera-box">
          {cameraOn && !cameraError
            ? <div className="w-full"><CameraReader active onRead={onCameraRead} onError={onCameraError} /></div>
            : (
              <p className="w-full px-6 text-center text-small text-[#D1D5DB]">
                {cameraError ? t(`bazar.sell.camera.${cameraError}`) : t('bazar.sell.camera.off')}
              </p>
            )}
          <span className="absolute bottom-2 inset-x-0 text-center text-small text-[#D1D5DB] pointer-events-none">
            {mode === 'cart' ? t('bazar.cart.scanHint') : t('bazar.sell.scanHint')}
          </span>
          <button type="button" onClick={() => { setCameraError(null); setCameraOn(v => !v) }}
            aria-label={cameraOn ? t('bazar.sell.cameraOff') : t('bazar.sell.cameraOn')}
            className="absolute top-2 end-2 w-11 h-11 rounded-full bg-black/55 text-white flex items-center justify-center">
            {cameraOn ? <CameraOff size={18} /> : <Camera size={18} />}
          </button>
        </div>

        {/* HID scanner / typed code: the one scan input. It opens no keyboard until "Type a code". */}
        <div className="flex items-center gap-2">
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
          <button type="button" onClick={() => {
            const next = !typing
            setTyping(next)
            if (next) setTimeout(() => scanner.inputRef.current?.focus(), 0)
          }} aria-pressed={typing}
            className={cn('h-11 px-3 rounded-lg border border-line flex items-center gap-1.5 text-small font-semibold shrink-0',
              typing ? 'bg-trace-blue text-white border-trace-blue' : 'bg-surface text-primary')}>
            <Keyboard size={16} aria-hidden />{t('bazar.sell.typeCode')}
          </button>
        </div>
        {/* B7a: no barcode — pick a product (phone: a sheet; computer: the grid below). */}
        <button type="button" onClick={() => setSheet({ kind: 'pick', number: null })} data-testid="open-pick"
          className="lg:hidden h-11 rounded-lg border border-line bg-surface flex items-center justify-center gap-2 text-small font-semibold text-primary">
          <LayoutGrid size={16} aria-hidden />{t('bazar.pick.open')}
        </button>
        <div className="hidden lg:block"><ProductGrid catalog={catalog} onPick={pick} /></div>
        {dropped && <p className="text-small text-muted" role="status">{t('bazar.sell.alreadySending', { code: labelFor(dropped) })}</p>}
        {scanner.queueFull && <p className="text-small text-critical" role="alert">{t('bazar.sell.queueFull')}</p>}
        {scanner.pending > 0 && <p className="text-small text-muted">{t('bazar.sell.waiting', { count: scanner.pending })}</p>}
    </>
  )
  const recentList = (
      <section className="flex flex-col gap-1.5 min-h-0" aria-label={t('bazar.sell.recent')}>
        <span className="text-small font-medium text-muted">{t('bazar.sell.recent')}</span>
        {sales && recent.length === 0 && <p className="text-small text-muted">{t('bazar.sell.noSales')}</p>}
        <ul className="flex flex-col gap-1.5" data-testid="bazar-recent">
          {recent.map(s => isReceipt(s) ? (
            <li key={s.id}>
              <button type="button" onClick={() => setSheet({ kind: 'receipt', receipt: s })} data-testid="recent-receipt"
                className="w-full flex items-center gap-2.5 px-3 py-2.5 rounded-xl border border-line bg-surface text-start min-h-[44px]">
                <span className="font-mono text-small text-muted w-11 shrink-0" dir="ltr">{saleTime(s.sold_at)}</span>
                <span className={cn('flex-1 min-w-0 truncate font-medium', s.status !== 'active' && 'line-through text-muted')}>
                  {t('bazar.cart.row', { count: s.line_count })}
                </span>
                {s.status === 'undone'
                  ? <span className="badge border bg-muted/[0.14] text-neutral-text border-muted/[0.30]">{t('bazar.sell.undoneShort')}</span>
                  : s.method && <span className="badge border bg-muted/[0.14] text-neutral-text border-muted/[0.30]">{t(`bazar.method.${s.method}`)}</span>}
                <span className={cn('font-semibold tabular-nums shrink-0', s.status !== 'active' && 'line-through text-muted')}>
                  {formatMoney(s.total).replace(/^EGP /, '')}
                </span>
              </button>
            </li>
          ) : (
            <li key={s.id}>
              <button type="button" onClick={() => setSheet({ kind: 'sale', sale: s })}
                className="w-full flex items-center gap-2.5 px-3 py-2.5 rounded-xl border border-line bg-surface text-start min-h-[44px]">
                <span className="font-mono text-small text-muted w-11 shrink-0" dir="ltr">{saleTime(s.sold_at)}</span>
                <span className={cn('flex-1 min-w-0 truncate font-medium', s.status !== 'active' && 'line-through text-muted')}>
                  {pieceTitle(s)}
                </span>
                <SaleBadge sale={s} />
                <span className={cn('font-semibold tabular-nums shrink-0', s.status !== 'active' && 'line-through text-muted')}>
                  {formatMoney(s.price).replace(/^EGP /, '')}
                </span>
              </button>
            </li>
          ))}
        </ul>
      </section>
  )
  const Back = i18n.dir() === 'rtl' ? ArrowRight : ArrowLeft

  return (
    <PhoneFrame testId="bazar-sell" wide header={
      <>
      <div className="flex items-center justify-between gap-3">
        <button type="button" onClick={() => navigate(`/bazar/${encodeURIComponent(eventId)}/home`)}
          className="min-h-[44px] -ms-2 px-2 flex items-center gap-1.5 text-sidebar-text hover:text-sidebar-active text-small font-semibold">
          <Back size={18} aria-hidden />{t('bazar.sell.home')}
        </button>
        <span className="flex items-center gap-3 min-w-0">
          <span className="text-small font-semibold text-sidebar-active truncate">
            {live.day >= 1 && live.day <= live.dayCount ? t('bazar.sell.titleDay', { name: live.name, day: live.day }) : live.name}
          </span>
          {/* B7b: the A4 price sheet (computer: print it; the phone has it in the bazar menu). */}
          <button type="button" onClick={priceSheet} disabled={sheetBusy} data-testid="sell-price-sheet"
            className="hidden lg:flex h-9 px-3 rounded-lg border border-white/20 text-small font-semibold text-sidebar-active items-center gap-1.5 shrink-0 disabled:opacity-60">
            {sheetBusy ? <Loader2 size={14} className="animate-spin" aria-hidden /> : <FileText size={14} aria-hidden />}
            {t('bazar.pick.priceSheet')}
          </button>
        </span>
      </div>
      <PhoneTabs eventId={eventId} active="sell" />
      <ModeSwitch mode={mode} onChange={setMode} />
      </>
    } footer={
      <footer className="sticky bottom-0 z-10 bg-surface border-t border-line px-4 pt-3 pb-[max(16px,env(safe-area-inset-bottom))] flex items-center justify-between gap-3"
              data-testid="bazar-sell-footer">
        <span className="flex flex-col">
          <span className="text-small text-muted">{t('bazar.phone.today')}</span>
          <span className="text-body-lg font-bold text-primary tabular-nums">{today ? formatMoney(today.total) : '…'}</span>
        </span>
        <span className="text-small text-muted text-end">
          {today ? t('bazar.sell.salesCount', { count: today.sale_count }) : ''}
          <br />{t('bazar.sell.defaultPay', { method: t(`bazar.method.${method}`) })}
        </span>
      </footer>
    }>
      {mode === 'cart' ? (
        <CartPanel eventId={eventId} cart={cart} methods={methods} defaultMethod={method} scanner={scanBlock}
          onCompleted={r => { upsert(r); reload(); reloadToday(); reloadCatalog() }} onChanged={receiptChanged} />
      ) : (
        <div className="flex flex-col gap-3 lg:grid lg:grid-cols-[minmax(0,1fr)_380px] lg:items-start lg:gap-5">
          <div className="flex flex-col gap-3 min-w-0">{scanBlock}</div>
          <div className="flex flex-col gap-3 min-w-0">
            <StatusCard card={quick.card} eventId={eventId} methods={methods} labelFor={labelFor}
              onChanged={changed} onCustomer={s => setSheet({ kind: 'customer', sale: s })} />
            {recentList}
          </div>
        </div>
      )}

      {mode === 'cart' && recentList}

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
    </PhoneFrame>
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
      <section className="rounded-2xl border border-dashed border-line px-4 py-5 text-center text-small text-muted" data-testid="sell-card-idle">
        {t('bazar.sell.idle')}
      </section>
    )
  }
  if (card.kind === 'sending') {
    return (
      <section role="status" className="rounded-2xl bg-trace-blue/10 border border-trace-blue/30 px-4 py-4 flex items-center gap-3" data-testid="sell-card-sending">
        <Loader2 size={20} className="animate-spin text-trace-blue" aria-hidden />
        <span className="font-semibold text-primary">{t('bazar.sell.sending')}</span>
        <span className="font-mono text-small text-muted ms-auto" dir="ltr">{labelFor(card.code)}</span>
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
        <span className="text-small font-bold tracking-wider text-muted">{t('bazar.sell.undoneTitle', { number: sale.sale_number })}</span>
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

  return (
    <section role="status" className="rounded-2xl bg-[#16A34A] text-white px-4 py-3.5 flex flex-col gap-2.5" data-testid="sell-card-sold">
      <div className="flex items-start justify-between gap-3">
        <span className="flex flex-col gap-0.5 min-w-0">
          <span className="text-small font-bold tracking-wider">{t('bazar.sell.soldTitle', { number: sale.sale_number })}</span>
          <span className="text-body-lg font-semibold truncate">{pieceTitle(sale)}</span>
        </span>
        <span className="text-h3 font-bold tabular-nums shrink-0" data-testid="sold-price">{formatMoney(sale.price).replace(/^EGP /, '')}</span>
      </div>
      <MethodChips methods={methods} value={sale.method} busy={pay.busy} tone="onGreen" disabled={!editable}
                   onPick={m => pay.pick(sale, m)} />
      {pay.error && <div className="rounded-md bg-white px-2 py-1"><ErrorLine error={pay.error} /></div>}
      <div className="flex items-center justify-between gap-2">
        {editable ? (
          <button type="button" onClick={() => onCustomer(sale)} className="min-h-[44px] font-semibold underline underline-offset-2 text-start truncate">
            {sale.customer_name || sale.customer_phone
              ? [sale.customer_name, sale.customer_phone].filter(Boolean).join(' · ')
              : t('bazar.customer.addShort')}
          </button>
        ) : <span />}
        {showUndo && (
          <button type="button" onClick={undo} disabled={undoing} data-testid="sold-undo"
            className="min-h-[44px] px-3 rounded-lg border border-white/60 font-semibold flex items-center gap-1.5 shrink-0">
            {undoing && <Loader2 size={14} className="animate-spin" aria-hidden />}
            {t('bazar.sell.undoCountdown', { seconds: left })}
          </button>
        )}
      </div>
      {undoError && <div className="rounded-md bg-white px-2 py-1"><ErrorLine error={undoError} /></div>}
    </section>
  )
}

/** Quick (every scan is a sale) / Cart (several pieces, one total). */
function ModeSwitch({ mode, onChange }: { mode: Mode; onChange: (m: Mode) => void }) {
  const { t } = useTranslation()
  return (
    <div role="radiogroup" aria-label={t('bazar.cart.mode')} data-testid="bazar-mode-switch"
         className="grid grid-cols-2 gap-1 p-1 rounded-xl bg-white/10">
      {(['quick', 'cart'] as const).map(m => (
        <button key={m} type="button" role="radio" aria-checked={mode === m} onClick={() => onChange(m)}
          className={cn('h-9 rounded-lg text-small font-semibold transition-colors',
            mode === m ? 'bg-white text-primary' : 'text-sidebar-text hover:text-sidebar-active')}>
          {t(`bazar.cart.${m}`)}
        </button>
      ))}
    </div>
  )
}
