import { ReactNode, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Check } from 'lucide-react'
import { bookExchangeNow, ReturnRequestDetail } from '../../api'
import { Button, cn, useToast } from '../../components/ui'
import { dateTimeLabel } from './requestFormat'
import { CloseDialog } from './RequestLifecycle'
import { BookingState } from './ReturnRequestDrawer'

/**
 * Step 5c (mockup X5) — an approved exchange request: what goes out, the Bosta exchange trip,
 * and its progress, derived from the request, the Traced exchange (internal replacement order +
 * its forward leg) and the old item's intake. Nothing here is stored as a stage of its own.
 *
 * Approved → Exchange trip booked (AWB) → Replacement being packed → Replacement with courier →
 * Swapped at the door → Old item scanned in → Exchanged.
 */
type StepState = 'done' | 'current' | 'todo'
type Step = { key: string; label: string; state: StepState; meta?: ReactNode }

const OPEN = ['approved', 'pickup_booked', 'received']
const PACKED_ORDER = ['packed', 'awaiting_pickup', 'with_courier', 'delivered']
const LEFT_SHIPMENT = ['with_courier', 'delivered', 'returning', 'returned', 'exception', 'lost']

export default function ExchangeProgressView({
  detail, onReload,
}: {
  detail: ReturnRequestDetail
  onReload: () => Promise<void>
}) {
  const { t, i18n } = useTranslation()
  const { toast } = useToast()
  const [booking, setBooking] = useState(false)
  const [closing, setClosing] = useState(false)
  const item = detail.items[0]
  const ex = detail.exchange ?? null
  const open = OPEN.includes(detail.status)
  const history = detail.history ?? []
  const eventAt = (type: string) => history.find(e => e.type === type)?.occurredAt ?? null
  const when = (iso: string | null | undefined) => (iso ? dateTimeLabel(i18n.language, iso) : null)

  const booked = ex != null || detail.bookingStatus === 'booked' || detail.bookingStatus === 'needs_review'
  const packed = ex != null && (PACKED_ORDER.includes(ex.orderStatus ?? '') || LEFT_SHIPMENT.includes(ex.shipmentState ?? ''))
  const withCourier = ex != null && (ex.withCourierAt != null || LEFT_SHIPMENT.includes(ex.shipmentState ?? ''))
  const swapped = ex != null && ex.shipmentState === 'delivered'
  const scanned = item?.itemStatus === 'arrived' || item?.itemStatus === 'done'
  const exchanged = detail.status === 'exchanged'

  const flags: Array<[string, boolean, ReactNode?]> = [
    ['approved', detail.decidedAt != null, [when(detail.decidedAt), detail.decidedByName].filter(Boolean).join(' · ')],
    ['booked', booked, booked
      ? [when(eventAt('pickup_booked')), t('exchangesRefunds.requests.exchange.progress.byTraced')].filter(Boolean).join(' · ')
      : undefined],
    ['packing', packed],
    ['withCourier', withCourier, when(ex?.withCourierAt)],
    ['swapped', swapped, when(ex?.deliveredAt)],
    ['scanned', scanned, when(item?.arrivedAt)],
    ['exchanged', exchanged, when(eventAt('exchanged'))],
  ]
  const firstTodo = open ? flags.findIndex(f => !f[1]) : -1
  const steps: Step[] = flags.map(([key, done, meta], i) => ({
    key,
    label: t(`exchangesRefunds.requests.exchange.progress.${key}`),
    state: done ? 'done' : i === firstTodo ? 'current' : 'todo',
    meta: done ? (meta || undefined)
      : i === firstTodo && key === 'packing' && ex != null ? t('exchangesRefunds.requests.exchange.progress.packingNow')
        : undefined,
  }))

  async function bookNow() {
    setBooking(true)
    try {
      await bookExchangeNow(detail.id)
      toast({ tone: 'success', message: t('exchangesRefunds.requests.exchange.progress.bookNowDone') })
    } catch {
      toast({ tone: 'error', message: t('exchangesRefunds.requests.drawer.actionFailed') })
    } finally {
      setBooking(false)
      await onReload()
    }
  }

  const bookingProblem = detail.bookingStatus != null && detail.bookingStatus !== 'booked'

  return (
    <>
      <div className="flex-1 overflow-y-auto p-6 space-y-5" data-testid="exchange-progress-body">
        {item && (
          <section className="rounded-xl border border-line bg-elevated px-4 py-3 space-y-1" data-testid="exchange-summary-card">
            <p className="text-body font-semibold text-primary">
              <bdi>{item.productTitle}</bdi>
              {item.variantTitle && <> · <bdi>{item.variantTitle}</bdi></>}
              {' '}<span aria-hidden="true" className="inline-block rtl:rotate-180">→</span>{' '}
              <bdi>{item.replacementVariantTitle}</bdi>
            </p>
            {(detail.bostaTrackingNumber || ex?.orderNumber) && (
              <p className="text-small text-secondary" data-testid="exchange-trip-line">
                {detail.bostaTrackingNumber && (
                  <>
                    {t('exchangesRefunds.requests.exchange.progress.trip')}{' '}
                    <bdi className="font-mono" dir="ltr">AWB {detail.bostaTrackingNumber}</bdi>
                  </>
                )}
                {detail.bostaTrackingNumber && ex?.orderNumber && ' · '}
                {ex?.orderNumber && (
                  <>
                    {t('exchangesRefunds.requests.exchange.progress.order')}{' '}
                    <bdi className="font-mono" dir="ltr" data-testid="exchange-order-number">{ex.orderNumber}</bdi>
                  </>
                )}
              </p>
            )}
          </section>
        )}

        {detail.bookNowAvailable && (
          <div className="rounded-lg border border-info/30 bg-info/[0.08] px-4 py-3 flex items-center gap-3" data-testid="book-now">
            <p className="text-small text-primary flex-1 min-w-0">{t('exchangesRefunds.requests.exchange.progress.bookNowHint')}</p>
            <Button size="sm" loading={booking} disabled={booking} onClick={bookNow}>
              {t('exchangesRefunds.requests.exchange.progress.bookNow')}
            </Button>
          </div>
        )}

        {bookingProblem && (
          <section className="text-body" data-testid="exchange-booking">
            <h3 className="text-body font-semibold text-primary mb-1.5">{t('exchangesRefunds.requests.exchange.progress.bookingLabel')}</h3>
            {detail.bookingStatus === 'pending'
              ? <p className="text-secondary">{t('exchangesRefunds.requests.drawer.bookingPending')}</p>
              : <BookingState detail={detail} onChanged={onReload} />}
          </section>
        )}

        {detail.status === 'closed' && detail.closeReason && (
          <section data-testid="request-close-reason">
            <h3 className="text-body font-semibold text-primary mb-1">{t('exchangesRefunds.requests.drawer.closeReason')}</h3>
            <p className="text-body text-primary">{t(`exchangesRefunds.requests.closeReasons.${detail.closeReason}`)}</p>
            {detail.closeNote && <p className="text-body text-secondary whitespace-pre-wrap mt-1" dir="auto">{detail.closeNote}</p>}
          </section>
        )}

        <section>
          <h3 className="text-body font-semibold text-primary mb-3">{t('exchangesRefunds.requests.exchange.progress.title')}</h3>
          <ol className="space-y-0" data-testid="exchange-steps">
            {steps.map((s, i) => (
              <li key={s.key} className="flex gap-3" data-testid={`exchange-step-${s.key}`} data-state={s.state}>
                <div className="flex flex-col items-center">
                  <span className={cn('w-[22px] h-[22px] rounded-full flex items-center justify-center flex-shrink-0',
                    s.state === 'done' && 'bg-success text-white',
                    s.state === 'current' && 'border-2 border-accent',
                    s.state === 'todo' && 'border-2 border-line')} aria-hidden="true">
                    {s.state === 'done' && <Check size={13} strokeWidth={3} />}
                  </span>
                  {i < steps.length - 1 && (
                    <span className={cn('w-0.5 flex-1 min-h-[14px]', s.state === 'done' ? 'bg-success' : 'bg-line')} aria-hidden="true" />
                  )}
                </div>
                <div className="pb-3 min-w-0 -mt-0.5">
                  <p className={cn('text-body',
                    s.state === 'done' && 'font-semibold text-primary',
                    s.state === 'current' && 'font-semibold text-accent',
                    s.state === 'todo' && 'text-secondary')}>
                    {s.label}
                    {s.state !== 'todo' && <span className="sr-only"> — {t(`exchangesRefunds.requests.exchange.progress.state.${s.state}`)}</span>}
                  </p>
                  {s.meta && <p className="text-small text-muted"><bdi>{s.meta}</bdi></p>}
                </div>
              </li>
            ))}
          </ol>
        </section>
      </div>

      {open && (
        <div className="p-5 border-t border-line flex justify-end" data-testid="close-footer">
          <Button variant="outline" className="!border-critical/40 !text-critical hover:!bg-critical/5" onClick={() => setClosing(true)}>
            {t('exchangesRefunds.requests.drawer.closeRequest')}
          </Button>
        </div>
      )}
      {closing && open && <CloseDialog detail={detail} onClose={() => setClosing(false)} onDone={onReload} />}
    </>
  )
}
