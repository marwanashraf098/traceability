import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Alert, Button, Spinner, useToast } from '../../components/ui'
import { ReturnRequestDetail, TransferCommandError, cancelLookupExchange } from '../../api'

/**
 * C2 Lookup Exchange — the drawer's block for an exchange made in Lookup (otherCourier.replacement.lookupExchange):
 * the reserved replacement piece and, until it leaves, "Reserved for this exchange — no waybill yet" plus Cancel
 * exchange (a short confirm). After it left there is no undo. The waybill itself is entered in ManualCourierPanel.
 */
export default function LookupExchangePanel({ detail, onChanged }: {
  detail: ReturnRequestDetail
  onChanged: () => Promise<void>
}) {
  const { t, i18n } = useTranslation()
  const { toast } = useToast()
  const rep = detail.otherCourier?.replacement
  const lx = rep?.lookupExchange
  const [confirming, setConfirming] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  if (!rep || !lx) return null
  const code = lx.pieceShortCode ?? ''

  async function cancel() {
    setBusy(true)
    setError(null)
    try {
      await cancelLookupExchange(detail.id)
      toast({ tone: 'success', message: t('lookupExchange.drawer.cancelled') })
      setConfirming(false)
      await onChanged()
    } catch (e) {
      setError(e instanceof TransferCommandError
        ? (i18n.language === 'ar' ? e.messageAr : e.messageEn)
        : t('lookupExchange.drawer.cancelFailed'))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="col-span-2 min-w-0 space-y-3" data-testid="lookup-exchange">
      <div>
        <dt className="text-caption text-muted mb-0.5">{t('lookupExchange.drawer.label')}</dt>
        <dd className="text-body text-primary">
          {t('lookupExchange.drawer.order')}{' '}<bdi className="font-mono" dir="ltr">{rep.orderNumber}</bdi>
          {code && <>{' · '}<bdi className="font-mono" dir="ltr">{code}</bdi></>}
        </dd>
      </div>

      {lx.cancelled ? (
        <p className="text-small text-muted" data-testid="lookup-exchange-cancelled">{t('lookupExchange.drawer.wasCancelled')}</p>
      ) : lx.left ? (
        <p className="text-small text-muted" data-testid="lookup-exchange-left">{t('lookupExchange.drawer.left', { code })}</p>
      ) : (
        <div data-testid="lookup-exchange-reserved">
          <Alert tone="warning" title={t('lookupExchange.drawer.reservedTitle')}>
            {t('lookupExchange.drawer.reservedBody', { code })}
          </Alert>
        </div>
      )}

      {lx.canCancel && !confirming && (
        <div className="flex justify-end">
          <button type="button" className="btn-outline text-small" data-testid="lookup-exchange-cancel"
            onClick={() => { setConfirming(true); setError(null) }}>
            {t('lookupExchange.drawer.cancel')}
          </button>
        </div>
      )}
      {lx.canCancel && confirming && (
        <div className="rounded-md border border-line p-3 space-y-3" data-testid="lookup-exchange-cancel-confirm">
          <p className="text-small text-primary">{t('lookupExchange.drawer.cancelConfirm', { code })}</p>
          {error && <p className="text-small text-critical" role="alert">{error}</p>}
          <div className="flex gap-2 justify-end">
            <Button size="sm" variant="ghost" onClick={() => { setConfirming(false); setError(null) }}>{t('common.cancel')}</Button>
            <button type="button" className="btn-danger text-small" disabled={busy}
              data-testid="lookup-exchange-cancel-confirm-btn" onClick={cancel}>
              {busy ? <Spinner size={14} /> : t('lookupExchange.drawer.cancelSubmit')}
            </button>
          </div>
        </div>
      )}
    </div>
  )
}
