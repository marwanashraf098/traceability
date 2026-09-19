import { useState, FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { Button, Input, Progress } from '../../../components/ui'

const TOTAL_STEPS = 5

// ── Bosta setup wizard — 5-step static instructional guide ending in the
// existing API-key connect form. Deliberately NOT a generalization of
// SetupWizard.tsx (the Shopify wizard): that component is coupled to
// server-sourced shopifySetup config and a 3-field step-10 form. Bosta's guide
// is pure narrative copy with no server-sourced values, so this duplicates the
// small Progress + "Step n of N" + Back/Next + StepBody shell rather than
// forcing a shared abstraction two call sites don't yet justify.
//
// Unlike SetupWizard, this component never unmounts during submit (no
// "connecting" full-panel swap in the parent) — apiKey/loading/error can
// safely live here as local state; a failed submit just re-renders step 5
// in place with the error shown, nothing to preserve across a remount.

function StepBody({ title, body }: { title: string; body: string }) {
  return (
    <div>
      <h4 className="text-body-lg font-semibold text-primary mb-1">{title}</h4>
      <p className="text-body text-muted">{body}</p>
    </div>
  )
}

export default function BostaSetupWizard({
  onBack,
  onConnect,
  onSuccess,
}: {
  onBack: () => void
  onConnect: (apiKey: string) => Promise<{ webhookSecret: string }>
  onSuccess: (webhookSecret: string) => void | Promise<void>
}) {
  const { t } = useTranslation()
  const [step, setStep] = useState(1)
  const [apiKey, setApiKey] = useState('')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  function handleBack() {
    if (step === 1) { onBack(); return }
    setStep(s => s - 1)
  }

  async function handleConnect(e: FormEvent) {
    e.preventDefault()
    setError('')
    if (!apiKey.trim()) return
    setLoading(true)
    try {
      const result = await onConnect(apiKey.trim())
      await onSuccess(result.webhookSecret)
    } catch {
      setError(t('connections.bosta.error'))
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="space-y-4" data-testid="bosta-setup-wizard">
      <h3 className="text-h4 text-primary">{t('connections.bosta.wizard.title')}</h3>

      <div className="space-y-2">
        <span className="text-small text-muted font-medium">
          {t('connections.bosta.wizard.stepLabel', { current: step, total: TOTAL_STEPS })}
        </span>
        <Progress value={(step / TOTAL_STEPS) * 100} />
      </div>

      <div className="min-h-[140px]">
        {step === 1 && (
          <StepBody
            title={t('connections.bosta.wizard.step1.title')}
            body={t('connections.bosta.wizard.step1.body')}
          />
        )}

        {step === 2 && (
          <StepBody
            title={t('connections.bosta.wizard.step2.title')}
            body={t('connections.bosta.wizard.step2.body')}
          />
        )}

        {step === 3 && (
          <StepBody
            title={t('connections.bosta.wizard.step3.title')}
            body={t('connections.bosta.wizard.step3.body')}
          />
        )}

        {step === 4 && (
          <StepBody
            title={t('connections.bosta.wizard.step4.title')}
            body={t('connections.bosta.wizard.step4.body')}
          />
        )}

        {step === 5 && (
          <div className="space-y-3">
            <StepBody
              title={t('connections.bosta.wizard.step5.title')}
              body={t('connections.bosta.wizard.step5.body')}
            />

            {error && (
              <div role="alert" className="text-small text-danger bg-danger/10 border border-danger/25 rounded px-3 py-2">
                {error}
              </div>
            )}

            <form onSubmit={handleConnect} className="space-y-3 pt-2 border-t border-line">
              <div>
                <label className="block text-small text-muted mb-1.5" htmlFor="bostaWizardApiKey">
                  {t('connections.bosta.apiKeyLabel')}
                </label>
                <Input
                  id="bostaWizardApiKey"
                  type="password"
                  value={apiKey}
                  onChange={e => setApiKey(e.target.value)}
                  placeholder={t('connections.bosta.apiKeyPlaceholder')}
                  dir="ltr"
                  disabled={loading}
                  autoComplete="off"
                />
              </div>
              <Button type="submit" variant="primary" className="w-full" disabled={loading || !apiKey.trim()}>
                {loading ? t('connections.bosta.connecting') : t('connections.bosta.connectBtn')}
              </Button>
            </form>
          </div>
        )}
      </div>

      <div className="flex gap-2.5 pt-1">
        <Button variant="secondary" onClick={handleBack}>
          {t('connections.bosta.wizard.back')}
        </Button>
        {step < TOTAL_STEPS && (
          <Button variant="primary" className="flex-1" onClick={() => setStep(s => Math.min(TOTAL_STEPS, s + 1))}>
            {t('connections.bosta.wizard.next')}
          </Button>
        )}
      </div>
    </div>
  )
}
