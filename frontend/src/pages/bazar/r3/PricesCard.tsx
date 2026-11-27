import { useCallback, useEffect, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { getTransfer, type TransferDetail } from '../../../api'
import {
  BazarError, clearBazarPrice, listBazarPrices, setBazarBulkRule, setBazarPrice,
  type BazarEventDetail, type BazarPriceRow,
} from '../../../bazar/api'
import { toNumber, variantName } from '../../../bazar/format'
import { Input, Spinner, cn } from '../../../components/ui'
import { amount } from '../r1/parts'
import { Chip } from '../r2/parts'
import { HelpLink } from '../../../help/HelpLink'

// Bazar POS B1 / B3 prices — moved out of BazarEventPage in the R3 restyle (design/bazar-v2 V2_Setup) and shared by
// Get ready and the live page's Prices tab. Same calls, same rules: whole pounds, the rule then the per-variant
// override, clearing an override recomputes. The filters and the value total are display only (on the loaded rows).

export interface PriceData {
  rows: BazarPriceRow[] | null
  setRows: (r: BazarPriceRow[]) => void
  /** Pieces per variant on the linked transfer (qty_out); empty when the transfer didn't load. */
  qty: Map<string, number>
}

/** The price rows + the transfer's pieces per variant (both existing reads). */
export function usePriceData(ev: BazarEventDetail): PriceData {
  const [rows, setRows] = useState<BazarPriceRow[] | null>(null)
  const [qty, setQty] = useState<Map<string, number>>(new Map())
  useEffect(() => {
    listBazarPrices(ev.id).then(setRows).catch(() => setRows([]))
    getTransfer(ev.transfer_id)
      .then((tr: TransferDetail) => setQty(new Map(tr.lines.map(l => [l.variant_id, l.qty_out]))))
      .catch(() => { /* the pieces column stays empty */ })
  }, [ev.id, ev.transfer_id, ev.counts.sent])
  return { rows, setRows, qty }
}

/** Shopify value and bazar value of what's at the bazar (price × pieces), and how many products. */
export function priceTotals(d: PriceData) {
  let shopify = 0, bazar = 0
  for (const r of d.rows ?? []) {
    const n = d.qty.get(r.variant_id) ?? 0
    shopify += toNumber(r.shopify_price) * n
    bazar += toNumber(r.price) * n
  }
  return { shopify, bazar, products: d.rows?.length ?? 0, missing: (d.rows ?? []).filter(r => r.price == null).length }
}

function pct(a: number, b: number): string {
  if (!b) return ''
  const p = Math.round(((a - b) / b) * 100)
  return p === 0 ? '0%' : `${p > 0 ? '+' : '−'}${Math.abs(p)}%`
}

type Filter = 'all' | 'custom' | 'none'

export function PricesCard({ ev, data, live }: { ev: BazarEventDetail; data: PriceData; live?: boolean }) {
  const { t, i18n } = useTranslation()
  const { rows, setRows, qty } = data
  const [discount, setDiscount] = useState(ev.discount_pct != null ? String(toNumber(ev.discount_pct)) : '')
  // B3: prices are whole pounds; the rule's round-to starts at 5 (an empty field still rounds to 1 pound).
  const [roundTo, setRoundTo] = useState(ev.round_to != null ? String(toNumber(ev.round_to)) : '5')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [filter, setFilter] = useState<Filter>('all')
  const ruleOn = ev.discount_pct != null || ev.round_to != null

  const run = useCallback(async (p: Promise<BazarPriceRow[]>) => {
    setBusy(true)
    setError(null)
    try { setRows(await p) } catch (e) {
      setError(e instanceof BazarError ? e.message_(i18n.language) : t('bazar.saveFailed'))
    } finally { setBusy(false) }
  }, [i18n.language, t, setRows])

  function applyRule() {
    const d = discount.trim() === '' ? null : Number(discount)
    const r = roundTo.trim() === '' ? null : Number(roundTo)
    if ((d !== null && !(d > 0 && d < 100)) || (r !== null && !(r >= 1 && Number.isInteger(r)))) {
      setError(t('bazar.prices.ruleInvalid')); return
    }
    run(setBazarBulkRule(ev.id, d, r))
  }

  const missing = rows?.filter(r => r.price == null).length ?? 0
  const custom = rows?.filter(r => r.source === 'override').length ?? 0
  const shown = useMemo(() => (rows ?? []).filter(r =>
    filter === 'all' ? true : filter === 'custom' ? r.source === 'override' : r.price == null), [rows, filter])
  const totals = priceTotals(data)

  return (
    <section className="card p-4 sm:p-5 flex flex-col gap-3.5 min-w-0" data-testid="bazar-prices">
      <div className="flex flex-col">
        <h2 className="text-body-lg font-semibold text-primary">{t('bazar.prices.title')}</h2>
        <span className="text-small text-muted">{t('bazar.r3.prices.sub')}</span>
        <HelpLink screen="bazarPrices" className="self-start mt-1" />
      </div>

      <div className="flex items-center gap-2 flex-wrap self-start rounded-xl border border-line bg-charcoal px-2.5 py-2">
        <label className="flex items-center gap-1.5 text-small text-muted whitespace-nowrap">
          {t('bazar.r3.prices.everything')}
          {/* U1b: no "20" placeholder — it read as a discount already set. Empty with a hint until Apply. */}
          <Input className="w-16 h-9 text-center font-semibold" inputMode="decimal" value={discount} onChange={e => setDiscount(e.target.value)}
                 aria-label={t('bazar.prices.discountAria')} aria-describedby="bazar-discount-state" />%
        </label>
        <label className="flex items-center gap-1.5 text-small text-muted whitespace-nowrap">
          {t('bazar.r3.prices.roundTo')}
          <Input className="w-16 h-9 text-center font-semibold" inputMode="decimal" value={roundTo} onChange={e => setRoundTo(e.target.value)}
                 aria-label={t('bazar.prices.roundToAria')} placeholder="5" />
        </label>
        <button type="button" onClick={applyRule} disabled={busy}
          className="h-9 px-3.5 rounded-lg bg-grey-900 text-white text-small font-semibold disabled:opacity-60">{t('bazar.prices.apply')}</button>
        {ruleOn && (
          <button type="button" className="h-9 px-2 text-small font-semibold text-trace-blue" disabled={busy}
            onClick={() => { setDiscount(''); setRoundTo('5'); run(setBazarBulkRule(ev.id, null, null)) }}>
            {t('bazar.prices.clearRule')}
          </button>
        )}
      </div>
      <p id="bazar-discount-state" className="-mt-1.5 text-small" data-testid="bazar-discount-state">
        {ev.discount_pct != null
          ? <span className="font-semibold text-success-text">{t('bazar.u1b.prices.applied', { pct: toNumber(ev.discount_pct) })}</span>
          : <span className="text-muted">{t('bazar.u1b.prices.discountHint')}</span>}
      </p>

      {error && <p role="alert" className="text-small text-critical">{error}</p>}
      {missing > 0 && (
        <p className="text-small text-warning-text bg-warning/10 border border-warning/30 rounded-lg px-3 py-2" data-testid="bazar-missing-prices">
          {t('bazar.prices.missing', { count: missing })}
        </p>
      )}

      {rows && rows.length > 0 && (
        <div className="flex gap-1.5 flex-wrap" role="group" aria-label={t('bazar.r3.prices.filter')} data-testid="bazar-price-filters">
          {([['all', rows.length], ['custom', custom], ['none', missing]] as [Filter, number][]).map(([k, n]) => (
            <button key={k} type="button" aria-pressed={filter === k} onClick={() => setFilter(k)} data-testid={`price-filter-${k}`}
              className={cn('h-9 px-3 rounded-lg border text-small font-semibold',
                filter === k ? 'bg-grey-900 border-grey-900 text-white' : 'bg-surface border-line text-primary hover:bg-charcoal')}>
              {t(`bazar.r3.prices.filters.${k}`)} <span dir="ltr">{n}</span>
            </button>
          ))}
        </div>
      )}

      {!rows ? <Spinner /> : rows.length === 0 ? (
        <p className="text-body text-muted py-6 text-center">{t('bazar.prices.empty')}</p>
      ) : (
        <div className="overflow-x-auto -mx-1 px-1">
          <table className="w-full min-w-[560px]">
            <thead>
              <tr className="text-[11px] font-bold uppercase tracking-[0.06em] text-muted">
                <th className="tbl-cell text-start">{t('bazar.prices.product')}</th>
                <th className="tbl-cell text-end">{t('bazar.r3.prices.pieces')}</th>
                <th className="tbl-cell text-end">{t('bazar.prices.shopify')}</th>
                <th className="tbl-cell text-end">{t('bazar.prices.bazar')}</th>
                <th className="tbl-cell text-end">{t('bazar.prices.change')}</th>
              </tr>
            </thead>
            <tbody>
              {shown.map(r => (
                <PriceRow key={r.variant_id} row={r} qty={qty.get(r.variant_id)} disabled={busy}
                  onSave={price => run(setBazarPrice(ev.id, r.variant_id, price))}
                  onClear={() => run(clearBazarPrice(ev.id, r.variant_id))} />
              ))}
              {shown.length === 0 && (
                <tr><td colSpan={5} className="tbl-cell text-center text-muted">{t('bazar.r3.prices.noneInFilter')}</td></tr>
              )}
            </tbody>
          </table>
        </div>
      )}

      {rows && rows.length > 0 && (
        <p className="flex justify-end gap-1.5 flex-wrap text-small text-muted border-t border-line pt-3" data-testid="bazar-price-totals">
          {t('bazar.r3.prices.shopifyValue')} <b dir="ltr" className="text-primary tabular-nums">{amount(totals.shopify)}</b>
          <span aria-hidden className="rtl:-scale-x-100">→</span>
          {t('bazar.r3.prices.bazarValue')} <b dir="ltr" className="text-primary tabular-nums">{amount(totals.bazar)} EGP</b>
        </p>
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
  // U1b: an emptied box goes back to the price it had and says why (a price is needed), never silently.
  const [kept, setKept] = useState<string | null>(null)
  useEffect(() => { setValue(shown); setInvalid(false) }, [shown])

  const isPrice = (v: string) => v !== '' && Number.isFinite(Number(v)) && Number(v) >= 0 && Number.isInteger(Number(v))

  function change(v: string) {
    setValue(v)
    setKept(null)
    if (invalid && isPrice(v.trim())) setInvalid(false)   // the red outline clears as soon as a valid price is typed
  }

  function commit() {
    const v = value.trim()
    if (v === '' && shown !== '') { setValue(shown); setInvalid(false); setKept(shown); return }
    if (v === shown) { setInvalid(false); return }
    // B3: whole pounds only — no piasters.
    if (!isPrice(v)) { setInvalid(true); return }
    setInvalid(false)
    onSave(Number(v))
  }

  const shop = row.shopify_price == null ? null : toNumber(row.shopify_price)
  const price = row.price == null ? null : toNumber(row.price)
  const sourceLabel = row.source === 'bulk' ? t('bazar.prices.sourceRule') : row.source === 'default' ? t('bazar.prices.sourceShopify') : null
  const differs = price != null && shop != null && price !== shop

  return (
    <tr className="tbl-row" data-testid={`price-row-${row.variant_id}`}>
      <td className="tbl-cell">
        <span className="block font-semibold text-primary min-w-[120px]">{row.product_title}</span>
        <span className="text-small text-muted">{[variantName(row.variant_title), row.sku].filter(Boolean).join(' · ')}</span>
      </td>
      <td className="tbl-cell text-end tabular-nums text-primary" dir="ltr">{qty ?? '—'}</td>
      {/* U1b: crossed out only when the bazar price is different. */}
      <td className={cn('tbl-cell text-end tabular-nums text-muted', differs && 'line-through')} dir="ltr"
          data-testid={`price-shop-${row.variant_id}`}>{shop == null ? '—' : amount(shop)}</td>
      <td className="tbl-cell text-end">
        <div className="flex items-center justify-end gap-2">
          {row.source === 'override' && (
            <button type="button" className="text-small font-semibold text-trace-blue whitespace-nowrap min-h-[36px]"
                    disabled={disabled} onClick={onClear}>{t('bazar.prices.clearOverride')}</button>
          )}
          <Input className={cn('w-28 h-10 text-end font-semibold tabular-nums', row.source === 'override' && 'border-trace-blue')}
                 inputMode="decimal" dir="ltr" value={value} invalid={invalid} aria-invalid={invalid || undefined} disabled={disabled}
                 aria-label={t('bazar.prices.priceAria', { name: row.product_title })}
                 onChange={e => change(e.target.value)} onBlur={commit}
                 onKeyDown={e => { if (e.key === 'Enter') (e.target as HTMLInputElement).blur() }} />
        </div>
        {kept != null
          ? <span role="status" className="block mt-1 text-small font-semibold text-warning-text" data-testid={`price-kept-${row.variant_id}`}>
              {t('bazar.u1b.prices.needed', { price: amount(Number(kept)) })}</span>
          : invalid
          ? <span role="alert" className="block mt-1 text-small font-semibold text-critical-text">{t('bazar.u1b.prices.wholePounds')}</span>
          : price == null
          ? <span className="block mt-1 text-small font-semibold text-warning-text">{t('bazar.prices.noPrice')}</span>
          : sourceLabel && <span className="block mt-1 text-small text-muted">{sourceLabel}</span>}
      </td>
      <td className="tbl-cell text-end">
        {row.source === 'override'
          ? <Chip tone="info">{t('bazar.r3.prices.custom')}{differs && <span dir="ltr">{' · '}{pct(price!, shop!)}</span>}</Chip>
          : differs
            ? <Chip tone={price < shop ? 'good' : 'warn'}><span dir="ltr">{pct(price, shop)}</span></Chip>
            : null}
      </td>
    </tr>
  )
}
