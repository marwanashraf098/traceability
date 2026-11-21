import { useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Loader2 } from 'lucide-react'
import {
  BazarError, changeBazarPayment, currentUserId, managesBazar, setBazarCustomer, undoBazarSale,
  SELLER_UNDO_WINDOW_MS, type BazarSale, type PaymentMethod,
} from '../../../bazar/api'
import { pieceTitle, saleTime, sellErrorText } from '../../../bazar/format'
import { useScanBurst } from '../../../bazar/useScanBurst'
import { Button, Input, cn } from '../../../components/ui'
import { Sheet } from './shared'
import { money } from '../r2/parts'

// What the phone offers on a sale follows B2's rules (the server is still the referee — a refusal is shown as is):
//   change method / customer: owner or manager, or the seller who made the sale — while the event is live;
//   undo: owner or manager any time while live; the seller who made it within 2 minutes.

export function isMine(sale: BazarSale): boolean {
  const me = currentUserId()
  return !!me && sale.seller_id === me
}

export function canEditSale(sale: BazarSale): boolean {
  return sale.status === 'active' && (managesBazar() || isMine(sale))
}

export function canUndoSale(sale: BazarSale, now = Date.now()): boolean {
  if (sale.status !== 'active' || sale.exchange_of_sale_id) return false
  if (managesBazar()) return true
  return isMine(sale) && now - new Date(sale.sold_at).getTime() < SELLER_UNDO_WINDOW_MS
}

/** Undone · Returned · Exchanged (the old half) · Exchange (the new half) · else the method. */
export function SaleBadge({ sale }: { sale: BazarSale }) {
  const { t } = useTranslation()
  const neutral = 'badge border bg-muted/[0.14] text-neutral-text border-muted/[0.30]'
  const warn = 'badge border bg-warning/[0.12] text-warning-text border-warning/[0.30]'
  const info = 'badge border bg-trace-blue/[0.10] text-trace-blue border-trace-blue/[0.30]'
  if (sale.status === 'undone') return <span className={neutral}>{t('bazar.sell.undoneShort')}</span>
  if (sale.status === 'returned') {
    return sale.exchanged_for_sale_number
      ? <span className={info} data-testid="badge-exchanged">{t('bazar.sell.exchangedBadge')}</span>
      : <span className={warn} data-testid="badge-returned">{t('bazar.sell.returnedBadge')}</span>
  }
  if (sale.exchange_of_sale_number) return <span className={info} data-testid="badge-exchange">{t('bazar.sell.exchangeBadge')}</span>
  return sale.method ? <span className={neutral}>{t(`bazar.method.${sale.method}`)}</span> : null
}

export function MethodChips({ methods, value, onPick, busy, tone = 'plain', disabled }: {
  methods: PaymentMethod[]
  value: PaymentMethod | null
  onPick: (m: PaymentMethod) => void
  busy: PaymentMethod | null
  tone?: 'plain' | 'onGreen'
  disabled?: boolean
}) {
  const { t } = useTranslation()
  return (
    <div className="grid gap-1.5" style={{ gridTemplateColumns: `repeat(${Math.max(methods.length, 1)}, minmax(0, 1fr))` }}
         role="group" aria-label={t('bazar.sell.paidBy')}>
      {methods.map(m => {
        const on = m === value
        return (
          <button key={m} type="button" aria-pressed={on} disabled={disabled || busy !== null}
            onClick={() => { if (!on) onPick(m) }}
            className={cn('h-12 rounded-[10px] text-body font-semibold flex items-center justify-center gap-1.5 transition-colors disabled:opacity-70',
              tone === 'onGreen'
                ? on ? 'bg-white text-success-text' : 'bg-white/[0.16] text-white hover:bg-white/[0.22]'
                : on ? 'bg-[#0D1117] text-white' : 'border border-line bg-surface text-primary hover:bg-charcoal')}>
            {busy === m && <Loader2 size={14} className="animate-spin" aria-hidden />}
            {t(`bazar.method.${m}`)}
          </button>
        )
      })}
    </div>
  )
}

