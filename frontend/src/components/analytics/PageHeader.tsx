import { useState, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { cn } from '../ui'
import { useFmt } from '../../analytics/format'
import {
  COMPARE_THRESHOLD_DAYS, MAX_RANGE_DAYS, cairoToday, daysBetween, useAnalyticsState, type PeriodKey, type RevenueMode,
} from '../../analytics/period'

/** Segmented buttons in the mockup's style ("dates" / "seg"). */
function Seg<T extends string>({ options, value, onChange, label, dark = false }: {
  options: Array<{ value: T; label: string }>
  value: T | null
  onChange: (v: T) => void
  label: string
  dark?: boolean
}) {
  return (
    <div role="group" aria-label={label} className={cn('inline-flex flex-wrap gap-0.5 p-[3px] border rounded-[10px]',
      dark ? 'border-grey-100 bg-surface' : 'border-line bg-elevated')}>
      {options.map(o => (
        <button key={o.value} type="button" aria-pressed={o.value === value} onClick={() => onChange(o.value)}
          className={cn('px-[11px] py-1.5 rounded-[7px] text-[13px] font-medium transition-colors',
            o.value === value
              ? dark ? 'bg-primary text-white' : 'bg-surface text-primary shadow-e1'
              : 'text-neutral-text hover:text-primary')}>
          {o.label}
        </button>
      ))}
    </div>
  )
}

function CustomRange({ from, to, onApply, onCancel }: { from: string; to: string; onApply: (f: string, t: string) => void; onCancel: () => void }) {
  const { t } = useTranslation()
  const [f, setF] = useState(from)
  const [tt, setT] = useState(to)
  const today = cairoToday()
  const valid = !!f && !!tt && f <= tt && tt <= today && daysBetween(f, tt) <= MAX_RANGE_DAYS
  return (
    <form className="flex flex-wrap items-end gap-2" onSubmit={e => { e.preventDefault(); if (valid) onApply(f, tt) }}>
      <label className="flex flex-col gap-1 text-[12px] text-muted">{t('analytics.period.from')}
        <input type="date" className="input py-1 text-small" value={f} max={today} onChange={e => setF(e.target.value)} />
      </label>
      <label className="flex flex-col gap-1 text-[12px] text-muted">{t('analytics.period.to')}
        <input type="date" className="input py-1 text-small" value={tt} max={today} onChange={e => setT(e.target.value)} />
      </label>
      <button type="submit" disabled={!valid} className="btn h-[34px] px-3 rounded-lg bg-primary text-white text-small disabled:opacity-40">{t('analytics.period.apply')}</button>
      <button type="button" onClick={onCancel} className="btn h-[34px] px-3 rounded-lg border border-line text-small">{t('analytics.period.cancel')}</button>
      {!valid && f && tt && <span className="text-[12px] text-critical-text w-full">{t('analytics.period.invalid', { days: MAX_RANGE_DAYS })}</span>}
    </form>
  )
}

/**
 * The analytics page header: crumb, title, subtitle, the date presets (Today, Yesterday, 7 / 30
 * days, This month, Custom), and below it the Booked / Realized switch (pages that use it) and the
 * Compare control (only past 92 days, where the backend skips the previous period by default).
 */
export function AnalyticsPageHeader({ title, subtitle, showMode = false, right }: {
  title: string
  subtitle: string
  showMode?: boolean
  right?: ReactNode
}) {
  const { t } = useTranslation()
  const s = useAnalyticsState()
  const fmt = useFmt()
  const [custom, setCustom] = useState(false)

  const presets: Array<{ value: PeriodKey; label: string }> = (['today', 'yesterday', '7d', '30d', 'month', 'custom'] as PeriodKey[])
    .map(k => ({ value: k, label: t(`analytics.period.${k}`) }))
  const longPeriod = s.days > COMPARE_THRESHOLD_DAYS

  return (
    <div className="mb-5">
      <div className="flex flex-wrap items-start gap-x-6 gap-y-3.5 mb-3.5">
        <div className="flex-1 min-w-0">
          <div className="text-[12.5px] text-muted font-medium mb-0.5">{t('analytics.crumb')}</div>
          <h1 className="text-[38px] max-md:text-[30px] font-extrabold tracking-[-0.03em] leading-[1.1] text-primary">{title}</h1>
          <div className="text-neutral-text text-[14px] mt-1.5">{subtitle}</div>
        </div>
        <Seg label={t('analytics.period.label')} options={presets} value={custom ? 'custom' : s.periodKey}
          onChange={k => { if (k === 'custom') setCustom(true); else { setCustom(false); s.setPeriod(k) } }} />
      </div>
      {custom && (
        <div className="mb-3.5">
          <CustomRange from={s.from} to={s.to} onCancel={() => setCustom(false)}
            onApply={(f, tt) => { s.setCustom(f, tt); setCustom(false) }} />
        </div>
      )}
      <div className="flex flex-wrap items-center gap-x-4 gap-y-2.5">
        {showMode && (
          <Seg<RevenueMode> dark label={t('analytics.mode.label')} value={s.mode} onChange={s.setMode}
            options={[{ value: 'booked', label: t('analytics.mode.booked') }, { value: 'realized', label: t('analytics.mode.realized') }]} />
        )}
        <span className="text-[12.5px] text-muted" data-testid="period-range">
          {s.days === 1
            ? t('analytics.period.showingDay', { day: fmt.day(s.from) })
            : t('analytics.period.showing', { from: fmt.day(s.from), to: fmt.day(s.to), days: s.days })}
        </span>
        {longPeriod && (
          <button type="button" aria-pressed={s.compare} onClick={() => s.setCompare(!s.compare)}
            className={cn('h-[30px] px-3 rounded-lg border text-[12.5px] font-medium',
              s.compare ? 'bg-primary text-white border-primary' : 'border-grey-100 bg-surface text-neutral-text hover:text-primary')}>
            {s.compare ? t('analytics.period.comparing') : t('analytics.period.compare')}
          </button>
        )}
        <span className="flex-1" />
        {right}
      </div>
    </div>
  )
}
