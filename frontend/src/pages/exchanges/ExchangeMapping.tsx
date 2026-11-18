import { useEffect, useRef, useState } from 'react'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { CheckCircle2, Minus, Plus, Trash2, X } from 'lucide-react'
import {
  getExchangeDetail, mapExchange, mapExchangeItems, getExchangeOutboundCandidates, getExchangeOriginalLines,
  ExchangeSummary, ExchangeOriginalLine, CatalogProduct, CatalogVariant,
} from '../../api'
import { Alert, Badge, Button, Input, ProductThumb, Skeleton } from '../../components/ui'
import ExchangeVariantPicker from './ExchangeVariantPicker'

interface SelectedVariant {
  variant: CatalogVariant
  product: CatalogProduct
}

/**
 * S3a (2026-11-18) — one row of the N-row mapping: a variant (an inbound row may stay "don't know yet"), a quantity and
 * the courier-text hint the person keeps for it (prefilled from Bosta's description).
 */
interface Row {
  key: number
  selected: SelectedVariant | null
  quantity: number
  hint: string
  unknown: boolean
}

type Leg = 'outbound' | 'inbound'

const EMPTY_COUNTS = {
  available: 0, reserved: 0, packed: 0, awaiting_pickup: 0, with_courier: 0,
  delivered: 0, return_in_transit: 0, return_pending_inspection: 0, damaged: 0,
  lost: 0, destroyed: 0, out_on_transfer: 0, sold: 0, total: 0,
}

/** A lightweight stand-in for a variant known only by id + titles (resolver candidate, original order line). */
function stub(variantId: string, variantTitle: string | null, productTitle: string | null, imageUrl: string | null = null): SelectedVariant {
  return {
    variant: { id: variantId, title: variantTitle ?? '', sku: null, price: null, pieceCounts: { ...EMPTY_COUNTS }, committed: 0, available: 0 },
    product: { id: '', title: productTitle ?? '', status: 'active', imageUrl, variants: [] },
  }
}

/** Trims TRAILING whitespace only — raw courier text is shown verbatim otherwise, never parsed. */
function trimTrailing(s: string | null): string {
  return (s ?? '').replace(/\s+$/, '')
}

const MAX_QTY = 99

