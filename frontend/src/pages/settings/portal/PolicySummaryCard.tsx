import { useTranslation } from 'react-i18next'
import { describeFee } from '../CustomerFeesSection'
import { PolicyInput, policySummary, renderPolicyLines } from './policySummary'

/** "Your policy in plain words" — the settings on screen as sentences (policySummary.ts). */
export default function PolicySummary({ input }: { input: PolicyInput }) {
  const { t, i18n } = useTranslation()
  const lines = renderPolicyLines(policySummary(input), t, i18n.language, describeFee)
  return (
    <section aria-labelledby="portal-summary-title" className="card px-6 py-5" data-testid="policy-summary">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h3 id="portal-summary-title" className="text-caption font-bold uppercase tracking-wider text-muted">
          {t('settings.portal.summary.title')}
        </h3>
        <span className="text-small text-muted">{t('settings.portal.summary.subtitle')}</span>
      </div>
      <ul className="mt-3 grid gap-2">
        {lines.map(line => (
          <li key={line} className="flex gap-2.5 text-body text-primary leading-relaxed" data-testid="policy-line">
            <span aria-hidden="true" className="mt-2 w-1.5 h-1.5 rounded-full bg-trace-blue shrink-0" />
            <span>{line}</span>
          </li>
        ))}
      </ul>
    </section>
  )
}
