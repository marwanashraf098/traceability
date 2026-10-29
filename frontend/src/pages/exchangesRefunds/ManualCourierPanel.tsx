import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Button, cn, useToast } from '../../components/ui'
import { ReturnRequestDetail, TransferCommandError, listGenericCarriers, setManualCourier } from '../../api'

/**
 * Non-Bosta couriers G4a — the drawer's "Book the pickup with <carrier>" block, in place of the Bosta booking
 * row, for a request whose order went out with another courier (detail.otherCourier). Traced books nothing:
 * the merchant books their courier, records which one, and — when they have it — the return's tracking
 * number, which creates its return parcel (scanned back in Scan returns). The tracking number is optional;
 * without it the return is scanned in by its pieces.
 *
 * G4b — an exchange: "Book the exchange with <carrier>". The replacement's OUTBOUND waybill (required to ship —
 * it makes the replacement pickable; packs by waybill scan, leaves at Pickups) and an optional RETURN waybill for
 * the old item, which must differ from the outbound one. Each can be entered once, in one save or two.
 */
export default function ManualCourierPanel({ detail, onChanged }: {
  detail: ReturnRequestDetail
  onChanged: () => Promise<void>
}) {
  const { t, i18n } = useTranslation()
  const { toast } = useToast()
  const other = detail.otherCourier!
  const exchange = detail.type === 'exchange'
  const outboundSet = other.replacement?.outboundTrackingNumber ?? null
  // C2 — an exchange made in Lookup: the replacement is in hand, so its waybill hands it to the courier at once.
  // Its outbound waybill can still be entered after the old item came back ('exchanged'); nothing else can then.
  const lookup = other.replacement?.lookupExchange ?? null
  const lateOutbound = !!lookup && detail.status === 'exchanged' && !outboundSet && !lookup.cancelled
  const returnSet = detail.returnTrackingNumber ?? null
  const current = other.carrierName ?? other.forwardCarrierName ?? ''
  const [carrier, setCarrier] = useState(current)
  const [tracking, setTracking] = useState('')
  const [outbound, setOutbound] = useState('')
  const [carriers, setCarriers] = useState<string[]>([])
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const editable = detail.bookingStatus === 'manual' && !lookup?.cancelled && (exchange
    ? ((detail.status === 'approved' || detail.status === 'pickup_booked') && (!outboundSet || !returnSet)) || lateOutbound
    : detail.status === 'approved' && !detail.returnShipmentId)
  const askOutbound = exchange && !outboundSet
  const askReturn = !returnSet && !lateOutbound

  useEffect(() => {
    if (!editable) return
    let cancelled = false
    listGenericCarriers()
      .then(list => { if (!cancelled) setCarriers(list.map(c => c.carrier)) })
      .catch(() => {})
    return () => { cancelled = true }
  }, [editable])

  useEffect(() => { setCarrier(current) }, [current])

  async function save() {
    const c = carrier.trim()
    if (!c) { setError(t('exchangesRefunds.requests.drawer.manual.carrierRequired')); return }
    setBusy(true)
    setError(null)
    try {
      const r = await setManualCourier(detail.id, c, (askReturn && tracking.trim()) || undefined,
        (askOutbound && outbound.trim()) || undefined)
      toast({ tone: 'success', message: t(
        r.outcome === 'OUTBOUND_CREATED' || r.outcome === 'LEGS_CREATED'
          ? (lookup ? 'exchangesRefunds.requests.drawer.manual.savedOutboundLookup' : 'exchangesRefunds.requests.drawer.manual.savedOutbound')
          : r.outcome === 'LEG_CREATED'
            ? 'exchangesRefunds.requests.drawer.manual.savedWithTracking'
            : 'exchangesRefunds.requests.drawer.manual.savedCarrier', { carrier: r.carrierName }) })
      setTracking('')
      setOutbound('')
      await onChanged()
    } catch (e) {
      setError(e instanceof TransferCommandError
        ? (i18n.language === 'ar' ? e.messageAr : e.messageEn)
        : t('exchangesRefunds.requests.drawer.bookingActionFailed'))
    } finally {
      setBusy(false)
    }
  }

  const shownCarrier = current || t('exchangesRefunds.requests.drawer.manual.theirCourier')
  const suggestions = Array.from(new Set([other.forwardCarrierName, other.carrierName, ...carriers]
    .filter((v): v is string => !!v)))
  const saveKey = (askOutbound && outbound.trim())
    ? (lookup ? 'exchangesRefunds.requests.drawer.manual.saveOutboundLookup' : 'exchangesRefunds.requests.drawer.manual.saveOutbound')
    : (askReturn && tracking.trim()) ? 'exchangesRefunds.requests.drawer.manual.saveWithTracking'
      : 'exchangesRefunds.requests.drawer.manual.saveCarrier'

  return (
    <div className="col-span-2 min-w-0 space-y-3" data-testid="manual-courier">
      <div>
        <dt className="text-caption text-muted mb-0.5">
          {t(exchange ? 'exchangesRefunds.requests.drawer.manual.labelExchange' : 'exchangesRefunds.requests.drawer.manual.label')}
        </dt>
        <dd className="text-body text-primary space-y-0.5">
          {editable && (
            <p data-testid="manual-courier-title">
              {t(exchange ? 'exchangesRefunds.requests.drawer.manual.titleExchange' : 'exchangesRefunds.requests.drawer.manual.title',
                { carrier: shownCarrier })}
            </p>
          )}
          {exchange && outboundSet && (
            <p data-testid="manual-courier-outbound">
              {t('exchangesRefunds.requests.drawer.manual.outboundWith', { carrier: shownCarrier })}{' '}
              <bdi className="font-mono" dir="ltr">{outboundSet}</bdi>
            </p>
          )}
          {!editable && (returnSet ? (
            <p data-testid="manual-courier-booked">
              {t(exchange ? 'exchangesRefunds.requests.drawer.manual.returnWith' : 'exchangesRefunds.requests.drawer.manual.bookedWith',
                { carrier: shownCarrier })}{' '}
              <bdi className="font-mono" dir="ltr">{returnSet}</bdi>
            </p>
          ) : (
            <p data-testid="manual-courier-booked">
              {t(exchange ? 'exchangesRefunds.requests.drawer.manual.noReturnWaybill' : 'exchangesRefunds.requests.drawer.manual.bookedNoTracking',
                { carrier: shownCarrier })}
            </p>
          ))}
          {editable && exchange && returnSet && (
            <p data-testid="manual-courier-booked">
              {t('exchangesRefunds.requests.drawer.manual.returnWith', { carrier: shownCarrier })}{' '}
              <bdi className="font-mono" dir="ltr">{returnSet}</bdi>
            </p>
          )}
        </dd>
      </div>

      {editable && (
        <div className="rounded-md border border-line p-3 space-y-3" data-testid="manual-courier-form">
          <p className="text-small text-muted">
            {t(lookup ? 'exchangesRefunds.requests.drawer.manual.helpExchangeLookup'
              : exchange ? 'exchangesRefunds.requests.drawer.manual.helpExchange' : 'exchangesRefunds.requests.drawer.manual.help')}
          </p>
          <div className="text-small" data-testid="manual-courier-pickup">
            <p className="text-caption text-muted">
              {t(exchange ? 'exchangesRefunds.requests.drawer.manual.deliverTo' : 'exchangesRefunds.requests.drawer.manual.collectFrom')}
            </p>
            {other.pickup.name && <p className="text-primary"><bdi>{other.pickup.name}</bdi></p>}
            {other.pickup.phone && <p className="text-primary font-mono" dir="ltr"><bdi>{other.pickup.phone}</bdi></p>}
            {other.pickup.addressLines.map((l, i) => <p key={i} className="text-primary break-words"><bdi>{l}</bdi></p>)}
          </div>
          <div className="grid gap-3 sm:grid-cols-2">
            <div className="min-w-0 space-y-1">
              <label htmlFor="manual-carrier" className="text-small font-medium text-primary block">
                {t('exchangesRefunds.requests.drawer.manual.carrierLabel')}
              </label>
              <input id="manual-carrier" className="input w-full" list="manual-carrier-options" autoComplete="off"
                maxLength={80} value={carrier} onChange={e => { setCarrier(e.target.value); setError(null) }} />
              <datalist id="manual-carrier-options">
                {suggestions.map(c => <option key={c} value={c} />)}
              </datalist>
            </div>
            {askOutbound && (
              <div className="min-w-0 space-y-1">
                <label htmlFor="manual-outbound" className="text-small font-medium text-primary block">
                  {t('exchangesRefunds.requests.drawer.manual.outboundLabel')}
                </label>
                <input id="manual-outbound" className={cn('input w-full font-mono', error && 'border-critical')} dir="ltr"
                  autoComplete="off" maxLength={100} value={outbound} aria-invalid={!!error}
                  onChange={e => { setOutbound(e.target.value); setError(null) }} />
              </div>
            )}
            {askReturn && (
              <div className="min-w-0 space-y-1">
                <label htmlFor="manual-tracking" className="text-small font-medium text-primary block">
                  {t(exchange ? 'exchangesRefunds.requests.drawer.manual.returnLabel' : 'exchangesRefunds.requests.drawer.manual.trackingLabel')}{' '}
                  <span className="font-normal text-muted">{t('exchangesRefunds.requests.drawer.manual.optional')}</span>
                </label>
                <input id="manual-tracking" className={cn('input w-full font-mono', error && 'border-critical')} dir="ltr"
                  autoComplete="off" maxLength={100} value={tracking} aria-invalid={!!error}
                  onChange={e => { setTracking(e.target.value); setError(null) }} />
              </div>
            )}
          </div>
          {error && <p className="text-small text-critical" role="alert" data-testid="manual-courier-error">{error}</p>}
          <div className="flex justify-end">
            <Button size="sm" loading={busy} disabled={busy} onClick={save}>{t(saveKey)}</Button>
          </div>
        </div>
      )}
    </div>
  )
}