export default function ExchangeMapping() {
  const { t } = useTranslation()
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  // 2026-11-16: opened from Pick & Pack's "Exchanges to map" — success goes back there, straight to picking.
  const [searchParams] = useSearchParams()
  const fromFulfill = searchParams.get('from') === 'fulfill'
  const [mappedOrderId, setMappedOrderId] = useState<string | null>(null)

  const [exchange, setExchange] = useState<ExchangeSummary | null>(null)
  const [loading, setLoading]   = useState(true)
  const [loadError, setLoadError] = useState('')

  const nextKey = useRef(0)
  const newRow = (quantity: number, hint: string): Row =>
    ({ key: nextKey.current++, selected: null, quantity, hint, unknown: false })
  const [outRows, setOutRows] = useState<Row[]>([])
  const [inRows, setInRows]   = useState<Row[]>([])
  const [picker, setPicker] = useState<{ leg: Leg; key: number; catalog: boolean } | null>(null)
  const [originalLines, setOriginalLines] = useState<ExchangeOriginalLine[]>([])

  const [confirming, setConfirming] = useState(false)
  const [confirmError, setConfirmError] = useState('')
  const [mappedOrderNumber, setMappedOrderNumber] = useState<string | null>(null)

  const defaultOutQty = Math.min(MAX_QTY, Math.max(1, exchange?.outbound_items_count ?? 1))
  const defaultInQty = Math.min(MAX_QTY, Math.max(1, exchange?.inbound_items_count ?? 1))

  useEffect(() => {
    if (!id) return
    // S2 (2026-11-18): the single-exchange read — the old "first 100 of the list" lookup said "not found" for
    // any older exchange.
    getExchangeDetail(id)
      .then(ex => {
        setExchange(ex)
        const outQty = Math.min(MAX_QTY, Math.max(1, ex.outbound_items_count ?? 1))
        const inQty = Math.min(MAX_QTY, Math.max(1, ex.inbound_items_count ?? 1))
        setOutRows(rows => rows.length ? rows : [newRow(outQty, trimTrailing(ex.outbound_description))])
        setInRows(rows => rows.length ? rows : [newRow(inQty, trimTrailing(ex.inbound_description))])
        if (ex.matched_order_id) {
          getExchangeOriginalLines(id).then(setOriginalLines).catch(() => { /* catalog search still works */ })
        }
      })
      .catch((e: unknown) => setLoadError(
        (e as Error)?.message?.startsWith('404') ? t('exchange.mapping.notFound') : t('common.error')))
      .finally(() => setLoading(false))
  }, [id, t]) // eslint-disable-line react-hooks/exhaustive-deps

  // RECS pre-population (build task Part C): pre-select the FIRST outbound row's best-ranked candidate WITHOUT
  // committing anything — the operator still confirms or overrides via the normal picker below.
  useEffect(() => {
    if (!id) return
    getExchangeOutboundCandidates(id)
      .then(res => {
        if (res.classification !== 'RECS' || res.candidates.length === 0) return
        const best = res.candidates[0]
        setOutRows(rows => rows.length && !rows[0].selected
          ? [{ ...rows[0], selected: stub(best.variantId, best.variantTitle, best.productTitle) }, ...rows.slice(1)]
          : rows)
      })
      .catch(() => { /* pre-population is a convenience — the manual picker still works if this fails */ })
  }, [id, outRows.length > 0]) // eslint-disable-line react-hooks/exhaustive-deps

  const setRows = (leg: Leg) => (leg === 'outbound' ? setOutRows : setInRows)
  const update = (leg: Leg, key: number, patch: Partial<Row>) =>
    setRows(leg)(rows => rows.map(r => (r.key === key ? { ...r, ...patch } : r)))

  const ready = outRows.length > 0 && outRows.every(r => r.selected)
    && inRows.every(r => r.selected || r.unknown)

  async function handleConfirm() {
    if (!id || !ready) return
    setConfirming(true); setConfirmError('')
    try {
      // One plain item each way, as Bosta counted it → the single-item mapping (unchanged); anything else → map-items.
      const single = outRows.length === 1 && inRows.length === 1 && !!inRows[0].selected && !inRows[0].unknown
        && outRows[0].quantity === defaultOutQty && inRows[0].quantity === defaultInQty
      const result = single
        ? await mapExchange(id, outRows[0].selected!.variant.id, inRows[0].selected!.variant.id)
        : await mapExchangeItems(id,
            outRows.map(r => ({ variantId: r.selected!.variant.id, quantity: r.quantity, descriptionHint: r.hint.trim() || null })),
            inRows.map(r => ({
              variantId: r.unknown ? null : r.selected?.variant.id ?? null,
              quantity: r.quantity, descriptionHint: r.hint.trim() || null,
            })))
      setMappedOrderNumber(`EXC-${exchange?.tracking_number ?? ''}` || result.orderId)
      setMappedOrderId(result.orderId ?? null)
    } catch (e: unknown) {
      setConfirmError((e as Error).message ?? t('common.error'))
    } finally {
      setConfirming(false)
    }
  }

  if (loading) {
    return (
      <div className="space-y-4">
        <Skeleton className="h-8 w-64 rounded-xl" />
        <Skeleton className="h-48 rounded-2xl" />
        <Skeleton className="h-48 rounded-2xl" />
      </div>
    )
  }

  if (loadError || !exchange) {
    return <Alert tone="critical" title={loadError || t('exchange.mapping.notFound')} />
  }

  if (mappedOrderNumber) {
    return (
      <div className="card p-8 text-center space-y-4" data-testid="exchange-mapped-success">
        <CheckCircle2 className="mx-auto text-success" size={40} />
        <h2 className="text-h3 text-primary">{t('exchange.mapping.successTitle')}</h2>
        <p className="text-body text-muted">
          {t('exchange.mapping.successBody', { order: mappedOrderNumber })}
        </p>
        {fromFulfill ? (
          <div className="flex flex-wrap justify-center gap-3">
            {mappedOrderId && (
              <Button onClick={() => navigate(`/fulfill?pick=${encodeURIComponent(mappedOrderId)}`)}>
                {t('exchange.mapping.startPicking')}
              </Button>
            )}
            <Link to="/fulfill" className="text-trace-blue hover:underline text-small self-center">
              {t('exchange.mapping.backToPickPack')}
            </Link>
          </div>
        ) : (
          <Link to="/exceptions" className="text-trace-blue hover:underline text-small">
            {t('exchange.mapping.backToExceptions')}
          </Link>
        )}
      </div>
    )
  }

  const openPicker = (leg: Leg, key: number) =>
    setPicker({ leg, key, catalog: leg === 'outbound' || originalLines.length === 0 })

  return (
    <div className="space-y-4 max-w-3xl">
      <div>
        <h1 className="text-h2 text-primary">{t('exchange.mapping.title', { tracking: exchange.tracking_number })}</h1>
        {(exchange.customer_name || exchange.customer_phone) && (
          <p className="text-small text-muted mt-1">
            {[exchange.customer_name, exchange.customer_phone].filter(Boolean).join(' · ')}
          </p>
        )}
      </div>

      {confirmError && <Alert tone="critical" title={confirmError} />}

      <LegCard
        leg="outbound"
        legLabel={t('exchange.mapping.outboundLabel')}
        legHint={t('exchange.mapping.outboundHint')}
        description={trimTrailing(exchange.outbound_description)}
        descriptionAr={null}
        rows={outRows}
        onSelect={key => openPicker('outbound', key)}
        onUpdate={(key, patch) => update('outbound', key, patch)}
        onAdd={() => setOutRows(rows => [...rows, newRow(1, '')])}
        onRemove={key => setOutRows(rows => rows.filter(r => r.key !== key))}
        testId="exchange-leg-outbound"
      />

      <LegCard
        leg="inbound"
        legLabel={t('exchange.mapping.inboundLabel')}
        legHint={t('exchange.mapping.inboundHint')}
        description={trimTrailing(exchange.inbound_description)}
        descriptionAr={trimTrailing(exchange.inbound_description_ar)}
        rows={inRows}
        onSelect={key => openPicker('inbound', key)}
        onUpdate={(key, patch) => update('inbound', key, patch)}
        onAdd={() => setInRows(rows => [...rows, newRow(1, '')])}
        onRemove={key => setInRows(rows => rows.filter(r => r.key !== key))}
        testId="exchange-leg-inbound"
      />

      <div className="flex justify-end pt-2">
        <Button
          disabled={!ready}
          loading={confirming}
          onClick={handleConfirm}
        >
          {t('exchange.mapping.confirmButton')}
        </Button>
      </div>

      {picker && picker.catalog && (
        <ExchangeVariantPicker
          onClose={() => setPicker(null)}
          onSelect={(variant, product) => {
            update(picker.leg, picker.key, { selected: { variant, product }, unknown: false })
            setPicker(null)
          }}
        />
      )}
      {picker && !picker.catalog && (
        <OriginalLinePicker
          lines={originalLines}
          onClose={() => setPicker(null)}
          onCatalog={() => setPicker({ ...picker, catalog: true })}
          onSelect={line => {
            update(picker.leg, picker.key, {
              selected: stub(line.variantId, line.variantTitle, line.productTitle, line.imageUrl), unknown: false,
            })
            setPicker(null)
          }}
        />
      )}
    </div>
  )
}

