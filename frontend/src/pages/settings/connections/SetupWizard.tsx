import { useState, FormEvent, ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { Button, Input, Progress } from '../../../components/ui'
import { CopyRow } from './CopyRow'
import type { ConnectionsStatus } from '../../../api'

const SHOP_RE = /^[a-zA-Z0-9][a-zA-Z0-9-]*\.myshopify\.com$/
const TOTAL_STEPS = 10

// ── Static toggle indicator (illustrates a Shopify admin setting to flip — NOT an
// interactive control, unlike ui.tsx's Toggle) ─────────────────────────────────

function ToggleIndicator({ on, label }: { on: boolean; label: string }) {
  return (
    <div
      className={`inline-flex items-center gap-2 rounded-lg px-3 py-2 text-small font-semibold ${
        on ? 'bg-success/10 text-success' : 'bg-elevated text-muted'
      }`}
    >
      <span className={`relative w-8 h-5 rounded-full flex-shrink-0 ${on ? 'bg-success' : 'bg-line'}`}>
        <span
          className={`absolute top-0.5 w-4 h-4 rounded-full bg-white transition-all ${on ? 'start-[14px]' : 'start-0.5'}`}
        />
      </span>
      {label}
    </div>
  )
}

function StepBody({ title, body, children }: { title: string; body: string; children?: ReactNode }) {
  return (
    <div className="space-y-3">
      <div>
        <h4 className="text-body-lg font-semibold text-primary mb-1">{title}</h4>
        <p className="text-body text-muted">{body}</p>
      </div>
      {children}
    </div>
  )
}

// ── Setup wizard — 10-step controlled flow, "Set it up myself" / custom_app_cc
// reconnect path. Copyable config values (app URL, redirect URL, webhook API version,
// scopes) come from shopifySetup (server-sourced, single source of truth — see
// ConnectionsController — never hardcoded here so the guide can't drift from
// shopify.app.toml). Step navigation (Back/Next) is local UI state; the actual
// connect submission is handed to the parent via onSubmit, which owns the async
// call and the transition to the "connecting" panel — see ShopifyConnectionCard.

export default function SetupWizard({
  shopifySetup,
  initialStep,
  initialError,
  onBack,
  onSubmit,
}: {
  shopifySetup: ConnectionsStatus['shopifySetup']
  initialStep: number
  initialError?: string
  onBack: () => void
  onSubmit: (shopDomain: string, clientId: string, clientSecret: string) => void
}) {
  const { t } = useTranslation()
  const [step, setStep] = useState(initialStep)
  const [shopDomain, setShopDomain] = useState('')
  const [clientId, setClientId] = useState('')
  const [clientSecret, setClientSecret] = useState('')
  const [formError, setFormError] = useState(initialError ?? '')

  const scopesCsv = shopifySetup.scopes.join(',')
  const copyLabel = t('connections.shopify.wizard.copy')
  const copiedLabel = t('connections.shopify.wizard.copied')

  function handleBack() {
    if (step === 1) { onBack(); return }
    setStep(s => s - 1)
  }

  function handleConnect(e: FormEvent) {
    e.preventDefault()
    setFormError('')
    const trimmedDomain = shopDomain.trim()
    if (!SHOP_RE.test(trimmedDomain)) {
      setFormError(t('connections.shopify.wizard.step10.domainInvalid'))
      return
    }
    if (!clientId.trim()) {
      setFormError(t('connections.shopify.wizard.step10.clientIdRequired'))
      return
    }
    if (!clientSecret.trim()) {
      setFormError(t('connections.shopify.wizard.step10.secretRequired'))
      return
    }
    onSubmit(trimmedDomain, clientId.trim(), clientSecret.trim())
  }

  return (
    <div className="space-y-4" data-testid="shopify-setup-wizard">
      <h3 className="text-h4 text-primary">{t('connections.shopify.wizard.title')}</h3>

      <div className="space-y-2">
        <span className="text-small text-muted font-medium">
          {t('connections.shopify.wizard.stepLabel', { current: step, total: TOTAL_STEPS })}
        </span>
        <Progress value={(step / TOTAL_STEPS) * 100} />
      </div>

      <div className="min-h-[140px]">
        {step === 1 && (
          <StepBody
            title={t('connections.shopify.wizard.step1.title')}
            body={t('connections.shopify.wizard.step1.body')}
          />
        )}

        {step === 2 && (
          <StepBody
            title={t('connections.shopify.wizard.step2.title')}
            body={t('connections.shopify.wizard.step2.body')}
          >
            <CopyRow value="Traced" copyLabel={copyLabel} copiedLabel={copiedLabel} />
          </StepBody>
        )}

        {step === 3 && (
          <StepBody
            title={t('connections.shopify.wizard.step3.title')}
            body={t('connections.shopify.wizard.step3.body')}
          >
            <CopyRow value={shopifySetup.appUrl} copyLabel={copyLabel} copiedLabel={copiedLabel} />
          </StepBody>
        )}

        {step === 4 && (
          <StepBody
            title={t('connections.shopify.wizard.step4.title')}
            body={t('connections.shopify.wizard.step4.body')}
          >
            <ToggleIndicator on label={t('connections.shopify.wizard.toggleOn')} />
          </StepBody>
        )}

        {step === 5 && (
          <StepBody
            title={t('connections.shopify.wizard.step5.title')}
            body={t('connections.shopify.wizard.step5.body')}
          >
            <CopyRow value={shopifySetup.webhookApiVersion} copyLabel={copyLabel} copiedLabel={copiedLabel} />
          </StepBody>
        )}

        {step === 6 && (
          <StepBody
            title={t('connections.shopify.wizard.step6.title')}
            body={t('connections.shopify.wizard.step6.body')}
          >
            <CopyRow value={scopesCsv} copyLabel={copyLabel} copiedLabel={copiedLabel} />
          </StepBody>
        )}

        {step === 7 && (
          <StepBody
            title={t('connections.shopify.wizard.step7.title')}
            body={t('connections.shopify.wizard.step7.body')}
          >
            <ToggleIndicator on={false} label={t('connections.shopify.wizard.toggleOff')} />
          </StepBody>
        )}

        {step === 8 && (
          <StepBody
            title={t('connections.shopify.wizard.step8.title')}
            body={t('connections.shopify.wizard.step8.body')}
          >
            <CopyRow value={shopifySetup.redirectUrl} copyLabel={copyLabel} copiedLabel={copiedLabel} />
          </StepBody>
        )}

        {step === 9 && (
          <StepBody
            title={t('connections.shopify.wizard.step9.title')}
            body={t('connections.shopify.wizard.step9.body')}
          />
        )}

        {step === 10 && (
          <div className="space-y-3">
            <div>
              <h4 className="text-body-lg font-semibold text-primary mb-1">
                {t('connections.shopify.wizard.step10.title')}
              </h4>
              <p className="text-body text-muted">{t('connections.shopify.wizard.step10.body')}</p>
            </div>

            {formError && (
              <div role="alert" className="text-small text-danger bg-danger/10 border border-danger/25 rounded px-3 py-2">
                {formError}
              </div>
            )}

            <form onSubmit={handleConnect} className="space-y-3 pt-2 border-t border-line">
              <div>
                <label className="block text-small text-muted mb-1.5" htmlFor="wizardShopDomain">
                  {t('connections.shopify.wizard.step10.domain')}
                </label>
                <Input
                  id="wizardShopDomain"
                  type="text"
                  value={shopDomain}
                  onChange={e => setShopDomain(e.target.value)}
                  placeholder={t('connections.shopify.shopPlaceholder')}
                  dir="ltr"
                  autoComplete="off"
                />
              </div>
              <div>
                <label className="block text-small text-muted mb-1.5" htmlFor="wizardClientId">
                  {t('connections.shopify.wizard.step10.clientId')}
                </label>
                <Input
                  id="wizardClientId"
                  type="text"
                  value={clientId}
                  onChange={e => setClientId(e.target.value)}
                  placeholder={t('connections.shopify.wizard.step10.clientIdPlaceholder')}
                  dir="ltr"
                  autoComplete="off"
                />
              </div>
              <div>
                <label className="block text-small text-muted mb-1.5" htmlFor="wizardClientSecret">
                  {t('connections.shopify.wizard.step10.secret')}
                </label>
                <Input
                  id="wizardClientSecret"
                  type="password"
                  value={clientSecret}
                  onChange={e => setClientSecret(e.target.value)}
                  placeholder={t('connections.shopify.wizard.step10.secretPlaceholder')}
                  dir="ltr"
                  autoComplete="off"
                />
              </div>
              <p className="text-small text-muted">{t('connections.shopify.wizard.step10.secure')}</p>
              <Button type="submit" variant="primary" className="w-full">
                {t('connections.shopify.wizard.step10.cta')}
              </Button>
            </form>
          </div>
        )}
      </div>

      <div className="flex gap-2.5 pt-1">
        <Button variant="secondary" onClick={handleBack}>
          {t('connections.shopify.wizard.back')}
        </Button>
        {step < TOTAL_STEPS && (
          <Button variant="primary" className="flex-1" onClick={() => setStep(s => Math.min(TOTAL_STEPS, s + 1))}>
            {t('connections.shopify.wizard.next')}
          </Button>
        )}
      </div>
    </div>
  )
}
