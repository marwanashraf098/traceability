import { useState, useEffect } from 'react'
import { Link } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { getOnboardingStatus, OnboardingStep } from '../api'

// ── Step destination map ──────────────────────────────────────────────────────

const STEP_DEST: Record<string, string> = {
  connect_shopify: '/connections',
  connect_bosta:   '/connections',
  initial_import:  '/connections',
  test_label:      '/receiving',
  first_receiving: '/receiving',
}

// ── Icons ─────────────────────────────────────────────────────────────────────

function IconCheck() {
  return (
    <svg width="20" height="20" viewBox="0 0 24 24" fill="none"
      stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
      <path d="M20 6L9 17l-5-5" />
    </svg>
  )
}

function IconStar() {
  return (
    <svg width="40" height="40" viewBox="0 0 24 24" fill="none"
      stroke="currentColor" strokeWidth="1.5">
      <polygon points="12 2 15.09 8.26 22 9.27 17 14.14 18.18 21.02 12 17.77 5.82 21.02 7 14.14 2 9.27 8.91 8.26 12 2" />
    </svg>
  )
}

// ── Single step row ───────────────────────────────────────────────────────────

function StepRow({ step, index }: { step: OnboardingStep; index: number }) {
  const { t } = useTranslation()
  const isDone    = step.status === 'done'
  const dest      = STEP_DEST[step.key]
  const hasHint   = step.key === 'initial_import' || step.key === 'test_label'
  const hintKey   = `onboarding.steps.${step.key}.hint`

  return (
    <div
      data-testid={`step-${step.key}`}
      data-status={step.status}
      className={[
        'flex items-start gap-4 p-4 rounded-xl border transition-colors',
        isDone
          ? 'border-success/20 bg-success/5'
          : 'border-line bg-panel hover:bg-elevated',
      ].join(' ')}
    >
      {/* Step number + status icon */}
      <div className={[
        'flex-shrink-0 w-9 h-9 rounded-full flex items-center justify-center text-small font-bold',
        isDone
          ? 'bg-success/15 text-success'
          : 'bg-elevated text-muted',
      ].join(' ')}>
        {isDone ? <IconCheck /> : <span>{index + 1}</span>}
      </div>

      {/* Content */}
      <div className="flex-1 min-w-0 space-y-1">
        <p className={`text-body font-medium ${isDone ? 'text-muted line-through' : 'text-primary'}`}>
          {t(`onboarding.steps.${step.key}.label`, step.label)}
        </p>

        {/* Signal-lag hint for specific steps */}
        {!isDone && hasHint && (
          <p
            data-testid={`step-${step.key}-hint`}
            className="text-caption text-muted"
          >
            {t(hintKey)}
          </p>
        )}
      </div>

      {/* Status badge or action link */}
      <div className="flex-shrink-0 flex items-center">
        {isDone ? (
          <span className="text-caption font-medium text-success">
            {t('onboarding.done')}
          </span>
        ) : (
          <Link
            to={dest}
            className="btn btn-outline text-small py-1.5 px-3 whitespace-nowrap"
          >
            {t(`onboarding.steps.${step.key}.action`)}
          </Link>
        )}
      </div>
    </div>
  )
}

// ── Completed state ───────────────────────────────────────────────────────────

function AllDoneState() {
  const { t } = useTranslation()
  return (
    <div
      data-testid="onboarding-complete"
      className="card p-10 text-center space-y-4"
    >
      <div className="flex justify-center text-success">
        <IconStar />
      </div>
      <h2 className="text-h2 text-primary">{t('onboarding.allDone')}</h2>
      <p className="text-small text-muted max-w-sm mx-auto">{t('onboarding.allDoneSubtitle')}</p>
      <Link to="/overview" className="btn btn-brand inline-flex">
        {t('onboarding.goToOverview')}
      </Link>
    </div>
  )
}

// ── Onboarding page ───────────────────────────────────────────────────────────

export default function Onboarding() {
  const { t } = useTranslation()

  const [steps,   setSteps]   = useState<OnboardingStep[]>([])
  const [allDone, setAllDone] = useState(false)
  const [loading, setLoading] = useState(true)
  const [error,   setError]   = useState('')

  useEffect(() => {
    async function load() {
      try {
        const res = await getOnboardingStatus()
        setSteps(res.steps)
        setAllDone(res.allDone)
      } catch {
        setError(t('common.error'))
      } finally {
        setLoading(false)
      }
    }
    load()
  }, [t])

  return (
    <div className="max-w-2xl mx-auto space-y-6">
      <div>
        <h1 className="text-h1 text-primary">{t('onboarding.title')}</h1>
        <p className="text-small text-muted mt-1">{t('onboarding.subtitle')}</p>
      </div>

      {loading && (
        <div className="flex items-center justify-center py-16">
          <svg className="animate-spin w-6 h-6 text-brand" fill="none" viewBox="0 0 24 24">
            <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
            <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z" />
          </svg>
        </div>
      )}

      {!loading && error && (
        <div
          role="alert"
          data-testid="onboarding-error"
          className="text-small text-danger bg-danger/10 border border-danger/25 rounded px-3 py-2"
        >
          {error}
        </div>
      )}

      {!loading && !error && allDone && <AllDoneState />}

      {!loading && !error && !allDone && steps.length > 0 && (
        <div className="space-y-3">
          {steps.map((step, i) => (
            <StepRow key={step.key} step={step} index={i} />
          ))}
        </div>
      )}
    </div>
  )
}
