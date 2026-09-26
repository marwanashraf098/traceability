import { FormEvent, ReactNode, useEffect, useId, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { AlertCircle, Check, Info } from 'lucide-react'
import {
  closeReturnRequest, linkReturnLeg, recordRefund, voidRefund,
  LinkableParcel, RefundMethod, RefundSuggestion, ReturnRefund, ReturnRequestDetail, ReturnRequestEvent, ReturnRequestItem,
} from '../../api'
import { Badge, BadgeTone, Button, cn, useToast } from '../../components/ui'
import { dateTimeLabel, formatMoney, reasonLabel, shortDate, todayIso } from './requestFormat'

/**
 * Returns Step 4d-2 — the request drawer's lifecycle pieces (mockups R1–R5): items with their
 * outcome, the "different product" flag, the refund form, the refunds list, the history
 * timeline, and the Close (R4) / Link parcel (R5) dialogs. Money is shown here only — this
 * drawer is owner / manager only (the refund endpoints answer 403 to a worker).
 */

export const REFUND_METHODS: RefundMethod[] = ['cash', 'instapay', 'wallet', 'bank_transfer', 'other']
export const REFUND_REFERENCE_MAX = 100
export const REFUND_NOTE_MAX = 300
export const CLOSE_NOTE_MAX = 300

/** Did any item come back (arrived, or arrived and decided)? */
export function arrivedCount(items: ReturnRequestItem[]): number {
  return items.filter(i => i.itemStatus === 'arrived' || i.itemStatus === 'done').length
}

function itemOutcome(item: ReturnRequestItem): { key: string; tone: BadgeTone } {
  if (item.itemStatus === 'done' && item.disposition === 'damaged') return { key: 'damaged', tone: 'critical' }
  if (item.itemStatus === 'done') return { key: 'restocked', tone: 'success' }
  if (item.itemStatus === 'arrived') return { key: 'toInspect', tone: 'info' }
  if (item.itemStatus === 'not_coming') return { key: 'notComing', tone: 'neutral' }
  return { key: 'notArrived', tone: 'neutral' }
}

/** R1 / R3 — the items with their state on the right. `showReason`: R3 shows the customer's reason. */
export function ItemsWithOutcome({ items, showReason }: { items: ReturnRequestItem[]; showReason: boolean }) {
  const { t } = useTranslation()
  const arrived = arrivedCount(items)
  const counted = items.filter(i => i.itemStatus !== 'not_coming').length
  return (
    <section>
      <h3 className="text-body font-semibold text-primary mb-2">
        {showReason && arrived < counted
          ? t('exchangesRefunds.requests.drawer.itemsArrived', { arrived, count: counted })
          : t('exchangesRefunds.requests.drawer.itemsPlain', { count: items.length })}
      </h3>
      <ul className="space-y-2">
        {items.map(item => {
          const outcome = itemOutcome(item)
          const back = item.itemStatus === 'arrived' || item.itemStatus === 'done'
          const extra = item.disposition === 'damaged' && item.damageReason
            ? item.damageReason
            : showReason ? reasonLabel(t, item.reasonCode) : null
          return (
            <li key={item.id} className="card px-3.5 py-3 flex items-center gap-3" data-testid="request-item">
              <div className="min-w-0 flex-1">
                <p className="text-body font-medium text-primary truncate">{item.productTitle}</p>
                <p className="text-small text-muted truncate">
                  {item.variantTitle}
                  {back && <>{item.variantTitle && ' · '}<span className="font-mono" dir="ltr"><bdi>{item.shortCode}</bdi></span></>}
                  {extra && <>{(item.variantTitle || back) && ' · '}<bdi>{extra}</bdi></>}
                </p>
              </div>
              <span data-testid="item-outcome">
                <Badge tone={outcome.tone} label={t(`exchangesRefunds.requests.outcome.${outcome.key}`)} />
              </span>
            </li>
          )
        })}
      </ul>
    </section>
  )
}

/** R3 — a product that isn't part of the request came back in the parcel. */
export function UnexpectedItems({ detail }: { detail: ReturnRequestDetail }) {
  const { t } = useTranslation()
  const items = detail.unexpectedItems ?? []
  if (items.length === 0) return null
  return (
    <div className="flex gap-3 rounded-lg px-4 py-3.5 border border-warning/30 bg-warning/[0.08]" data-testid="unexpected-item">
      <AlertCircle size={18} strokeWidth={2} className="flex-shrink-0 mt-0.5 text-warning-text" />
      <div className="min-w-0 space-y-1">
        <p className="text-body font-semibold text-warning-text">{t('exchangesRefunds.requests.drawer.unexpectedTitle', { count: items.length })}</p>
        {items.map(u => (
          <p key={u.pieceId} className="text-body text-primary">
            <bdi>{[u.productTitle, u.variantTitle].filter(Boolean).join(' · ')}</bdi>
            {' · '}<span className="font-mono" dir="ltr"><bdi>{u.shortCode}</bdi></span>
            {' '}{t('exchangesRefunds.requests.drawer.unexpectedBody')}
          </p>
        ))}
      </div>
    </div>
  )
}

/** "Linen Shirt EGP 850 + Flipped Pants ×2 EGP 600". */
function suggestionParts(s: RefundSuggestion): string {
  return s.lines
    .filter(l => l.lineTotal != null)
    .map(l => `${l.productTitle}${l.quantity > 1 ? ` ×${l.quantity}` : ''} ${formatMoney(l.lineTotal, s.currency)}`)
    .join(' + ')
}

/** R1 — record a refund. The amount is prefilled from the suggestion and stays editable. */
export function RefundForm({
  detail, suggestion, onSaved, onCancel,
}: {
  detail: ReturnRequestDetail
  suggestion: RefundSuggestion | null
  onSaved: () => Promise<void>
  onCancel?: () => void
}) {
  const { t } = useTranslation()
  const { toast } = useToast()
  const id = useId()
  const currency = suggestion?.currency ?? detail.currency ?? 'EGP'
  const [method, setMethod] = useState<RefundMethod | null>(null)
  const [amount, setAmount] = useState('')
  const [amountTouched, setAmountTouched] = useState(false)
  const [date, setDate] = useState(todayIso())
  const [reference, setReference] = useState('')
  const [note, setNote] = useState('')
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [saving, setSaving] = useState(false)

  useEffect(() => {
    if (!amountTouched && suggestion?.amount) setAmount(trimZeros(suggestion.amount))
  }, [suggestion, amountTouched])

  const restocked = detail.items.filter(i => i.itemStatus === 'done' && i.disposition === 'restocked')

  async function submit(e: FormEvent) {
    e.preventDefault()
    const next: Record<string, string> = {}
    const value = Number(amount.replace(/,/g, ''))
    if (!method) next.method = t('exchangesRefunds.requests.refund.methodRequired')
    if (!amount.trim() || !Number.isFinite(value) || value <= 0 || !/^\d+(\.\d{1,2})?$/.test(amount.replace(/,/g, '').trim())) {
      next.amount = t('exchangesRefunds.requests.refund.amountInvalid')
    }
    if (!date || date > todayIso()) next.date = t('exchangesRefunds.requests.refund.dateInvalid')
    setErrors(next)
    if (Object.keys(next).length > 0) return
    setSaving(true)
    try {
      await recordRefund(detail.id, {
        method: method!, amount: amount.replace(/,/g, '').trim(), refundedOn: date,
        reference: reference.trim() || undefined, note: note.trim() || undefined,
      })
      toast({ tone: 'success', message: t('exchangesRefunds.requests.refund.saved') })
      await onSaved()
    } catch (err) {
      const code = err instanceof Error ? err.message.slice(0, 3) : ''
      toast({ tone: 'error', message: t(code === '409' ? 'exchangesRefunds.requests.refund.conflict' : 'exchangesRefunds.requests.refund.failed') })
      if (code === '409') await onSaved()
    } finally {
      setSaving(false)
    }
  }

  return (
    <form onSubmit={submit} noValidate className="rounded-xl border border-line bg-elevated p-5 space-y-4" data-testid="refund-form">
      <div className="space-y-1">
        <h3 className="text-body-lg font-semibold text-primary">{t('exchangesRefunds.requests.refund.title')}</h3>
        {suggestion?.amount && (
          <p className="text-body text-secondary" data-testid="refund-suggestion">
            {t('exchangesRefunds.requests.refund.suggested')}{' '}
            <strong className="text-primary" dir="ltr">{formatMoney(suggestion.amount, currency)}</strong>
            {suggestionParts(suggestion) && <>: <bdi>{suggestionParts(suggestion)}</bdi></>}
            {t('exchangesRefunds.requests.reasonSeparator')}{t(`exchangesRefunds.requests.refund.source.${suggestion.source}`)}
            {suggestion.approximate && (
              <span className="ms-2" data-testid="refund-approximate">
                <Badge tone="warning" label={t('exchangesRefunds.requests.refund.approximate')} />
              </span>
            )}
          </p>
        )}
      </div>

      <fieldset>
        <legend className="text-body font-semibold text-primary mb-2">{t('exchangesRefunds.requests.refund.method')}</legend>
        <div className="flex flex-wrap gap-2" role="radiogroup" aria-invalid={!!errors.method}>
          {REFUND_METHODS.map(m => (
            <label
              key={m}
              className={cn('inline-flex items-center gap-2 h-11 px-3.5 rounded-lg border bg-surface cursor-pointer text-body',
                method === m ? 'border-trace-blue ring-1 ring-trace-blue font-semibold text-primary' : 'border-line text-primary')}
            >
              <input type="radio" name={`${id}-method`} value={m} checked={method === m}
                onChange={() => { setMethod(m); setErrors(x => ({ ...x, method: '' })) }} className="accent-trace-blue" />
              {t(`exchangesRefunds.requests.refund.methods.${m}`)}
            </label>
          ))}
        </div>
        {errors.method && <p className="text-small text-critical mt-1" role="alert">{errors.method}</p>}
      </fieldset>

      <div className="grid grid-cols-2 gap-4">
        <div className="space-y-1.5">
          <label htmlFor={`${id}-amount`} className="text-body font-semibold text-primary block">{t('exchangesRefunds.requests.refund.amount')}</label>
          <div className="flex" dir="ltr">
            <span className="inline-flex items-center px-3 rounded-s-lg border border-e-0 border-line bg-elevated text-muted text-body">{currency}</span>
            <input
              id={`${id}-amount`} className={cn('input rounded-s-none flex-1 min-w-0', errors.amount && 'border-critical')}
              inputMode="decimal" autoComplete="off" value={amount} aria-invalid={!!errors.amount}
              onChange={e => { setAmount(e.target.value); setAmountTouched(true); setErrors(x => ({ ...x, amount: '' })) }}
            />
          </div>
          {errors.amount && <p className="text-small text-critical" role="alert">{errors.amount}</p>}
        </div>
        <div className="space-y-1.5">
          <label htmlFor={`${id}-date`} className="text-body font-semibold text-primary block">{t('exchangesRefunds.requests.refund.date')}</label>
          <input
            id={`${id}-date`} type="date" className={cn('input w-full', errors.date && 'border-critical')} max={todayIso()}
            value={date} aria-invalid={!!errors.date} onChange={e => { setDate(e.target.value); setErrors(x => ({ ...x, date: '' })) }}
          />
          {errors.date && <p className="text-small text-critical" role="alert">{errors.date}</p>}
        </div>
      </div>

      <div className="space-y-1.5">
        <label htmlFor={`${id}-reference`} className="text-body font-semibold text-primary block">
          {t('exchangesRefunds.requests.refund.reference')} <span className="font-normal text-muted">{t('exchangesRefunds.requests.refund.optional')}</span>
        </label>
        <input
          id={`${id}-reference`} className="input w-full" dir="auto" maxLength={REFUND_REFERENCE_MAX} value={reference}
          placeholder={t('exchangesRefunds.requests.refund.referencePlaceholder')} onChange={e => setReference(e.target.value)}
        />
      </div>

      <div className="space-y-1.5">
        <label htmlFor={`${id}-note`} className="text-body font-semibold text-primary block">
          {t('exchangesRefunds.requests.refund.note')} <span className="font-normal text-muted">{t('exchangesRefunds.requests.refund.optional')}</span>
        </label>
        <textarea
          id={`${id}-note`} rows={2} className="input w-full resize-none" dir="auto" maxLength={REFUND_NOTE_MAX}
          value={note} onChange={e => setNote(e.target.value)}
        />
      </div>

      {restocked.length > 0 && (
        <div className="flex gap-3 rounded-lg px-3.5 py-3 border border-warning/30 bg-warning/[0.08]" data-testid="restock-warning">
          <Info size={18} strokeWidth={2} className="flex-shrink-0 mt-0.5 text-warning-text" />
          <p className="text-small text-warning-text">
            {t('exchangesRefunds.requests.refund.restockWarning', {
              count: restocked.length, items: restocked.map(i => i.productTitle).join(t('exchangesRefunds.requests.reasonSeparator')),
            })}
          </p>
        </div>
      )}

      <div className="flex gap-2">
        {onCancel && (
          <Button type="button" variant="outline" disabled={saving} onClick={onCancel}>
            {t('exchangesRefunds.requests.drawer.cancel')}
          </Button>
        )}
        <Button type="submit" className="flex-1" loading={saving} disabled={saving}>
          {t('exchangesRefunds.requests.refund.save')}
        </Button>
      </div>
    </form>
  )
}

function trimZeros(amount: string): string {
  return amount.includes('.') ? amount.replace(/\.?0+$/, '') : amount
}

/** R2 — the refunds recorded so far, each voidable once (with a confirm) while the request is open. */
export function RefundsList({
  detail, suggestion, canChange, onChanged,
}: {
  detail: ReturnRequestDetail
  suggestion: RefundSuggestion | null
  canChange: boolean
  onChanged: () => Promise<void>
}) {
  const { t, i18n } = useTranslation()
  const { toast } = useToast()
  const [confirming, setConfirming] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const refunds = detail.refunds ?? []
  const currency = detail.currency ?? 'EGP'

  async function doVoid(r: ReturnRefund) {
    setBusy(true)
    try {
      await voidRefund(detail.id, r.id)
      toast({ tone: 'success', message: t('exchangesRefunds.requests.refund.voided') })
      setConfirming(null)
      await onChanged()
    } catch {
      toast({ tone: 'error', message: t('exchangesRefunds.requests.refund.failed') })
    } finally {
      setBusy(false)
    }
  }

  return (
    <section data-testid="refunds-list">
      <div className="flex items-baseline justify-between gap-3 mb-2">
        <h3 className="text-body-lg font-semibold text-primary">{t('exchangesRefunds.requests.refund.listTitle')}</h3>
        <p className="text-small text-muted" data-testid="refunds-total">
          {t('exchangesRefunds.requests.refund.total')}{' '}
          <strong className="text-primary" dir="ltr">{formatMoney(detail.refundTotal ?? '0', currency)}</strong>
          {suggestion?.amount && <> {t('exchangesRefunds.requests.refund.ofSuggested', { amount: formatMoney(suggestion.amount, '').trim() })}</>}
        </p>
      </div>
      <ul className="space-y-2">
        {refunds.map(r => (
          <li key={r.id} className={cn('card px-4 py-3.5', r.voided && 'opacity-70')} data-testid="refund-entry">
            <div className="flex items-center gap-3">
              <span className={cn('w-10 h-10 rounded-lg flex items-center justify-center shrink-0',
                r.voided ? 'bg-elevated text-muted' : 'bg-success/10 text-success')}>
                <Check size={18} strokeWidth={2.2} />
              </span>
              <div className="min-w-0 flex-1">
                <p className={cn('text-body font-semibold text-primary', r.voided && 'line-through')}>
                  <span dir="ltr">{formatMoney(r.amount, r.currency)}</span> · {t(`exchangesRefunds.requests.refund.methods.${r.method}`)}
                </p>
                <p className="text-small text-muted">
                  {shortDate(i18n.language, r.refundedOn)}
                  {r.reference && <> · {t('exchangesRefunds.requests.refund.ref')} <span className="font-mono" dir="ltr"><bdi>{r.reference}</bdi></span></>}
                  {r.recordedByName && <> · {t('exchangesRefunds.requests.refund.recordedBy', { name: r.recordedByName })}</>}
                </p>
                {r.voided && (
                  <p className="text-small text-critical" data-testid="refund-voided">
                    {t('exchangesRefunds.requests.refund.voidedBy', { name: r.voidedByName ?? '' })}
                  </p>
                )}
              </div>
              {canChange && !r.voided && confirming !== r.id && (
                <button type="button" className="text-small font-medium text-critical hover:underline"
                  onClick={() => setConfirming(r.id)}>
                  {t('exchangesRefunds.requests.refund.void')}
                </button>
              )}
            </div>
            {confirming === r.id && (
              <div className="mt-3 pt-3 border-t border-line flex flex-wrap items-center justify-between gap-2" data-testid="void-confirm">
                <p className="text-small text-primary">{t('exchangesRefunds.requests.refund.voidConfirm')}</p>
                <div className="flex gap-2">
                  <Button size="sm" variant="outline" disabled={busy} onClick={() => setConfirming(null)}>
                    {t('exchangesRefunds.requests.drawer.cancel')}
                  </Button>
                  <Button size="sm" variant="danger" loading={busy} disabled={busy} onClick={() => doVoid(r)}>
                    {t('exchangesRefunds.requests.refund.voidConfirmButton')}
                  </Button>
                </div>
              </div>
            )}
          </li>
        ))}
      </ul>
    </section>
  )
}

/** Request-level events shown in the R2 timeline (item-level events stay out of it). */
const TIMELINE_EVENTS = new Set([
  'requested', 'approved', 'rejected', 'pickup_booked', 'leg_linked', 'received', 'refund_pending',
  'refund_recorded', 'refund_voided', 'refunded', 'rest_not_coming', 'closed', 'item_substituted',
  'unexpected_item_received',
])

function eventTitle(t: ReturnType<typeof useTranslation>['t'], e: ReturnRequestEvent): ReactNode {
  const m = (e.metadata ?? {}) as Record<string, string | number | null>
  const k = `exchangesRefunds.requests.history.${e.type}`
  switch (e.type) {
    case 'pickup_booked':
    case 'leg_linked':
      return m.tracking_number
        ? <>{t(k)} · <span className="font-mono" dir="ltr"><bdi>AWB {m.tracking_number}</bdi></span></>
        : t(k)
    case 'refund_recorded':
      return <>{t(k)} · <span dir="ltr">{formatMoney(m.amount as string, (m.currency as string) ?? 'EGP')}</span>{' '}
        {m.method ? t(`exchangesRefunds.requests.refund.methods.${m.method}`) : ''}</>
    case 'refund_voided':
      return <>{t(k)} · <span dir="ltr">{formatMoney(m.amount as string, (m.currency as string) ?? 'EGP')}</span></>
    case 'closed':
      return m.reason ? `${t(k)} · ${t(`exchangesRefunds.requests.closeReasonsShort.${m.reason}`)}` : t(k)
    default:
      return t(k, { defaultValue: e.type.replace(/_/g, ' ') })
  }
}

function eventDetail(t: ReturnType<typeof useTranslation>['t'], e: ReturnRequestEvent): string {
  const m = (e.metadata ?? {}) as Record<string, string | number | boolean | null>
  const h = 'exchangesRefunds.requests.history'
  if (e.type === 'requested') return t(`${h}.viaPortal`)
  if (e.type === 'received') return e.actorName ? t(`${h}.scannedBy`, { name: e.actorName }) : t(`${h}.traced`)
  if (e.type === 'refund_pending') return t(`${h}.itemsDecided`, { count: Number(m.items ?? 0) })
  if (e.type === 'approved' && !e.actorName) return t(`${h}.automatically`)
  return e.actorName ?? t(`${h}.traced`)
}

/** R2 — the request history, newest first. */
export function HistoryTimeline({ detail }: { detail: ReturnRequestDetail }) {
  const { t, i18n } = useTranslation()
  const events = (detail.history ?? []).filter(e => TIMELINE_EVENTS.has(e.type)
    && !(e.type === 'leg_linked' && (e.metadata as Record<string, unknown> | null)?.source === 'traced_booking'))
  if (events.length === 0) return null
  return (
    <section data-testid="request-history">
      <h3 className="text-body-lg font-semibold text-primary mb-3">{t('exchangesRefunds.requests.history.title')}</h3>
      <ol className="relative">
        {events.map((e, i) => (
          <li key={`${e.type}-${e.occurredAt}-${i}`} className="relative ps-6 pb-4 last:pb-0" data-testid="history-entry">
            {i < events.length - 1 && <span className="absolute start-[5px] top-3 bottom-0 w-px bg-line" aria-hidden="true" />}
            <span className={cn('absolute start-0 top-1.5 w-[11px] h-[11px] rounded-full',
              i === 0 ? 'bg-success' : 'bg-muted/60')} aria-hidden="true" />
            <p className="text-body font-semibold text-primary">{eventTitle(t, e)}</p>
            <p className="text-small text-muted">{dateTimeLabel(i18n.language, e.occurredAt)} · <bdi>{eventDetail(t, e)}</bdi></p>
          </li>
        ))}
      </ol>
    </section>
  )
}

/** Shared dialog shell for R4 / R5. */
function Dialog({ labelledBy, onClose, children, width }: { labelledBy: string; onClose: () => void; children: ReactNode; width: string }) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose() }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [onClose])
  return (
    <div className="fixed inset-0 bg-black/45 flex items-center justify-center z-[60] p-4" onClick={onClose}>
      <div role="dialog" aria-modal="true" aria-labelledby={labelledBy}
        className={cn('bg-surface rounded-2xl shadow-e4 w-full p-6 space-y-4', width)}
        onClick={e => e.stopPropagation()}>
        {children}
      </div>
    </div>
  )
}

