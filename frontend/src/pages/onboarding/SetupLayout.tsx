import { ReactNode } from 'react'
import { useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { Check, Globe, LogOut } from 'lucide-react'
import { Logo } from '../../components/Logo'
import { cn } from '../../components/ui'
import { logoutThisDevice } from '../../api'
import { clearAccessToken } from '../../auth'
import { stationDeviceId } from '../../phone/PhoneScanProvider'
import { refreshSetupStep } from '../../setupGate'

// Onboarding setup screens (design/onboarding/Traced_onboarding_Flow-1…4): the shared full-screen frame —
// no sidebar; a top bar with the logo, the setup stepper, "Need help?", the language toggle and Log out
// (or, on the soft-banner address form, "Back to Traced").

/** WhatsApp support. */
export const HELP_WHATSAPP_URL = 'https://wa.me/201037371204'
/** Free setup call (Flow-4). */
export const SETUP_CALL_URL = 'https://calendly.com/tracedtechnology/traced-setup-call'

function toggleLang(i18n: { language: string; changeLanguage: (lng: string) => void }) {
  const next = i18n.language === 'en' ? 'ar' : 'en'
  i18n.changeLanguage(next)
  try { localStorage.setItem('lang', next) } catch { /* private mode */ }
  document.documentElement.dir  = next === 'ar' ? 'rtl' : 'ltr'
  document.documentElement.lang = next
}

/** Shopify · Warehouse address · Bosta · then your first receiving. `current` = the step being done (0–2). */
export function SetupStepper({ current }: { current: 0 | 1 | 2 }) {
  const { t } = useTranslation()
  const labels = [t('setupGate.stepper.shopify'), t('setupGate.stepper.address'), t('setupGate.stepper.courier')]
  return (
    <ol aria-label={t('setupGate.stepsLabel')} data-testid="setup-stepper"
      className="flex flex-wrap items-center gap-2 text-[13.5px] max-[900px]:order-3 max-[900px]:w-full">
      {labels.map((label, i) => {
        const state = i < current ? 'done' : i === current ? 'current' : 'todo'
        return (
          <li key={label} className="contents">
            <span data-state={state} aria-current={state === 'current' ? 'step' : undefined}
              className={cn('flex items-center gap-2',
                state === 'done' && 'text-success-text',
                state === 'current' && 'font-semibold text-primary',
                state === 'todo' && 'text-muted')}>
              <span className={cn('w-6 h-6 rounded-full flex items-center justify-center text-caption font-semibold flex-shrink-0',
                state === 'done' && 'bg-success text-white',
                state === 'current' && 'bg-trace-blue text-white',
                state === 'todo' && 'bg-line text-primary/80')}>
                {state === 'done' ? <Check size={13} strokeWidth={3} aria-hidden /> : i + 1}
              </span>
              {label}
            </span>
            <span aria-hidden className={cn('w-7 h-0.5 rounded-full max-[480px]:w-3', i < current ? 'bg-success' : 'bg-line')} />
          </li>
        )
      })}
      <li className="text-muted">{t('setupGate.stepper.then')}</li>
    </ol>
  )
}

export function GateFrame({ children, onExit, exitLabel, stepper, width = 'narrow' }: {
  children: ReactNode
  /** Banner mode: a "Back to Traced" link instead of Log out. */
  onExit?: () => void
  exitLabel?: string
  stepper?: ReactNode
  /** narrow = one card (manager, banner form); wide = card + side panel; medium = Flow-4. */
  width?: 'narrow' | 'medium' | 'wide'
}) {
  const { t, i18n } = useTranslation()
  const navigate = useNavigate()

  async function logout() {
    let deviceId: string | undefined
    try { deviceId = stationDeviceId() } catch { deviceId = undefined }
    try { await logoutThisDevice(deviceId) } catch { /* ignore */ }
    clearAccessToken()
    refreshSetupStep()
    navigate('/login')
  }

  return (
    <div className="min-h-screen bg-bg flex flex-col" data-testid="onboarding-gate">
      <header className="bg-surface border-b border-line flex flex-wrap items-center justify-between gap-x-4 gap-y-3 px-7 py-3.5 max-[640px]:px-4 flex-shrink-0">
        <Logo variant="mark" size={22} className="text-primary" />
        {stepper}
        <div className="flex items-center gap-1">
          <a href={HELP_WHATSAPP_URL} target="_blank" rel="noopener noreferrer" data-testid="setup-help"
            className="h-10 inline-flex items-center px-3.5 me-1 rounded-lg border border-line text-body font-semibold text-primary hover:bg-black/[0.03] transition-colors">
            {t('setupGate.needHelp')}
          </a>
          <button type="button" onClick={() => toggleLang(i18n)}
            className="inline-flex items-center gap-1.5 text-caption text-muted hover:text-primary hover:bg-black/[0.04] transition-colors px-2.5 py-1.5 rounded-lg">
            <Globe size={14} strokeWidth={1.75} />
            {i18n.language === 'en' ? 'العربية' : 'English'}
          </button>
          {onExit ? (
            <button type="button" onClick={onExit}
              className="inline-flex items-center gap-1.5 text-small font-medium text-muted hover:text-primary hover:bg-black/[0.04] transition-colors px-2.5 py-1.5 rounded-lg">
              {exitLabel}
            </button>
          ) : (
            <button type="button" onClick={() => void logout()} data-testid="onboarding-logout"
              className="inline-flex items-center gap-1.5 text-small font-medium text-muted hover:text-danger hover:bg-danger/10 transition-colors px-2.5 py-1.5 rounded-lg">
              <LogOut size={15} strokeWidth={1.75} className="rtl:-scale-x-100" />
              {t('nav.logout')}
            </button>
          )}
        </div>
      </header>
      <main className="flex-1 flex justify-center px-7 py-12 max-[640px]:px-4 max-[640px]:py-6">
        <div className={cn('w-full', width === 'narrow' && 'max-w-[520px] space-y-6',
          width === 'medium' && 'max-w-[920px]', width === 'wide' && 'max-w-[1040px]')}>
          {children}
        </div>
      </main>
    </div>
  )
}

/** The main card and its side panel; the panel drops below the card on a narrow screen. */
export function SetupColumns({ children, aside }: { children: ReactNode; aside?: ReactNode }) {
  return (
    <div className="flex flex-wrap items-start gap-7">
      <div className="flex-[999_1_520px] min-w-0">{children}</div>
      {aside && <aside className="flex-[1_1_300px] max-w-[380px] max-[860px]:max-w-none flex flex-col gap-3.5">{aside}</aside>}
    </div>
  )
}

/** The big white card of a setup screen. */
export function SetupCard({ children, className, ...rest }: { children: ReactNode; className?: string } & React.HTMLAttributes<HTMLElement>) {
  return (
    <section {...rest} className={cn('bg-surface border border-line rounded-[18px] p-8 max-[640px]:p-5 flex flex-col gap-5', className)}>
      {children}
    </section>
  )
}

/** "STEP 1 OF 3 · ABOUT 1 MINUTE", the title and its lead paragraph. */
export function SetupHeading({ eyebrow, title, lead }: { eyebrow?: string; title: string; lead?: string }) {
  return (
    <div className="flex flex-col gap-2.5">
      {eyebrow && <span className="text-[13px] font-semibold uppercase tracking-wide text-trace-blue">{eyebrow}</span>}
      <h1 className="text-[32px] leading-[1.2] font-bold tracking-tight text-primary max-[640px]:text-[26px]">{title}</h1>
      {lead && <p className="text-body-lg text-primary/80 leading-relaxed">{lead}</p>}
    </div>
  )
}

/** A side-panel card: a title and short lines (or free content). */
export function SidePanel({ title, lines, children, testId }: { title?: string; lines?: string[]; children?: ReactNode; testId?: string }) {
  return (
    <div className="bg-surface border border-line rounded-2xl p-5 flex flex-col gap-3" data-testid={testId}>
      {title && <span className="text-[15px] font-semibold text-primary">{title}</span>}
      {lines?.map(l => <span key={l} className="text-body leading-relaxed text-primary/80">{l}</span>)}
      {children}
    </div>
  )
}
