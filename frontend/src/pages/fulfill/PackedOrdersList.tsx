import { useEffect, useRef, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { AlertTriangle, PackageOpen, Truck } from 'lucide-react'
import { getPackedOrders, getRoleFromToken, PackedOrderRow } from '../../api'
import { Alert, Button, Skeleton, cn } from '../../components/ui'
import { UnpackOrderDialog } from './UnpackOrderDialog'

// Unpack-order S2 — "Packed, not picked up" on Pick & Pack, both packing modes (a section on the waybill page,
// the "Packed" tab on the queue page). Which orders are on it is the server's rule (PackedOrderService
// .IN_WAREHOUSE_SQL), never derived here; so is the COD flag.
// S4: owner / manager get "Unpack" on each row (workers see the list, no button — repacking is the normal flow);
// ?order=<id> marks a row (and scrolls to it), ?unpack=1 with it opens that row's dialog (the edit-conflict alert).

const when = (iso: string, lang: string) =>
  new Date(iso).toLocaleString(lang, { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' })

const STATUS_TONE: Record<PackedOrderRow['status'], string> = {
  packed:              'bg-trace-blue/[0.12] text-trace-blue',
  awaiting_pickup:     'bg-success/[0.12] text-success-text',
  self_pickup_pending: 'bg-warning/[0.14] text-warning-text',
}

export function PackedOrdersList({ refreshKey, focusOrderId, onUnpacked }: {
  refreshKey: number
  focusOrderId?: string | null
  /** Called after an unpack, so the page reloads its own lists (queue, batches, printed-not-packed). */
  onUnpacked?: () => void
}) {
  const { t, i18n } = useTranslation()
  const [rows, setRows] = useState<PackedOrderRow[] | null>(null)
  const [reload, setReload] = useState(0)
  const [unpacking, setUnpacking] = useState<PackedOrderRow | null>(null)
  const [done, setDone] = useState<string | null>(null)
  const canUnpack = ['owner', 'manager'].includes(getRoleFromToken() ?? '')
  const [searchParams] = useSearchParams()
  const autoOpen = useRef(searchParams.get('unpack') === '1')
  const focusRef = useRef<HTMLLIElement | null>(null)

  useEffect(() => {
    let cancelled = false
    getPackedOrders()
      .then(r => { if (!cancelled) setRows(Array.isArray(r) ? r : []) })
      .catch(() => { if (!cancelled) setRows([]) })
    return () => { cancelled = true }
  }, [refreshKey, reload])

  // The deep link: scroll to the marked row once it's there, and open its dialog when asked (once).
  useEffect(() => {
    if (!rows || !focusOrderId) return
    focusRef.current?.scrollIntoView?.({ block: 'center' })
    if (autoOpen.current && canUnpack) {
      autoOpen.current = false
      const row = rows.find(r => r.orderId === focusOrderId)
      if (row) setUnpacking(row)
    }
  }, [rows, focusOrderId, canUnpack])

  function statusLabel(r: PackedOrderRow) {
    switch (r.status) {
      case 'awaiting_pickup':     return t('fulfill.packed.statusAwaiting')
      case 'self_pickup_pending': return t('fulfill.packed.statusSelfPickup')
      default:                    return t('fulfill.packed.statusPacked')
    }
  }

  const money = (n: number | null) => (n ?? 0).toLocaleString(i18n.language)

  return (
    <section className="card p-5 space-y-3" data-testid="packed-orders">
      <div className="flex items-center justify-between gap-3">
        <h2 className="text-h3 text-primary">{t('fulfill.packed.title')}</h2>
        {rows && rows.length > 0 && (
          <span className="text-small text-muted">{t('fulfill.packed.count', { count: rows.length })}</span>
        )}
      </div>
      {done && <Alert tone="success" title={done} dismissible onDismiss={() => setDone(null)} />}
      {rows === null ? (
        <Skeleton className="h-24 rounded-xl" />
      ) : rows.length === 0 ? (
        <p className="text-small text-muted py-6 text-center" data-testid="packed-empty">
          {t('fulfill.packed.empty')}
        </p>
      ) : (
        <ul className="divide-y divide-line">
          {rows.map(r => (
            <li key={r.orderId} data-testid="packed-row" data-order-id={r.orderId}
              ref={focusOrderId === r.orderId ? focusRef : undefined}
              className={cn('py-3 flex flex-col gap-2 sm:flex-row sm:items-start sm:gap-3',
                focusOrderId === r.orderId && 'bg-trace-blue/[0.06] rounded-lg px-2 -mx-2')}>
              <div className="min-w-0 flex-1 space-y-1">
                <p className="text-body text-primary">
                  <bdi className="font-mono font-semibold">{r.orderNumber ?? '—'}</bdi>
                  {r.customerName && <span> · {r.customerName}</span>}
                </p>
                <p className="text-small text-muted flex flex-wrap items-center gap-x-2 gap-y-0.5">
                  {r.trackingNumber && (
                    <span className="inline-flex items-center gap-1">
                      <Truck size={13} strokeWidth={2} />
                      <bdi className="font-mono">{r.trackingNumber}</bdi>
                      {r.courier === 'generic' && r.carrierName && <span>· {r.carrierName}</span>}
                    </span>
                  )}
                  <span>{t('fulfill.packed.pieces', { count: r.pieces })}</span>
                  {r.packedAt && (
                    <span>
                      {r.packedByName
                        ? t('fulfill.packed.packedBy', { name: r.packedByName, time: when(r.packedAt, i18n.language) })
                        : t('fulfill.packed.packedAt', { time: when(r.packedAt, i18n.language) })}
                    </span>
                  )}
                </p>
                <div className="flex flex-wrap items-center gap-1.5">
                  <span className={cn('text-caption font-semibold rounded-full px-2.5 py-0.5', STATUS_TONE[r.status])}
                    data-testid={`packed-status-${r.status}`}>
                    {statusLabel(r)}
                  </span>
                  {r.selfPickup && r.status !== 'self_pickup_pending' && (
                    <span className="text-caption font-semibold rounded-full px-2.5 py-0.5 bg-elevated text-muted">
                      {t('fulfill.packed.selfPickup')}
                    </span>
                  )}
                  {r.editConflict && (
                    <span className="text-caption font-semibold rounded-full px-2.5 py-0.5 bg-warning/[0.14] text-warning-text"
                      data-testid="packed-edited">
                      {t('fulfill.packed.edited')}
                    </span>
                  )}
                  {r.pickupSessionId && (
                    <Link to={`/pickups?session=${r.pickupSessionId}`} data-testid="packed-in-pickup"
                      className="text-caption font-semibold rounded-full px-2.5 py-0.5 bg-trace-blue/[0.10] text-trace-blue hover:underline">
                      {r.pickupTimeSlot
                        ? t('fulfill.packed.inPickupSlot', { date: r.pickupDate ?? '', slot: r.pickupTimeSlot })
                        : t('fulfill.packed.inPickup', { date: r.pickupDate ?? '' })}
                    </Link>
                  )}
                </div>
                {r.codMismatch && (
                  <p className="text-small text-warning-text flex items-start gap-1.5" data-testid="packed-cod">
                    <AlertTriangle size={14} strokeWidth={2} className="mt-0.5 flex-shrink-0" />
                    <span>{t('fulfill.packed.cod', { bosta: money(r.bostaCod), order: money(r.orderCod) })}</span>
                  </p>
                )}
              </div>
              {canUnpack && (
                <div className="flex-shrink-0">
                  <Button size="sm" variant="secondary" iconStart={PackageOpen} onClick={() => setUnpacking(r)}>
                    {t('fulfill.unpack.button')}
                  </Button>
                </div>
              )}
            </li>
          ))}
        </ul>
      )}
      {unpacking && (
        <UnpackOrderDialog
          row={unpacking}
          onClose={() => setUnpacking(null)}
          onUnpacked={res => {
            setUnpacking(null)
            setDone(t('fulfill.unpack.done', { number: res.orderNumber ?? unpacking.orderNumber ?? '' }))
            setReload(k => k + 1)
            onUnpacked?.()
          }}
        />
      )}
    </section>
  )
}
