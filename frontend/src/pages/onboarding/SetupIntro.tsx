import { FormEvent, useEffect, useRef, useState } from 'react'
import { Navigate, useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { Check } from 'lucide-react'
import { Button, Input, Spinner, cn } from '../../components/ui'
import { ErrorAlert } from '../settings/connections/StoreFinder'
import { WebhookSecretReveal } from '../settings/ConnectionsTab'
import { useStation } from '../../components/StationProvider'
import {
  bostaConnect, getConnections, getCourierSettings, getOnboardingProgress, getRoleFromToken,
  patchOnboardingPrefs, updateCourierSettings, type OnboardingProgress,
} from '../../api'
import { refreshSetupStep, takeIntroRedirect, useSetupState, type SetupIntro } from '../../setupGate'
import {
  GateFrame, SETUP_CALL_URL, SetupCard, SetupColumns, SetupHeading, SetupStepper, SidePanel,
} from './SetupLayout'

// Onboarding O2 — the setup intro after the gate (design/onboarding/Traced_onboarding_Flow-3-Bosta,
// Flow-4-Ready). Outside SetupGate: these screens never lock anyone in. Owner only; GET /me setupIntro
// (OnboardingProgressService.setupIntro) decides whether they show — a manager, a worker, station mode,
// a gated tenant or one with nothing to show is sent to Overview.

function Loading() {
  return (
    <div className="min-h-screen bg-bg flex items-center justify-center" data-testid="setup-intro-loading">
      <Spinner />
    </div>
  )
}

/** 'show', a redirect target, or undefined while loading. */
function useIntroGuard(screen: 'courier' | 'ready'): 'show' | string | undefined {
  const { stationMode } = useStation()
  const state = useSetupState()
  if (stationMode || getRoleFromToken() !== 'owner') return '/overview'
  if (state === undefined) return undefined
  if (state.step !== null) return '/overview'
  const intro: SetupIntro = state.intro
  if (screen === 'courier') return intro === 'courier' ? 'show' : intro === 'ready' ? '/setup/ready' : '/overview'
  // Flow-4 shows for any intro still open: Skip / Continue on Flow-3 lands here even if its write failed.
  return intro !== null ? 'show' : '/overview'
}

// ── Flow-3 — How do you ship your orders? ─────────────────────────────────────

type Choice = 'bosta' | 'other'

export function SetupCourierPage() {
  const guard = useIntroGuard('courier')
  if (guard === undefined) return <Loading />
  if (guard !== 'show') return <Navigate to={guard} replace />
  return <SetupCourier />
}

function SetupCourier() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const [choice, setChoice] = useState<Choice>('bosta')
  const [apiKey, setApiKey] = useState('')
  const [busy, setBusy] = useState<'connect' | 'other' | 'skip' | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [secret, setSecret] = useState<string | null>(null)
  const progress = useRef<OnboardingProgress | null>(null)
  const defaultCarrier = useRef<string | null>(null)

  useEffect(() => {
    takeIntroRedirect('courier')   // shown: never redirected here again this page load
    getOnboardingProgress().then(p => { progress.current = p }).catch(() => {})
    getCourierSettings().then(c => { defaultCarrier.current = c.defaultCarrier }).catch(() => {})
  }, [])

  function next() {
    refreshSetupStep()
    navigate('/setup/ready', { replace: true })
  }

  async function connectBosta(e?: FormEvent) {
    e?.preventDefault()
    setError(null)
    if (!apiKey.trim()) { setError(t('setupIntro.courier.errKey')); return }
    setBusy('connect')
    try {
      const res = await bostaConnect(apiKey.trim())
      setSecret(res.webhookSecret)
    } catch {
      setError(t('connections.bosta.error'))
    } finally {
      setBusy(null)
    }
  }

  async function useOther() {
    setError(null)
    setBusy('other')
    try {
      await updateCourierSettings({ enabled: true, defaultCarrier: defaultCarrier.current })
      next()
    } catch {
      setError(t('setupIntro.courier.errSave'))
      setBusy(null)
    }
  }

  async function skip() {
    setError(null)
    setBusy('skip')
    const kept = (progress.current?.steps ?? []).filter(s => s.skipped && s.key !== 'courier').map(s => s.key)
    try {
      await patchOnboardingPrefs({ skipped: [...kept, 'courier'] as ('courier' | 'test_label')[] })
    } catch {
      // Skipping must never trap anyone here: go on; the next sign-in asks again.
    }
    next()
  }

  const options: { id: Choice; title: string; desc: string }[] = [
    { id: 'bosta', title: t('setupIntro.courier.bosta'),  desc: t('setupIntro.courier.bostaDesc') },
    { id: 'other', title: t('setupIntro.courier.other'),  desc: t('setupIntro.courier.otherDesc') },
  ]

  return (
    <GateFrame width="wide" stepper={<SetupStepper current={2} />}>
      <SetupColumns aside={<>
        <SidePanel title={t('setupIntro.courier.panelTitle')}
          lines={[t('setupIntro.courier.panel1'), t('setupIntro.courier.panel2'), t('setupIntro.courier.panel3')]} />
        <SidePanel>
          <span className="text-body leading-relaxed text-primary/80">
            <b className="text-primary">{t('setupIntro.courier.skipTitle')}</b> {t('setupIntro.courier.skipBody')}
          </span>
        </SidePanel>
      </>}>
        <SetupCard data-testid="setup-courier">
          <SetupHeading eyebrow={t('setupIntro.courier.eyebrow')} title={t('setupIntro.courier.title')} />

          {secret ? (
            <div data-testid="setup-bosta-secret">
              <WebhookSecretReveal secret={secret} onDone={next} />
            </div>
          ) : (<>
            <div role="radiogroup" aria-label={t('setupIntro.courier.groupLabel')} className="grid grid-cols-2 gap-3 max-[560px]:grid-cols-1">
              {options.map(o => {
                const on = choice === o.id
                return (
                  <button key={o.id} type="button" role="radio" aria-checked={on} data-testid={`setup-courier-${o.id}`}
                    onClick={() => { setChoice(o.id); setError(null) }}
                    className={cn('flex flex-col items-start gap-2 p-[18px] rounded-[14px] bg-surface text-start transition-shadow',
                      on ? 'border-2 border-trace-blue shadow-[0_0_0_4px_#DBEAFE]' : 'border border-line hover:border-grey-100')}>
                    <span className="text-body-lg font-bold text-primary">{o.title}</span>
                    <span className="text-body leading-relaxed text-primary/80">{o.desc}</span>
                  </button>
                )
              })}
            </div>

            {choice === 'bosta' ? (
              <form onSubmit={e => void connectBosta(e)} className="flex flex-col gap-3" noValidate>
                <label htmlFor="setupBostaKey" className="text-body font-semibold text-primary">{t('setupIntro.courier.keyLabel')}</label>
                <Input id="setupBostaKey" className="h-12" value={apiKey} onChange={e => { setApiKey(e.target.value); setError(null) }}
                  placeholder={t('setupIntro.courier.keyPlaceholder')} autoComplete="off" spellCheck={false} dir="ltr" />
                <span className="text-[13.5px] leading-relaxed text-primary/80">{t('setupIntro.courier.keyHelp')}</span>
              </form>
            ) : (
              <div className="flex flex-col gap-2 bg-bg rounded-xl p-4 text-body leading-relaxed text-primary/80" data-testid="setup-courier-other-note">
                <span><b className="text-primary">{t('setupIntro.courier.otherNoteTitle')}</b> {t('setupIntro.courier.otherNote')}</span>
                <span className="text-warning-text">{t('setupIntro.courier.otherWarn')}</span>
              </div>
            )}

            {error && <ErrorAlert title={error} />}

            <div className="flex flex-wrap items-center justify-end gap-3.5 pt-1.5">
              <button type="button" onClick={() => void skip()} disabled={busy !== null} data-testid="setup-courier-skip"
                className="text-body text-muted hover:text-primary px-1 py-3 disabled:opacity-50">
                {t('setupIntro.courier.skip')}
              </button>
              {choice === 'bosta' ? (
                <Button variant="primary" size="lg" className="px-7" loading={busy === 'connect'}
                  disabled={busy !== null && busy !== 'connect'} onClick={() => void connectBosta()}>
                  {t('setupIntro.courier.connect')}
                </Button>
              ) : (
                <Button variant="primary" size="lg" className="px-7" loading={busy === 'other'}
                  disabled={busy !== null && busy !== 'other'} onClick={() => void useOther()}>
                  {t('setupIntro.courier.continue')}
                </Button>
              )}
            </div>
          </>)}
        </SetupCard>
      </SetupColumns>
    </GateFrame>
  )
}