/** Change the method of a sale (void + new payment row on the server). */
export function usePaymentChange(eventId: string, onChanged: (s: BazarSale) => void) {
  const [busy, setBusy] = useState<PaymentMethod | null>(null)
  const [error, setError] = useState<BazarError | null>(null)
  async function pick(sale: BazarSale, m: PaymentMethod) {
    setBusy(m)
    setError(null)
    try {
      onChanged(await changeBazarPayment(eventId, sale.id, m))
    } catch (e) {
      setError(e instanceof BazarError ? e : new BazarError(0, { code: 'NETWORK' }))
    } finally {
      setBusy(null)
    }
  }
  return { busy, error, pick }
}

export function ErrorLine({ error }: { error: BazarError | null }) {
  const { t, i18n } = useTranslation()
  if (!error) return null
  const text = error.code === 'NETWORK' ? t('bazar.phone.networkError') : sellErrorText(t, i18n.language, error).title
  return <p role="alert" className="text-small text-critical">{text}</p>
}

/**
 * "+ Customer": name and Egyptian mobile, both optional. A worker sees a saved phone masked: the field starts empty
 * and, left empty, isn't sent — the server keeps the stored number (a field not sent stays as it is).
 */
export function CustomerSheet({ eventId, sale, onSaved, onClose, onScanBurst }: {
  eventId: string
  sale: BazarSale
  onSaved: (s: BazarSale) => void
  onClose: () => void
  /**
   * B6c review: a HID scan typed into the name / phone box goes to the screen's scan path (Quick: a sale; Cart: the
   * cart) and the box is put back as it was (useScanBurst). Normal typing is untouched.
   */
  onScanBurst?: (code: string) => void
}) {
  const { t } = useTranslation()
  const fields = useRef<HTMLDivElement>(null)
  useScanBurst(fields, onScanBurst)
  const masked = !!sale.customer_phone && sale.customer_phone.includes('*')
  const [name, setName] = useState(sale.customer_name ?? '')
  const [phone, setPhone] = useState(masked ? '' : sale.customer_phone ?? '')
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<BazarError | null>(null)

  async function save() {
    setSaving(true)
    setError(null)
    const fields: { name: string | null; phone?: string | null } = { name: name.trim() || null }
    if (!masked || phone.trim()) fields.phone = phone.trim() || null
    try {
      onSaved(await setBazarCustomer(eventId, sale.id, fields))
      onClose()
    } catch (e) {
      setError(e instanceof BazarError ? e : new BazarError(0, { code: 'NETWORK' }))
    } finally {
      setSaving(false)
    }
  }

  return (
    <Sheet title={t('bazar.customer.title')} onClose={onClose} testId="bazar-customer-sheet">
      <div ref={fields} className="contents">
      <label className="flex flex-col gap-1">
        <span className="text-small font-medium text-muted">{t('bazar.customer.name')}</span>
        <Input value={name} maxLength={80} autoComplete="off" onChange={e => setName(e.target.value)}
               placeholder={t('bazar.customer.namePlaceholder')} />
      </label>
      <div className="flex flex-col gap-1">
        <label htmlFor="bazar-customer-phone" className="text-small font-medium text-muted">{t('bazar.customer.phone')}</label>
        <Input id="bazar-customer-phone" value={phone} inputMode="tel" autoComplete="off" dir="ltr"
               onChange={e => setPhone(e.target.value)} placeholder="01012345678" />
        {masked && (
          <p className="text-small text-muted" data-testid="bazar-customer-keep-hint">
            {t('bazar.customer.keepPhoneHint')} <span className="font-mono" dir="ltr">{sale.customer_phone}</span>
          </p>
        )}
      </div>
      </div>
      <ErrorLine error={error} />
      <Button className="w-full" onClick={save} loading={saving}>{t('bazar.customer.save')}</Button>
    </Sheet>
  )
}

