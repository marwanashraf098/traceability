import { useTranslation } from 'react-i18next'
import { ExchangePricingPart, FeesEditorProps } from '../CustomerFeesSection'
import { EditedTag, SectionCard, SwitchRow } from './ui'

/**
 * Exchanges — "Allow exchanges" (its preconditions and text unchanged) and, while exchange pricing is shown for the
 * store, what customers can swap to and how a price difference is handled. Everything goes into the tab's draft
 * (Pass B). With exchanges switched off the pricing is hidden, not unmounted.
 */
export default function ExchangesSection({
  exchangesOn, onExchanges, disabled, note, error, description, edited, fees,
}: {
  exchangesOn: boolean
  onExchanges: (v: boolean) => void
  disabled: boolean
  note?: string
  error?: string
  description: string
  edited: boolean
  /** null while fees are hidden for the store. */
  fees: FeesEditorProps | null
}) {
  const { t } = useTranslation()
  return (
    <SectionCard id="portal-exchanges" title={t('settings.portal.sections.exchanges.title')}
      description={t('settings.portal.sections.exchanges.description')}>
      <SwitchRow
        title={t('settings.portal.exchanges.title')}
        description={description}
        extra={edited ? <EditedTag /> : undefined}
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
      {fees && (
        <div hidden={!exchangesOn}>
          <ExchangePricingPart {...fees} />
        </div>
      )}
    </SectionCard>
  )
}
