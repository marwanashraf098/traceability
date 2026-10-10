import { ReactNode, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Banknote, Building2, Smartphone, Trash2, Zap } from 'lucide-react'
import { getRefundDetails, RefundDetailsView, ReturnRequestDetail } from '../../api'
import { Button } from '../../components/ui'
import { writeToClipboard } from '../settings/connections/CopyRow'

const ICONS: Record<string, ReactNode> = {
  bank_transfer: <Building2 className="w-[18px] h-[18px]" aria-hidden="true" />,
  instapay: <Zap className="w-[18px] h-[18px]" aria-hidden="true" />,
  wallet: <Smartphone className="w-[18px] h-[18px]" aria-hidden="true" />,
  cash: <Banknote className="w-[18px] h-[18px]" aria-hidden="true" />,
}

/**
 * Returns portal P2 — "Customer asked for" (design/Traced_portal_refund_method_dc.html, c): the
 * method + hint the request detail carries; "Show details" fetches the decrypted details on demand
 * (GET /return-requests/{id}/refund-details — nothing cached, nothing kept after Hide); after the
 * 30-day purge or a privacy request only the method (and hint) remain.
 */
export default function CustomerRefundChoice({ detail }: { detail: ReturnRequestDetail }) {
  const { t, i18n } = useTranslation()
  const [details, setDetails] = useState<RefundDetailsView | null>(null)
  const [loading, setLoading] = useState(false)
  const [failed, setFailed] = useState(false)
  const [copied, setCopied] = useState<string | null>(null)
  const method = detail.refundMethod
  if (!method) return null

  async function show() {
    setLoading(true)
    setFailed(false)
    try {
      setDetails(await getRefundDetails(detail.id))
    } catch {
      setFailed(true)
    } finally {
      setLoading(false)
    }
  }

  async function copy(key: string, value: string) {
    try {
      await writeToClipboard(value)
      setCopied(key)
      setTimeout(() => setCopied(c => (c === key ? null : c)), 1500)
    } catch { /* visible — copy by hand */ }
  }

  const rows: Array<{ key: string; label: string; value: string; mono?: boolean }> = []
  if (details) {
    if (details.holderName) rows.push({ key: 'holderName', label: t('exchangesRefunds.requests.asked.holderName'), value: details.holderName })
    if (details.bankName) rows.push({ key: 'bankName', label: t('exchangesRefunds.requests.asked.bankName'), value: details.bankName })
    if (details.account) {
      const iban = details.account.startsWith('EG')
      rows.push({ key: 'account', label: t(iban ? 'exchangesRefunds.requests.asked.iban' : 'exchangesRefunds.requests.asked.account'),
        value: iban ? details.account.replace(/(.{4})/g, '$1 ').trim() : details.account, mono: true })
    }
    if (details.instapay) rows.push({ key: 'instapay', label: t('exchangesRefunds.requests.asked.instapay'), value: details.instapay, mono: true })
    if (details.provider) rows.push({ key: 'provider', label: t('exchangesRefunds.requests.asked.provider'),
      value: t(`exchangesRefunds.requests.asked.providers.${details.provider}`, { defaultValue: details.provider }) })
    if (details.walletNumber) rows.push({ key: 'walletNumber', label: t('exchangesRefunds.requests.asked.walletNumber'), value: details.walletNumber, mono: true })
  }

  const purgedAt = detail.refundDetailsPurgedAt
  const canShow = method !== 'cash' && !!detail.refundDetailsAvailable

  return (
    <section className="rounded-xl border border-line bg-elevated p-3.5 space-y-2.5" data-testid="customer-refund-choice">
      <h3 className="text-caption font-semibold text-muted uppercase tracking-wider">{t('exchangesRefunds.requests.asked.label')}</h3>
      <div className="flex items-center gap-2.5">
        <span className="w-[34px] h-[34px] rounded-lg bg-panel border border-line grid place-items-center text-muted shrink-0">
          {ICONS[method]}
        </span>
        <p className="flex-1 min-w-0 text-body" data-testid="refund-choice-summary">
          <span className="font-semibold text-primary">{t(`exchangesRefunds.requests.refund.methods.${method}`)}</span>
          {detail.refundHint && <span className="text-muted"> · <bdi className="font-mono" dir="ltr">{detail.refundHint}</bdi></span>}
        </p>
        {canShow && (details
          ? <Button variant="tertiary" size="sm" onClick={() => setDetails(null)}>{t('exchangesRefunds.requests.asked.hide')}</Button>
          : <Button variant="secondary" size="sm" loading={loading} onClick={show}>{t('exchangesRefunds.requests.asked.show')}</Button>)}
      </div>
      {failed && <p className="text-small text-critical" role="alert">{t('exchangesRefunds.requests.asked.failed')}</p>}
      {details && (
        <dl className="rounded-lg bg-panel border border-line px-3" data-testid="refund-choice-details">
          {rows.map(r => (
            <div key={r.key} className="flex items-center justify-between gap-3 py-2.5 border-b border-line last:border-b-0">
              <dt className="text-small text-muted">{r.label}</dt>
              <dd className="flex items-center gap-1.5 min-w-0">
                <bdi className={r.mono ? 'font-mono text-body break-all' : 'text-body font-medium'} dir={r.mono ? 'ltr' : undefined}>{r.value}</bdi>
                <button type="button" className="text-small font-semibold text-trace-blue rounded-md px-2.5 py-1.5 hover:bg-trace-blue/10"
                  onClick={() => copy(r.key, r.mono ? r.value.replace(/\s/g, '') : r.value)}
                  aria-label={t('exchangesRefunds.requests.asked.copyLabel', { field: r.label })}>
                  {copied === r.key ? t('exchangesRefunds.requests.asked.copied') : t('exchangesRefunds.requests.asked.copy')}
                </button>
              </dd>
            </div>
          ))}
        </dl>
      )}
      {details && <p className="text-small text-muted">{t('exchangesRefunds.requests.asked.notStored')}</p>}
      {purgedAt && (
        <p className="text-small text-muted flex items-start gap-2" data-testid="refund-choice-removed">
          <Trash2 className="w-4 h-4 mt-0.5 shrink-0" aria-hidden="true" />
          {detail.refundHint
            ? t('exchangesRefunds.requests.asked.removed', {
                date: new Date(purgedAt).toLocaleDateString(i18n.language, { day: 'numeric', month: 'short', year: 'numeric' }) })
            : t('exchangesRefunds.requests.asked.removedPrivacy')}
        </p>
      )}
    </section>
  )
}