function LegCard({
  leg, legLabel, legHint, description, descriptionAr, rows, onSelect, onUpdate, onAdd, onRemove, testId,
}: {
  leg: Leg
  legLabel: string
  legHint: string
  description: string
  descriptionAr: string | null
  rows: Row[]
  onSelect: (key: number) => void
  onUpdate: (key: number, patch: Partial<Row>) => void
  onAdd: () => void
  onRemove: (key: number) => void
  testId: string
}) {
  const { t } = useTranslation()
  return (
    <div className="card p-5 space-y-3" data-testid={testId}>
      <div className="flex items-center gap-2">
        <Badge tone="neutral" label={legLabel} />
        <span className="text-small text-muted">{legHint}</span>
      </div>

      <div className="space-y-1">
        {description && <p className="text-body text-primary" dir="auto">{description}</p>}
        {descriptionAr && <p className="text-body text-primary" dir="rtl">{descriptionAr}</p>}
        <p className="text-caption text-muted italic">{t('exchange.mapping.rawDescriptionNote')}</p>
      </div>

      <div className="space-y-2">
        {rows.map((row, i) => (
          <ItemRow
            key={row.key}
            leg={leg}
            index={i}
            row={row}
            removable={rows.length > 1}
            onSelect={() => onSelect(row.key)}
            onUpdate={patch => onUpdate(row.key, patch)}
            onRemove={() => onRemove(row.key)}
          />
        ))}
      </div>

      <div>
        <button
          type="button"
          onClick={onAdd}
          className="inline-flex items-center gap-1.5 text-small font-semibold text-trace-blue hover:underline"
          data-testid={`${testId}-add`}
        >
          <Plus size={16} strokeWidth={2} />
          {t('exchange.mapping.addItem')}
        </button>
      </div>
    </div>
  )
}

