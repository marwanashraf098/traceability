import { ReactNode, useState } from 'react'
import { useTranslation } from 'react-i18next'
import ItemPhotos from './ItemPhotos'
import { AlertCircle, ArrowRight } from 'lucide-react'
import { approveReturnRequest, switchExchangeToRefund, ReturnRequestDetail } from '../../api'
import { Button, cn, useToast } from '../../components/ui'
import { reasonLabel, shortCustomerName } from './requestFormat'
import { PieceCode } from './RequestLifecycle'
import CustomAddressBlock, { PiiRemovedNote } from './CustomAddressBlock'

/**
 * Step 5b — the request drawer for an exchange (mockups X4 / X6): what comes back → what goes
 * out, with the replacement's live stock (VariantStockService at render time).
 *
 * X4 (in stock): Customer / Order / Area, the swap card, the customer's note, the refund-fallback
 * line, and Reject / Approve exchange. The approve line says approving books one Bosta exchange
 * trip (Step 5c — the booking itself runs after the approval commits).
 * X6 (sold out): the swap card with "Out of stock", a warning, and — only when the customer agreed
 * to a refund — "Switch to refund and approve"; otherwise Reject only.
 *
 * Approve is re-checked server-side; a 409 means the replacement sold out meanwhile (or someone
 * decided first) — the drawer reloads and shows whichever state is now true.
 */
