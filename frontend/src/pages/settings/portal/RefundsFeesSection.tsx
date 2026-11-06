import { useTranslation } from 'react-i18next'
import type { CustomerFeeSettings } from '../../../api'
import { cn } from '../../../components/ui'
import CustomerFeesSection, { FeeDraft } from '../CustomerFeesSection'
import { SectionCard } from './ui'

export const PORTAL_REFUND_METHODS = ['bank_transfer', 'instapay', 'wallet', 'cash'] as const

export function orderedMethods(v: string[]): string[] {
  return PORTAL_REFUND_METHODS.filter(m => v.includes(m))
}

export function sameMethods(a: string[], b: string[]): boolean {
  return orderedMethods(a).join() === orderedMethods(b).join()
}

/**
 * Refunds & fees — the refund methods (saved with "Save changes") and, while fees are shown for the store, the fee
 * table, the store's-fault reasons and fees by area (each with its own Save, as before; area fees save at once).
 */
export default function RefundsFeesSection({ refundMethods, onRefundMethods, fees, onFees, onFeeDraft }: {
  refundMethods: string[]
  onRefundMethods: (v: string[]) => void
  fees: CustomerFeeSettings | null
  onFees: (s: CustomerFeeSettings) => void
  onFeeDraft: (d: FeeDraft) => void
}) {
  const { t } = useTranslation()
  return (
    <SectionCard id="portal-refunds" title={t('settings.portal.sections.refunds.title')}
      description={t('settings.portal.sections.refunds.description')}>
      <RefundMethodsRow value={refundMethods} onChange={onRefundMethods} />
      {fees?.visible && <CustomerFeesSection part="fees" settings={fees} onSettings={onFees} onDraft={onFeeDraft} />}
    </SectionCard>
  )
}

/** Settings → Returns portal → "Refund methods" (design/Traced_portal_refund_method_dc.html, a). */
function RefundMethodsRow({ value, onChange }: { value: string[]; onChange: (v: string[]) => void }) {
  const { t } = useTranslation()
  return (
    <div className="px-6 py-5 space-y-4" role="group" aria-labelledby="refund-methods-title" data-testid="refund-methods-settings">
      <div>
        <h4 id="refund-methods-title" className="text-body font-semibold text-primary">{t('settings.portal.refundMethods.title')}</h4>
        <p className="text-small text-muted mt-1">{t('settings.portal.refundMethods.help')}</p>
      </div>
      <div className="grid gap-2.5 sm:grid-cols-2">
        {PORTAL_REFUND_METHODS.map(m => {
          const checked = value.includes(m)
          return (
            <label key={m}
              className={cn('flex gap-3 items-start rounded-xl border p-3.5 cursor-pointer transition-colors',
                'has-[:focus-visible]:ring-2 has-[:focus-visible]:ring-trace-blue',
                checked ? 'border-trace-blue ring-1 ring-trace-blue bg-trace-blue/5' : 'border-grey-100 bg-panel hover:border-grey-300')}>
              <input type="checkbox" checked={checked} className="w-[18px] h-[18px] mt-0.5 accent-trace-blue shrink-0"
                onChange={e => onChange(e.target.checked ? orderedMethods([...value, m]) : value.filter(x => x !== m))} />
              <span>
                <span className="block text-body font-semibold text-primary">{t(`settings.portal.refundMethods.methods.${m}.title`)}</span>
                <span className="block text-small text-muted">{t(`settings.portal.refundMethods.methods.${m}.desc`)}</span>
              </span>
            </label>
          )
        })}
      </div>
      <p className="text-small text-muted rounded-lg bg-elevated px-3.5 py-3">{t('settings.portal.refundMethods.note')}</p>
    </div>
  )
}
