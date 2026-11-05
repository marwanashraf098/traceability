import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { getRefundSuggestion, RefundSuggestion, ReturnRequestDetail } from '../../api'
import { RefundForm, RefundsList } from './RequestLifecycle'

/**
 * Returns portal fees F3 — an exchange that leaves the customer owed money (the replacement costs less than they paid,
 * after the fee): once the old item is back (received / exchanged), the merchant records what they paid the customer
 * — the same refund ledger and form, prefilled with the frozen amount owed. Renders nothing otherwise.
 */
export default function OwedPayment({ detail, onChanged }: { detail: ReturnRequestDetail; onChanged: () => Promise<void> }) {
  const { t } = useTranslation()
  const owed = Number(detail.amounts?.customerOwed ?? 0)
  const payable = detail.type === 'exchange' && owed > 0 && (detail.status === 'received' || detail.status === 'exchanged')
  const [suggestion, setSuggestion] = useState<RefundSuggestion | null>(null)
  const [adding, setAdding] = useState(false)
  useEffect(() => {
    if (!payable) return
    let cancelled = false
    getRefundSuggestion(detail.id)
      .then(s => { if (!cancelled) setSuggestion(s && typeof s === 'object' ? s : null) })
      .catch(() => { if (!cancelled) setSuggestion(null) })
    return () => { cancelled = true }
  }, [payable, detail.id])
  if (!payable) return null
  const refunds = detail.refunds ?? []
  const active = refunds.filter(r => !r.voided)
  const showForm = active.length === 0 || adding
  return (
    <section className="space-y-3" data-testid="owed-payment">
      {refunds.length > 0 && <RefundsList detail={detail} suggestion={suggestion} canChange onChanged={onChanged} />}
      {refunds.length > 0 && !adding && (
        <button type="button" className="text-body font-semibold text-trace-blue hover:underline" onClick={() => setAdding(true)}>
          {t('exchangesRefunds.requests.refund.addAnother')}
        </button>
      )}
      {showForm && (
        <RefundForm detail={detail} suggestion={suggestion}
          onSaved={async () => { setAdding(false); await onChanged() }}
          onCancel={adding ? () => setAdding(false) : undefined} />
      )}
    </section>
  )
}
