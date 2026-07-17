import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { getOrder, OrderDetail as IOrderDetail, ShipmentDetail, AttemptEntry, DeliveryHistoryEntry } from '../api'
import { Alert, Badge, Button, DeliveryBadge, Skeleton } from '../components/ui'

export default function OrderDetail() {
  const { t } = useTranslation()
  const { id } = useParams<{ id: string }>()
  const [order,   setOrder]   = useState<IOrderDetail | null>(null)
  const [loading, setLoading] = useState(true)
  const [error,   setError]   = useState('')

  useEffect(() => {
    if (!id) return
    getOrder(id)
      .then(setOrder)
      .catch(() => setError(t('common.error')))
      .finally(() => setLoading(false))
  }, [id, t])

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

          <section className="card p-4">
            <h2 className="text-caption text-muted uppercase tracking-widest mb-3">
              {t('orderDetail.status')}
            </h2>
            <dl className="space-y-2">
              <div className="flex items-start gap-2">
                <dt className="text-small text-muted w-24 shrink-0">{t('orderDetail.status')}</dt>
                <dd><Badge status={order.status} /></dd>
              </div>
              {order.onHold && (
                <div className="flex items-center gap-2">
                  <Badge tone="critical" label={t('orderDetail.onHold')} />
                  {order.holdReason && (
                    <span className="text-small text-muted">{order.holdReason}</span>
                  )}
                </div>
              )}
              <InfoRow label={t('orderDetail.payment')}  value={order.paymentMethod} />
              <InfoRow label={t('orderDetail.cod')}      value={order.codAmount != null ? `${order.codAmount.toLocaleString()} EGP` : null} />
              <InfoRow label={t('orderDetail.placedAt')} value={order.placedAt ? new Date(order.placedAt).toLocaleString() : null} />
            </dl>
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
      <h2 className="text-caption text-muted uppercase tracking-widest">
        {isReturn ? t('orderDetail.returnShipment') : t('orderDetail.shipment')}
      </h2>

      {/* Delivery status — prominent */}
      <div className="flex items-start gap-2">
        <dt className="text-small text-muted w-24 shrink-0">{t('orderDetail.status')}</dt>
        <dd>
          <DeliveryBadge
            state={shipment.internalState}
            exceptionReason={shipment.exceptionReason}
            shipmentLeg={shipment.shipmentLeg}
          />
        </dd>
      </div>

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

      {/* Per-attempt history */}
      {shipment.attempts.length > 0 && (
        <AttemptHistory attempts={shipment.attempts} />
      )}

      {/* Expandable delivery status timeline */}
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
          <DeliveryTimeline
            entries={shipment.deliveryHistory}
            shipmentLeg={shipment.shipmentLeg}
            noHistoryText={t('orderDetail.noDeliveryHistory')}
            title={t('orderDetail.deliveryHistory')}
          />
        )}
      </div>
    </section>
  )
}

function AttemptHistory({ attempts }: { attempts: AttemptEntry[] }) {
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
                {a.succeeded ? t('orderDetail.attemptSucceeded') : t('orderDetail.attemptFailed')}
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

function DeliveryTimeline({
  entries,
  shipmentLeg,
  noHistoryText,
  title,
}: {
  entries: DeliveryHistoryEntry[]
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
              <div className="flex items-baseline gap-2">
                <span className={`text-small font-medium ${isLast ? 'text-primary' : 'text-muted'}`}>
                  {label}
                </span>
                <span className="text-caption text-muted">
                  {new Date(e.occurredAt).toLocaleString()}
                </span>
              </div>
              {e.exceptionReason && (
                <p className="text-caption text-danger mt-0.5">{e.exceptionReason}</p>
              )}
            </li>
          )
        })}
      </ol>
    </div>
  )
}