/** A sale from the recent list: change method, add / edit the customer, undo — as the API allows. */
export function SaleSheet({ eventId, sale, methods, onChanged, onClose, onScanBurst }: {
  eventId: string
  sale: BazarSale
  methods: PaymentMethod[]
  onChanged: (s: BazarSale) => void
  onClose: () => void
  onScanBurst?: (code: string) => void
}) {
  const { t } = useTranslation()
  const [current, setCurrent] = useState(sale)
  const [customerOpen, setCustomerOpen] = useState(false)
  const [undoing, setUndoing] = useState(false)
  const [undoError, setUndoError] = useState<BazarError | null>(null)
  const changed = (s: BazarSale) => { setCurrent(s); onChanged(s) }
  const pay = usePaymentChange(eventId, changed)
  const editable = canEditSale(current)
  const undoable = canUndoSale(current)

  async function undo() {
    setUndoing(true)
    setUndoError(null)
    try {
      changed(await undoBazarSale(eventId, current.id))
    } catch (e) {
      setUndoError(e instanceof BazarError ? e : new BazarError(0, { code: 'NETWORK' }))
    } finally {
      setUndoing(false)
    }
  }

  if (customerOpen) {
    return <CustomerSheet eventId={eventId} sale={current} onSaved={changed} onClose={() => setCustomerOpen(false)}
                          onScanBurst={onScanBurst} />
  }

  return (
    <Sheet title={current.sale_number} onClose={onClose} testId="bazar-sale-sheet">
      <div className="flex items-start justify-between gap-3">
        <div className="flex flex-col min-w-0">
          <span className="font-semibold text-primary">{pieceTitle(current)}</span>
          <span className="text-small text-muted">
            {saleTime(current.sold_at)} · {isMine(current) ? t('bazar.sell.byYou') : current.seller_name}
            {current.piece_code && <> · <span className="font-mono" dir="ltr">{current.piece_code}</span></>}
          </span>
        </div>
        <span className={cn('text-h4 font-bold tabular-nums', current.status !== 'active' && 'line-through text-muted')}>
          {money(current.price)}
        </span>
      </div>
      {current.status === 'undone' && (
        <p className="text-small font-semibold text-muted" data-testid="sale-undone">{t('bazar.sell.undoneLabel')}</p>
      )}
      {current.status === 'returned' && (
        <p className="text-small font-semibold text-warning-text" data-testid="sale-returned">
          {current.exchanged_for_sale_number
            ? t('bazar.sell.exchangedLabel', { number: current.exchanged_for_sale_number, time: saleTime(current.returned_at) })
            : t('bazar.sell.returnedLabel', { time: saleTime(current.returned_at), name: current.returned_by_name ?? '' })}
          {current.return_condition === 'damaged' && ` · ${t('bazar.ret.cond.damaged')}`}
        </p>
      )}
      {current.exchange_of_sale_number && (
        <p className="text-small text-muted" data-testid="sale-exchange-of">
          {t('bazar.sell.exchangeOfLabel', { number: current.exchange_of_sale_number })}
        </p>
      )}

      {current.status === 'active' && (
        <>
          <span className="text-small font-medium text-muted">{t('bazar.sell.paidBy')}</span>
          <MethodChips methods={methods} value={current.method} busy={pay.busy} disabled={!editable}
                       onPick={m => pay.pick(current, m)} />
          <ErrorLine error={pay.error} />
          <div className="flex items-center justify-between gap-2 py-1">
            <span className="text-body text-primary min-w-0 truncate">
              {current.customer_name || current.customer_phone
                ? [current.customer_name, current.customer_phone].filter(Boolean).join(' · ')
                : <span className="text-muted">{t('bazar.customer.none')}</span>}
            </span>
            {editable && (
              <Button variant="secondary" size="sm" onClick={() => setCustomerOpen(true)}>
                {current.customer_name || current.customer_phone ? t('bazar.customer.edit') : t('bazar.customer.add')}
              </Button>
            )}
          </div>
          {undoable && (
            <Button variant="secondary" className="w-full" loading={undoing} onClick={undo}>{t('bazar.sell.undoSale')}</Button>
          )}
          <ErrorLine error={undoError} />
          {!editable && <p className="text-small text-muted">{t('bazar.sell.notYours')}</p>}
        </>
      )}
    </Sheet>
  )
}
