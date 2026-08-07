import { useEffect, useRef, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { getOrder, OrderDetail as IOrderDetail, ShipmentDetail, AttemptEntry, DeliveryHistoryEntry, holdOrder, releaseOrderHold, updateOrderCod } from '../api'
import { Alert, Badge, Button, DeliveryBadge, LegStatusBadge, Modal, OrderStatus, Skeleton } from '../components/ui'

export default function OrderDetail() {
  const { t } = useTranslation()
  const { id } = useParams<{ id: string }>()
  const [order,   setOrder]   = useState<IOrderDetail | null>(null)
  const [loading, setLoading] = useState(true)
  const [error,   setError]   = useState('')

  // Hold dialog state
  const [holdOpen,    setHoldOpen]    = useState(false)
  const [holdReason,  setHoldReason]  = useState('')
  const [holdBusy,    setHoldBusy]    = useState(false)
  const [holdErr,     setHoldErr]     = useState('')

  // COD inline edit state
  const [codEditing,  setCodEditing]  = useState(false)
  const [codValue,    setCodValue]    = useState('')
  const [codBusy,     setCodBusy]     = useState(false)
  const [codErr,      setCodErr]      = useState('')
  const codInputRef = useRef<HTMLInputElement>(null)

  const reload = () => {
    if (!id) return
    getOrder(id).then(setOrder).catch(() => {})
  }

  useEffect(() => {
    if (!id) return
    getOrder(id)
      .then(setOrder)
      .catch(() => setError(t('common.error')))
      .finally(() => setLoading(false))
  }, [id, t])

  const handleHold = async () => {
    if (!id || !holdReason.trim()) return
    setHoldBusy(true); setHoldErr('')
    try {
      await holdOrder(id, holdReason.trim())
      setHoldOpen(false); setHoldReason(''); reload()
    } catch { setHoldErr(t('common.error')) }
    finally { setHoldBusy(false) }
  }

  const handleUnhold = async () => {
    if (!id) return
    try { await releaseOrderHold(id); reload() }
    catch { /* noop — reload regardless */ }
  }

  const startCodEdit = () => {
    setCodValue(order?.codAmount?.toString() ?? '')
    setCodEditing(true); setCodErr('')
    setTimeout(() => codInputRef.current?.select(), 0)
  }

  const saveCod = async () => {
    if (!id) return
    const v = parseFloat(codValue)
    if (isNaN(v) || v <= 0 || v > 30000) { setCodErr(t('orderDetail.codValidation')); return }
    setCodBusy(true); setCodErr('')
    try { await updateOrderCod(id, v); setCodEditing(false); reload() }
    catch (e: unknown) {
      const msg = (e as { message?: string }).message
      setCodErr(msg ?? t('common.error'))
    }
    finally { setCodBusy(false) }
  }

  if (loading) return (
    <div className="space-y-6">
      <Skeleton className="h-8 w-48 rounded-xl" />
      <div className="grid grid-cols-1 lg:grid-cols-3 gap-4">
        <div className="lg:col-span-1 space-y-4">
          <Skeleton className="h-36 rounded-2xl" />
          <Skeleton className="h-36 rounded-2xl" />
          <Skeleton className="h-28 rounded-2xl" />
        </div>
        <div className="lg:col-span-2 space-y-3">
          <Skeleton className="h-40 rounded-2xl" />
          <Skeleton className="h-40 rounded-2xl" />
        </div>
      </div>
    </div>
  )

  if (error) return <Alert tone="critical" title={error} />
  if (!order) return null

  const addr = order.address
    ? Object.values(order.address).filter(Boolean).join(', ')
    : null

  return (
    <div className="space-y-6">
      <div>
        <Link to="/orders" className="text-small text-trace-blue hover:text-trace-blue-hover transition-colors">
          ← {t('orderDetail.back')}
        </Link>
        <h1 className="text-h1 text-primary mt-2">
          {t('orderDetail.title')} <span className="font-mono">{order.number}</span>
        </h1>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-4">

        {/* Left: customer + order meta + shipments */}
        <div className="lg:col-span-1 space-y-4">
          <section className="card p-4">
            <h2 className="text-caption text-muted uppercase tracking-widest mb-3">
              {t('orderDetail.customer')}
            </h2>
            <dl className="space-y-2">
              <InfoRow label={t('orderDetail.customer')} value={order.customerName} />
              <InfoRow label={t('orderDetail.phone')}    value={order.customerPhone} />
              <InfoRow label={t('orderDetail.address')}  value={addr} />
            </dl>
          </section>

          <section className="card p-4 space-y-3">
            <h2 className="text-caption text-muted uppercase tracking-widest">
              {t('orderDetail.status')}
            </h2>
            <dl className="space-y-2">
              <div className="flex items-start gap-2">
                <dt className="text-small text-muted w-24 shrink-0">{t('orderDetail.status')}</dt>
                <dd><OrderStatus derived={order.derivedStatus} /></dd>
              </div>
              {order.onHold && (
                <div className="flex items-start gap-2 flex-wrap">
                  <Badge tone="critical" label={t('orderDetail.onHold')} />
                  {order.holdReason && (
                    <span className="text-small text-muted">{order.holdReason}</span>
                  )}
                </div>
              )}
              <InfoRow label={t('orderDetail.payment')} value={order.paymentMethod} />

              {/* FR-7.5 COD inline edit */}
              <div className="flex items-center gap-2">
                <dt className="text-small text-muted w-24 shrink-0">{t('orderDetail.cod')}</dt>
                {codEditing ? (
                  <dd className="flex items-center gap-1.5 flex-wrap">
                    <input
                      ref={codInputRef}
                      type="number"
                      min={1} max={30000} step={1}
                      value={codValue}
                      onChange={e => setCodValue(e.target.value)}
                      onKeyDown={e => { if (e.key === 'Enter') saveCod(); if (e.key === 'Escape') setCodEditing(false) }}
                      className="input w-28 text-sm py-0.5 px-2"
                    />
                    <span className="text-small text-muted">EGP</span>
                    <Button size="sm" onClick={saveCod} loading={codBusy}>{t('orderDetail.codSave')}</Button>
                    <Button size="sm" variant="secondary" onClick={() => { setCodEditing(false); setCodErr('') }}>{t('orderDetail.codCancel')}</Button>
                    {codErr && <p className="text-critical text-small w-full">{codErr}</p>}
                  </dd>
                ) : (
                  <dd className="flex items-center gap-2">
                    <span className="text-small text-primary">
                      {order.codAmount != null ? `${order.codAmount.toLocaleString()} EGP` : '—'}
                    </span>
                    {(order.status === 'new' || order.status === 'ready_to_pick') && (
                      <button
                        onClick={startCodEdit}
                        className="text-trace-blue text-small hover:underline leading-none"
                        title={t('orderDetail.codEdit')}
                      >
                        ✎
                      </button>
                    )}
                  </dd>
                )}
              </div>

              <InfoRow label={t('orderDetail.placedAt')} value={order.placedAt ? new Date(order.placedAt).toLocaleString() : null} />
            </dl>

            {/* FR-7.4 Hold / Unhold */}
            <div className="pt-2 flex gap-2">
              {order.onHold ? (
                <Button size="sm" variant="secondary" onClick={handleUnhold}>
                  {t('orderDetail.unholdBtn')}
                </Button>
              ) : (
                !['delivered','cancelled','returned','lost'].includes(order.status) && (
                  <Button size="sm" variant="secondary" onClick={() => { setHoldErr(''); setHoldReason(''); setHoldOpen(true) }}>
                    {t('orderDetail.holdBtn')}
                  </Button>
                )
              )}
            </div>
          </section>

          {order.shipments.length === 0 ? (
            <section className="card p-4">
              <h2 className="text-caption text-muted uppercase tracking-widest mb-3">
                {t('orderDetail.shipment')}
              </h2>
              {order.bostaLinkStatus === 'not_created' ? (
                <Badge tone="critical" label={t('delivery.state.not_created')} />
              ) : (
                <DeliveryBadge state={null} />
              )}
            </section>
          ) : (
            order.shipments.map(shipment => (
              <ShipmentCard key={shipment.id} shipment={shipment} />
            ))
          )}
        </div>

        {/* Right: order items */}
        <div className="lg:col-span-2 space-y-3">
          <h2 className="text-caption text-muted uppercase tracking-widest">
            {t('orderDetail.items')}
          </h2>
          {order.items.map(item => (
            <div key={item.id} className="card p-4">
              <div className="flex items-start justify-between mb-3">
                <div>
                  <p className="text-body font-semibold text-primary">{item.productTitle}</p>
                  <p className="text-small text-muted">{item.variantTitle}</p>
                  {/* SKU — font-mono per spec */}
                  {item.sku && <p className="font-mono text-caption text-muted mt-0.5">{item.sku}</p>}
                </div>
                <Badge tone="neutral" label={`×${item.quantity}`} />
              </div>

              <div>
                <p className="text-caption text-muted uppercase tracking-widest mb-2">
                  {t('orderDetail.pieces')}
                </p>
                {item.allocatedPieces.length === 0 ? (
                  <p className="text-small text-muted/50">{t('orderDetail.noPieces')}</p>
                ) : (
                  <div className="flex flex-wrap gap-2">
                    {item.allocatedPieces.map(p => (
                      <div key={p.pieceId} className="flex items-center gap-1.5 border border-line rounded-md px-2 py-1 bg-elevated">
                        {/* Piece barcode — font-mono per spec */}
                        <span className="font-mono text-caption text-muted">{p.barcode}</span>
                        <Badge status={p.status} />
                      </div>
                    ))}
                  </div>
                )}
              </div>
            </div>
          ))}
        </div>
      </div>

      {/* FR-7.4 Hold dialog */}
      {holdOpen && <Modal
        title={t('orderDetail.holdDialog.title')}
        onClose={() => setHoldOpen(false)}
      >
        <div className="space-y-4">
          <div>
            <label className="text-small text-muted block mb-1">
              {t('orderDetail.holdDialog.reasonLabel')}
            </label>
            <input
              autoFocus
              type="text"
              className="input w-full"
              placeholder={t('orderDetail.holdDialog.reasonPlaceholder')}
              value={holdReason}
              onChange={e => setHoldReason(e.target.value)}
              onKeyDown={e => { if (e.key === 'Enter') handleHold() }}
              maxLength={200}
            />
          </div>
          {holdErr && <p className="text-critical text-small">{holdErr}</p>}
          <div className="flex justify-end gap-2">
            <Button variant="secondary" onClick={() => setHoldOpen(false)}>
              {t('orderDetail.holdDialog.cancel')}
            </Button>
            <Button
              variant="destructive"
              loading={holdBusy}
              disabled={!holdReason.trim()}
              onClick={handleHold}
            >
              {t('orderDetail.holdDialog.confirm')}
            </Button>
          </div>
        </div>
      </Modal>}
    </div>
  )
}

// ── Sub-components ────────────────────────────────────────────────────────────

function ShipmentCard({ shipment }: { shipment: ShipmentDetail }) {
  const { t } = useTranslation()
  const [historyOpen, setHistoryOpen] = useState(false)
  const isReturn = shipment.shipmentLeg === 'return'

  return (
    <section className="card p-4 space-y-3">
      <div className="flex items-center justify-between gap-2">
        <h2 className="text-caption text-muted uppercase tracking-widest">
          {isReturn ? t('orderDetail.returnShipment') : t('orderDetail.shipment')}
        </h2>
        {/* A3.1: leg-scoped badge, return leg ONLY — the forward leg's status lives
            solely in the order header above (never re-add a raw state badge here). */}
        {isReturn && <LegStatusBadge legStatus={shipment.legStatus} />}
      </div>

      {/* Facts only — the derived headline above is the single status source (A1/A2). */}
      <dl className="space-y-2">
        {/* Tracking number — font-mono */}
        <InfoRow label={t('orderDetail.tracking')} value={shipment.trackingNumber} mono />
        <InfoRow label={t('orderDetail.provider')} value={shipment.provider} />
        {shipment.courierName && (
          <InfoRow
            label={t('orderDetail.courier')}
            value={[shipment.courierName, shipment.courierPhone].filter(Boolean).join(' · ')}
          />
        )}
        {shipment.scheduledAt && (
          <InfoRow label={t('orderDetail.scheduledAt')} value={new Date(shipment.scheduledAt).toLocaleString()} />
        )}
        {shipment.lastFailureReason && (
          <InfoRow label={t('orderDetail.lastFailureReason')} value={shipment.lastFailureReason} />
        )}
        {shipment.awbUrl && (
          <div className="flex gap-2 items-start">
            <dt className="text-small text-muted w-24 shrink-0">{t('orderDetail.awb')}</dt>
            <dd>
              {/* AWB link — kept as <a>; Button renders <button> not <a> */}
              <a
                href={shipment.awbUrl}
                target="_blank"
                rel="noreferrer"
                className="text-small text-trace-blue hover:text-trace-blue-hover transition-colors"
              >
                Download
              </a>
            </dd>
          </div>
        )}
      </dl>

      {/* Attention pills */}
      <div className="flex flex-wrap gap-1.5">
        {shipment.failedDeliveryAttempts > 0 && (
          <Badge tone="critical" label={t('orderDetail.failedAttempts', { count: shipment.failedDeliveryAttempts })} />
        )}
        {(shipment.isDelayed || shipment.slaBreached) && (
          <Badge tone="warning" label={t('orderDetail.delayed')} />
        )}
      </div>

      {/* A4: Delivery attempts, promoted above the raw/collapsed history — already the
          case pre-A4 (this block was already rendered before the history toggle below),
          confirmed rather than reordered. */}
      {shipment.attempts.length > 0 && (
        <AttemptHistory attempts={shipment.attempts} isReturn={isReturn} />
      )}

      {/* Expandable delivery status timeline — collapsed/grouped by default (A4) */}
      <div className="border-t border-line pt-3">
        <Button
          variant="tertiary"
          size="sm"
          onClick={() => setHistoryOpen(o => !o)}
        >
          <span>{historyOpen ? '▾' : '▸'}</span>
          <span>{historyOpen ? t('orderDetail.historyToggleHide') : t('orderDetail.historyToggleShow')}</span>
        </Button>

        {historyOpen && (
          // A4: forward leg gets the collapsed/grouped view; return leg keeps its own
          // raw history unchanged (own returns-context timeline, not built this round).
          <DeliveryTimeline
            entries={isReturn ? toRawDisplay(shipment.deliveryHistory) : groupHistory(shipment.deliveryHistory)}
            shipmentLeg={shipment.shipmentLeg}
            noHistoryText={t('orderDetail.noDeliveryHistory')}
            title={t('orderDetail.deliveryHistory')}
          />
        )}
      </div>
    </section>
  )
}

function AttemptHistory({ attempts, isReturn }: { attempts: AttemptEntry[]; isReturn?: boolean }) {
  const { t } = useTranslation()
  return (
    <div className="border-t border-line pt-3">
      <p className="text-caption text-muted uppercase tracking-widest mb-2">
        {t('orderDetail.attemptHistory')}
      </p>
      <ol className="space-y-2">
        {attempts.map((a, i) => (
          // border-s-2 = border-inline-start (logical); ps-3 = padding-inline-start
          <li key={i} className="border-s-2 border-line ps-3 space-y-0.5">
            <div className="flex flex-wrap items-baseline gap-2">
              <span className={`text-small font-medium ${a.succeeded ? 'text-success' : 'text-danger'}`}>
                {a.succeeded
                  ? t(isReturn ? 'orderDetail.attemptSucceededReturn' : 'orderDetail.attemptSucceeded')
                  : t('orderDetail.attemptFailed')}
              </span>
              {a.type && (
                <span className="text-caption text-muted">({a.type})</span>
              )}
              {a.attemptDate && (
                <span className="text-caption text-muted">
                  {new Date(a.attemptDate).toLocaleString()}
                </span>
              )}
            </div>
            {(a.courierName || a.courierPhone) && (
              <p className="text-caption text-muted">
                {[a.courierName, a.courierPhone].filter(Boolean).join(' · ')}
              </p>
            )}
            {a.failureReason && (
              <p className="text-caption text-danger">{a.failureReason}</p>
            )}
          </li>
        ))}
      </ol>
    </div>
  )
}

function InfoRow({ label, value, mono }: { label: string; value: string | null | undefined; mono?: boolean }) {
  const { t } = useTranslation()
  return (
    <div className="flex gap-2 items-start">
      <dt className="text-small text-muted w-24 shrink-0">{label}</dt>
      <dd className={`text-small text-primary ${mono ? 'font-mono text-caption' : ''}`}>
        {value ?? <span className="text-muted">{t('common.na')}</span>}
      </dd>
    </div>
  )
}

// ── A4: history collapse (forward leg only) ─────────────────────────────────────
// A normalized display shape both the raw (return-leg) and grouped (forward-leg) paths
// render through, so DeliveryTimeline itself doesn't need to know which one it got.
export interface DisplayHistoryEntry {
  state: string
  count: number
  firstAt: string
  lastAt: string
  exceptionReason: string | null
}

export function toRawDisplay(entries: DeliveryHistoryEntry[]): DisplayHistoryEntry[] {
  return entries.map(e => ({
    state: e.state, count: 1, firstAt: e.occurredAt, lastAt: e.occurredAt,
    exceptionReason: e.exceptionReason,
  }))
}

// §8.4 NDR exception_code → chip map, mirrored from OrderStatusDeriver.NDR_CHIP_KEY —
// reused as-is (same translation keys the health-chip badge already renders), not forked
// into a second copy of the code→meaning mapping. Codes without dedicated copy, or a null
// code (the common case — confirmed 75/75 null in prod today), fall back to a generic
// "Delivery issue" line instead of the raw (always-null) exception_reason text.
const NDR_CHIP_KEY: Record<number, string> = {
  1:  'chip.not_at_address',
  3:  'chip.postponed',
  8:  'chip.customer_refused',
  21: 'chip.postponed',
  22: 'chip.postponed',
}
const EXCEPTION_FALLBACK_KEY = 'orderDetail.historyDeliveryIssue'

function exceptionDisplayKey(exceptionCode: number | null): string {
  return (exceptionCode != null && NDR_CHIP_KEY[exceptionCode]) || EXCEPTION_FALLBACK_KEY
}

/**
 * Folds consecutive identical internal_state rows into one grouped entry
 * ({ state, count, firstAt, lastAt }) — e.g. "In transit · 14 scans · Jul 25–30".
 * Terminals fold like any other state — a duplicate-webhook redelivery of the same
 * terminal is still the same reported state, not a second milestone. Exception rows fold
 * on (state + exception_code) together, so two 'exception' rows only merge when they carry
 * the same code (null == null included) — a code change is a materially different event
 * and starts a new group even though internal_state itself didn't change.
 */
export function groupHistory(entries: DeliveryHistoryEntry[]): DisplayHistoryEntry[] {
  const groups: DisplayHistoryEntry[] = []
  const groupKeys: string[] = []
  for (const e of entries) {
    const key = e.state === 'exception' ? `exception:${e.exceptionCode ?? 'null'}` : e.state
    const lastIdx = groups.length - 1
    const canMerge = lastIdx >= 0 && groupKeys[lastIdx] === key
    if (canMerge) {
      groups[lastIdx].count += 1
      groups[lastIdx].lastAt = e.occurredAt
    } else {
      groups.push({
        state: e.state, count: 1, firstAt: e.occurredAt, lastAt: e.occurredAt,
        exceptionReason: e.state === 'exception' ? exceptionDisplayKey(e.exceptionCode) : null,
      })
      groupKeys.push(key)
    }
  }
  return groups
}

export function formatDateRange(firstAt: string, lastAt: string): string {
  const a = new Date(firstAt)
  const b = new Date(lastAt)
  const fmtDay = (d: Date) => d.toLocaleDateString(undefined, { month: 'short', day: 'numeric' })
  if (a.toDateString() === b.toDateString()) return fmtDay(a)
  if (a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth()) {
    return `${a.toLocaleDateString(undefined, { month: 'short' })} ${a.getDate()}–${b.getDate()}`
  }
  return `${fmtDay(a)} – ${fmtDay(b)}`
}

function DeliveryTimeline({
  entries,
  shipmentLeg,
  noHistoryText,
  title,
}: {
  entries: DisplayHistoryEntry[]
  shipmentLeg?: string
  noHistoryText: string
  title: string
}) {
  const { t } = useTranslation()
  if (entries.length === 0) {
    return <p className="text-small text-muted mt-3">{noHistoryText}</p>
  }
  return (
    <div className="mt-3">
      <p className="text-caption text-muted uppercase tracking-widest mb-2">{title}</p>
      {/*
        border-s = border-inline-start (logical rail, flips to right in RTL)
        ms-2     = margin-inline-start (offsets rail from the start edge)
      */}
      <ol className="relative border-s border-line ms-2 space-y-3">
        {entries.map((e, i) => {
          const label = shipmentLeg === 'return'
            ? t(`delivery.state.return.${e.state}`, { defaultValue: t(`delivery.state.${e.state}`, { defaultValue: e.state.replace(/_/g, ' ') }) })
            : t(`delivery.state.${e.state}`, { defaultValue: e.state.replace(/_/g, ' ') })
          const isLast = i === entries.length - 1
          return (
            // ps-4 = padding-inline-start (indents content from the rail)
            <li key={i} className="ps-4">
              {/*
                -start-1.5 = negative inset-inline-start (centers dot on the inline-start rail)
                border-base = background-color ring that masks the rail behind the dot
                bg-trace-blue for the live/latest entry; bg-line for older entries
              */}
              <span className={`absolute -start-1.5 mt-0.5 h-3 w-3 rounded-full border-2 border-base
                ${isLast ? 'bg-trace-blue' : 'bg-line'}`}
              />
              <div className="flex flex-wrap items-baseline gap-2">
                <span className={`text-small font-medium ${isLast ? 'text-primary' : 'text-muted'}`}>
                  {label}
                </span>
                {e.count > 1 ? (
                  <>
                    <span className="text-caption text-muted">
                      {t('orderDetail.historyGroupScans', { count: e.count })}
                    </span>
                    <span className="text-caption text-muted">{formatDateRange(e.firstAt, e.lastAt)}</span>
                  </>
                ) : (
                  <span className="text-caption text-muted">{new Date(e.firstAt).toLocaleString()}</span>
                )}
              </div>
              {e.exceptionReason && (
                <p className="text-caption text-danger mt-0.5">
                  {t(e.exceptionReason, { defaultValue: e.exceptionReason })}
                </p>
              )}
            </li>
          )
        })}
      </ol>
    </div>
  )
}