function ChoiceCard({ checked, onChange, name, title, hint }: {
  checked: boolean; onChange: () => void; name: string; title: ReactNode; hint?: ReactNode
}) {
  return (
    <label className={cn('flex gap-3 rounded-xl border px-4 py-3 cursor-pointer',
      checked ? 'border-trace-blue ring-1 ring-trace-blue bg-trace-blue/[0.06]' : 'border-line')}>
      <input type="radio" name={name} checked={checked} onChange={onChange} className="mt-1 accent-trace-blue" />
      <span className="min-w-0">
        <span className="block text-body font-medium text-primary">{title}</span>
        {hint && <span className="block text-small text-muted">{hint}</span>}
      </span>
    </label>
  )
}

/** R4 — close the request without a refund (4d-1 close endpoint). */
export function CloseDialog({ detail, onClose, onDone }: { detail: ReturnRequestDetail; onClose: () => void; onDone: () => Promise<void> }) {
  const { t } = useTranslation()
  const { toast } = useToast()
  const id = useId()
  const [reason, setReason] = useState<'no_refund' | 'other'>('no_refund')
  const [note, setNote] = useState('')
  const [busy, setBusy] = useState(false)

  async function submit() {
    setBusy(true)
    try {
      await closeReturnRequest(detail.id, reason, note.trim() || undefined)
      toast({ tone: 'success', message: t('exchangesRefunds.requests.close.done') })
      onClose()
      await onDone()
    } catch (e) {
      const code = e instanceof Error ? e.message.slice(0, 3) : ''
      toast({ tone: 'error', message: t(code === '409' ? 'exchangesRefunds.requests.close.conflict' : 'exchangesRefunds.requests.drawer.actionFailed') })
    } finally {
      setBusy(false)
    }
  }

  return (
    <Dialog labelledBy={`${id}-title`} onClose={onClose} width="max-w-[500px]">
      <div data-testid="close-dialog" className="space-y-4">
        <div className="space-y-1">
          <h2 id={`${id}-title`} className="text-h3 text-primary">
            {t('exchangesRefunds.requests.close.title')} <span className="font-mono"><bdi>{detail.reference}</bdi></span>
          </h2>
          <p className="text-body text-secondary">{t('exchangesRefunds.requests.close.body')}</p>
        </div>
        <fieldset className="space-y-2">
          <legend className="text-body font-semibold text-primary mb-2">{t('exchangesRefunds.requests.close.reason')}</legend>
          <ChoiceCard name={`${id}-reason`} checked={reason === 'no_refund'} onChange={() => setReason('no_refund')}
            title={t('exchangesRefunds.requests.close.noRefund')} hint={t('exchangesRefunds.requests.close.noRefundHint')} />
          <ChoiceCard name={`${id}-reason`} checked={reason === 'other'} onChange={() => setReason('other')}
            title={t('exchangesRefunds.requests.close.other')} hint={t('exchangesRefunds.requests.close.otherHint')} />
        </fieldset>
        <div className="space-y-1.5">
          <label htmlFor={`${id}-note`} className="text-body font-semibold text-primary block">
            {t('exchangesRefunds.requests.refund.note')} <span className="font-normal text-muted">{t('exchangesRefunds.requests.refund.optional')}</span>
          </label>
          <textarea id={`${id}-note`} rows={3} dir="auto" className="input w-full resize-none" maxLength={CLOSE_NOTE_MAX}
            value={note} onChange={e => setNote(e.target.value)} />
        </div>
        <div className="flex justify-end gap-2">
          <Button variant="outline" disabled={busy} onClick={onClose}>{t('exchangesRefunds.requests.drawer.cancel')}</Button>
          <Button variant="danger" loading={busy} disabled={busy} onClick={submit}>{t('exchangesRefunds.requests.close.confirm')}</Button>
        </div>
      </div>
    </Dialog>
  )
}