// ── Flow-4 — You're connected ─────────────────────────────────────────────────

export function SetupReadyPage() {
  const guard = useIntroGuard('ready')
  if (guard === undefined) return <Loading />
  if (guard !== 'show') return <Navigate to={guard} replace />
  return <SetupReady />
}

type CourierChip = 'bosta' | 'other' | 'later'

function SetupReady() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const [courier, setCourier] = useState<CourierChip | null>(null)
  const marked = useRef(false)

  useEffect(() => {
    // Shown once: setupIntroSeen (best effort — a failed write just shows it again next time).
    takeIntroRedirect('ready')
    if (!marked.current) {
      marked.current = true
      patchOnboardingPrefs({ setupIntroSeen: true }).catch(() => {}).finally(() => refreshSetupStep())
    }
    let cancelled = false
    Promise.allSettled([getConnections(), getOnboardingProgress()]).then(([c, p]) => {
      if (cancelled) return
      const bosta = c.status === 'fulfilled' && c.value.bosta.connected && !c.value.bosta.simulated
      const step = p.status === 'fulfilled' ? p.value.steps.find(s => s.key === 'courier') : undefined
      setCourier(bosta ? 'bosta' : step?.done ? 'other' : 'later')
    })
    return () => { cancelled = true }
  }, [])

  if (courier === null) return <Loading />

  const chips: { label: string; done: boolean; testId: string }[] = [
    { label: t('setupIntro.ready.chipShopify'), done: true, testId: 'chip-shopify' },
    { label: t('setupIntro.ready.chipAddress'), done: true, testId: 'chip-address' },
    courier === 'bosta' ? { label: t('setupIntro.ready.chipBosta'), done: true, testId: 'chip-courier' }
      : courier === 'other' ? { label: t('setupIntro.ready.chipOther'), done: true, testId: 'chip-courier' }
      : { label: t('setupIntro.ready.chipBostaLater'), done: false, testId: 'chip-courier' },
  ]
  const steps = [
    { title: t('setupIntro.ready.receive'), body: t('setupIntro.ready.receiveBody') },
    { title: t('setupIntro.ready.pack'),    body: t('setupIntro.ready.packBody') },
    { title: t('setupIntro.ready.pickup'),
      body: courier === 'bosta' ? t('setupIntro.ready.pickupBodyBosta') : t('setupIntro.ready.pickupBody') },
    { title: t('setupIntro.ready.returns'), body: t('setupIntro.ready.returnsBody') },
  ]

  return (
    <GateFrame width="medium">
      <div className="flex flex-col gap-6" data-testid="setup-ready">
        <div className="flex flex-col items-center gap-3.5 text-center">
          <span className="w-14 h-14 rounded-full bg-success-text text-white flex items-center justify-center">
            <Check size={26} strokeWidth={3} aria-hidden />
          </span>
          <h1 className="text-[34px] leading-[1.2] font-bold tracking-tight text-primary max-[640px]:text-[28px]">{t('setupIntro.ready.title')}</h1>
          <ul className="flex flex-wrap justify-center gap-2" aria-label={t('setupIntro.ready.chipsLabel')}>
            {chips.map(c => (
              <li key={c.testId} data-testid={c.testId} data-done={c.done}
                className={cn('inline-flex items-center gap-1.5 text-[13.5px] font-semibold px-3 py-1.5 rounded-full',
                  c.done ? 'bg-success/[0.08] text-success-text' : 'bg-elevated text-muted')}>
                {c.label}{c.done && <Check size={13} strokeWidth={3} aria-hidden />}
              </li>
            ))}
          </ul>
        </div>

        <SetupCard className="gap-[22px] p-7">
          <div className="flex flex-col gap-2">
            <h2 className="text-h4 font-bold text-primary">{t('setupIntro.ready.stockTitle')}</h2>
            <p className="text-[15px] leading-relaxed text-primary/80">{t('setupIntro.ready.stockBody')}</p>
          </div>

          <ol aria-label={t('setupIntro.ready.journeyLabel')} className="grid grid-cols-4 gap-2.5 max-[760px]:grid-cols-2 max-[420px]:grid-cols-1">
            {steps.map((s, i) => (
              <li key={s.title} className={cn('flex flex-col gap-2 p-4 rounded-xl',
                i === 0 ? 'border-2 border-trace-blue bg-trace-blue/[0.06]' : 'bg-bg')}>
                <span className={cn('text-caption uppercase tracking-wide', i === 0 ? 'font-bold text-trace-blue-hover' : 'font-semibold text-muted')}>
                  {i === 0 ? t('setupIntro.ready.startHere') : t('setupIntro.ready.then')}
                </span>
                <span className="text-body-lg font-bold text-primary">{i + 1} · {s.title}</span>
                <span className="text-[13px] leading-snug text-primary/80">{s.body}</span>
              </li>
            ))}
          </ol>

          <div className="flex flex-wrap items-center justify-between gap-3 pt-1 max-[520px]:flex-col-reverse max-[520px]:items-stretch">
            <button type="button" onClick={() => navigate('/overview')} data-testid="setup-explore"
              className="text-[14.5px] font-medium text-primary/80 hover:text-primary px-1 py-3">
              {t('setupIntro.ready.explore')}
            </button>
            <Button variant="primary" size="lg" className="px-7" onClick={() => navigate('/receiving?guided=1')}>
              {t('setupIntro.ready.start')}
            </Button>
          </div>
        </SetupCard>

        <p className="text-center text-body text-muted">
          {t('setupIntro.ready.callLead')}{' '}
          <a href={SETUP_CALL_URL} target="_blank" rel="noopener noreferrer" data-testid="setup-call"
            className="text-trace-blue hover:text-trace-blue-hover hover:underline">{t('setupIntro.ready.call')}</a>
        </p>
      </div>
    </GateFrame>
  )
}
