import { useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { Check, Search, X } from 'lucide-react'
import { cn } from './ui'
import type { OnboardingProgress, OnboardingProgressStep } from '../api'
import { setCardState, setupDone } from '../onboardingProgress'

// Onboarding O3 — the Get started card on Overview (design/onboarding/Traced_onboarding_Flow-9-Overview-Real, the
// task list from Flow-8). Fed by GET /onboarding/progress: every step and task state is derived on the server;
// this card only shows them and writes the card state (Minimise / × / Show → PATCH /onboarding/prefs card).
// Replaces the old OnboardingCard (manual checkboxes) and FreshTenantCard. No tours until O5.

const DAY_MS = 24 * 60 * 60 * 1000

/** full | slim | hidden as shown: a stored 'full' nobody chose becomes slim once the store is settled in
 *  (setup finished more than 7 days ago, or 6+ tasks tried) — shown only, never written. A choice always wins. */
export function shownCardState(p: OnboardingProgress, now = Date.now()): OnboardingProgress['card'] {
  if (p.card !== 'full' || p.cardChosen) return p.card
  const setupAt = p.setupCompletedAt ? Date.parse(p.setupCompletedAt) : NaN
  const settled = (!Number.isNaN(setupAt) && now - setupAt > 7 * DAY_MS) || p.tasks.filter(t => t.tried).length >= 6
  return settled ? 'slim' : 'full'
}

type StepView = { key: OnboardingProgressStep['key']; done: boolean; label: string; to: string }

function stepViews(p: OnboardingProgress, t: (k: string) => string): StepView[] {
  const order: OnboardingProgressStep['key'][] = ['shopify', 'address', 'first_receiving', 'courier', 'test_label']
  return order.flatMap(key => {
    const s = p.steps.find(x => x.key === key)
    if (!s) return []
    const v = (done: string, todo: string, skipped: string | null, to: string): StepView =>
      ({ key, done: s.done, label: s.done ? done : s.skipped && skipped ? skipped : todo, to })
    switch (key) {
      case 'shopify':         return [v(t('getStarted.step.shopify'), t('getStarted.step.shopifyTodo'), null, '/settings?tab=connections')]
      case 'address':         return [v(t('getStarted.step.address'), t('getStarted.step.addressTodo'), null, '/settings?tab=locations')]
      case 'first_receiving': return [v(t('getStarted.step.receiving'), t('getStarted.step.receivingTodo'), null, '/receiving')]
      case 'courier':         return [v(p.bostaConnected ? t('getStarted.step.bosta') : t('getStarted.step.courier'),
                                        t('getStarted.step.courierTodo'), t('getStarted.step.courierLater'), '/settings?tab=connections')]
      case 'test_label':      return [v(t('getStarted.step.testLabel'), t('getStarted.step.testLabelTodo'),
                                        t('getStarted.step.testLabelLater'), '/receiving')]
    }
  })
}

/** The next step to do: the first pending one not put off, else the first pending one. */
function nextStep(steps: StepView[], p: OnboardingProgress): StepView | null {
  const pending = steps.filter(s => !s.done)
  const skipped = (k: string) => p.steps.find(x => x.key === k)?.skipped
  return pending.find(s => !skipped(s.key)) ?? pending[0] ?? null
}

const TASK_TO: Record<string, string> = {
  receive: '/receiving', pack: '/fulfill', pickup: '/pickups', returns: '/returns', transfer: '/transfers',
  stock_take: '/stock-take', portal: '/settings?tab=portal', team: '/settings?tab=users', places: '/settings?tab=places',
}

function Bar({ value, className }: { value: number; className?: string }) {
  return (
    <span className={cn('block h-1.5 rounded-full bg-line overflow-hidden', className)} aria-hidden>
      <span className="block h-full bg-trace-blue rounded-full transition-[width] duration-300 motion-reduce:transition-none"
        style={{ width: `${Math.round(value * 100)}%` }} />
    </span>
  )
}

const ICON_BTN = 'h-[34px] inline-flex items-center justify-center rounded-lg border border-line bg-surface text-primary/80 hover:text-primary hover:bg-black/[0.03] transition-colors'

export default function GetStartedCard({ progress }: { progress: OnboardingProgress }) {
  const { t } = useTranslation()
  const [q, setQ] = useState('')
  const state = shownCardState(progress)
  const steps = useMemo(() => stepViews(progress, t), [progress, t])
  const done = setupDone(progress)
  const total = steps.length

  if (state === 'hidden') {
    return (
      <div className="flex justify-end -mt-2" data-testid="get-started-hidden">
        <button type="button" onClick={() => setCardState('full')}
          className="text-small font-medium text-trace-blue hover:text-trace-blue-hover px-1 py-1">
          {t('getStarted.showAgain')}
        </button>
      </div>
    )
  }

  const hideBtn = (
    <button type="button" aria-label={t('getStarted.hide')} onClick={() => setCardState('hidden')}
      className={cn(ICON_BTN, 'w-[34px]')} data-testid="get-started-close">
      <X size={16} strokeWidth={2} />
    </button>
  )

  if (state === 'slim') {
    const next = nextStep(steps, progress)
    return (
      <section aria-label={t('getStarted.title')} data-testid="get-started-slim"
        className="card px-4 py-2.5 flex flex-wrap items-center justify-between gap-3 animate-fadeIn motion-reduce:animate-none">
        <div className="flex flex-wrap items-center gap-x-3.5 gap-y-1.5 text-body min-w-0">
          <span className="font-bold text-primary">{t('getStarted.short')}</span>
          <span className="text-primary/80">{t('getStarted.setupOf', { done, total })}</span>
          <Bar value={total ? done / total : 0} className="w-[120px]" />
          {next && (
            <span className="text-primary/80">
              {t('getStarted.next')}{' '}
              <Link to={next.to} className="text-trace-blue hover:text-trace-blue-hover font-medium" data-testid="get-started-next">{next.label}</Link>
            </span>
          )}
        </div>
        <div className="flex gap-1.5">
          <button type="button" onClick={() => setCardState('full')} className={cn(ICON_BTN, 'px-3 text-[13px] font-semibold')}>
            {t('getStarted.show')}
          </button>
          {hideBtn}
        </div>
      </section>
    )
  }

  const tasks = progress.tasks.map(task => {
    const title = task.key === 'pickup'
      ? (progress.bostaConnected ? t('getStarted.task.pickupBosta') : t('getStarted.task.pickup'))
      : t(`getStarted.task.${task.key}`)
    return { key: task.key, tried: task.tried, title, words: `${title} ${t(`getStarted.words.${task.key}`)}`.toLowerCase() }
  }).filter(task => TASK_TO[task.key])
  const query = q.trim().toLowerCase()
  const shown = query ? tasks.filter(task => task.words.includes(query)) : tasks

  return (
    <section aria-label={t('getStarted.title')} data-testid="get-started-card"
      className="bg-surface border-[1.5px] border-trace-blue/80 rounded-2xl px-6 py-[22px] max-[640px]:px-4 flex flex-col gap-[18px] animate-fadeIn motion-reduce:animate-none">
      <div className="flex items-center justify-between gap-3">
        <h2 className="text-[17px] font-bold text-primary">{t('getStarted.title')}</h2>
        <div className="flex items-center gap-1.5">
          <button type="button" onClick={() => setCardState('slim')} className={cn(ICON_BTN, 'px-3 text-[13px] font-medium')}>
            {t('getStarted.minimise')}
          </button>
          {hideBtn}
        </div>
      </div>

      <div className="flex flex-col gap-2.5">
        <div className="flex justify-between text-[13px] text-muted">
          <span className="font-semibold text-primary">{t('getStarted.setup')}</span>
          <span data-testid="get-started-count">{t('getStarted.doneOf', { done, total })}</span>
        </div>
        <Bar value={total ? done / total : 0} />
        <ul className="grid grid-cols-5 gap-2 max-[1100px]:grid-cols-3 max-[640px]:grid-cols-1" aria-label={t('getStarted.setup')}>
          {steps.map(s => (
            <li key={s.key} data-testid={`get-started-step-${s.key}`} data-done={s.done}>
              {s.done ? (
                <span className="flex items-center gap-2 h-full px-3 py-2.5 rounded-[10px] bg-success/[0.08] text-[13.5px] text-success-text">
                  <Check size={14} strokeWidth={3} className="flex-shrink-0" aria-hidden />
                  <span className="sr-only">{t('getStarted.doneSr')}</span>{s.label}
                </span>
              ) : (
                <Link to={s.to}
                  className="flex items-center justify-between gap-2 h-full px-3 py-2.5 rounded-[10px] border border-trace-blue/30 bg-trace-blue/[0.06] text-[13.5px] font-semibold text-trace-blue-hover hover:bg-trace-blue/[0.1] transition-colors">
                  {s.label}<span aria-hidden className="rtl:-scale-x-100">→</span>
                </Link>
              )}
            </li>
          ))}
        </ul>
      </div>

      <div className="flex flex-col gap-2.5 pt-3.5 border-t border-line">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <span className="text-[13px] font-semibold text-primary">
            {t('getStarted.tasksTitle')}{' '}
            <span className="font-normal text-muted" data-testid="get-started-tried">
              · {t('getStarted.tried', { done: tasks.filter(x => x.tried).length, total: tasks.length })}
            </span>
          </span>
          <label className="relative block flex-[0_1_340px] min-w-0 max-[640px]:flex-auto">
            <span className="sr-only">{t('getStarted.search')}</span>
            <Search size={14} className="absolute start-3 top-1/2 -translate-y-1/2 text-muted pointer-events-none" aria-hidden />
            <input type="search" value={q} onChange={e => setQ(e.target.value)} placeholder={t('getStarted.searchPlaceholder')}
              className="input ps-8 h-[38px] text-[13.5px]" data-testid="get-started-search" />
          </label>
        </div>
        {shown.length === 0 ? (
          <p className="text-body text-muted py-2" data-testid="get-started-no-match">{t('getStarted.noMatch')}</p>
        ) : (
          <ul className="grid grid-cols-4 gap-2 max-[1100px]:grid-cols-2 max-[480px]:grid-cols-1">
            {shown.map(task => (
              <li key={task.key} data-testid={`get-started-task-${task.key}`} data-tried={task.tried}
                className={cn('flex flex-col gap-1.5 px-3.5 py-3 rounded-[10px]', task.tried ? 'bg-success/[0.08]' : 'bg-bg')}>
                <span className="flex justify-between gap-1.5 text-body font-semibold text-primary">
                  {task.title}
                  {task.tried && (
                    <span className="text-success-text font-bold">
                      <Check size={15} strokeWidth={3} aria-hidden /><span className="sr-only">{t('getStarted.triedSr')}</span>
                    </span>
                  )}
                </span>
                <Link to={TASK_TO[task.key]} className="text-[13px] text-trace-blue hover:text-trace-blue-hover self-start"
                  aria-label={t('getStarted.openTask', { task: task.title })}>
                  {t('getStarted.open')}
                </Link>
              </li>
            ))}
          </ul>
        )}
      </div>
    </section>
  )
}