function ItemRow({
  leg, index, row, removable, onSelect, onUpdate, onRemove,
}: {
  leg: Leg
  index: number
  row: Row
  removable: boolean
  onSelect: () => void
  onUpdate: (patch: Partial<Row>) => void
  onRemove: () => void
}) {
  const { t } = useTranslation()
  const sel = row.selected
  return (
    <div className="rounded-xl border border-line p-3 space-y-2.5" data-testid={`exchange-row-${leg}-${index}`}>
      <div className="flex items-center gap-3">
        {sel ? (
          <>
            <ProductThumb src={sel.product.imageUrl} alt={sel.product.title} size={40} cdnWidth={80} />
            <div className="flex-1 min-w-0">
              <div className="text-small font-semibold text-primary truncate">{sel.product.title}</div>
              <div className="text-caption text-muted truncate">{sel.variant.title}</div>
            </div>
            <button
              type="button"
              onClick={onSelect}
              className="text-caption text-trace-blue hover:underline flex-shrink-0"
            >
              {t('exchange.mapping.changeVariant')}
            </button>
          </>
        ) : row.unknown ? (
          <p className="flex-1 text-small text-muted">{t('exchange.mapping.unknownChosen')}</p>
        ) : (
          <div className="flex-1">
            <Button variant="secondary" onClick={onSelect}>
              {t('exchange.mapping.selectVariant')}
            </Button>
          </div>
        )}
        {removable && (
          <button
            type="button"
            onClick={onRemove}
            aria-label={t('exchange.mapping.removeItem')}
            className="w-8 h-8 rounded-lg flex items-center justify-center text-muted hover:text-critical hover:bg-elevated flex-shrink-0"
            data-testid={`exchange-row-${leg}-${index}-remove`}
          >
            <Trash2 size={16} strokeWidth={2} />
          </button>
        )}
      </div>

      <div className="flex flex-wrap items-end gap-3">
        <div>
          <span className="block text-caption text-muted mb-1">{t('exchange.mapping.quantity')}</span>
          <div className="inline-flex items-center rounded-lg border border-line" data-testid={`exchange-row-${leg}-${index}-qty`}>
            <button type="button" className="w-8 h-8 flex items-center justify-center text-muted hover:text-primary disabled:opacity-40"
              aria-label={t('exchange.mapping.decrease')} disabled={row.quantity <= 1}
              onClick={() => onUpdate({ quantity: Math.max(1, row.quantity - 1) })}>
              <Minus size={14} strokeWidth={2} />
            </button>
            <span className="w-8 text-center text-small font-semibold tabular-nums">{row.quantity}</span>
            <button type="button" className="w-8 h-8 flex items-center justify-center text-muted hover:text-primary disabled:opacity-40"
              aria-label={t('exchange.mapping.increase')} disabled={row.quantity >= MAX_QTY}
              onClick={() => onUpdate({ quantity: Math.min(MAX_QTY, row.quantity + 1) })}>
              <Plus size={14} strokeWidth={2} />
            </button>
          </div>
        </div>
        <label className="flex-1 min-w-[12rem]">
          <span className="block text-caption text-muted mb-1">{t('exchange.mapping.hintLabel')}</span>
          <Input value={row.hint} maxLength={500} dir="auto" onChange={e => onUpdate({ hint: e.target.value })}
            data-testid={`exchange-row-${leg}-${index}-hint`} />
        </label>
      </div>

      {leg === 'inbound' && !sel && (
        <label className="inline-flex items-center gap-2 text-small text-muted cursor-pointer">
          <input type="checkbox" checked={row.unknown} onChange={e => onUpdate({ unknown: e.target.checked })}
            data-testid={`exchange-row-${leg}-${index}-unknown`} />
          {t('exchange.mapping.unknownInbound')}
        </label>
      )}
    </div>
  )
}

