import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { getOrder, OrderDetail as IOrderDetail } from '../api'

const PIECE_STATUS_COLOR: Record<string, string> = {
  available:  'bg-green-100 text-green-700',
  reserved:   'bg-yellow-100 text-yellow-700',
  packed:     'bg-purple-100 text-purple-700',
  with_courier: 'bg-sky-100 text-sky-700',
  delivered:  'bg-green-200 text-green-800',
}

export default function OrderDetail() {
  const { t } = useTranslation()
  const { id } = useParams<{ id: string }>()
  const [order, setOrder] = useState<IOrderDetail | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')

  useEffect(() => {
    if (!id) return
    getOrder(id)
      .then(setOrder)
      .catch(() => setError(t('common.error')))
      .finally(() => setLoading(false))
  }, [id, t])

  if (loading) return <p className="text-gray-500 text-sm">{t('common.loading')}</p>
  if (error)   return <p className="text-red-600 text-sm">{error}</p>
  if (!order)  return null

  const addr = order.address
    ? Object.values(order.address).filter(Boolean).join(', ')
    : null

  return (
    <div>
      <div className="mb-6">
        <Link to="/orders" className="text-sm text-indigo-600 hover:underline">
          {t('orderDetail.back')}
        </Link>
        <h1 className="text-xl font-semibold text-gray-900 mt-2">
          {t('orderDetail.title')} {order.number}
        </h1>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">

        {/* Left column: customer + order info */}
        <div className="lg:col-span-1 space-y-4">
          <section className="bg-white shadow rounded-lg p-4">
            <h2 className="text-sm font-semibold text-gray-700 mb-3 uppercase tracking-wide">
              {t('orderDetail.customer')}
            </h2>
            <dl className="space-y-2 text-sm">
              <Row label={t('orderDetail.customer')} value={order.customerName} />
              <Row label={t('orderDetail.phone')}    value={order.customerPhone} />
              <Row label={t('orderDetail.address')}  value={addr} />
            </dl>
          </section>

          <section className="bg-white shadow rounded-lg p-4">
            <h2 className="text-sm font-semibold text-gray-700 mb-3 uppercase tracking-wide">
              {t('orderDetail.status')}
            </h2>
            <dl className="space-y-2 text-sm">
              <Row label={t('orderDetail.status')}  value={order.status.replace(/_/g, ' ')} />
              {order.onHold && (
                <div className="flex items-center gap-2">
                  <span className="text-xs font-bold bg-red-100 text-red-700 px-2 py-0.5 rounded">
                    {t('orderDetail.onHold')}
                  </span>
                  {order.holdReason && <span className="text-xs text-gray-500">{order.holdReason}</span>}
                </div>
              )}
              <Row label={t('orderDetail.payment')} value={order.paymentMethod} />
              <Row label={t('orderDetail.cod')}     value={order.codAmount != null ? `${order.codAmount.toLocaleString()} EGP` : null} />
              <Row label={t('orderDetail.placedAt')} value={order.placedAt ? new Date(order.placedAt).toLocaleString() : null} />
            </dl>
          </section>

          {order.shipment && (
            <section className="bg-white shadow rounded-lg p-4">
              <h2 className="text-sm font-semibold text-gray-700 mb-3 uppercase tracking-wide">
                {t('orderDetail.shipment')}
              </h2>
              <dl className="space-y-2 text-sm">
                <Row label={t('orderDetail.tracking')} value={order.shipment.trackingNumber} mono />
                <Row label={t('orderDetail.provider')} value={order.shipment.provider} />
                <Row label={t('orderDetail.status')}   value={order.shipment.internalState.replace(/_/g, ' ')} />
                <Row label={t('orderDetail.attempts')} value={String(order.shipment.numberOfAttempts)} />
                {order.shipment.awbUrl && (
                  <div className="flex gap-2 items-start">
                    <dt className="text-gray-500 w-24 shrink-0">{t('orderDetail.awb')}</dt>
                    <dd>
                      <a href={order.shipment.awbUrl} target="_blank" rel="noreferrer"
                         className="text-indigo-600 hover:underline">Download</a>
                    </dd>
                  </div>
                )}
              </dl>
            </section>
          )}
        </div>

        {/* Right column: order items */}
        <div className="lg:col-span-2 space-y-4">
          <h2 className="text-sm font-semibold text-gray-700 uppercase tracking-wide">
            {t('orderDetail.items')}
          </h2>
          {order.items.map(item => (
            <div key={item.id} className="bg-white shadow rounded-lg p-4">
              <div className="flex items-start justify-between mb-3">
                <div>
                  <p className="font-medium text-gray-900">{item.productTitle}</p>
                  <p className="text-sm text-gray-500">{item.variantTitle}</p>
                  {item.sku && <p className="text-xs font-mono text-gray-400 mt-0.5">{item.sku}</p>}
                </div>
                <span className="text-sm font-medium text-gray-700">×{item.quantity}</span>
              </div>

              <div>
                <p className="text-xs font-medium text-gray-500 uppercase tracking-wide mb-2">
                  {t('orderDetail.pieces')}
                </p>
                {item.allocatedPieces.length === 0 ? (
                  <p className="text-xs text-gray-400">{t('orderDetail.noPieces')}</p>
                ) : (
                  <div className="flex flex-wrap gap-2">
                    {item.allocatedPieces.map(p => (
                      <div key={p.pieceId} className="flex items-center gap-1.5 border border-gray-200 rounded px-2 py-1">
                        <span className="font-mono text-xs text-gray-700">{p.barcode}</span>
                        <span className={`text-xs px-1.5 py-0.5 rounded-full font-medium ${PIECE_STATUS_COLOR[p.status] ?? 'bg-gray-100 text-gray-600'}`}>
                          {p.status}
                        </span>
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

function Row({ label, value, mono }: { label: string; value: string | null | undefined; mono?: boolean }) {
  const { t } = useTranslation()
  return (
    <div className="flex gap-2 items-start">
      <dt className="text-gray-500 w-24 shrink-0">{label}</dt>
      <dd className={`text-gray-900 ${mono ? 'font-mono text-xs' : ''}`}>
        {value ?? t('common.na')}
      </dd>
    </div>
  )
}