export default function ExchangeRequestView({
  detail, onReload, onReject, footerExtra,
}: {
  detail: ReturnRequestDetail
  onReload: () => Promise<void>
  onReject: () => void
  footerExtra?: ReactNode
}) {
  const { t, i18n } = useTranslation()
  const { toast } = useToast()
  const [busy, setBusy] = useState<'approve' | 'switch' | null>(null)
  const item = detail.items[0]
  const requested = detail.status === 'requested'
  const inStock = item?.replacementInStock !== false
  const soldOut = requested && !inStock
  const area = (i18n.language === 'ar' ? detail.pickupDistrictNameAr || detail.pickupDistrictName : detail.pickupDistrictName)
    ? [detail.pickupCityName, i18n.language === 'ar' ? detail.pickupDistrictNameAr || detail.pickupDistrictName : detail.pickupDistrictName].filter(Boolean).join(' · ')
    : [detail.pickupCity, detail.pickupZone].filter(Boolean).join(' · ')

  async function approve() {
    setBusy('approve')
    try {
      await approveReturnRequest(detail.id)
      toast({ tone: 'success', message: t('exchangesRefunds.requests.exchange.approved') })
    } catch (e) {
      const conflict = e instanceof Error && e.message.startsWith('409')
      toast({ tone: conflict ? 'warning' : 'error', message: t(conflict
        ? 'exchangesRefunds.requests.exchange.approveConflict' : 'exchangesRefunds.requests.drawer.actionFailed') })
    } finally {
      setBusy(null)
      await onReload()
    }
  }

  async function switchToRefund() {
    setBusy('switch')
    try {
      await switchExchangeToRefund(detail.id)
      toast({ tone: 'success', message: t('exchangesRefunds.requests.exchange.switched') })
    } catch {
      toast({ tone: 'error', message: t('exchangesRefunds.requests.drawer.actionFailed') })
    } finally {
      setBusy(null)
      await onReload()
    }
  }

  if (!item) return null

  return (
    <>
      <div className="flex-1 overflow-y-auto p-6 space-y-5" data-testid="exchange-drawer-body">
        {!soldOut && (
          <dl className="grid grid-cols-3 gap-x-5 gap-y-3">
            <Field label={t('exchangesRefunds.requests.drawer.customer')}>
              <span className="font-semibold"><bdi>{shortCustomerName(detail.customerName)}</bdi></span>
            </Field>
            <Field label={t('exchangesRefunds.requests.drawer.order')}>
              <span className="font-semibold"><bdi>{detail.orderNumber}</bdi></span>
            </Field>
            {area && (
              <Field label={t('exchangesRefunds.requests.exchange.area')}>
                <bdi>{area}</bdi>
                <CustomAddressBlock detail={detail} />
              </Field>
            )}
            <PiiRemovedNote detail={detail} />
          </dl>
        )}

        <section className="rounded-xl border border-line p-4 space-y-3" data-testid="exchange-swap">
          <p className="text-body font-semibold text-primary">
            <bdi>{item.productTitle}</bdi> · {reasonLabel(t, item.reasonCode)}
          </p>
          <ItemPhotos requestId={detail.id} item={item} />
          <div className="flex items-stretch gap-3">
            <div className="flex-1 rounded-lg bg-elevated px-3 py-2.5" data-testid="exchange-coming-back">
              <p className="text-caption text-muted">{t('exchangesRefunds.requests.exchange.comingBack')}</p>
              <p className="text-body font-semibold text-primary"><bdi>{item.variantTitle}</bdi></p>
              {!soldOut && <p className="text-small text-muted"><PieceCode item={item} /></p>}
            </div>
            <div className="flex items-center text-muted" aria-hidden="true">
              <ArrowRight size={18} className="rtl:rotate-180" />
            </div>
            <div
              className={cn('flex-1 rounded-lg px-3 py-2.5', inStock ? 'bg-info/[0.10]' : 'bg-critical/[0.10]')}
              data-testid="exchange-going-out"
            >
              <p className={cn('text-caption', inStock ? 'text-muted' : 'text-critical-text')}>
                {t('exchangesRefunds.requests.exchange.goingOut')}
              </p>
              <p className="text-body font-semibold text-primary"><bdi>{item.replacementVariantTitle}</bdi></p>
              <p className={cn('text-small', inStock ? 'text-success-text' : 'text-critical-text font-semibold')}
                data-testid="exchange-stock">
                {inStock
                  ? t('exchangesRefunds.requests.exchange.inStock', { count: item.replacementAvailable ?? 0 })
                  : t('exchangesRefunds.requests.exchange.outOfStock')}
              </p>
            </div>
          </div>
        </section>

        {soldOut && (
          <div className="flex gap-3 rounded-lg px-4 py-3.5 border border-warning/30 bg-warning/[0.08]" data-testid="exchange-sold-out">
            <AlertCircle size={18} strokeWidth={2} className="flex-shrink-0 mt-0.5 text-warning-text" />
            <div className="min-w-0 space-y-1">
              <p className="text-body font-semibold text-warning-text">
                {t('exchangesRefunds.requests.exchange.soldOutTitle', { variant: item.replacementVariantTitle ?? '' })}
              </p>
              <p className="text-body text-primary">
                {detail.refundFallbackOk
                  ? t('exchangesRefunds.requests.exchange.soldOutFallback', { variant: item.variantTitle ?? '' })
                  : t('exchangesRefunds.requests.exchange.soldOutNoFallback')}
              </p>
            </div>
          </div>
        )}

        {!soldOut && detail.note && (
          <section>
            <h3 className="text-body font-semibold text-primary mb-2">{t('exchangesRefunds.requests.drawer.note')}</h3>
            <p className="text-body text-primary whitespace-pre-wrap rounded-lg bg-elevated px-3.5 py-3" dir="auto">{detail.note}</p>
          </section>
        )}

        {!soldOut && detail.refundFallbackOk && (
          <p className="text-small text-secondary" data-testid="exchange-fallback">
            {t('exchangesRefunds.requests.exchange.fallback')}
          </p>
        )}
      </div>

      {requested && !footerExtra && (
        <div className="px-6 py-4 border-t border-line space-y-3" data-testid="exchange-footer">
          {!soldOut && (
            <p className="text-small text-secondary" data-testid="exchange-approve-helper">
              {t('exchangesRefunds.requests.exchange.approveHelper')}
            </p>
          )}
          <div className="flex gap-3">
            <Button variant="outline" className="!border-critical/40 !text-critical hover:!bg-critical/5 px-6"
              disabled={busy != null} onClick={onReject}>
              {t('exchangesRefunds.requests.drawer.reject')}
            </Button>
            {!soldOut && (
              <Button className="flex-1" loading={busy === 'approve'} disabled={busy != null} onClick={approve}>
                {t('exchangesRefunds.requests.exchange.approve')}
              </Button>
            )}
            {soldOut && detail.refundFallbackOk && (
              <Button className="flex-1" loading={busy === 'switch'} disabled={busy != null} onClick={switchToRefund}>
                {t('exchangesRefunds.requests.exchange.switch')}
              </Button>
            )}
          </div>
        </div>
      )}
      {footerExtra}
    </>
  )
}

function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="min-w-0">
      <dt className="text-caption text-muted mb-0.5">{label}</dt>
      <dd className="text-body text-primary">{children}</dd>
    </div>
  )
}
