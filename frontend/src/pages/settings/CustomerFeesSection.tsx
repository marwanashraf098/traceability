import { Component, type ErrorInfo, type ReactNode, useEffect, useMemo, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import {
  CustomerFeeSettings, ExchangePricing, FeeArea, FeeAreaOverride, FeeKind, FeeMode, FeeRule, FeeRuleInput,
  TransferCommandError, addFeeAreaOverride, deleteFeeAreaOverride, getCustomerFees, getFeeAreas, getRoleFromToken,
  saveExchangePricing, saveFeeRule, saveMerchantFaultReasons,
} from '../../api'
import { Button, Input, Toggle, cn, useToast } from '../../components/ui'
import { RadioCards, SavesRightAway } from './portal/ui'

/** The portal's return reasons, in the portal's order (PortalService.REASON_CODES). */
export const REASON_CODES = ['wrong_size', 'damaged', 'not_as_pictured', 'wrong_item', 'changed_mind', 'other'] as const

const KINDS: FeeKind[] = ['exchange', 'refund']

/** What the editors show right now, saved or not — for the tab's plain-words summary. */
export interface FeeDraft {
  pricing?: ExchangePricing
  rules?: Partial<Record<FeeKind, FeeRuleInput>>
}

/**
 * Returns portal fees F1 — Settings → Returns portal → Exchange pricing and Customer fees. Each block saves on its
 * own (its own endpoint, audit-logged). Owner edits; a manager sees it read-only. Hidden entirely while the backend
 * says {@code visible:false} (until fees are charged) or when it can't be loaded — the rest of the tab is unchanged.
 *
 * Wrapped in its own error boundary (prod report 2026-11-06: a render crash here blanked the whole settings page):
 * a failure shows "Something went wrong — Reload" in place of these cards and the rest of the page keeps working.
 *
 * Redesign Pass A: the tab places the two parts in its own sections — `part="pricing"` inside Exchanges, `part="fees"`
 * (the Fee / Type / Amount / On table, the store's-fault reasons, fees by area) inside Refunds & fees — and passes the
 * one loaded `settings` to both. With no `part` both render as two cards, and with no `settings` it loads its own.
 * Endpoints, bodies and when each block saves are unchanged.
 */
export default function CustomerFeesSection({ part = 'all', settings, onSettings, onDraft }: {
  part?: 'all' | 'pricing' | 'fees'
  /** Given by the tab (one load for both parts); undefined → loads its own. */
  settings?: CustomerFeeSettings | null
  onSettings?: (s: CustomerFeeSettings) => void
  onDraft?: (d: FeeDraft) => void
}) {
  return (
    <FeesErrorBoundary compact={part !== 'all'}>
      <CustomerFeesCards part={part} given={settings} onSettings={onSettings} onDraft={onDraft} />
    </FeesErrorBoundary>
  )
}

/** Catches a render error inside the fee cards only. Logs the error and its component stack — never settings data. */
export class FeesErrorBoundary extends Component<{ children: ReactNode; compact?: boolean }, { failed: boolean }> {
  state = { failed: false }

  static getDerivedStateFromError() {
    return { failed: true }
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error('Customer fees settings crashed', error, info.componentStack)
  }

  render() {
    return this.state.failed ? <FeesCrashed compact={this.props.compact} /> : this.props.children
  }
}

function FeesCrashed({ compact }: { compact?: boolean }) {
  const { t } = useTranslation()
  return (
    <div className={cn(compact ? 'p-6 space-y-3' : 'card max-w-3xl p-6 space-y-3')} role="alert" data-testid="customer-fees-crashed">
      <p className="text-body text-primary">{t('settings.portal.fees.crashed')}</p>
      <Button size="sm" variant="outline" onClick={() => window.location.reload()}>{t('settings.portal.fees.reload')}</Button>
    </div>
  )
}

/** Loads the settings itself when the tab didn't pass them. */
export function useCustomerFeeSettings(enabled = true): [CustomerFeeSettings | null, (s: CustomerFeeSettings) => void] {
  const [settings, setSettings] = useState<CustomerFeeSettings | null>(null)
  useEffect(() => {
    if (!enabled) return
    let cancelled = false
    getCustomerFees().then(s => { if (!cancelled) setSettings(s) }).catch(() => {})
    return () => { cancelled = true }
  }, [enabled])
  return [settings, setSettings]
}

function CustomerFeesCards({ part, given, onSettings, onDraft }: {
  part: 'all' | 'pricing' | 'fees'
  given: CustomerFeeSettings | null | undefined
  onSettings?: (s: CustomerFeeSettings) => void
  onDraft?: (d: FeeDraft) => void
}) {
  const { t } = useTranslation()
  const own = given === undefined
  const [loaded, setLoaded] = useCustomerFeeSettings(own)
  const settings = own ? loaded : given
  const owner = getRoleFromToken() === 'owner'
  const saved = (s: CustomerFeeSettings) => { if (own) setLoaded(s); onSettings?.(s) }

  if (!settings?.visible) return null

  const ownerNote = !owner && <p className="text-small text-warning-text mt-1.5">{t('settings.portal.fees.ownerOnly')}</p>

  const pricing = (
    <ExchangePricingEditor value={settings.exchangePricing} owner={owner} onSaved={saved} onDraft={onDraft} />
  )
  const fees = (
    <>
      <FeeRulesTable rules={settings.rules} owner={owner} onSaved={saved} onDraft={onDraft} />
      <ReasonsEditor value={settings.merchantFaultReasons} owner={owner} onSaved={saved} />
      <AreaOverrides overrides={settings.overrides} owner={owner} onSaved={saved} />
    </>
  )

  if (part === 'pricing') {
    return (
      <div className="divide-y divide-line" data-testid="customer-fees-pricing">
        <div className="px-6 pt-5 pb-1">
          <h4 className="text-body font-semibold text-primary">{t('settings.portal.fees.pricing.title')}</h4>
          <p className="text-small text-muted mt-0.5">{t('settings.portal.fees.pricing.description')}</p>
          {ownerNote}
        </div>
        {pricing}
      </div>
    )
  }
  if (part === 'fees') {
    return (
      <div className="divide-y divide-line" data-testid="customer-fees-rules">
        <div className="px-6 pt-5 pb-4">
          <h4 className="text-body font-semibold text-primary">{t('settings.portal.fees.title')}</h4>
          <p className="text-small text-muted mt-0.5">{t('settings.portal.fees.table.help')}</p>
          {ownerNote}
        </div>
        {fees}
      </div>
    )
  }
  return (
    <div className="space-y-4" data-testid="customer-fees-section">
      <section className="card overflow-hidden divide-y divide-line max-w-3xl" aria-labelledby="exchange-pricing-title">
        <div className="p-6">
          <h2 id="exchange-pricing-title" className="text-h3 text-primary">{t('settings.portal.fees.pricing.title')}</h2>
          <p className="text-small text-muted mt-1">{t('settings.portal.fees.pricing.description')}</p>
          {ownerNote}
        </div>
        {pricing}
      </section>

      <section className="card overflow-hidden divide-y divide-line max-w-3xl" aria-labelledby="customer-fees-title">
        <div className="p-6">
          <h2 id="customer-fees-title" className="text-h3 text-primary">{t('settings.portal.fees.title')}</h2>
          <p className="text-small text-muted mt-1">{t('settings.portal.fees.description')}</p>
          {ownerNote}
        </div>
        {fees}
      </section>
    </div>
  )
}

/** Calls `report` whenever `value` changes (by content), without re-running when the callback's identity changes. */
function useReport<V>(value: V, report: ((v: V) => void) | undefined) {
  const ref = useRef(report)
  ref.current = report
  const key = JSON.stringify(value)
  useEffect(() => { ref.current?.(value) }, [key])   // eslint-disable-line react-hooks/exhaustive-deps
}

// ── shared ───────────────────────────────────────────────────────────────────

function useSave(onSaved: (s: CustomerFeeSettings) => void) {
  const { t, i18n } = useTranslation()
  const { toast } = useToast()
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | undefined>()
  async function run(call: () => Promise<CustomerFeeSettings>) {
    setSaving(true)
    setError(undefined)
    try {
      onSaved(await call())
      toast({ tone: 'success', message: t('settings.portal.fees.saved') })
      return true
    } catch (e) {
      setError(e instanceof TransferCommandError ? (i18n.language === 'ar' ? e.messageAr : e.messageEn)
        : t('settings.portal.fees.saveFailed'))
      return false
    } finally {
      setSaving(false)
    }
  }
  return { saving, error, run, clearError: () => setError(undefined) }
}

/** '' (or anything that isn't a number) → null; else a number (the backend validates it). */
function num(s: string | null | undefined): number | null {
  const v = (s ?? '').trim()
  if (v === '') return null
  const n = Number(v)
  return Number.isFinite(n) ? n : null
}

/** A stored amount as the field's text; '' for none. Tolerates a number sent as a string. */
function str(n: number | string | null | undefined) {
  if (n == null) return ''
  const v = typeof n === 'number' ? n : Number(n)
  return Number.isFinite(v) ? String(v) : ''
}

/** A stored amount as a number (or null) — the dirty checks compare like with like. */
function numOrNull(n: number | string | null | undefined): number | null {
  return n == null ? null : num(String(n))
}

function modeOf(m: unknown): FeeMode { return m === 'percent' ? 'percent' : 'fixed' }

function ErrorLine({ error, testId }: { error?: string; testId: string }) {
  return error ? <p className="text-small text-critical" role="alert" data-testid={testId}>{error}</p> : null
}

function MoneyInput({ id, label, value, onChange, disabled, testId }: {
  id: string; label: string; value: string; onChange: (v: string) => void; disabled?: boolean; testId?: string
}) {
  const { t } = useTranslation()
  return (
    <label htmlFor={id} className="flex flex-col gap-1 text-small text-muted">
      {label}
      <span className="flex items-center gap-2">
        <span className="w-28">
          <Input id={id} type="number" min={0} max={30000} step={1} inputMode="numeric" value={value}
            disabled={disabled} data-testid={testId} onChange={e => onChange(e.target.value)} />
        </span>
        <span className="text-body text-muted">{t('settings.portal.fees.currency')}</span>
      </span>
    </label>
  )
}

function ModeChoice({ name, value, onChange, disabled }: {
  name: string; value: FeeMode; onChange: (m: FeeMode) => void; disabled?: boolean
}) {
  const { t } = useTranslation()
  return (
    <div role="radiogroup" className="inline-flex rounded-lg bg-elevated p-0.5 gap-0.5">
      {(['fixed', 'percent'] as FeeMode[]).map(m => (
        // `relative`: the sr-only radio is position:absolute — without a positioned label it is laid out against a far
        // ancestor, and focusing it on click scrolled the page away (prod 2026-11-06, ~3,500 px).
        <label key={m} className={cn('relative rounded-md px-3 py-1.5 text-small cursor-pointer select-none whitespace-nowrap',
          'has-[:focus-visible]:ring-2 has-[:focus-visible]:ring-trace-blue',
          value === m ? 'bg-surface text-primary font-semibold shadow-e1' : 'text-muted hover:text-primary',
          disabled && 'cursor-not-allowed opacity-60')}>
          <input type="radio" className="sr-only" name={name} value={m} checked={value === m} disabled={disabled}
            onChange={() => onChange(m)} />
          {t(`settings.portal.fees.mode.${m}`)}
        </label>
      ))}
    </div>
  )
}

/** "EGP 50" / "10% (min EGP 20, max EGP 300)" — what a rule or override charges. */
export function describeFee(f: { mode: FeeMode; amount: number | null; percent: number | null; minAmount: number | null; maxAmount: number | null },
                            t: (k: string, o?: Record<string, unknown>) => string): string {
  if (modeOf(f.mode) === 'fixed') return t('settings.portal.fees.fixedLabel', { amount: f.amount ?? 0 })
  const base = t('settings.portal.fees.percentLabel', { percent: f.percent ?? 0 })
  const parts: string[] = []
  if (f.minAmount != null) parts.push(t('settings.portal.fees.minLabel', { amount: f.minAmount }))
  if (f.maxAmount != null) parts.push(t('settings.portal.fees.maxLabel', { amount: f.maxAmount }))
  return parts.length ? `${base} (${parts.join(', ')})` : base
}

// ── exchange pricing ─────────────────────────────────────────────────────────

function ExchangePricingEditor({ value, owner, onSaved, onDraft }: {
  value: ExchangePricing; owner: boolean; onSaved: (s: CustomerFeeSettings) => void; onDraft?: (d: FeeDraft) => void
}) {
  const { t } = useTranslation()
  const [anyProduct, setAnyProduct] = useState(value.anyProduct)
  const [freeSwaps, setFreeSwaps] = useState(value.freeVariantSwaps)
  const [lowerOnly, setLowerOnly] = useState(value.sameOrLowerOnly)
  const [maxDiff, setMaxDiff] = useState(str(value.maxDifference))
  const { saving, error, run } = useSave(onSaved)
  const dirty = anyProduct !== value.anyProduct || freeSwaps !== value.freeVariantSwaps || lowerOnly !== value.sameOrLowerOnly || num(maxDiff) !== numOrNull(value.maxDifference)
  const ro = !owner || saving
  const input: ExchangePricing = {
    anyProduct, sameOrLowerOnly: lowerOnly, maxDifference: lowerOnly ? null : num(maxDiff), freeVariantSwaps: freeSwaps,
  }
  useReport(input, onDraft ? (pricing => onDraft({ pricing })) : undefined)

  return (
    <div className="divide-y divide-line" data-testid="exchange-pricing">
      <div className="px-6 py-5 space-y-3">
        <p id="exchange-swap-to-title" className="text-body font-semibold text-primary">{t('settings.portal.fees.swapTo.title')}</p>
        <RadioCards labelledBy="exchange-swap-to-title" value={anyProduct ? 'any' : 'same'} disabled={ro}
          onChange={v => setAnyProduct(v === 'any')} testId="exchange-swap-to"
          options={[
            { value: 'same', title: t('settings.portal.fees.swapTo.same'), description: t('settings.portal.fees.swapTo.sameDesc') },
            { value: 'any', title: t('settings.portal.fees.swapTo.any'), description: t('settings.portal.fees.swapTo.anyDesc') },
          ]} />
      </div>
      <PricingSwitch title={t('settings.portal.fees.pricing.freeSwaps')} help={t('settings.portal.fees.pricing.freeSwapsHelp')}
        checked={freeSwaps} onChange={setFreeSwaps} disabled={ro} />
      <div className="px-6 py-5 space-y-4">
        <PricingSwitch inline title={t('settings.portal.fees.pricing.lowerOnly')} help={t('settings.portal.fees.pricing.lowerOnlyHelp')}
          checked={lowerOnly} onChange={setLowerOnly} disabled={ro} />
        {!lowerOnly && (
          <div className="space-y-1 rounded-xl border border-line bg-elevated/60 p-4">
            <MoneyInput id="exchange-max-difference" label={t('settings.portal.fees.pricing.maxDifference')} value={maxDiff}
              onChange={setMaxDiff} disabled={ro} testId="max-difference" />
            <p className="text-small text-muted">{t('settings.portal.fees.pricing.maxDifferenceHelp')}</p>
          </div>
        )}
      </div>
      {(error || owner) && (
        <div className="px-6 py-4 flex items-center justify-end gap-3 flex-wrap">
          <span className="flex-1 min-w-0"><ErrorLine error={error} testId="error-exchange-pricing" /></span>
          {owner && (
            <Button size="sm" disabled={!dirty || saving} onClick={() => run(() => saveExchangePricing(input))}>
              {t('settings.portal.fees.save')}
            </Button>
          )}
        </div>
      )}
    </div>
  )
}

function PricingSwitch({ title, help, checked, onChange, disabled, inline }: {
  title: string; help: string; checked: boolean; onChange: (v: boolean) => void; disabled: boolean; inline?: boolean
}) {
  return (
    <div className={cn('flex items-start gap-4', !inline && 'px-6 py-5')}>
      <div className="flex-1 min-w-0">
        <p className="text-body font-semibold text-primary">{title}</p>
        <p className="text-small text-muted mt-0.5 max-w-xl">{help}</p>
      </div>
      <Toggle checked={checked} onChange={onChange} ariaLabel={title} disabled={disabled} />
    </div>
  )
}

// ── fee rules ────────────────────────────────────────────────────────────────

/** The mockup's Fee / Type / Amount / On table — one row per kind, each still saved by its own Save. */
const FEE_GRID = 'sm:grid sm:grid-cols-[minmax(0,1.4fr)_minmax(0,1.1fr)_minmax(0,1fr)_auto] sm:gap-4 sm:items-center'

function FeeRulesTable({ rules, owner, onSaved, onDraft }: {
  rules: Record<FeeKind, FeeRule | null>; owner: boolean; onSaved: (s: CustomerFeeSettings) => void; onDraft?: (d: FeeDraft) => void
}) {
  const { t } = useTranslation()
  const drafts = useRef<Partial<Record<FeeKind, FeeRuleInput>>>({})
  const report = onDraft && ((kind: FeeKind, r: FeeRuleInput) => {
    drafts.current = { ...drafts.current, [kind]: r }
    onDraft({ rules: drafts.current })
  })
  return (
    <div data-testid="fee-rules-table">
      <div className={cn('hidden px-6 py-2.5 bg-elevated/70 text-caption font-semibold text-muted', FEE_GRID)} aria-hidden="true">
        <span>{t('settings.portal.fees.table.fee')}</span>
        <span>{t('settings.portal.fees.table.type')}</span>
        <span>{t('settings.portal.fees.table.amount')}</span>
        <span className="text-end">{t('settings.portal.fees.table.on')}</span>
      </div>
      <div className="divide-y divide-line border-t border-line">
        {KINDS.map(k => (
          <RuleEditor key={k} kind={k} rule={rules[k]} owner={owner} onSaved={onSaved}
            onDraft={report ? (r => report(k, r)) : undefined} />
        ))}
      </div>
    </div>
  )
}

function RuleEditor({ kind, rule, owner, onSaved, onDraft }: {
  kind: FeeKind; rule: FeeRule | null; owner: boolean; onSaved: (s: CustomerFeeSettings) => void
  onDraft?: (r: FeeRuleInput) => void
}) {
  const { t } = useTranslation()
  const [enabled, setEnabled] = useState(rule?.enabled ?? false)
  const [mode, setMode] = useState<FeeMode>(modeOf(rule?.mode))
  const [amount, setAmount] = useState(str(rule?.amount))
  const [percent, setPercent] = useState(str(rule?.percent))
  const [min, setMin] = useState(str(rule?.minAmount))
  const [max, setMax] = useState(str(rule?.maxAmount))
  const [waive, setWaive] = useState(rule?.waiveMerchantFault ?? true)
  const { saving, error, run } = useSave(onSaved)
  const ro = !owner || saving

  const input = useMemo(() => ({
    enabled, mode, waiveMerchantFault: waive,
    amount: mode === 'fixed' ? num(amount) : null,
    percent: mode === 'percent' ? num(percent) : null,
    minAmount: mode === 'percent' ? num(min) : null,
    maxAmount: mode === 'percent' ? num(max) : null,
  }), [enabled, mode, waive, amount, percent, min, max])
  useReport(input, onDraft)
  const dirty = !rule
    ? enabled || amount !== '' || percent !== ''
    : input.enabled !== rule.enabled || input.mode !== modeOf(rule.mode) || input.waiveMerchantFault !== rule.waiveMerchantFault
      || input.amount !== numOrNull(rule.amount) || input.percent !== numOrNull(rule.percent)
      || input.minAmount !== numOrNull(rule.minAmount) || input.maxAmount !== numOrNull(rule.maxAmount)

  const title = t(`settings.portal.fees.${kind}.title`)
  return (
    <div className="px-6 py-4 space-y-3" data-testid={`fee-rule-${kind}`}>
      <div className={cn('flex flex-col gap-3', FEE_GRID)}>
        <div className={cn('flex items-start gap-4 sm:block', !enabled && 'opacity-70')}>
          <div className="flex-1 min-w-0">
            <p className="text-body font-semibold text-primary">{title}</p>
            <p className="text-small text-muted mt-0.5">{t(`settings.portal.fees.${kind}.description`)}</p>
          </div>
        </div>
        <div><ModeChoice name={`fee-mode-${kind}`} value={mode} onChange={setMode} disabled={ro} /></div>
        <div>
          {mode === 'fixed' ? (
            <AmountField id={`fee-amount-${kind}`} label={t('settings.portal.fees.amount')} unit={t('settings.portal.fees.currency')}
              value={amount} onChange={setAmount} disabled={ro} testId={`fee-amount-${kind}`} max={30000} step={1} />
          ) : (
            <AmountField id={`fee-percent-${kind}`} label={t('settings.portal.fees.percent')} unit="%"
              value={percent} onChange={setPercent} disabled={ro} testId={`fee-percent-${kind}`} max={100} step={0.01} decimal />
          )}
        </div>
        <div className="flex items-center gap-3 sm:justify-end">
          <span className={cn('text-small font-medium', enabled ? 'text-primary' : 'text-muted')} aria-hidden="true">
            {enabled ? t('settings.portal.on') : t('settings.portal.off')}
          </span>
          <Toggle checked={enabled} onChange={setEnabled} ariaLabel={title} disabled={ro} />
        </div>
      </div>
      {/* Percentage: the stored min / max sit under the row — never hidden while percent is chosen. */}
      {mode === 'percent' && (
        <div className="flex flex-wrap gap-4 rounded-xl border border-line bg-elevated/60 p-3.5">
          <MoneyInput id={`fee-min-${kind}`} label={t('settings.portal.fees.min')} value={min} onChange={setMin} disabled={ro} />
          <MoneyInput id={`fee-max-${kind}`} label={t('settings.portal.fees.max')} value={max} onChange={setMax} disabled={ro} />
        </div>
      )}
      <div className="flex items-end justify-between gap-3 flex-wrap">
        <label className="flex items-start gap-2 text-small text-primary flex-1 min-w-[220px]">
          <input type="checkbox" className="mt-0.5 w-4 h-4 accent-trace-blue shrink-0" checked={waive} disabled={ro}
            onChange={e => setWaive(e.target.checked)} data-testid={`fee-waive-${kind}`} />
          <span>
            {t('settings.portal.fees.waive')}
            <span className="block text-small text-muted">
              {t(mode === 'fixed' ? 'settings.portal.fees.waiveFixedHelp' : 'settings.portal.fees.waivePercentHelp')}
            </span>
          </span>
        </label>
        {owner && (
          <Button size="sm" disabled={!dirty || saving}
            onClick={() => run(() => saveFeeRule(kind, input))}>
            {t('settings.portal.fees.save')}
          </Button>
        )}
      </div>
      <ErrorLine error={error} testId={`error-fee-${kind}`} />
    </div>
  )
}

/** A number in a table cell: the column header names it; the label stays for screen readers. */
function AmountField({ id, label, unit, value, onChange, disabled, testId, max, step, decimal }: {
  id: string; label: string; unit: string; value: string; onChange: (v: string) => void; disabled?: boolean
  testId: string; max: number; step: number; decimal?: boolean
}) {
  return (
    <span className="flex items-center gap-2">
      <label htmlFor={id} className="sr-only">{label}</label>
      <span className="w-28">
        <Input id={id} type="number" min={0} max={max} step={step} inputMode={decimal ? 'decimal' : 'numeric'}
          value={value} disabled={disabled} data-testid={testId} onChange={e => onChange(e.target.value)} />
      </span>
      <span className="text-body text-muted">{unit}</span>
    </span>
  )
}

// ── merchant-fault reasons ─────────────────────────────────────────────────────

function ReasonsEditor({ value, owner, onSaved }: {
  value: string[]; owner: boolean; onSaved: (s: CustomerFeeSettings) => void
}) {
  const { t } = useTranslation()
  const [picked, setPicked] = useState<string[]>(value)
  const { saving, error, run } = useSave(onSaved)
  const dirty = picked.length !== value.length || picked.some(r => !value.includes(r))
  return (
    <fieldset className="px-6 py-5 space-y-3" data-testid="merchant-fault-reasons">
      <legend className="sr-only">{t('settings.portal.fees.reasons.title')}</legend>
      <div>
        <p className="text-body font-semibold text-primary">{t('settings.portal.fees.reasons.title')}</p>
        <p className="text-small text-muted mt-0.5">{t('settings.portal.fees.reasons.description')}</p>
      </div>
      <div className="flex flex-wrap gap-2">
        {REASON_CODES.map(r => {
          const on = picked.includes(r)
          return (
            <button key={r} type="button" aria-pressed={on} disabled={!owner || saving} data-testid={`reason-${r}`}
              onClick={() => setPicked(p => on ? p.filter(x => x !== r) : [...p, r])}
              className={cn('inline-flex items-center gap-1.5 rounded-full border px-3 py-1.5 text-small font-medium transition-colors',
                'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-trace-blue disabled:cursor-not-allowed',
                on ? 'border-trace-blue bg-trace-blue/10 text-trace-blue-hover' : 'border-line bg-panel text-primary hover:border-grey-300',
                (!owner || saving) && 'opacity-70')}>
              {on && <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3"
                aria-hidden="true"><path d="M20 6 9 17l-5-5" /></svg>}
              {t(`exchangesRefunds.requests.reasons.${r}`)}
            </button>
          )
        })}
      </div>
      <ErrorLine error={error} testId="error-reasons" />
      {owner && (
        <div className="flex justify-end">
          <Button size="sm" disabled={!dirty || saving}
            onClick={() => run(() => saveMerchantFaultReasons(REASON_CODES.filter(r => picked.includes(r))))}>
            {t('settings.portal.fees.save')}
          </Button>
        </div>
      )}
    </fieldset>
  )
}

// ── area overrides ───────────────────────────────────────────────────────────

function AreaOverrides({ overrides, owner, onSaved }: {
  overrides: FeeAreaOverride[]; owner: boolean; onSaved: (s: CustomerFeeSettings) => void
}) {
  const { t, i18n } = useTranslation()
  const ar = i18n.language === 'ar'
  const { saving, error, run } = useSave(onSaved)
  const [adding, setAdding] = useState(false)

  const areaName = (o: FeeAreaOverride) => {
    const city = (ar ? o.cityNameAr : null) ?? o.cityName ?? o.cityId
    if (!o.districtId) return t('settings.portal.fees.areas.wholeCity', { city })
    return `${city} · ${(ar ? o.districtNameAr : null) ?? o.districtName ?? o.districtId}`
  }

  return (
    <div className="px-6 py-5 space-y-3" data-testid="fee-area-overrides">
      <div>
        <p className="text-body font-semibold text-primary">{t('settings.portal.fees.areas.title')}{owner && <SavesRightAway />}</p>
        <p className="text-small text-muted mt-0.5">{t('settings.portal.fees.areas.description')}</p>
      </div>
      {overrides.length === 0 ? (
        <p className="text-small text-muted" data-testid="fee-areas-empty">{t('settings.portal.fees.areas.empty')}</p>
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full text-small">
            <thead>
              <tr className="text-muted text-start">
                <th className="text-start font-medium py-1.5 pe-3">{t('settings.portal.fees.areas.area')}</th>
                <th className="text-start font-medium py-1.5 pe-3">{t('settings.portal.fees.areas.kind')}</th>
                <th className="text-start font-medium py-1.5 pe-3">{t('settings.portal.fees.areas.fee')}</th>
                {owner && <th className="py-1.5"><span className="sr-only">{t('settings.portal.fees.areas.remove')}</span></th>}
              </tr>
            </thead>
            <tbody className="divide-y divide-line">
              {overrides.map(o => (
                <tr key={o.id} data-testid="fee-area-row">
                  <td className="py-2 pe-3 text-primary"><bdi>{areaName(o)}</bdi></td>
                  <td className="py-2 pe-3 text-primary">{t(`settings.portal.fees.${o.kind}.short`)}</td>
                  <td className="py-2 pe-3 text-primary">{describeFee(o, t)}</td>
                  {owner && (
                    <td className="py-2 text-end">
                      <Button size="sm" variant="ghost" disabled={saving}
                        onClick={() => run(() => deleteFeeAreaOverride(o.id))}>
                        {t('settings.portal.fees.areas.remove')}
                      </Button>
                    </td>
                  )}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <ErrorLine error={error} testId="error-fee-areas" />
      {owner && !adding && (
        <Button size="sm" variant="outline" onClick={() => setAdding(true)}>
          {t('settings.portal.fees.areas.add')}
        </Button>
      )}
      {owner && adding && (
        <AddAreaForm saving={saving} onCancel={() => setAdding(false)}
          onAdd={async o => { if (await run(() => addFeeAreaOverride(o))) setAdding(false) }} />
      )}
    </div>
  )
}

function AddAreaForm({ saving, onAdd, onCancel }: {
  saving: boolean
  onAdd: (o: Parameters<typeof addFeeAreaOverride>[0]) => void
  onCancel: () => void
}) {
  const { t, i18n } = useTranslation()
  const ar = i18n.language === 'ar'
  const [cities, setCities] = useState<FeeArea[] | null>(null)
  const [districts, setDistricts] = useState<FeeArea[]>([])
  const [kind, setKind] = useState<FeeKind>('refund')
  const [cityId, setCityId] = useState('')
  const [districtId, setDistrictId] = useState('')
  const [mode, setMode] = useState<FeeMode>('fixed')
  const [amount, setAmount] = useState('')
  const [percent, setPercent] = useState('')
  const [loadFailed, setLoadFailed] = useState(false)

  useEffect(() => {
    getFeeAreas().then(setCities).catch(() => setLoadFailed(true))
  }, [])
  useEffect(() => {
    setDistrictId('')
    setDistricts([])
    if (!cityId) return
    let cancelled = false
    getFeeAreas(cityId).then(d => { if (!cancelled) setDistricts(d) }).catch(() => {})
    return () => { cancelled = true }
  }, [cityId])

  const label = (a: FeeArea) => (ar ? a.nameAr : null) ?? a.name
  const selectClass = 'w-full rounded-xl border border-line bg-surface px-3 py-2 text-body text-primary'

  return (
    <div className="rounded-xl border border-line p-4 space-y-3" data-testid="add-area-form">
      {loadFailed && <p className="text-small text-critical" role="alert">{t('settings.portal.fees.areas.loadFailed')}</p>}
      <div className="grid sm:grid-cols-3 gap-3">
        <label className="flex flex-col gap-1 text-small text-muted">
          {t('settings.portal.fees.areas.kind')}
          <select className={selectClass} value={kind} onChange={e => setKind(e.target.value as FeeKind)} data-testid="area-kind">
            {KINDS.map(k => <option key={k} value={k}>{t(`settings.portal.fees.${k}.short`)}</option>)}
          </select>
        </label>
        <label className="flex flex-col gap-1 text-small text-muted">
          {t('settings.portal.fees.areas.city')}
          <select className={selectClass} value={cityId} onChange={e => setCityId(e.target.value)} data-testid="area-city"
            disabled={!cities}>
            <option value="">{t('settings.portal.fees.areas.chooseCity')}</option>
            {(cities ?? []).map(c => <option key={c.id} value={c.id}>{label(c)}</option>)}
          </select>
        </label>
        <label className="flex flex-col gap-1 text-small text-muted">
          {t('settings.portal.fees.areas.district')}
          <select className={selectClass} value={districtId} onChange={e => setDistrictId(e.target.value)} data-testid="area-district"
            disabled={!cityId}>
            <option value="">{t('settings.portal.fees.areas.allDistricts')}</option>
            {districts.map(d => <option key={d.id} value={d.id}>{label(d)}</option>)}
          </select>
        </label>
      </div>
      <ModeChoice name="area-fee-mode" value={mode} onChange={setMode} disabled={saving} />
      {mode === 'fixed' ? (
        <MoneyInput id="area-fee-amount" label={t('settings.portal.fees.amount')} value={amount} onChange={setAmount}
          disabled={saving} testId="area-amount" />
      ) : (
        <label htmlFor="area-fee-percent" className="flex flex-col gap-1 text-small text-muted">
          {t('settings.portal.fees.percent')}
          <span className="flex items-center gap-2">
            <span className="w-24">
              <Input id="area-fee-percent" type="number" min={0} max={100} step={0.01} inputMode="decimal" value={percent}
                disabled={saving} onChange={e => setPercent(e.target.value)} data-testid="area-percent" />
            </span>
            <span className="text-body text-muted">%</span>
          </span>
        </label>
      )}
      <p className="text-small text-muted">{t('settings.portal.fees.areas.zeroHelp')}</p>
      <div className="flex justify-end gap-2">
        <Button size="sm" variant="ghost" onClick={onCancel} disabled={saving}>{t('settings.portal.fees.areas.cancel')}</Button>
        <Button size="sm" disabled={saving || !cityId}
          onClick={() => onAdd({
            kind, cityId, districtId: districtId || null, mode,
            amount: mode === 'fixed' ? num(amount) : null,
            percent: mode === 'percent' ? num(percent) : null,
            minAmount: null, maxAmount: null,
          })}>
          {t('settings.portal.fees.areas.save')}
        </Button>
      </div>
    </div>
  )
}
