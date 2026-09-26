import { ReactNode, useCallback, useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { X } from 'lucide-react'
import {
  approveReturnRequest, confirmBooking, getPortalSettings, getRefundSuggestion, getReturnRequest, getReturnRequestPickupAreas,
  markBookingNotBooked, markRestNotComing, markReturnRequestRefunded, rejectReturnRequest, retryBooking, setReturnRequestPickupArea,
  LinkableParcel, PickupDistrict, RefundSuggestion, ReturnRequestDetail, ReturnRequestPickupAreas,
} from '../../api'
import { Alert, Badge, Button, ProductThumb, Skeleton, cn, useToast } from '../../components/ui'
import { displayStatus, displayStatusTone, reasonLabel, sentLabel, shortCustomerName, shortDate } from './requestFormat'
import {
  arrivedCount, CloseDialog, HistoryTimeline, ItemsWithOutcome, LinkParcelDialog, RefundForm, RefundsList, UnexpectedItems,
} from './RequestLifecycle'

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
 *
 * Step 4c-2: Pickup shows the request's chosen area (city · district, in the UI language),
 * falling back to the order address city · zone. "Change area" (not in the M2 mockup) opens an
 * inline select of the city's pickup-available districts, grouped by zone — offered while the
 * request is requested or approved (the backend enforces the same).
 *
 * Step 4c-3: a "Bosta pickup" row shows the booking — Booking… / Booked · tracking / Failed:
 * reason + Retry / Check in Bosta (ambiguous: "It wasn't booked — retry" or "It was booked —
 * enter tracking number") / Check in Bosta: details differ. "Change area" is offered only
 * while nothing is booked.
 *
 * Step 4d-2 (R1–R5): once anything came back (or the request is received / refund pending /
 * refunded) the drawer switches to the lifecycle layout — Customer / Order / Returned (or
 * Parcel while partly received), items with their outcome, the "different product" flag, the
 * refund form (R1), the refunds list + history (R2), and the partial-arrival actions (R3).
 * Close (R4) and Link parcel (R5) open as dialogs. "Partly received" is a display label only.
 */
export default function ReturnRequestDrawer({
  requestId,
  initialParcelId = null,
  onClose,
  onChanged,
}: {
  requestId: string | null
  /** Opens the R5 dialog for this parcel once the request loads (the return_link_ambiguous exception's link). */
  initialParcelId?: string | null
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
          'fixed top-0 end-0 h-screen w-[580px] max-w-[92vw] bg-surface border-s border-line z-modal',
          'flex flex-col shadow-e4 transition-transform duration-200',
          open ? 'translate-x-0' : 'ltr:translate-x-full rtl:-translate-x-full'
        )}
      >
        {requestId && (
          <DrawerContent
            key={requestId}
            requestId={requestId}
            initialParcelId={initialParcelId}
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
  initialParcelId,
  onClose,
  onChanged,
  onLoaded,
}: {
  requestId: string
  initialParcelId: string | null
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
  const [editingArea, setEditingArea] = useState(false)
  // Step 4d-2
  const [suggestion, setSuggestion] = useState<RefundSuggestion | null>(null)
  const [addingRefund, setAddingRefund] = useState(false)
  const [closing, setClosing] = useState(false)
  const [linking, setLinking] = useState<LinkableParcel | null>(null)
  const [autoLinkDone, setAutoLinkDone] = useState(false)
  const [lifecycleBusy, setLifecycleBusy] = useState<'refunded' | 'restNotComing' | null>(null)

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

  // Step 4d-2: the suggested amount, once the request has something to refund. Never blocks
  // the drawer — no suggestion just leaves the amount empty.
  const refundStage = detail != null && ['received', 'refund_pending', 'refunded'].includes(detail.status)
  useEffect(() => {
    if (!refundStage) return
    let cancelled = false
    getRefundSuggestion(requestId)
      .then(s => { if (!cancelled) setSuggestion(s && typeof s === 'object' ? s : null) })
      .catch(() => { if (!cancelled) setSuggestion(null) })
    return () => { cancelled = true }
  }, [refundStage, requestId])

  // The return_link_ambiguous exception opens the drawer with ?parcel=<shipmentId>.
  useEffect(() => {
    if (!detail || autoLinkDone || !initialParcelId) return
    const parcel = (detail.linkableParcels ?? []).find(p => p.shipmentId === initialParcelId)
    if (parcel) setLinking(parcel)
    setAutoLinkDone(true)
  }, [detail, initialParcelId, autoLinkDone])

  const reloadAll = useCallback(async () => {
    setAddingRefund(false)
    await load()
    onChanged()
  }, [load, onChanged])

  async function lifecycleAction(kind: 'refunded' | 'restNotComing') {
    setLifecycleBusy(kind)
    try {
      if (kind === 'refunded') await markReturnRequestRefunded(requestId)
      else await markRestNotComing(requestId)
      toast({ tone: 'success', message: t(kind === 'refunded' ? 'exchangesRefunds.requests.refund.markedRefunded' : 'exchangesRefunds.requests.drawer.restNotComingDone') })
      await reloadAll()
    } catch (e) {
      const conflict = e instanceof Error && e.message.startsWith('409')
      toast({ tone: 'error', message: t(conflict ? 'exchangesRefunds.requests.drawer.conflict' : 'exchangesRefunds.requests.drawer.actionFailed') })
      if (conflict) await reloadAll()
    } finally {
      setLifecycleBusy(null)
    }
  }

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
          <Badge tone={displayStatusTone(shownStatus(detail))} label={t(`exchangesRefunds.requests.status.${shownStatus(detail)}`)} />
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

  const districtName = i18n.language === 'ar'
    ? (detail.pickupDistrictNameAr || detail.pickupDistrictName)
    : detail.pickupDistrictName
  const pickup = districtName
    ? [detail.pickupCityName, districtName].filter(Boolean).join(' · ')
    : [detail.pickupCity, detail.pickupZone].filter(Boolean).join(' · ')
  const bookingLocksArea = detail.bookingStatus === 'pending' || detail.bookingStatus === 'booked'
    || detail.bookingStatus === 'needs_review'
  const areaEditable = (detail.status === 'requested' || detail.status === 'approved') && !bookingLocksArea
  const showBooking = detail.bookingStatus != null || (detail.status === 'approved' && pickupBooking)
  const isRequested = detail.status === 'requested'
  const reasonLength = reason.length

  // Step 4d-2 — the lifecycle layout (R1–R3).
  const arrived = arrivedCount(detail.items)
  const partly = shownStatus(detail) === 'partly_received'
  const lifecycle = arrived > 0 || refundStage
  if (lifecycle) {
    const refunds = detail.refunds ?? []
    const activeRefunds = refunds.filter(r => !r.voided)
    const refundable = detail.status === 'received' || detail.status === 'refund_pending'
    const showForm = refundable && (activeRefunds.length === 0 || addingRefund)
    const showRefunds = refunds.length > 0
    const canClose = ['approved', 'pickup_booked', 'received', 'refund_pending'].includes(detail.status)
    return (
      <>
        {header}
        <div className="flex-1 overflow-y-auto p-6 space-y-5" data-testid="return-request-drawer-body">
          {!showRefunds && (
            <dl className="grid grid-cols-3 gap-x-5 gap-y-3">
              <Field label={t('exchangesRefunds.requests.drawer.customer')}>
                <span className="font-semibold"><bdi>{shortCustomerName(detail.customerName)}</bdi></span>
              </Field>
              <Field label={t('exchangesRefunds.requests.drawer.order')}>
                <span className="font-semibold"><bdi>{detail.orderNumber}</bdi></span>
              </Field>
              {partly && detail.returnTrackingNumber ? (
                <Field label={t('exchangesRefunds.requests.drawer.parcel')}>
                  <span className="font-mono" dir="ltr"><bdi>{detail.returnTrackingNumber}</bdi></span>
                </Field>
              ) : detail.receivedAt ? (
                <Field label={t('exchangesRefunds.requests.drawer.returned')}>
                  {shortDate(i18n.language, detail.receivedAt)}
                </Field>
              ) : null}
            </dl>
          )}

          {!showRefunds && <ItemsWithOutcome items={detail.items} showReason={partly || detail.status === 'received'} />}

          {detail.status !== 'refunded' && detail.status !== 'closed' && <UnexpectedItems detail={detail} />}

          {!showRefunds && detail.note && (
            <section>
              <h3 className="text-body font-semibold text-primary mb-2">{t('exchangesRefunds.requests.drawer.note')}</h3>
              <p className="text-body text-primary whitespace-pre-wrap rounded-lg bg-elevated px-3.5 py-3" dir="auto">{detail.note}</p>
            </section>
          )}

          <LinkParcelPrompt detail={detail} onLink={setLinking} />

          {showRefunds && (
            <RefundsList detail={detail} suggestion={suggestion} canChange={refundable} onChanged={reloadAll} />
          )}
          {showRefunds && refundable && !addingRefund && (
            <button type="button" className="text-body font-semibold text-trace-blue hover:underline"
              onClick={() => setAddingRefund(true)} data-testid="add-refund">
              {t('exchangesRefunds.requests.refund.addAnother')}
            </button>
          )}
          {showForm && (
            <RefundForm detail={detail} suggestion={suggestion} onSaved={reloadAll}
              onCancel={addingRefund ? () => setAddingRefund(false) : undefined} />
          )}

          {detail.status === 'closed' && detail.closeReason && (
            <section data-testid="request-close-reason">
              <h3 className="text-caption font-semibold text-muted uppercase tracking-wider mb-2">
                {t('exchangesRefunds.requests.drawer.closeReason')}
              </h3>
              <p className="text-body text-primary">{t(`exchangesRefunds.requests.closeReasons.${detail.closeReason}`)}</p>
              {detail.closeNote && <p className="text-body text-secondary whitespace-pre-wrap mt-1" dir="auto">{detail.closeNote}</p>}
            </section>
          )}

          {(showRefunds || detail.status === 'refunded' || detail.status === 'closed') && <HistoryTimeline detail={detail} />}
        </div>

        {detail.status === 'refund_pending' && activeRefunds.length > 0 && (
          <div className="px-6 py-4 border-t border-line space-y-3" data-testid="refunded-footer">
            <p className="text-small text-secondary">{t('exchangesRefunds.requests.refund.markHint')}</p>
            <Button className="w-full" loading={lifecycleBusy === 'refunded'} disabled={lifecycleBusy != null}
              onClick={() => lifecycleAction('refunded')}>
              {t('exchangesRefunds.requests.refund.markRefunded')}
            </Button>
          </div>
        )}
        {refundable && activeRefunds.length === 0 && (
          <div className="px-6 py-4 border-t border-line flex items-center justify-between gap-3" data-testid="close-footer">
            <p className="text-small text-secondary">{t('exchangesRefunds.requests.drawer.notRefunding')}</p>
            <Button variant="outline" className="!border-critical/40 !text-critical hover:!bg-critical/5" onClick={() => setClosing(true)}>{t('exchangesRefunds.requests.drawer.closeWithoutRefund')}</Button>
          </div>
        )}
        {partly && (
          <div className="px-6 py-4 border-t border-line space-y-3" data-testid="partial-footer">
            <p className="text-small text-secondary">{t('exchangesRefunds.requests.drawer.laterArrivals')}</p>
            <div className="flex gap-3">
              <Button variant="outline" className="flex-1" loading={lifecycleBusy === 'restNotComing'} disabled={lifecycleBusy != null}
                onClick={() => lifecycleAction('restNotComing')}>
                {t('exchangesRefunds.requests.drawer.restNotComing')}
              </Button>
              <Button variant="outline" className="flex-1 !border-critical/40 !text-critical hover:!bg-critical/5" disabled={lifecycleBusy != null} onClick={() => setClosing(true)}>
                {t('exchangesRefunds.requests.drawer.closeRequest')}
              </Button>
            </div>
          </div>
        )}

        {closing && canClose && <CloseDialog detail={detail} onClose={() => setClosing(false)} onDone={reloadAll} />}
        {linking && (
          <LinkParcelDialog parcel={linking} orderNumber={detail.orderNumber} preselect={detail.id}
            onClose={() => setLinking(null)} onDone={reloadAll} />
        )}
      </>
    )
  }

  const closableEarly = detail.status === 'approved' || detail.status === 'pickup_booked'

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
          {(pickup || areaEditable) && (
            <Field label={t('exchangesRefunds.requests.drawer.pickup')}>
              <span data-testid="request-pickup"><bdi>{pickup || '—'}</bdi></span>
              {areaEditable && !editingArea && (
                <button
                  type="button"
                  className="block text-small font-medium text-trace-blue hover:underline mt-0.5"
                  onClick={() => setEditingArea(true)}
                >
                  {t('exchangesRefunds.requests.drawer.changeArea')}
                </button>
              )}
            </Field>
          )}
          {showBooking && (
            <div className="col-span-2 min-w-0" data-testid="booking-row">
              <dt className="text-caption text-muted mb-0.5">{t('exchangesRefunds.requests.drawer.bookingLabel')}</dt>
              <dd className="text-body text-primary">
                <BookingState detail={detail} onChanged={async () => { await load(); onChanged() }} />
              </dd>
            </div>
          )}
          {detail.email && (
            <Field label={t('exchangesRefunds.requests.drawer.email')}>
              <span dir="ltr" className="break-all">{detail.email}</span>
            </Field>
          )}
        </dl>

        {editingArea && areaEditable && (
          <AreaEditor
            requestId={requestId}
            onCancel={() => setEditingArea(false)}
            onSaved={async () => {
              setEditingArea(false)
              await load()
              onChanged()
            }}
          />
        )}

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

        {detail.status === 'closed' && detail.closeReason && (
          <section data-testid="request-close-reason">
            <h3 className="text-caption font-semibold text-muted uppercase tracking-wider mb-2">
              {t('exchangesRefunds.requests.drawer.closeReason')}
            </h3>
            <p className="text-body text-primary">
              {t(`exchangesRefunds.requests.closeReasons.${detail.closeReason}`)}
            </p>
            {detail.closeNote && (
              <p className="text-body text-secondary whitespace-pre-wrap mt-1" dir="auto">{detail.closeNote}</p>
            )}
          </section>
        )}

        <LinkParcelPrompt detail={detail} onLink={setLinking} />

        {detail.decidedAt && (
          <p className="text-small text-muted" data-testid="request-decided">
            {detail.decidedByName
              ? t('exchangesRefunds.requests.drawer.decidedBy', { name: detail.decidedByName, date: shortDate(i18n.language, detail.decidedAt) })
              : t('exchangesRefunds.requests.drawer.decidedAuto', { date: shortDate(i18n.language, detail.decidedAt) })}
          </p>
        )}
      </div>

      {closableEarly && (
        <div className="p-5 border-t border-line flex justify-end" data-testid="close-footer">
          <Button variant="outline" className="!border-critical/40 !text-critical hover:!bg-critical/5" onClick={() => setClosing(true)}>{t('exchangesRefunds.requests.drawer.closeRequest')}</Button>
        </div>
      )}
      {closing && closableEarly && <CloseDialog detail={detail} onClose={() => setClosing(false)} onDone={reloadAll} />}
      {linking && (
        <LinkParcelDialog parcel={linking} orderNumber={detail.orderNumber} preselect={detail.id}
          onClose={() => setLinking(null)} onDone={reloadAll} />
      )}

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

/** R3 / R6 display status: "Partly received" for an approved / booked request with items back. */
function shownStatus(detail: ReturnRequestDetail) {
  return displayStatus(detail.status, arrivedCount(detail.items))
}

/** Step 4d-2 (R5 entry point) — a courier return on this order that no request holds yet. */
function LinkParcelPrompt({ detail, onLink }: { detail: ReturnRequestDetail; onLink: (p: LinkableParcel) => void }) {
  const { t } = useTranslation()
  const parcels = detail.linkableParcels ?? []
  if (parcels.length === 0) return null
  return (
    <div className="space-y-2" data-testid="link-parcel-prompt">
      {parcels.map(p => (
        <div key={p.shipmentId} className="rounded-lg border border-info/30 bg-info/[0.08] px-4 py-3 flex items-center gap-3">
          <p className="text-small text-primary flex-1 min-w-0">
            {t('exchangesRefunds.requests.link.prompt')} <span className="font-mono" dir="ltr"><bdi>AWB {p.trackingNumber}</bdi></span>
          </p>
          <Button size="sm" variant="outline" onClick={() => onLink(p)}>{t('exchangesRefunds.requests.link.open')}</Button>
        </div>
      ))}
    </div>
  )
}

/** Step 4c-3 — the booking row's state and actions. */
function BookingState({ detail, onChanged }: { detail: ReturnRequestDetail; onChanged: () => Promise<void> }) {
  const { t } = useTranslation()
  const { toast } = useToast()
  const [busy, setBusy] = useState<'retry' | 'notBooked' | 'confirm' | null>(null)
  const [entering, setEntering] = useState(false)
  const [tracking, setTracking] = useState('')
  const [confirmError, setConfirmError] = useState<string | null>(null)
  const status = detail.bookingStatus ?? 'pending'

  async function run(kind: 'retry' | 'notBooked') {
    setBusy(kind)
    try {
      if (kind === 'retry') await retryBooking(detail.id)
      else await markBookingNotBooked(detail.id)
      toast({ tone: 'success', message: t('exchangesRefunds.requests.drawer.bookingRetried') })
      await onChanged()
    } catch {
      toast({ tone: 'error', message: t('exchangesRefunds.requests.drawer.bookingActionFailed') })
    } finally {
      setBusy(null)
    }
  }

  async function confirm() {
    const tn = tracking.replace(/\s+/g, '')
    if (!tn) return
    setBusy('confirm')
    setConfirmError(null)
    try {
      await confirmBooking(detail.id, tn)
      toast({ tone: 'success', message: t('exchangesRefunds.requests.drawer.bookingConfirmed') })
      setEntering(false)
      await onChanged()
    } catch (e) {
      const code = e instanceof Error ? e.message.slice(0, 3) : ''
      setConfirmError(t(code === '400' ? 'exchangesRefunds.requests.drawer.bookingConfirmInvalid'
        : code === '409' ? 'exchangesRefunds.requests.drawer.bookingConfirmConflict'
          : 'exchangesRefunds.requests.drawer.bookingActionFailed'))
    } finally {
      setBusy(null)
    }
  }

  if (status === 'booked') {
    return (
      <span data-testid="booking-booked">
        {t('exchangesRefunds.requests.drawer.bookingBooked', { tracking: '' })}
        <bdi className="font-mono" dir="ltr">{detail.bostaTrackingNumber}</bdi>
      </span>
    )
  }
  if (status === 'failed') {
    return (
      <div className="space-y-2" data-testid="booking-failed">
        <p className="text-critical">{t('exchangesRefunds.requests.drawer.bookingFailed', { reason: detail.bookingError ?? '' })}</p>
        <Button size="sm" variant="outline" loading={busy === 'retry'} disabled={busy != null} onClick={() => run('retry')}>
          {t('exchangesRefunds.requests.drawer.bookingRetry')}
        </Button>
      </div>
    )
  }
  if (status === 'needs_review') {
    return (
      <div className="space-y-1" data-testid="booking-review">
        <p className="text-warning-text">{t('exchangesRefunds.requests.drawer.bookingReview', { reason: detail.bookingError ?? '' })}</p>
        {detail.bostaTrackingNumber && <p className="font-mono text-small" dir="ltr"><bdi>{detail.bostaTrackingNumber}</bdi></p>}
      </div>
    )
  }
  if (status === 'failed_ambiguous') {
    return (
      <div className="space-y-2" data-testid="booking-ambiguous">
        <p className="text-warning-text">{t('exchangesRefunds.requests.drawer.bookingAmbiguous')}</p>
        {!entering ? (
          <div className="flex flex-wrap gap-2">
            <Button size="sm" variant="outline" loading={busy === 'notBooked'} disabled={busy != null} onClick={() => run('notBooked')}>
              {t('exchangesRefunds.requests.drawer.bookingNotBooked')}
            </Button>
            <Button size="sm" variant="outline" disabled={busy != null} onClick={() => { setEntering(true); setConfirmError(null) }}>
              {t('exchangesRefunds.requests.drawer.bookingWasBooked')}
            </Button>
          </div>
        ) : (
          <div className="space-y-1.5">
            <label htmlFor="booking-tracking" className="text-small font-medium text-primary block">
              {t('exchangesRefunds.requests.drawer.bookingTrackingLabel')}
            </label>
            <div className="flex gap-2">
              <input
                id="booking-tracking" className={cn('input flex-1 font-mono', confirmError && 'border-critical')} dir="ltr"
                inputMode="numeric" autoComplete="off" value={tracking}
                aria-invalid={!!confirmError}
                onChange={e => { setTracking(e.target.value); setConfirmError(null) }}
              />
              <Button size="sm" loading={busy === 'confirm'} disabled={busy != null || !tracking.trim()} onClick={confirm}>
                {t('exchangesRefunds.requests.drawer.bookingConfirm')}
              </Button>
              <Button size="sm" variant="outline" disabled={busy != null} onClick={() => setEntering(false)}>
                {t('exchangesRefunds.requests.drawer.cancel')}
              </Button>
            </div>
            {confirmError && <p className="text-small text-critical" role="alert">{confirmError}</p>}
          </div>
        )}
      </div>
    )
  }
  return <span className="text-muted" data-testid="booking-pending">{t('exchangesRefunds.requests.drawer.bookingPending')}</span>
}

/** Inline "Change area": the request city's pickup-available districts, grouped by zone. */
function AreaEditor({
  requestId, onCancel, onSaved,
}: { requestId: string; onCancel: () => void; onSaved: () => Promise<void> }) {
  const { t, i18n } = useTranslation()
  const { toast } = useToast()
  const [areas, setAreas] = useState<ReturnRequestPickupAreas | null>(null)
  const [loadError, setLoadError] = useState(false)
  const [districtId, setDistrictId] = useState('')
  const [saving, setSaving] = useState(false)
  const ar = i18n.language === 'ar'

  useEffect(() => {
    let cancelled = false
    getReturnRequestPickupAreas(requestId)
      .then(a => { if (!cancelled) { setAreas(a); setDistrictId(a.selectedDistrictId ?? '') } })
      .catch(() => { if (!cancelled) setLoadError(true) })
    return () => { cancelled = true }
  }, [requestId])

  async function save() {
    if (!districtId) return
    setSaving(true)
    try {
      await setReturnRequestPickupArea(requestId, districtId)
      toast({ tone: 'success', message: t('exchangesRefunds.requests.drawer.areaSaved') })
      await onSaved()
    } catch (e) {
      const conflict = e instanceof Error && e.message.startsWith('409')
      toast({ tone: 'error', message: t(conflict ? 'exchangesRefunds.requests.drawer.areaConflict' : 'exchangesRefunds.requests.drawer.areaFailed') })
      if (conflict) await onSaved()
    } finally {
      setSaving(false)
    }
  }

  const name = (d: PickupDistrict) => (ar ? d.nameAr || d.name : d.name)
  const groups = groupByZone(areas?.districts ?? [], ar)

  return (
    <section className="card p-4 space-y-3" data-testid="area-editor">
      {loadError ? (
        <p className="text-small text-critical" role="alert">{t('exchangesRefunds.requests.drawer.areaLoadFailed')}</p>
      ) : !areas ? (
        <Skeleton className="h-10 w-full" />
      ) : areas.districts.length === 0 ? (
        <p className="text-small text-muted">{t('exchangesRefunds.requests.drawer.areaNone')}</p>
      ) : (
        <div className="space-y-1.5">
          <label htmlFor="request-area" className="text-body font-medium text-primary block">
            {t('exchangesRefunds.requests.drawer.areaLabel')}
            {areas.cityName && <span className="font-normal text-muted"> · <bdi>{ar ? areas.cityNameAr || areas.cityName : areas.cityName}</bdi></span>}
          </label>
          <select id="request-area" className="input w-full" value={districtId} onChange={e => setDistrictId(e.target.value)}>
            <option value="" disabled>{t('exchangesRefunds.requests.drawer.areaPlaceholder')}</option>
            {groups.map(g => g.zone
              ? (
                <optgroup key={g.zone} label={g.zone}>
                  {g.districts.map(d => <option key={d.id} value={d.id}>{name(d)}</option>)}
                </optgroup>
              )
              : g.districts.map(d => <option key={d.id} value={d.id}>{name(d)}</option>))}
          </select>
        </div>
      )}
      <div className="flex justify-end gap-2">
        <Button variant="outline" size="sm" disabled={saving} onClick={onCancel}>
          {t('exchangesRefunds.requests.drawer.cancel')}
        </Button>
        {areas && areas.districts.length > 0 && (
          <Button size="sm" loading={saving} disabled={!districtId || saving} onClick={save}>
            {t('exchangesRefunds.requests.drawer.areaSave')}
          </Button>
        )}
      </div>
    </section>
  )
}

/** Consecutive districts sharing a zone (the backend sorts by zone), labelled in the UI language. */
function groupByZone(districts: PickupDistrict[], ar: boolean) {
  const groups: { zone: string | null; districts: PickupDistrict[] }[] = []
  for (const d of districts) {
    const zone = (ar ? d.zoneNameAr || d.zoneName : d.zoneName) || null
    const last = groups[groups.length - 1]
    if (last && last.zone === zone) last.districts.push(d)
    else groups.push({ zone, districts: [d] })
  }
  return groups
}

function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="min-w-0">
      <dt className="text-caption text-muted mb-0.5">{label}</dt>
      <dd className="text-body text-primary">{children}</dd>
    </div>
  )
}
