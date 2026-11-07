import { Link } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { Badge, cn } from '../../components/ui'

/**
 * The three receiving modes as cards, low → medium → high control (O4 rename, 2026-11-07). Copy only: the internal
 * values (count_only / print_stick / factory) and what each mode does are unchanged. Used by the New receiving
 * session chooser and the guided first receiving (Flow-5).
 *
 * High control (factory) works only with an OPEN factory label run: without one its card stays visible but
 * disabled, with "Needs a label run · Set up" → Receiving › Factory runs.
 */
export type ReceivingMode = 'print_stick' | 'count_only' | 'factory'

export const MODE_ORDER: ReceivingMode[] = ['count_only', 'print_stick', 'factory']
export const MODE_LEVEL: Record<ReceivingMode, 1 | 2 | 3> = { count_only: 1, print_stick: 2, factory: 3 }

/** Three bars, `level` of them filled. Decorative: the card's title already says the level. */
export function ControlMeter({ level }: { level: 1 | 2 | 3 }) {
  return (
    <span className="inline-flex items-end gap-[3px]" aria-hidden data-testid="mode-meter" data-level={level}>
      {[1, 2, 3].map(i => (
        <span key={i} data-filled={i <= level}
          className={cn('w-[5px] rounded-sm', i === 1 ? 'h-2' : i === 2 ? 'h-3' : 'h-4',
            i <= level ? 'bg-trace-blue' : 'bg-line')} />
      ))}
    </span>
  )
}

export default function ReceivingModeCards({ selected, onSelect, defaultMode, factoryAvailable, modes = MODE_ORDER, className }: {
  selected: ReceivingMode
  onSelect: (m: ReceivingMode) => void
  /** Shows "Your default" on that card (the normal chooser only). */
  defaultMode?: ReceivingMode
  /** true: an open factory run exists; false: none (card disabled + Set up); null: still loading (disabled). */
  factoryAvailable: boolean | null
  modes?: ReceivingMode[]
  className?: string
}) {
  const { t } = useTranslation()
  return (
    <div className={cn('grid gap-3 sm:grid-cols-2 lg:grid-cols-3', className)} role="group"
      aria-label={t('receiving.mode.title')}>
      {modes.map(m => {
        const disabled = m === 'factory' && factoryAvailable !== true
        const on = selected === m && !disabled
        const head = (
          <div className="flex items-start justify-between gap-2">
            <ControlMeter level={MODE_LEVEL[m]} />
            <span className="flex flex-wrap justify-end gap-1">
              {m === defaultMode && (
                <span data-testid={`receiving-mode-default-${m}`}><Badge tone="neutral" label={t('receiving.mode.yourDefault')} /></span>
              )}
            </span>
          </div>
        )
        const text = (
          <div className="space-y-1.5 flex-1">
            <p className="text-h3 text-primary">{t(`receiving.mode.${m}.title`)}</p>
            <p className="text-small text-muted">{t(`receiving.mode.${m}.body`)}</p>
          </div>
        )
        if (disabled) {
          return (
            <div key={m} data-testid={`receiving-mode-${m}`} aria-disabled="true"
              className="card text-start p-5 flex flex-col gap-3 opacity-80 bg-elevated">
              {head}
              <div className="opacity-70">{text}</div>
              {factoryAvailable === false && (
                <p className="text-small text-muted border-t border-line pt-3" data-testid="receiving-mode-factory-needs-run">
                  {t('receiving.mode.needsRun')} ·{' '}
                  <Link to="/receiving?tab=runs" className="font-medium text-trace-blue hover:text-trace-blue-hover">
                    {t('receiving.mode.setUp')}
                  </Link>
                </p>
              )}
            </div>
          )
        }
        return (
          <button key={m} type="button" aria-pressed={on} data-testid={`receiving-mode-${m}`}
            onClick={() => onSelect(m)}
            className={cn('card text-start p-5 flex flex-col gap-3 transition-colors focus-visible:border-trace-blue',
              on ? 'border-trace-blue bg-trace-blue/[0.06]' : 'hover:border-trace-blue')}>
            {head}
            {text}
          </button>
        )
      })}
    </div>
  )
}
