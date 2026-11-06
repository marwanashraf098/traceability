import { useId, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { overrideRequestAmounts, ReturnRequestDetail } from '../../api'
import { Badge, Button, useToast } from '../../components/ui'
import { formatMoney } from './requestFormat'

/**
 * Returns portal fees F2 — the request's frozen fee breakdown in the drawer (owner / manager only, like every amount
 * here). Refund: item value − fee = due. Exchange: what the customer owes (F3: + the price difference) and, since F4,
 * who collects it: "Bosta will collect EGP X" (booked as the type 30's COD), "Ask your courier to collect EGP X" (another
 * courier / a pre-connect order — display only), else "collect it yourself". Before approval the merchant may waive the
 * fee or change it.
 * Renders nothing for a request without amounts (no fee rule on at submit).
 */
/** F4 — the instruction by who collects (ExchangeCod / RequestAmounts.collector); none = the F2/F3 copy. */
const COLLECT_KEY: Record<string, string> = {
  bosta: 'collectBosta', bosta_pending: 'collectBostaPending', courier: 'collectCourier', merchant: 'collectMerchant',
}

export default function FeeBreakdown({ detail, onChanged }: { detail: ReturnRequestDetail; onChanged: () => Promise<void> }) {
  const { t } = useTranslation()
  const { toast } = useToast()
  const id = useId()
  const a = detail.amounts
  const [editing, setEditing] = useState(false)
  const [value, setValue] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  if (!a) return null
  const k = 'exchangesRefunds.requests.fees'
  const refund = a.kind === 'refund'
  // F3: an exchange priced by its difference (different product, or free swaps off).
  const diff = !refund && a.priceDifference != null ? Number(a.priceDifference) : null
  const signed = (n: number) => `${n > 0 ? '+ ' : n < 0 ? '− ' : ''}${money(String(Math.abs(n)))}`
  const money = (v: string | null) => formatMoney(v, a.currency)

  async function save(body: { waived: true } | { fee: number }) {
    setBusy(true)
    try {
      await overrideRequestAmounts(detail.id, body)
      toast({ tone: 'success', message: t(`${k}.saved`) })
      setEditing(false)
      await onChanged()
    } catch (e) {
      const code = e instanceof Error ? e.message.slice(0, 3) : ''
      toast({ tone: 'error', message: t(code === '409' ? `${k}.locked` : `${k}.failed`) })
      if (code === '409') await onChanged()
    } finally {
      setBusy(false)
    }
  }

  function submitFee() {
    const n = Number(value.trim())
    if (!/^\d+$/.test(value.trim()) || n > 30000) { setError(t(`${k}.feeInvalid`)); return }
    setError(null)
    void save({ fee: n })
  }

  const feeLabel = a.feeWaived
    ? t(a.waiveReason === 'merchant_override' ? `${k}.waivedByYou` : `${k}.waivedFault`)
    : refund ? `− ${money(a.fee)}` : money(a.fee)

  return (
    <section className="rounded-xl border border-line p-4 space-y-2" data-testid="fee-breakdown" aria-labelledby={`${id}-title`}>
      <div className="flex items-center justify-between gap-2">
        <h3 id={`${id}-title`} className="text-body font-semibold text-primary">
          {t(refund ? `${k}.titleRefund` : diff != null ? `${k}.titleExchangePrice` : `${k}.titleExchange`)}
        </h3>
        {a.approximate && <Badge tone="warning" label={t(`${k}.approximate`)} />}
      </div>
      <dl className="space-y-1 text-body">
        {diff == null ? (
          <div className="flex justify-between gap-4">
            <dt className="text-secondary">{t(`${k}.itemValue`)}</dt>
            <dd dir="ltr" data-testid="fee-item-value">{money(a.itemValue)}</dd>
          </div>
        ) : (
          <>
            <div className="flex justify-between gap-4">
              <dt className="text-secondary">{t(`${k}.paidPrice`)}</dt>
              <dd dir="ltr" data-testid="fee-paid-price">{money(a.paidPrice ?? a.itemValue)}</dd>
            </div>
            <div className="flex justify-between gap-4">
              <dt className="text-secondary">{t(`${k}.newPrice`)}</dt>
              <dd dir="ltr" data-testid="fee-new-price">{money(a.replacementPrice ?? null)}</dd>
            </div>
            <div className="flex justify-between gap-4">
              <dt className="text-secondary">{t(`${k}.priceDifference`)}</dt>
              <dd dir="ltr" data-testid="fee-difference">{signed(diff)}</dd>
            </div>
          </>
        )}
        <div className="flex justify-between gap-4">
          <dt className="text-secondary">
            {t(refund ? `${k}.returnFee` : `${k}.exchangeFee`)}
            {a.mode === 'percent' && a.percent && !a.feeWaived && <span className="text-muted"> · {t(`${k}.percentOf`, { percent: Number(a.percent) })}</span>}
          </dt>
          <dd dir="ltr" data-testid="fee-amount">{feeLabel}</dd>
        </div>
        {refund ? (
          <div className="flex justify-between gap-4 font-semibold border-t border-line pt-1">
            <dt>{t(`${k}.refundDue`)}</dt>
            <dd dir="ltr" data-testid="fee-due">{money(a.refundDue)}</dd>
          </div>
        ) : null}
      </dl>
      {a.overriddenAt && a.calculatedFee != null && (
        <p className="text-small text-muted" data-testid="fee-overridden">
          {t(`${k}.overridden`, { name: a.overriddenByName ?? '', amount: money(a.calculatedFee) })}
        </p>
      )}
      {refund && a.nothingOwed && <p className="text-small text-secondary" data-testid="fee-nothing-owed">{t(`${k}.nothingOwed`)}</p>}
      {!refund && Number(a.collect ?? 0) > 0 && (
        <p className="text-small text-secondary" data-testid="fee-collect">{t(`${k}.${COLLECT_KEY[a.collector ?? ''] ?? 'collect'}`, { amount: money(a.collector === 'bosta' && a.bookedCod ? a.bookedCod : a.collect) })}</p>
      )}
      {!refund && Number(a.customerOwed ?? 0) > 0 && (
        <p className="text-small text-secondary" data-testid="fee-owed">{t(`${k}.owed`, { amount: money(a.customerOwed ?? null) })}</p>
      )}
      {a.canOverride && !editing && (
        <div className="flex flex-wrap gap-2 pt-1">
          {!(a.feeWaived && a.waiveReason === 'merchant_override') && Number(a.fee) > 0 && (
            <Button size="sm" variant="outline" disabled={busy} onClick={() => void save({ waived: true })}>{t(`${k}.waive`)}</Button>
          )}
          <Button size="sm" variant="outline" disabled={busy} onClick={() => { setValue(String(Number(a.fee))); setEditing(true) }}>
            {t(`${k}.change`)}
          </Button>
        </div>
      )}
      {a.canOverride && editing && (
        <div className="space-y-1 pt-1">
          <label htmlFor={`${id}-fee`} className="text-small font-semibold text-primary block">{t(`${k}.newFee`)}</label>
          <div className="flex gap-2">
            <input id={`${id}-fee`} className="input w-32" inputMode="numeric" dir="ltr" value={value}
              aria-invalid={error != null} onChange={e => setValue(e.target.value)} />
            <Button size="sm" disabled={busy} onClick={submitFee}>{t(`${k}.save`)}</Button>
            <Button size="sm" variant="outline" disabled={busy} onClick={() => { setEditing(false); setError(null) }}>{t(`${k}.cancel`)}</Button>
          </div>
          {error && <p className="text-small text-danger" role="alert">{error}</p>}
        </div>
      )}
      {a.canOverride && <p className="text-small text-muted">{t(`${k}.beforeApproval`)}</p>}
    </section>
  )
}
