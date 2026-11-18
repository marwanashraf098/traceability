import { SaleBadge } from './phone/SaleSheets'
import { useCallback, useEffect, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { ScanLine, Smartphone } from 'lucide-react'
import { getTransfer, type TransferDetail } from '../../api'
import {
  BazarError, clearBazarPrice, getBazarEvent, getBazarLeftovers, listBazarDays, listBazarPrices, listBazarSales, markBazarSent, orderedMethods,
  setBazarBulkRule, setBazarPrice, startBazarEvent, type BazarDay, type BazarDays, type BazarEventDetail, type BazarLeftovers, type BazarPriceRow,
  type BazarSale,
} from '../../bazar/api'
import {
  eventDay, formatMoney, liveLabel, pieceTitle, saleTime, shortDate, toNumber, transferCode,
} from '../../bazar/format'
import { resetCapabilitiesCache } from '../../capabilities'
import { Badge, Button, Input, Modal, Spinner, cn } from '../../components/ui'
import { QrCode } from '../../phone/PhonePairModal'
import { STATUS_TONE } from './BazarEvents'
import { CloseDayForm, DayStatusBadge, dayTitle } from './CloseDayForm'
import { EndEventPanel, LeftoversPanel } from './Leftovers'

/** Bazar POS B6 — /bazar/:id. Draft → setup (mockup step 2); live / ended → the event's numbers (step 3). */
export default function BazarEventPage() {
  const { id } = useParams<{ id: string }>()
  const { t, i18n } = useTranslation()
  const navigate = useNavigate()
  const [ev, setEv] = useState<BazarEventDetail | null>(null)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(() => {
    if (!id) return Promise.resolve()
    return getBazarEvent(id).then(e => { setEv(e); setError(null) }).catch(e => {
      if (e instanceof BazarError && e.code === 'BAZAR_NOT_ENABLED') { navigate('/overview', { replace: true }); return }
      setError(e instanceof BazarError ? e.message_(i18n.language) : t('bazar.loadFailed'))
    })
  }, [id, navigate, t, i18n.language])
  useEffect(() => { load() }, [load])

  if (error && !ev) return <p role="alert" className="text-body text-critical">{error}</p>
  if (!ev) return <div className="py-16 flex justify-center"><Spinner size={28} /></div>

  return (
    <div className="flex flex-col gap-5 max-w-[1080px]" data-testid="bazar-event">
      <div className="flex flex-col gap-1">
        <Link to="/bazar" className="text-small text-muted hover:text-primary self-start">{t('bazar.breadcrumb')}</Link>
      </div>
      {ev.status === 'draft'
        ? <SetupView ev={ev} reload={load} onChanged={setEv} />
        : <LiveView ev={ev} reload={load} />}
    </div>
  )
}

// ── Setup (draft) ─────────────────────────────────────────────────────────────

function SetupView({ ev, reload, onChanged }: {
  ev: BazarEventDetail
  reload: () => Promise<void>
  onChanged: (e: BazarEventDetail) => void
}) {
  const { t, i18n } = useTranslation()
  const navigate = useNavigate()
  const [confirmSend, setConfirmSend] = useState(false)
  const [busy, setBusy] = useState<'send' | 'start' | null>(null)
  const [error, setError] = useState<string | null>(null)
  const preparing = ev.transfer_status === 'preparing'
  const sent = ev.transfer_status === 'sent'

  async function act(kind: 'send' | 'start') {
    setBusy(kind)
    setError(null)
    try {
      const next = kind === 'send' ? await markBazarSent(ev.id) : await startBazarEvent(ev.id)
      if (kind === 'start') resetCapabilitiesCache()   // GET /me's bazarLive changes
      onChanged(next)
    } catch (e) {
      setError(e instanceof BazarError ? e.message_(i18n.language) : t('bazar.saveFailed'))
      reload()
    } finally {
      setBusy(null)
      setConfirmSend(false)
    }
  }

  return (
    <>
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div className="flex flex-col gap-1.5">
          <h1 className="text-h2 text-primary">{ev.name}</h1>
          <p className="text-body text-muted">{t('bazar.setup.subtitle')}</p>
        </div>
        <Badge tone="warning" label={t('bazar.setup.notStarted', {
          from: shortDate(ev.starts_on, i18n.language), to: shortDate(ev.ends_on, i18n.language) })} />
      </div>
      {error && <p role="alert" className="text-body text-critical">{error}</p>}

      <div className="flex flex-wrap gap-5 items-start">
        <aside className="flex-[1_1_300px] min-w-0 flex flex-col gap-4">
          <section className="card p-5 flex flex-col gap-3" data-testid="bazar-transfer-card">
            <div className="flex items-center justify-between gap-2">
              <h2 className="text-body-lg font-semibold text-primary">{t('bazar.transfer.title')}</h2>
              <Badge tone={sent ? 'info' : 'neutral'} label={sent ? t('bazar.transfer.sent') : t('bazar.transfer.preparing')} />
            </div>
            <div className="flex flex-col gap-1 p-3 rounded-xl bg-charcoal border border-line">
              <span className="text-primary">
                <span className="font-mono font-medium" dir="ltr">{transferCode(ev.transfer_id)}</span> · {t('bazar.transfer.kind')}
              </span>
              <span className="text-small text-muted">{t('bazar.transfer.route', { to: ev.location_name })}</span>
              <span className="text-h3 font-bold text-primary mt-1" data-testid="bazar-sent-count">
                {ev.counts.sent} <span className="text-body font-medium text-muted">{t('bazar.transfer.piecesOut', { count: ev.counts.sent })}</span>
              </span>
            </div>
            {preparing && (
              <>
                <Button variant="secondary" iconStart={ScanLine} onClick={() => navigate(`/bazar/${ev.id}/scan-out`)}>
                  {ev.counts.sent > 0 ? t('bazar.transfer.scanMore') : t('bazar.transfer.scanOut')}
                </Button>
                <Button onClick={() => setConfirmSend(true)} disabled={ev.counts.sent === 0} loading={busy === 'send'}>
                  {t('bazar.transfer.markSent')}
                </Button>
                {ev.counts.sent === 0 && <p className="text-small text-muted">{t('bazar.transfer.scanFirst')}</p>}
              </>
            )}
            <p className="text-small text-muted">{t('bazar.transfer.note')}</p>
          </section>

          <section className="card p-5 flex flex-col gap-2.5">
            <h2 className="text-body-lg font-semibold text-primary">{t('bazar.start.title')}</h2>
            <p className="text-body text-muted">{sent ? t('bazar.start.ready') : t('bazar.start.needSent')}</p>
            <Button onClick={() => act('start')} disabled={!sent} loading={busy === 'start'} className="h-11">
              {t('bazar.start.button')}
            </Button>
          </section>
        </aside>

        <PricesCard ev={ev} />
      </div>

      {confirmSend && (
        <Modal title={t('bazar.transfer.confirmTitle')} onClose={() => setConfirmSend(false)}>
          <p className="text-body text-primary">{t('bazar.transfer.confirmBody', { count: ev.counts.sent })}</p>
          <div className="flex justify-end gap-2 mt-5">
            <Button variant="secondary" onClick={() => setConfirmSend(false)}>{t('bazar.form.cancel')}</Button>
            <Button onClick={() => act('send')} loading={busy === 'send'}>{t('bazar.transfer.markSent')}</Button>
          </div>
        </Modal>
      )}
    </>
  )
}

function pct(a: number, b: number): string {
  if (!b) return ''
  const p = Math.round(((a - b) / b) * 100)
  return p === 0 ? '0%' : `${p > 0 ? '+' : '−'}${Math.abs(p)}%`
}

function PricesCard({ ev, live }: { ev: BazarEventDetail; live?: boolean }) {
  const { t, i18n } = useTranslation()
  const [rows, setRows] = useState<BazarPriceRow[] | null>(null)
  const [qty, setQty] = useState<Map<string, number>>(new Map())
  const [discount, setDiscount] = useState(ev.discount_pct != null ? String(toNumber(ev.discount_pct)) : '')
  // B3: prices are whole pounds; the rule's round-to starts at 5 (an empty field still rounds to 1 pound).
  const [roundTo, setRoundTo] = useState(ev.round_to != null ? String(toNumber(ev.round_to)) : '5')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const ruleOn = ev.discount_pct != null || ev.round_to != null

  useEffect(() => {
    listBazarPrices(ev.id).then(setRows).catch(() => setRows([]))
    getTransfer(ev.transfer_id)
      .then((tr: TransferDetail) => setQty(new Map(tr.lines.map(l => [l.variant_id, l.qty_out]))))
      .catch(() => { /* the count column stays empty */ })
  }, [ev.id, ev.transfer_id, ev.counts.sent])

  async function run(p: Promise<BazarPriceRow[]>) {
    setBusy(true)
    setError(null)
    try { setRows(await p) } catch (e) {
      setError(e instanceof BazarError ? e.message_(i18n.language) : t('bazar.saveFailed'))
    } finally { setBusy(false) }
  }

  function applyRule() {
    const d = discount.trim() === '' ? null : Number(discount)
    const r = roundTo.trim() === '' ? null : Number(roundTo)
    if ((d !== null && !(d > 0 && d < 100)) || (r !== null && !(r >= 1 && Number.isInteger(r)))) {
      setError(t('bazar.prices.ruleInvalid')); return
    }
    run(setBazarBulkRule(ev.id, d, r))
  }

  const missing = rows?.filter(r => r.price == null).length ?? 0

  return (
    <section className="card p-5 flex-[999_1_560px] min-w-0 flex flex-col gap-3.5" data-testid="bazar-prices">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h2 className="text-body-lg font-semibold text-primary">{t('bazar.prices.title')}</h2>
        <div className="flex flex-wrap items-center gap-2">
          <label className="flex items-center gap-1.5 text-small text-muted whitespace-nowrap">
            {t('bazar.prices.discount')}
            <Input className="w-20 h-9" inputMode="decimal" value={discount} onChange={e => setDiscount(e.target.value)}
                   aria-label={t('bazar.prices.discountAria')} placeholder="20" />%
          </label>
          <label className="flex items-center gap-1.5 text-small text-muted whitespace-nowrap">
            {t('bazar.prices.roundTo')}
            <Input className="w-20 h-9" inputMode="decimal" value={roundTo} onChange={e => setRoundTo(e.target.value)}
                   aria-label={t('bazar.prices.roundToAria')} placeholder="5" />
          </label>
          <Button variant="secondary" size="sm" onClick={applyRule} loading={busy}>{t('bazar.prices.apply')}</Button>
          {ruleOn && (
            <Button variant="ghost" size="sm" onClick={() => { setDiscount(''); setRoundTo('5'); run(setBazarBulkRule(ev.id, null, null)) }}>
              {t('bazar.prices.clearRule')}
            </Button>
          )}
        </div>
      </div>
      {error && <p role="alert" className="text-small text-critical">{error}</p>}
      {missing > 0 && (
        <p className="text-small text-warning-text bg-warning/10 border border-warning/30 rounded-lg px-3 py-2" data-testid="bazar-missing-prices">
          {t('bazar.prices.missing', { count: missing })}
        </p>
      )}
      {!rows ? <Spinner /> : rows.length === 0 ? (
        <p className="text-body text-muted py-6 text-center">{t('bazar.prices.empty')}</p>
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full min-w-[600px]">
            <thead>
              <tr className="text-small text-muted">
                <th className="tbl-cell text-start font-medium">{t('bazar.prices.product')}</th>
                <th className="tbl-cell text-end font-medium">{t('bazar.prices.atBazar')}</th>
                <th className="tbl-cell text-end font-medium">{t('bazar.prices.shopify')}</th>
                <th className="tbl-cell text-start font-medium">{t('bazar.prices.bazar')}</th>
                <th className="tbl-cell text-start font-medium">{t('bazar.prices.change')}</th>
              </tr>
            </thead>
            <tbody>
              {rows.map(r => (
                <PriceRow key={r.variant_id} row={r} qty={qty.get(r.variant_id)} disabled={busy}
                  onSave={price => run(setBazarPrice(ev.id, r.variant_id, price))}
                  onClear={() => run(clearBazarPrice(ev.id, r.variant_id))} />
              ))}
            </tbody>
          </table>
        </div>
      )}
      <p className="text-small text-muted">{t('bazar.prices.note')}</p>
      {live && <p className="text-small text-primary" data-testid="bazar-prices-live-note">{t('bazar.prices.liveNote')}</p>}
    </section>
  )
}

function PriceRow({ row, qty, disabled, onSave, onClear }: {
  row: BazarPriceRow
  qty: number | undefined
  disabled: boolean
  onSave: (price: number) => void
  onClear: () => void
}) {
  const { t } = useTranslation()
  const shown = row.price == null ? '' : String(toNumber(row.price))
  const [value, setValue] = useState(shown)
  const [invalid, setInvalid] = useState(false)
  useEffect(() => { setValue(shown); setInvalid(false) }, [shown])

  function commit() {
    const v = value.trim()
    if (v === shown) return
    const n = Number(v)
    // B3: whole pounds only — no piasters.
    if (v === '' || !Number.isFinite(n) || n < 0 || !Number.isInteger(n)) { setInvalid(true); return }
    setInvalid(false)
    onSave(n)
  }

  const shop = row.shopify_price == null ? null : toNumber(row.shopify_price)
  const price = row.price == null ? null : toNumber(row.price)
  const sourceLabel = row.source === 'override' ? t('bazar.prices.sourceOverride')
    : row.source === 'bulk' ? t('bazar.prices.sourceRule') : row.source === 'default' ? t('bazar.prices.sourceShopify') : null

  return (
    <tr className="tbl-row" data-testid={`price-row-${row.variant_id}`}>
      <td className="tbl-cell">
        <span className="block font-semibold text-primary">{row.product_title}</span>
        <span className="text-small text-muted">
          {[row.variant_title, row.sku].filter(Boolean).join(' · ')}
        </span>
      </td>
      <td className="tbl-cell text-end tabular-nums text-primary">{qty ?? '—'}</td>
      <td className="tbl-cell text-end tabular-nums text-muted">{shop == null ? '—' : formatMoney(shop)}</td>
      <td className="tbl-cell">
        <div className="flex items-center gap-2">
          <Input className="w-28 h-9 font-semibold" inputMode="decimal" value={value} invalid={invalid} disabled={disabled}
                 aria-label={t('bazar.prices.priceAria', { name: row.product_title })}
                 onChange={e => setValue(e.target.value)} onBlur={commit}
                 onKeyDown={e => { if (e.key === 'Enter') (e.target as HTMLInputElement).blur() }} />
          {row.source === 'override' && (
            <button type="button" className="text-small font-semibold text-trace-blue whitespace-nowrap min-h-[36px]"
                    disabled={disabled} onClick={onClear}>{t('bazar.prices.clearOverride')}</button>
          )}
        </div>
        {price == null
          ? <span className="block mt-1 text-small font-semibold text-warning-text">{t('bazar.prices.noPrice')}</span>
          : sourceLabel && <span className="block mt-1 text-small text-muted">{sourceLabel}</span>}
      </td>
      <td className={cn('tbl-cell font-semibold', price != null && shop != null && price < shop ? 'text-success-text' : 'text-muted')}>
        {price != null && shop != null ? pct(price, shop) : ''}
      </td>
    </tr>
  )
}

// ── Live / ended ──────────────────────────────────────────────────────────────

function LiveView({ ev, reload }: { ev: BazarEventDetail; reload: () => Promise<void> }) {
  const { t, i18n } = useTranslation()
  const [sales, setSales] = useState<BazarSale[] | null>(null)
  const [ending, setEnding] = useState(false)
  const [left, setLeft] = useState<BazarLeftovers | null>(null)
  const [days, setDays] = useState<BazarDays | null>(null)
  const [closing, setClosing] = useState<BazarDay | null>(null)
  const [showPrices, setShowPrices] = useState(false)
  const live = ev.status === 'live'
  const { day, count } = eventDay(ev.starts_on, ev.ends_on, ev.today.business_day)
  const methods = orderedMethods(ev.payment_methods)

  const loadDays = useCallback(() => listBazarDays(ev.id).then(setDays).catch(() => {}), [ev.id])
  // B5: once ended, the leftovers coming back on the linked transfer.
  useEffect(() => {
    if (ev.status === 'ended') getBazarLeftovers(ev.id).then(setLeft).catch(() => {})
  }, [ev.id, ev.status])
  useEffect(() => {
    const loadSales = () => listBazarSales(ev.id, 10).then(p => setSales(p.items)).catch(() => setSales([]))
    loadSales()
    loadDays()
    if (!live) return
    const iv = window.setInterval(() => { reload(); loadSales(); loadDays() }, 30_000)
    return () => window.clearInterval(iv)
  }, [ev.id, live, reload, loadDays])

  const sent = ev.counts.sent || 0
  const soldPct = sent ? Math.round((ev.counts.sold / sent) * 100) : 0
  const sellUrl = `${window.location.origin}/bazar/${ev.id}/sell`

  return (
    <>
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div className="flex flex-col gap-1.5">
          <h1 className="text-h2 text-primary">{ev.name}</h1>
          <Badge tone={STATUS_TONE[ev.status]} className="self-start"
                 label={live ? liveLabel(t, day, count) : t('bazar.status.ended')} />
        </div>
        {live && (
          <div className="flex flex-wrap gap-2.5">
            <Button variant="secondary" aria-expanded={showPrices} onClick={() => setShowPrices(v => !v)}>
              {showPrices ? t('bazar.prices.hide') : t('bazar.prices.changePrices')}
            </Button>
            <Button variant="secondary" data-testid="desktop-close-day"
                    onClick={() => setClosing(days?.days.find(d => d.is_today) ?? days?.days[days.days.length - 1] ?? null)}
                    disabled={!days?.days.length}>{t('bazar.phone.closeDay')}</Button>
            <Button onClick={() => setEnding(true)}>{t('bazar.phone.endEvent')}</Button>
          </div>
        )}
        {ev.status === 'ended' && (
          <Link to={`/bazar/${ev.id}/report`} className="btn h-10 px-4 rounded-lg bg-trace-blue text-white font-semibold flex items-center"
                data-testid="view-report">{t('bazar.end.viewReport')}</Link>
        )}
      </div>

      <section className="grid grid-cols-[repeat(auto-fit,minmax(200px,1fr))] gap-3" data-testid="bazar-kpis">
        <Kpi label={t('bazar.live.today')} value={formatMoney(ev.today.total) || formatMoney(0)}
             sub={[t('bazar.live.salesCount', { count: ev.today.sales }),
                   ...(ev.today.returns ? [t('bazar.phone.returnsCount', { count: ev.today.returns })] : []),
                   ...(ev.today.exchanges ? [t('bazar.phone.exchangesCount', { count: ev.today.exchanges })] : [])].join(' · ')}
             testId="kpi-today" />
        {ev.totals && (
          <Kpi label={t('bazar.live.eventTotal')} value={formatMoney(ev.totals.total) || formatMoney(0)}
               sub={[t('bazar.live.salesCount', { count: ev.totals.sales }),
                     ...(ev.totals.returns ? [t('bazar.phone.returnsCount', { count: ev.totals.returns })] : []),
                     ...(ev.totals.exchanges ? [t('bazar.phone.exchangesCount', { count: ev.totals.exchanges })] : [])].join(' · ')}
               testId="kpi-event-total" />
        )}
        <Kpi label={t('bazar.live.byMethod')} value={null}
             sub={methods.map(m => `${t(`bazar.method.${m}`)} ${formatMoney(toNumber(ev.today.by_method[m])).replace(/^EGP /, '')}`).join(' · ')}
             testId="kpi-methods" />
        <Kpi label={t('bazar.live.sold')} value={String(ev.counts.sold)} sub={t('bazar.live.ofSent', { count: sent })} />
        <Kpi label={t('bazar.live.atBazar')} value={String(ev.counts.at_bazar)} sub={t('bazar.live.ofSent', { count: sent })} />
      </section>

      {live && showPrices && <div className="flex"><PricesCard ev={ev} live /></div>}

      <div className="flex flex-wrap gap-5 items-start">
        <div className="flex-[999_1_480px] min-w-0 flex flex-col gap-5">
          <section className="card p-5 flex flex-col gap-3.5">
            <div className="flex items-center justify-between gap-2">
              <h2 className="text-body-lg font-semibold text-primary">
                {t('bazar.transfer.title')} <span className="font-mono font-medium text-muted" dir="ltr">{transferCode(ev.transfer_id)}</span>
              </h2>
              <Link to={`/transfers/${ev.transfer_id}`} className="text-small font-semibold text-trace-blue">{t('bazar.live.openTransfer')}</Link>
            </div>
            <div className="flex h-3.5 rounded-full overflow-hidden bg-charcoal" aria-hidden>
              <div className="bg-[#16A34A]" style={{ width: `${soldPct}%` }} />
              <div className="bg-trace-blue" style={{ width: `${sent ? 100 - soldPct : 0}%` }} />
            </div>
            <div className="grid grid-cols-3 gap-3">
              <Count label={t('bazar.live.sentOut')} value={sent} />
              <Count label={t('bazar.live.sold')} value={ev.counts.sold} swatch="bg-[#16A34A]" />
              <Count label={t('bazar.live.atBazar')} value={ev.counts.at_bazar} swatch="bg-trace-blue" />
            </div>
          </section>

          {left && (
            <section className="card p-5 flex flex-col gap-3" data-testid="bazar-leftovers">
              <h2 className="text-body-lg font-semibold text-primary">{t('bazar.end.leftoversTitle')}</h2>
              <LeftoversPanel eventId={ev.id} initial={left} noCamera onClosed={l => { setLeft(l); reload() }} />
            </section>
          )}

          <DaysCard days={days} onClose={setClosing} />

          <section className="card p-5 flex flex-col gap-2" data-testid="bazar-live-sales">
            <h2 className="text-body-lg font-semibold text-primary">{t('bazar.live.recentSales')}</h2>
            {!sales ? <Spinner /> : sales.length === 0 ? (
              <p className="text-body text-muted">{t('bazar.sell.noSales')}</p>
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full min-w-[520px]">
                  <tbody>
                    {sales.map(s => (
                      <tr key={s.id} className="tbl-row">
                        <td className="tbl-cell font-mono text-small text-muted" dir="ltr">{saleTime(s.sold_at)}</td>
                        <td className="tbl-cell font-mono text-small text-muted" dir="ltr">{s.sale_number}</td>
                        <td className={cn('tbl-cell text-primary', s.status !== 'active' && 'line-through text-muted')}>{pieceTitle(s)}</td>
                        <td className="tbl-cell text-small text-muted"><SaleBadge sale={s} /></td>
                        <td className="tbl-cell text-small text-muted">{s.seller_name}</td>
                        <td className={cn('tbl-cell text-end font-semibold tabular-nums', s.status !== 'active' && 'line-through text-muted')}>
                          {formatMoney(s.price)}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </section>
        </div>

        {live && (
          <aside className="card p-5 flex-[1_1_260px] min-w-0 flex flex-col gap-3" data-testid="bazar-open-on-phone">
            <h2 className="text-body-lg font-semibold text-primary flex items-center gap-2">
              <Smartphone size={18} aria-hidden />{t('bazar.live.phoneTitle')}
            </h2>
            <p className="text-body text-primary">{t('bazar.live.phoneBody')}</p>
            <div className="self-center rounded-xl border border-line p-2 bg-white">
              <QrCode value={sellUrl} size={120} />
            </div>
            <p className="text-small text-muted text-center">{t('bazar.live.qrHint')}</p>
          </aside>
        )}
      </div>

      {closing && days && (
        <Modal title={`${t('bazar.close.title')} · ${dayTitle(t, closing, i18n.language)}`} onClose={() => setClosing(null)}>
          <CloseDayForm key={closing.business_day + ':' + closing.close_count} eventId={ev.id} day={closing} compact
                        methods={orderedMethods(days.payment_methods)}
                        onClosed={() => { setClosing(null); loadDays(); reload() }} />
        </Modal>
      )}
      {ending && (
        <Modal title={t('bazar.end.title')} onClose={() => setEnding(false)}>
          <EndEventPanel eventId={ev.id} name={ev.name}
            onEnded={l => { setEnding(false); setLeft(l); resetCapabilitiesCache(); reload() }} />
        </Modal>
      )}
    </>
  )
}

/** B4 — every day of the event with its money and close status; a day can be closed (again) from here. */
function DaysCard({ days, onClose }: { days: BazarDays | null; onClose: (d: BazarDay) => void }) {
  const { t, i18n } = useTranslation()
  return (
    <section className="card p-5 flex flex-col gap-2" data-testid="bazar-days">
      <h2 className="text-body-lg font-semibold text-primary">{t('bazar.close.days')}</h2>
      {!days ? <Spinner /> : days.days.length === 0 ? (
        <p className="text-body text-muted">{t('bazar.close.noDays')}</p>
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full min-w-[560px]">
            <tbody>
              {[...days.days].reverse().map(d => (
                <tr key={d.business_day} className="tbl-row" data-testid={`day-row-${d.business_day}`}>
                  <td className="tbl-cell font-semibold text-primary whitespace-nowrap">{dayTitle(t, d, i18n.language)}</td>
                  <td className="tbl-cell text-end font-semibold tabular-nums whitespace-nowrap">{formatMoney(d.money.total) || formatMoney(0)}</td>
                  <td className="tbl-cell text-small text-muted">
                    {orderedMethods(days.payment_methods).map(m => `${t(`bazar.method.${m}`)} ${formatMoney(toNumber(d.money.by_method[m])).replace(/^EGP /, '')}`).join(' · ')}
                  </td>
                  <td className="tbl-cell"><DayStatusBadge day={d} /></td>
                  <td className="tbl-cell text-end">
                    <button type="button" className="text-small font-semibold text-trace-blue min-h-[36px] px-1 whitespace-nowrap"
                            onClick={() => onClose(d)}>
                      {d.close ? t('bazar.close.closeAgain') : t('bazar.close.closeThisDay')}
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  )
}

function Kpi({ label, value, sub, testId }: { label: string; value: string | null; sub: string; testId?: string }) {
  return (
    <div className="card p-4 flex flex-col gap-1" data-testid={testId}>
      <span className="text-small text-muted">{label}</span>
      {value != null && <span className="text-h3 font-semibold text-primary tabular-nums">{value}</span>}
      <span className={cn('text-small', value == null ? 'text-primary text-body font-medium mt-1' : 'text-muted')}>{sub}</span>
    </div>
  )
}

function Count({ label, value, swatch }: { label: string; value: number; swatch?: string }) {
  return (
    <div className="flex flex-col">
      <span className="text-small text-muted flex items-center gap-1.5">
        {swatch && <span className={cn('w-2 h-2 rounded-sm', swatch)} aria-hidden />}{label}
      </span>
      <span className="text-h4 font-semibold text-primary tabular-nums">{value}</span>
    </div>
  )
}