/** S3a — the inbound row's picker when an original order is linked: that order's lines, or the catalog. */
function OriginalLinePicker({
  lines, onSelect, onCatalog, onClose,
}: {
  lines: ExchangeOriginalLine[]
  onSelect: (line: ExchangeOriginalLine) => void
  onCatalog: () => void
  onClose: () => void
}) {
  const { t } = useTranslation()
  return (
    <div className="fixed inset-0 z-modal flex items-center justify-center p-4" data-testid="exchange-original-line-picker">
      <div className="absolute inset-0 bg-black/45" onClick={onClose} />
      <div className="relative w-full max-w-md card p-5 space-y-3 max-h-[80vh] overflow-y-auto">
        <div className="flex items-center justify-between gap-3">
          <h2 className="text-body-lg font-semibold text-primary">{t('exchange.mapping.fromOrderTitle')}</h2>
          <button type="button" onClick={onClose} aria-label={t('common.close', { defaultValue: 'Close' })}
            className="w-8 h-8 rounded-lg flex items-center justify-center text-muted hover:text-primary hover:bg-elevated">
            <X size={18} strokeWidth={2} />
          </button>
        </div>
        <div className="space-y-2">
          {lines.map(l => (
            <button key={l.orderItemId} type="button" onClick={() => onSelect(l)}
              className="w-full flex items-center gap-3 rounded-xl border border-line p-3 text-start hover:bg-elevated"
              data-testid={`exchange-original-line-${l.orderItemId}`}>
              <ProductThumb src={l.imageUrl} alt={l.productTitle ?? ''} size={36} cdnWidth={72} />
              <div className="flex-1 min-w-0">
                <div className="text-small font-semibold text-primary truncate">{l.productTitle ?? '—'}</div>
                <div className="text-caption text-muted truncate">{[l.variantTitle, l.sku].filter(Boolean).join(' · ')}</div>
              </div>
              <span className="text-caption text-muted tabular-nums">×{l.quantity}</span>
            </button>
          ))}
        </div>
        <button type="button" onClick={onCatalog} className="text-small font-semibold text-trace-blue hover:underline"
          data-testid="exchange-original-line-catalog">
          {t('exchange.mapping.searchCatalog')}
        </button>
      </div>
    </div>
  )
}
