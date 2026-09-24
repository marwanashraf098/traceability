import { ReactNode, useCallback, useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { X } from 'lucide-react'
import {
  approveReturnRequest, getPortalSettings, getReturnRequest, rejectReturnRequest, ReturnRequestDetail,
} from '../../api'
import { Alert, Badge, Button, ProductThumb, Skeleton, cn, useToast } from '../../components/ui'
import { reasonLabel, requestStatusTone, sentLabel, shortCustomerName, shortDate } from './requestFormat'

export const REJECT_REASON_MAX = 300

/**
 * Returns portal Step 4e-A (M2 detail, M3 reject) — same slide-in shell as
 * ExchangeRefundDrawer. Approve / Reject are only offered while the request is 'requested'
 * (the backend answers 409 otherwise). A 409 means someone else decided first: the drawer
 * says so, reloads the request and asks the list to refresh.
 *
 * Footer copy: until Step 4c (pickupBooking false) approving does NOT book a Bosta pickup, so
 * the mockup's "Approving books a Bosta pickup…" line is replaced by "After approving, book
 * the pickup in Bosta."
 */
export default function ReturnRequestDrawer({
  requestId,
  onClose,
  onChanged,
}: {
  requestId: string | null
  onClose: () => void
  onChanged: () => void
}) {
  const { t } = useTranslation()
  const open = requestId != null
  const [detail, setDetail] = useState<ReturnRequestDetail | null>(null)

  return (
    <>
      <div
        className={cn(
          'fixed inset-0 bg-black/45 z-overlay transition-opacity duration-200',
          open ? 'opacity-100 pointer-events-auto' : 'opacity-0 pointer-events-none'
        )}
        onClick={onClose}
      />
      <aside
        aria-hidden={!open}
        aria-label={detail ? t('exchangesRefunds.requests.drawer.label', { reference: detail.reference }) : undefined}
        data-testid="return-request-drawer"
        className={cn(
          'fixed top-0 end-0 h-screen w-[480px] max-w-[92vw] bg-surface border-s border-line z-modal',
          'flex flex-col shadow-e4 transition-transform duration-200',
          open ? 'translate-x-0' : 'ltr:translate-x-full rtl:-translate-x-full'
        )}
      >
        {requestId && (
          <DrawerContent
            key={requestId}
            requestId={requestId}
            onClose={onClose}
            onChanged={onChanged}
            onLoaded={setDetail}
          />
        )}
      </aside>
    </>
  )
}

function DrawerContent({
  requestId,
  onClose,
  onChanged,
  onLoaded,
}: {
  requestId: string
  onClose: () => void
  onChanged: () => void
  onLoaded: (d: ReturnRequestDetail | null) => void
}) {
  const { t, i18n } = useTranslation()
  const { toast } = useToast()
  const [detail, setDetail] = useState<ReturnRequestDetail | null>(null)
  const [loadError, setLoadError] = useState(false)
  const [pickupBooking, setPickupBooking] = useState(false)
  const [mode, setMode] = useState<'view' | 'reject'>('view')
  const [reason, setReason] = useState('')
  const [reasonError, setReasonError] = useState(false)
  const [busy, setBusy] = useState<'approve' | 'reject' | null>(null)
  const [conflict, setConflict] = useState(false)

  const load = useCallback(async () => {
    setLoadError(false)
    try {
      const d = await getReturnRequest(requestId)
      setDetail(d)
      onLoaded(d)
    } catch {
      setLoadError(true)
      onLoaded(null)
    }
  }, [requestId, onLoaded])

  useEffect(() => { load() }, [load])

  useEffect(() => {
    getPortalSettings().then(s => setPickupBooking(!!s?.pickupBooking)).catch(() => {})
  }, [])

  async function decide(kind: 'approve' | 'reject') {
    if (kind === 'reject') {
      const trimmed = reason.trim()
      if (!trimmed || trimmed.length > REJECT_REASON_MAX) {
        setReasonError(true)
        return
      }
    }
    setBusy(kind)
    setConflict(false)
    try {
      if (kind === 'approve') await approveReturnRequest(requestId)
      else await rejectReturnRequest(requestId, reason.trim())
      toast({
        tone: 'success',
        message: t(kind === 'approve' ? 'exchangesRefunds.requests.drawer.approved' : 'exchangesRefunds.requests.drawer.rejected'),
      })
      setMode('view')
      await load()
      onChanged()
    } catch (e) {
      if (e instanceof Error && e.message.startsWith('409')) {
        setConflict(true)
        setMode('view')
        await load()
        onChanged()
      } else {
        toast({ tone: 'error', message: t('exchangesRefunds.requests.drawer.actionFailed') })
      }
    } finally {
      setBusy(null)
    }
  }

  const header = (
    <div className="p-5 pb-4 border-b border-line flex items-center gap-3">
      {detail ? (
        <>
          <span className="text-body-lg font-semibold font-mono text-primary"><bdi>{detail.reference}</bdi></span>
          <Badge tone={requestStatusTone(detail.status)} label={t(`exchangesRefunds.requests.status.${detail.status}`)} />
        </>
      ) : <Skeleton className="h-6 w-32" />}
      <div className="flex-1" />
      <button
        onClick={onClose}
        aria-label={t('exchangesRefunds.drawer.close')}
        className="w-8 h-8 rounded-lg flex items-center justify-center text-muted hover:text-primary hover:bg-elevated transition-colors"
      >
        <X size={18} strokeWidth={2} />
      </button>
    </div>
  )

  if (loadError) {
    return (
      <>
        {header}
        <div className="p-10 flex flex-col items-center gap-3 text-center">
          <p className="text-body font-semibold text-primary">{t('exchangesRefunds.requests.drawer.loadError')}</p>
          <button className="btn-outline" onClick={load}>{t('exchangesRefunds.retry')}</button>
        </div>
      </>
    )
  }

  if (!detail) {
    return (
      <>
        {header}
        <div className="p-5 space-y-3">
          <Skeleton className="h-4 w-2/3" />
          <Skeleton className="h-4 w-1/2" />
          <Skeleton className="h-16 w-full" />
        </div>
      </>
    )
  }

  const pickup = [detail.pickupCity, detail.pickupZone].filter(Boolean).join(' · ')
  const isRequested = detail.status === 'requested'
  const reasonLength = reason.length

  return (
    <>
      {header}

      <div className="flex-1 overflow-y-auto p-5 space-y-6" data-testid="return-request-drawer-body">
        {conflict && (
          <div data-testid="request-conflict">
            <Alert tone="info" title={t('exchangesRefunds.requests.drawer.conflict')} />
          </div>
        )}

        <dl className="grid grid-cols-2 gap-x-6 gap-y-4">
          <Field label={t('exchangesRefunds.requests.drawer.customer')}>
            <bdi>{shortCustomerName(detail.customerName)}</bdi>
          </Field>
          {detail.customerPhone && (
            <Field label={t('exchangesRefunds.requests.drawer.phone')}>
              <span dir="ltr" className="font-mono">{detail.customerPhone}</span>
            </Field>
          )}
          <Field label={t('exchangesRefunds.requests.drawer.order')}>
            {/* Plain text, not the mockup's link: /orders/:id is intentionally unrouted. */}
            <bdi>{detail.orderNumber}</bdi>
            {detail.deliveredAt && (
              <span className="text-muted">
                {' · '}{t('exchangesRefunds.requests.drawer.delivered', { date: shortDate(i18n.language, detail.deliveredAt) })}
              </span>
            )}
          </Field>
          <Field label={t('exchangesRefunds.requests.drawer.sent')}>
            {sentLabel(t, i18n.language, detail.createdAt)}
          </Field>
          {pickup && (
            <Field label={t('exchangesRefunds.requests.drawer.pickup')}>
              <bdi>{pickup}</bdi>
            </Field>
          )}
          {detail.email && (
            <Field label={t('exchangesRefunds.requests.drawer.email')}>
              <span dir="ltr" className="break-all">{detail.email}</span>
            </Field>
          )}
        </dl>

        <section>
          <h3 className="text-caption font-semibold text-muted uppercase tracking-wider mb-2">
            {t('exchangesRefunds.requests.drawer.items', { count: detail.items.length })}
          </h3>
          <ul className="space-y-2">
            {detail.items.map(item => (
              <li key={item.id} className="card p-3 flex items-center gap-3" data-testid="request-item">
                <ProductThumb src={item.imageUrl} alt={item.productTitle} size={40} />
                <div className="min-w-0 flex-1">
                  <p className="text-body font-medium text-primary truncate">{item.productTitle}</p>
                  <p className="text-small text-muted truncate">
                    {item.variantTitle && <>{item.variantTitle} · </>}
                    <span className="font-mono" dir="ltr"><bdi>{item.shortCode}</bdi></span>
                  </p>
                </div>
                <Badge tone="neutral" label={reasonLabel(t, item.reasonCode)} />
              </li>
            ))}
          </ul>
        </section>

        {detail.note && (
          <section>
            <h3 className="text-caption font-semibold text-muted uppercase tracking-wider mb-2">
              {t('exchangesRefunds.requests.drawer.note')}
            </h3>
            <p className="text-body text-primary whitespace-pre-wrap card p-3.5" dir="auto">{detail.note}</p>
          </section>
        )}

        {detail.status === 'rejected' && detail.rejectionReason && (
          <section>
            <h3 className="text-caption font-semibold text-muted uppercase tracking-wider mb-2">
              {t('exchangesRefunds.requests.drawer.rejectionReason')}
            </h3>
            <p className="text-body text-primary whitespace-pre-wrap" dir="auto">{detail.rejectionReason}</p>
          </section>
        )}

        {detail.decidedAt && (
          <p className="text-small text-muted" data-testid="request-decided">
            {detail.decidedByName
              ? t('exchangesRefunds.requests.drawer.decidedBy', { name: detail.decidedByName, date: shortDate(i18n.language, detail.decidedAt) })
              : t('exchangesRefunds.requests.drawer.decidedAuto', { date: shortDate(i18n.language, detail.decidedAt) })}
          </p>
        )}
      </div>

      {isRequested && mode === 'view' && (
        <div className="p-5 border-t border-line space-y-3" data-testid="request-footer">
          <p className="text-small text-muted" data-testid="approve-helper">
            {t(pickupBooking ? 'exchangesRefunds.requests.drawer.helperBooking' : 'exchangesRefunds.requests.drawer.helperManual')}
          </p>
          <div className="flex justify-end gap-2">
            <Button variant="outline" disabled={busy != null} onClick={() => { setMode('reject'); setReasonError(false) }}>
              {t('exchangesRefunds.requests.drawer.reject')}
            </Button>
            <Button loading={busy === 'approve'} disabled={busy != null} onClick={() => decide('approve')}>
              {t('exchangesRefunds.requests.drawer.approve')}
            </Button>
          </div>
        </div>
      )}

      {isRequested && mode === 'reject' && (
        <div className="p-5 border-t border-line space-y-2" data-testid="reject-form">
          <label htmlFor="reject-reason" className="text-body font-medium text-primary block">
            {t('exchangesRefunds.requests.drawer.rejectLabel')}{' '}
            <span className="font-normal text-muted">{t('exchangesRefunds.requests.drawer.rejectVisible')}</span>
          </label>
          <textarea
            id="reject-reason"
            rows={3}
            dir="auto"
            maxLength={REJECT_REASON_MAX}
            aria-invalid={reasonError}
            aria-describedby="reject-reason-count"
            className={cn('input resize-none w-full', reasonError && 'border-critical')}
            value={reason}
            onChange={e => { setReason(e.target.value); setReasonError(false) }}
          />
          <div className="flex items-center justify-between gap-3">
            <span className="text-small text-critical" role={reasonError ? 'alert' : undefined}>
              {reasonError ? t('exchangesRefunds.requests.drawer.rejectRequired') : ''}
            </span>
            <span id="reject-reason-count" className="text-small text-muted tabular-nums" dir="ltr">
              {reasonLength} / {REJECT_REASON_MAX}
            </span>
          </div>
          <div className="flex justify-end gap-2 pt-1">
            <Button variant="outline" disabled={busy != null} onClick={() => { setMode('view'); setReasonError(false) }}>
              {t('exchangesRefunds.requests.drawer.cancel')}
            </Button>
            <Button variant="danger" loading={busy === 'reject'} disabled={busy != null} onClick={() => decide('reject')}>
              {t('exchangesRefunds.requests.drawer.rejectConfirm')}
            </Button>
          </div>
        </div>
      )}
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
