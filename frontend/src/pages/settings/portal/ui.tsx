import { KeyboardEvent, ReactNode, useRef } from 'react'
import { useTranslation } from 'react-i18next'
import { Zap } from 'lucide-react'
import { Toggle, cn } from '../../../components/ui'

/** One section of the Returns portal tab: a card with a heading the jump links scroll to. */
export function SectionCard({ id, title, description, children, testId }: {
  id: string; title: string; description: string; children: ReactNode; testId?: string
}) {
  return (
    <section id={id} aria-labelledby={`${id}-title`} className="card overflow-hidden scroll-mt-4" data-testid={testId}>
      <header className="px-6 pt-5 pb-4">
        <h3 id={`${id}-title`} tabIndex={-1} className="text-body-lg font-semibold text-primary outline-none">{title}</h3>
        <p className="text-small text-muted mt-1">{description}</p>
      </header>
      <div className="divide-y divide-line border-t border-line">{children}</div>
    </section>
  )
}

/**
 * A row with a switch. The row keeps `p-6` and its text exactly as before the redesign (title, description, note,
 * On / Off) — the golden settings-text test (genericCourierG4bFix) reads it.
 */
export function SwitchRow({
  title, description, checked, onChange, disabled, note, error, noteTestId = 'switch-note', errorTestId = 'error-pickupBooking',
  extra,
}: {
  title: string; description: string; checked: boolean; onChange: (v: boolean) => void
  disabled?: boolean; note?: string; error?: string; noteTestId?: string; errorTestId?: string
  /** Shown after the title (e.g. "Saves right away"); never part of the switch's name. */
  extra?: ReactNode
}) {
  const { t } = useTranslation()
  return (
    <div className="p-6 flex items-start gap-4">
      <div className="flex-1 min-w-0">
        <p className="text-body font-semibold text-primary">{title}{extra}</p>
        <p className="text-small text-muted mt-0.5 max-w-xl">{description}</p>
        {note && <p className="text-small text-warning-text mt-1.5" data-testid={noteTestId}>{note}</p>}
        {error && <p className="text-small text-critical mt-1.5" role="alert" data-testid={errorTestId}>{error}</p>}
      </div>
      <span className={cn('text-small font-medium mt-0.5', checked ? 'text-primary' : 'text-muted')} aria-hidden="true">
        {checked ? t('settings.portal.on') : t('settings.portal.off')}
      </span>
      <Toggle checked={checked} onChange={onChange} ariaLabel={title} disabled={disabled} />
    </div>
  )
}

/** "Saves right away" — next to a control that saves the moment it's used (not with "Save changes"). */
export function SavesRightAway({ className }: { className?: string }) {
  const { t } = useTranslation()
  return (
    <span className={cn('inline-flex items-center gap-1 align-middle ms-2 rounded-full bg-elevated px-2 py-0.5',
      'text-caption font-medium text-muted', className)} data-testid="saves-right-away">
      <Zap className="w-3 h-3" aria-hidden="true" />{t('settings.portal.page.savesRightAway')}
    </span>
  )
}

export interface RadioCardOption<V extends string> { value: V; title: string; description: string }

/**
 * Two or more choices as cards (the mockup's "How requests are approved" / "What they can swap to"). Buttons with
 * role="radio" in a radiogroup — no hidden inputs — with the radio keyboard model: Tab reaches the chosen card, the
 * arrow keys move and choose.
 */
export function RadioCards<V extends string>({ labelledBy, value, options, onChange, disabled, testId }: {
  labelledBy: string; value: V; options: RadioCardOption<V>[]; onChange: (v: V) => void; disabled?: boolean; testId?: string
}) {
  const refs = useRef<Array<HTMLButtonElement | null>>([])
  function onKey(e: KeyboardEvent, i: number) {
    const rtl = (e.currentTarget as HTMLElement).closest('[dir=rtl]') !== null
    const next = e.key === 'ArrowDown' || e.key === (rtl ? 'ArrowLeft' : 'ArrowRight')
    const prev = e.key === 'ArrowUp' || e.key === (rtl ? 'ArrowRight' : 'ArrowLeft')
    if (!next && !prev) return
    e.preventDefault()
    const j = (i + (next ? 1 : -1) + options.length) % options.length
    onChange(options[j].value)
    refs.current[j]?.focus()
  }
  return (
    <div role="radiogroup" aria-labelledby={labelledBy} aria-disabled={disabled || undefined}
      className="grid gap-2.5 sm:grid-cols-2" data-testid={testId}>
      {options.map((o, i) => {
        const checked = o.value === value
        return (
          <button key={o.value} ref={el => { refs.current[i] = el }} type="button" role="radio" aria-checked={checked}
            tabIndex={checked ? 0 : -1} disabled={disabled}
            onClick={() => onChange(o.value)} onKeyDown={e => onKey(e, i)}
            className={cn('flex gap-3 items-start rounded-xl border p-3.5 text-start transition-colors',
              'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-trace-blue',
              checked ? 'border-trace-blue ring-1 ring-trace-blue bg-trace-blue/5' : 'border-grey-100 bg-panel hover:border-grey-300',
              disabled && 'opacity-60 cursor-not-allowed')}>
            <span aria-hidden="true" className={cn('mt-0.5 w-[18px] h-[18px] rounded-full border-[1.5px] grid place-items-center shrink-0',
              checked ? 'border-trace-blue' : 'border-grey-200')}>
              {checked && <span className="w-2 h-2 rounded-full bg-trace-blue" />}
            </span>
            <span>
              <span className="block text-body font-semibold text-primary">{o.title}</span>
              <span className="block text-small text-muted mt-0.5">{o.description}</span>
            </span>
          </button>
        )
      })}
    </div>
  )
}
