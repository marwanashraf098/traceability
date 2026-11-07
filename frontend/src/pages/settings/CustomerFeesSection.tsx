import { Component, type ErrorInfo, type ReactNode, useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import {
  CustomerFeeSettings, FeeArea, FeeAreaOverride, FeeKind, FeeMode,
  TransferCommandError, addFeeAreaOverride, deleteFeeAreaOverride, getCustomerFees, getFeeAreas, getRoleFromToken,
} from '../../api'
import { Button, Input, Toggle, cn, useToast } from '../../components/ui'
import { EditedTag, RadioCards, SavesRightAway } from './portal/ui'
import {
  Draft, EditedKey, PricingDraft, RuleDraft, Section, feesDraftOf, modeOf, num, savedWaiver, waiverMixed,
} from './portal/savePlan'
import { REASON_CODES } from './portal/usePortalSettingsDraft'
import type { PriceMode } from './portal/policySummary'

export { REASON_CODES } from './portal/usePortalSettingsDraft'

const KINDS: FeeKind[] = ['exchange', 'refund']

/** The fee parts of the tab's draft. */
export type FeesDraft = Pick<Draft, 'pricing' | 'rules' | 'waiver' | 'reasons'>

export interface FeesEditorProps {
  saved: CustomerFeeSettings
  draft: FeesDraft
  onChange: (fn: (d: FeesDraft) => FeesDraft) => void
  owner: boolean
  edited: ReadonlySet<EditedKey>
  errors: Partial<Record<Section, string>>
  /** "Up to a limit" chosen with an empty amount — blocks Save. */
  capError?: boolean
  onAreaFeesSaved: (s: CustomerFeeSettings) => void
}

/**
 * Returns portal fees — Exchange pricing and Customer fees, as fields of the Returns portal tab's draft.
 *
 * Redesign Pass B: nothing here saves on its own any more except fees by area (and the logo, elsewhere) — every other
 * change goes into the tab's draft and is sent by the one "Save changes" (portal/savePlan.ts). Price mode is one
 * radio group; the per-rule "no fee when it's the store's fault" boxes are one switch with the reason chips.
 * Owner edits; a manager sees it read-only. Hidden while the backend says {@code visible:false} or when it can't be
 * loaded. Wrapped in its own error boundary (prod report 2026-11-06: a render crash here blanked the settings page).
 *
 * The default export (no tab around it) loads its own settings and keeps its own draft — nothing to save it with; the
 * tab renders the two parts itself (`ExchangePricingPart`, `CustomerFeesPart`).
 */
export default function CustomerFeesSection() {
  return (
    <FeesErrorBoundary>
      <StandaloneFees />
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

function StandaloneFees() {
  const { t } = useTranslation()
  const [settings, setSettings] = useState<CustomerFeeSettings | null>(null)
  const [draft, setDraft] = useState<FeesDraft | null>(null)
  useEffect(() => {
    let cancelled = false
    getCustomerFees().then(s => {
      if (cancelled) return
      setSettings(s)
      setDraft(feesDraftOf(s))
    }).catch(() => {})
    return () => { cancelled = true }
  }, [])
  if (!settings?.visible || !draft) return null
  const props: FeesEditorProps = {
    saved: settings, draft, onChange: fn => setDraft(d => (d ? fn(d) : d)), owner: getRoleFromToken() === 'owner',
    edited: new Set(), errors: {}, onAreaFeesSaved: s => setSettings(prev => ({ ...(prev ?? s), overrides: s.overrides })),
  }
  const ownerNote = !props.owner && <p className="text-small text-warning-text mt-1.5">{t('settings.portal.fees.ownerOnly')}</p>
  return (
    <div className="space-y-4" data-testid="customer-fees-section">
      <section className="card overflow-hidden divide-y divide-line max-w-3xl" aria-labelledby="exchange-pricing-title">
        <div className="p-6">
          <h2 id="exchange-pricing-title" className="text-h3 text-primary">{t('settings.portal.fees.pricing.title')}</h2>
          <p className="text-small text-muted mt-1">{t('settings.portal.fees.pricing.description')}</p>
          {ownerNote}
        </div>
        <ExchangePricingEditor {...props} />
      </section>
      <section className="card overflow-hidden divide-y divide-line max-w-3xl" aria-labelledby="customer-fees-title">
        <div className="p-6">
          <h2 id="customer-fees-title" className="text-h3 text-primary">{t('settings.portal.fees.title')}</h2>
          <p className="text-small text-muted mt-1">{t('settings.portal.fees.description')}</p>
          {ownerNote}
        </div>
        <FeesEditor {...props} />
      </section>
    </div>
  )
}

/** Inside the tab's Exchanges section. */
export function ExchangePricingPart(props: FeesEditorProps) {
  const { t } = useTranslation()
  return (
    <FeesErrorBoundary compact>
      <div className="divide-y divide-line" data-testid="customer-fees-pricing">
        <div className="px-6 pt-5 pb-1">
          <h4 className="text-body font-semibold text-primary">{t('settings.portal.fees.pricing.title')}</h4>
          <p className="text-small text-muted mt-0.5">{t('settings.portal.fees.pricing.description')}</p>
          {!props.owner && <p className="text-small text-warning-text mt-1.5">{t('settings.portal.fees.ownerOnly')}</p>}
        </div>
        <ExchangePricingEditor {...props} />
      </div>
    </FeesErrorBoundary>
  )
}

/** Inside the tab's Refunds & fees section. */
export function CustomerFeesPart(props: FeesEditorProps) {
  const { t } = useTranslation()
  return (
    <FeesErrorBoundary compact>
      <div className="divide-y divide-line" data-testid="customer-fees-rules">
        <div className="px-6 pt-5 pb-4">
          <h4 className="text-body font-semibold text-primary">{t('settings.portal.fees.title')}</h4>
          <p className="text-small text-muted mt-0.5">{t('settings.portal.fees.table.help')}</p>
          {!props.owner && <p className="text-small text-warning-text mt-1.5">{t('settings.portal.fees.ownerOnly')}</p>}
        </div>
        <FeesEditor {...props} />
      </div>
    </FeesErrorBoundary>
  )
}

function FeesEditor(props: FeesEditorProps) {
  return (
    <>
      <FeeRulesTable {...props} />
      <WaiverEditor {...props} />
      <AreaOverrides overrides={props.saved.overrides} owner={props.owner} onSaved={props.onAreaFeesSaved} />
    </>
  )
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

function ErrorLine({ error, testId }: { error?: string; testId: string }) {
  return error ? <p className="text-small text-critical" role="alert" data-testid={testId}>{error}</p> : null
}

function MoneyInput({ id, label, value, onChange, disabled, testId, invalid }: {
  id: string; label: string; value: string; onChange: (v: string) => void; disabled?: boolean; testId?: string; invalid?: boolean
}) {
  const { t } = useTranslation()
  return (
    <label htmlFor={id} className="flex flex-col gap-1 text-small text-muted">
      {label}
      <span className="flex items-center gap-2">
        <span className="w-28">
          <Input id={id} type="number" min={0} max={30000} step={1} inputMode="numeric" value={value}
            disabled={disabled} data-testid={testId} invalid={invalid} aria-invalid={invalid || undefined}
            onChange={e => onChange(e.target.value)} />
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

function ExchangePricingEditor({ draft, onChange, owner, edited, errors, capError }: FeesEditorProps) {
  const { t } = useTranslation()
  const p = draft.pricing
  const set = (patch: Partial<PricingDraft>) => onChange(d => ({ ...d, pricing: { ...d.pricing, ...patch } }))
  const ro = !owner
  return (
    <div className="divide-y divide-line" data-testid="exchange-pricing">
      <div className="px-6 py-5 space-y-3">
        <div className="flex items-center">
          <p id="exchange-swap-to-title" className="text-body font-semibold text-primary">{t('settings.portal.fees.swapTo.title')}</p>
          {edited.has('pricing.swapTo') && <EditedTag />}
        </div>
        <RadioCards labelledBy="exchange-swap-to-title" value={p.anyProduct ? 'any' : 'same'} disabled={ro}
          onChange={v => set({ anyProduct: v === 'any' })} testId="exchange-swap-to"
          options={[
            { value: 'same', title: t('settings.portal.fees.swapTo.same'), description: t('settings.portal.fees.swapTo.sameDesc') },
            { value: 'any', title: t('settings.portal.fees.swapTo.any'), description: t('settings.portal.fees.swapTo.anyDesc') },
          ]} />
      </div>
      <PricingSwitch title={t('settings.portal.fees.pricing.freeSwaps')} help={t('settings.portal.fees.pricing.freeSwapsHelp')}
        checked={p.freeVariantSwaps} onChange={v => set({ freeVariantSwaps: v })} disabled={ro}
        edited={edited.has('pricing.freeSwaps')} />
      {/* V223 — only matters while same-product swaps use the price difference. */}
      {!p.freeVariantSwaps && (
        <PricingSwitch title={t('settings.portal.fees.pricing.sameProductRefunds')}
          help={t('settings.portal.fees.pricing.sameProductRefundsHelp')}
          checked={p.sameProductSwapRefunds} onChange={v => set({ sameProductSwapRefunds: v })} disabled={ro}
          edited={edited.has('pricing.sameProductRefunds')} />
      )}
      <div className="px-6 py-5 space-y-3">
        <div className="flex items-center">
          <p id="exchange-price-mode-title" className="text-body font-semibold text-primary">{t('settings.portal.fees.priceMode.title')}</p>
          {edited.has('pricing.priceMode') && <EditedTag />}
        </div>
        <RadioCards<PriceMode> labelledBy="exchange-price-mode-title" value={p.priceMode} disabled={ro} columns={3}
          onChange={v => set({ priceMode: v })} testId="exchange-price-mode"
          options={[
            { value: 'nolimit', title: t('settings.portal.fees.priceMode.nolimit'), description: t('settings.portal.fees.priceMode.nolimitDesc') },
            { value: 'cap', title: t('settings.portal.fees.priceMode.cap'), description: t('settings.portal.fees.priceMode.capDesc') },
            { value: 'lower', title: t('settings.portal.fees.pricing.lowerOnly'), description: t('settings.portal.fees.pricing.lowerOnlyHelp') },
          ]} />
        {p.priceMode === 'cap' && (
          <div className="space-y-1 rounded-xl border border-line bg-elevated/60 p-4">
            <MoneyInput id="exchange-max-difference" label={t('settings.portal.fees.pricing.maxDifference')} value={p.capText}
              onChange={v => set({ capText: v })} disabled={ro} testId="max-difference" invalid={capError} />
            {capError
              ? <p className="text-small text-critical" role="alert" data-testid="error-max-difference">{t('settings.portal.fees.priceMode.capRequired')}</p>
              : <p className="text-small text-muted">{t('settings.portal.fees.priceMode.capHelp')}</p>}
          </div>
        )}
      </div>
      {errors.pricing && <div className="px-6 py-4"><ErrorLine error={errors.pricing} testId="error-exchange-pricing" /></div>}
    </div>
  )
}

function PricingSwitch({ title, help, checked, onChange, disabled, inline, edited }: {
  title: string; help: string; checked: boolean; onChange: (v: boolean) => void; disabled: boolean; inline?: boolean
  edited?: boolean
}) {
  return (
    <div className={cn('flex items-start gap-4', !inline && 'px-6 py-5')}>
      <div className="flex-1 min-w-0">
        <p className="text-body font-semibold text-primary">{title}{edited && <EditedTag />}</p>
        <p className="text-small text-muted mt-0.5 max-w-xl">{help}</p>
      </div>
      <Toggle checked={checked} onChange={onChange} ariaLabel={title} disabled={disabled} />
    </div>
  )
}

// ── fee rules ────────────────────────────────────────────────────────────────

/** The mockup's Fee / Type / Amount / On table — one row per kind. */
const FEE_GRID = 'sm:grid sm:grid-cols-[minmax(0,1.4fr)_minmax(0,1.1fr)_minmax(0,1fr)_auto] sm:gap-4 sm:items-center'

function FeeRulesTable(props: FeesEditorProps) {
  const { t } = useTranslation()
  return (
    <div data-testid="fee-rules-table">
      <div className={cn('hidden px-6 py-2.5 bg-elevated/70 text-caption font-semibold text-muted', FEE_GRID)} aria-hidden="true">
        <span>{t('settings.portal.fees.table.fee')}</span>
        <span>{t('settings.portal.fees.table.type')}</span>
        <span>{t('settings.portal.fees.table.amount')}</span>
        <span className="text-end">{t('settings.portal.fees.table.on')}</span>
      </div>
      <div className="divide-y divide-line border-t border-line">
        {KINDS.map(k => <RuleEditor key={k} kind={k} {...props} />)}
      </div>
    </div>
  )
}

function RuleEditor({ kind, draft, onChange, owner, edited, errors }: FeesEditorProps & { kind: FeeKind }) {
  const { t } = useTranslation()
  const r = draft.rules[kind]
  const set = (patch: Partial<RuleDraft>) => onChange(d => ({ ...d, rules: { ...d.rules, [kind]: { ...d.rules[kind], ...patch } } }))
  const ro = !owner
  const title = t(`settings.portal.fees.${kind}.title`)
  return (
    <div className="px-6 py-4 space-y-3" data-testid={`fee-rule-${kind}`}>
      <div className={cn('flex flex-col gap-3', FEE_GRID)}>
        <div className={cn('flex items-start gap-4 sm:block', !r.enabled && 'opacity-70')}>
          <div className="flex-1 min-w-0">
            <p className="text-body font-semibold text-primary">{title}{edited.has(`rule.${kind}`) && <EditedTag />}</p>
            <p className="text-small text-muted mt-0.5">{t(`settings.portal.fees.${kind}.description`)}</p>
          </div>
        </div>
        <div><ModeChoice name={`fee-mode-${kind}`} value={r.mode} onChange={mode => set({ mode })} disabled={ro} /></div>
        <div>
          {r.mode === 'fixed' ? (
            <AmountField id={`fee-amount-${kind}`} label={t('settings.portal.fees.amount')} unit={t('settings.portal.fees.currency')}
              value={r.amount} onChange={amount => set({ amount })} disabled={ro} testId={`fee-amount-${kind}`} max={30000} step={1} />
          ) : (
            <AmountField id={`fee-percent-${kind}`} label={t('settings.portal.fees.percent')} unit="%"
              value={r.percent} onChange={percent => set({ percent })} disabled={ro} testId={`fee-percent-${kind}`} max={100} step={0.01} decimal />
          )}
        </div>
        <div className="flex items-center gap-3 sm:justify-end">
          <span className={cn('text-small font-medium', r.enabled ? 'text-primary' : 'text-muted')} aria-hidden="true">
            {r.enabled ? t('settings.portal.on') : t('settings.portal.off')}
          </span>
          <Toggle checked={r.enabled} onChange={enabled => set({ enabled })} ariaLabel={title} disabled={ro} />
        </div>
      </div>
      {/* Percentage: the stored min / max sit under the row — never hidden while percent is chosen. */}
      {r.mode === 'percent' && (
        <div className="flex flex-wrap gap-4 rounded-xl border border-line bg-elevated/60 p-3.5">
          <MoneyInput id={`fee-min-${kind}`} label={t('settings.portal.fees.min')} value={r.min} onChange={min => set({ min })} disabled={ro} />
          <MoneyInput id={`fee-max-${kind}`} label={t('settings.portal.fees.max')} value={r.max} onChange={max => set({ max })} disabled={ro} />
        </div>
      )}
      {/* How the one store's-fault waiver applies to this fee's type. */}
      {draft.waiver && (
        <p className="text-small text-muted" data-testid={`fee-waive-help-${kind}`}>
          {t(r.mode === 'fixed' ? 'settings.portal.fees.waiveFixedHelp' : 'settings.portal.fees.waivePercentHelp')}
        </p>
      )}
      <ErrorLine error={errors[kind === 'exchange' ? 'ruleExchange' : 'ruleRefund']} testId={`error-fee-${kind}`} />
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

// ── waiver + store's-fault reasons ───────────────────────────────────────────────

/**
 * One "Waive fees when it's the store's fault" switch for both fees, with the reasons that count as the store's fault.
 * On only when every existing fee waives; mixed fees show it off with a note — saving writes the switch into every fee
 * that exists with a valid amount, so they match.
 */
function WaiverEditor({ saved, draft, onChange, owner, edited, errors }: FeesEditorProps) {
  const { t } = useTranslation()
  const ro = !owner
  const title = t('settings.portal.fees.waiver.title')
  const mixed = waiverMixed(saved.rules) && draft.waiver === savedWaiver(saved.rules)
  return (
    <div className="divide-y divide-line" data-testid="fee-waiver">
      <div className="px-6 py-5 flex items-start gap-4">
        <div className="flex-1 min-w-0">
          <p className="text-body font-semibold text-primary">{title}{edited.has('waiver') && <EditedTag />}</p>
          <p className="text-small text-muted mt-0.5 max-w-xl">{t('settings.portal.fees.waiver.description')}</p>
          {mixed && <p className="text-small text-warning-text mt-1.5" data-testid="waiver-mixed">{t('settings.portal.fees.waiver.mixed')}</p>}
        </div>
        <span className={cn('text-small font-medium mt-0.5', draft.waiver ? 'text-primary' : 'text-muted')} aria-hidden="true">
          {draft.waiver ? t('settings.portal.on') : t('settings.portal.off')}
        </span>
        <Toggle checked={draft.waiver} onChange={waiver => onChange(d => ({ ...d, waiver }))} ariaLabel={title} disabled={ro} />
      </div>
      <fieldset className="px-6 py-5 space-y-3" data-testid="merchant-fault-reasons">
        <legend className="sr-only">{t('settings.portal.fees.reasons.title')}</legend>
        <div>
          <p className="text-body font-semibold text-primary">{t('settings.portal.fees.reasons.title')}{edited.has('reasons') && <EditedTag />}</p>
          <p className="text-small text-muted mt-0.5">{t('settings.portal.fees.reasons.description')}</p>
        </div>
        <div className="flex flex-wrap gap-2">
          {REASON_CODES.map(r => {
            const on = draft.reasons.includes(r)
            return (
              <button key={r} type="button" aria-pressed={on} disabled={ro} data-testid={`reason-${r}`}
                onClick={() => onChange(d => ({ ...d, reasons: on ? d.reasons.filter(x => x !== r) : [...d.reasons, r] }))}
                className={cn('inline-flex items-center gap-1.5 rounded-full border px-3 py-1.5 text-small font-medium transition-colors',
                  'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-trace-blue disabled:cursor-not-allowed',
                  on ? 'border-trace-blue bg-trace-blue/10 text-trace-blue-hover' : 'border-line bg-panel text-primary hover:border-grey-300',
                  ro && 'opacity-70')}>
                {on && <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3"
                  aria-hidden="true"><path d="M20 6 9 17l-5-5" /></svg>}
                {t(`exchangesRefunds.requests.reasons.${r}`)}
              </button>
            )
          })}
        </div>
        <ErrorLine error={errors.reasons} testId="error-reasons" />
      </fieldset>
    </div>
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
