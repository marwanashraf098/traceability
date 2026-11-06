import { useTranslation } from 'react-i18next'
import type { CustomerFeeSettings } from '../../../api'
import CustomerFeesSection, { FeeDraft } from '../CustomerFeesSection'
import { SectionCard, SwitchRow } from './ui'

/**
 * Exchanges — "Allow exchanges" (saved with "Save changes"; its preconditions and text unchanged) and, while exchange
 * pricing is shown for the store, what customers can swap to and how a price difference is handled (its own Save).
 * With exchanges switched off the pricing is hidden, not unmounted, so an unsaved edit there isn't lost.
 */
export default function ExchangesSection({
  exchangesOn, onExchanges, disabled, note, error, description, fees, onFees, onFeeDraft,
}: {
  exchangesOn: boolean
  onExchanges: (v: boolean) => void
  disabled: boolean
  note?: string
  error?: string
  description: string
  fees: CustomerFeeSettings | null
  onFees: (s: CustomerFeeSettings) => void
  onFeeDraft: (d: FeeDraft) => void
}) {
  const { t } = useTranslation()
  return (
    <SectionCard id="portal-exchanges" title={t('settings.portal.sections.exchanges.title')}
      description={t('settings.portal.sections.exchanges.description')}>
      <SwitchRow
        title={t('settings.portal.exchanges.title')}
        description={description}
        checked={exchangesOn}
        disabled={disabled}
        note={note}
        noteTestId="exchanges-note"
        error={error}
        errorTestId="error-exchangesEnabled"
        onChange={onExchanges}
      />
      {!exchangesOn && (
        <p className="px-6 py-4 text-body text-muted bg-elevated/50" data-testid="exchanges-off">
          {t('settings.portal.exchanges.off')}
        </p>
      )}
      {fees?.visible && (
        <div hidden={!exchangesOn}>
          <CustomerFeesSection part="pricing" settings={fees} onSettings={onFees} onDraft={onFeeDraft} />
        </div>
      )}
    </SectionCard>
  )
}