/** R5 — which request is this courier return for? (4d-1 link-leg endpoint). */
export function LinkParcelDialog({
  parcel, orderNumber, preselect, onClose, onDone,
}: {
  parcel: LinkableParcel
  orderNumber: string
  preselect: string | null
  onClose: () => void
  onDone: () => Promise<void>
}) {
  const { t, i18n } = useTranslation()
  const { toast } = useToast()
  const id = useId()
  const [selected, setSelected] = useState<string | null>(
    preselect && parcel.candidates.some(c => c.id === preselect) ? preselect : parcel.candidates[0]?.id ?? null)
  const [busy, setBusy] = useState(false)
  const note = i18n.language === 'ar' ? parcel.descriptionAr || parcel.description : parcel.description

  async function submit() {
    if (!selected) return
    setBusy(true)
    try {
      await linkReturnLeg(selected, parcel.shipmentId)
      toast({ tone: 'success', message: t('exchangesRefunds.requests.link.done') })
      onClose()
      await onDone()
    } catch (e) {
      const code = e instanceof Error ? e.message.slice(0, 3) : ''
      toast({ tone: 'error', message: t(code === '409' ? 'exchangesRefunds.requests.link.conflict' : 'exchangesRefunds.requests.drawer.actionFailed') })
    } finally {
      setBusy(false)
    }
  }

  return (
    <Dialog labelledBy={`${id}-title`} onClose={onClose} width="max-w-[560px]">
      <div data-testid="link-parcel-dialog" className="space-y-4">
        <div className="space-y-1">
          <h2 id={`${id}-title`} className="text-h3 text-primary">{t('exchangesRefunds.requests.link.title')}</h2>
          <p className="text-body text-secondary">{t('exchangesRefunds.requests.link.body')}</p>
        </div>
        <div className="rounded-xl border border-line bg-elevated px-4 py-3 space-y-0.5">
          <p className="font-mono text-body font-semibold text-primary" dir="ltr">
            <bdi>AWB {parcel.trackingNumber}</bdi> · <bdi>{t('exchangesRefunds.requests.link.order', { number: orderNumber })}</bdi>
          </p>
          {note && (
            <p className="text-small text-secondary">
              {t('exchangesRefunds.requests.link.bostaNote', { count: parcel.itemsCount ?? 0 })} <bdi>{note}</bdi>
            </p>
          )}
        </div>
        <fieldset className="space-y-2">
          <legend className="text-body font-semibold text-primary mb-2">{t('exchangesRefunds.requests.link.candidates')}</legend>
          {parcel.candidates.map(c => (
            <ChoiceCard key={c.id} name={`${id}-request`} checked={selected === c.id} onChange={() => setSelected(c.id)}
              title={<><span className="font-mono"><bdi>{c.reference}</bdi></span> · {t('exchangesRefunds.requests.link.items', { count: c.itemCount })}</>}
              hint={<><bdi>{c.itemSummary ?? ''}</bdi>{c.decidedAt && <> · {t('exchangesRefunds.requests.link.approvedOn', { date: shortDate(i18n.language, c.decidedAt) })}</>}</>} />
          ))}
        </fieldset>
        <div className="flex justify-end gap-2">
          <Button variant="outline" disabled={busy} onClick={onClose}>{t('exchangesRefunds.requests.link.notNow')}</Button>
          <Button loading={busy} disabled={busy || !selected} onClick={submit}>{t('exchangesRefunds.requests.link.confirm')}</Button>
        </div>
      </div>
    </Dialog>
  )
}
