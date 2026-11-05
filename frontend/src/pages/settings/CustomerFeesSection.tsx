import { useEffect, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import {
  CustomerFeeSettings, ExchangePricing, FeeArea, FeeAreaOverride, FeeKind, FeeMode, FeeRule, TransferCommandError,
  addFeeAreaOverride, deleteFeeAreaOverride, getCustomerFees, getFeeAreas, getRoleFromToken, saveExchangePricing,
  saveFeeRule, saveMerchantFaultReasons,
} from '../../api'
import { Button, Input, Toggle, cn, useToast } from '../../components/ui'

/** The portal's return reasons, in the portal's order (PortalService.REASON_CODES). */
export const REASON_CODES = ['wrong_size', 'damaged', 'not_as_pictured', 'wrong_item', 'changed_mind', 'other'] as const

const KINDS: FeeKind[] = ['exchange', 'refund']

/**
 * Returns portal fees F1 — Settings → Returns portal → Exchange pricing and Customer fees. Each block saves on its
 * own (its own endpoint, audit-logged). Owner edits; a manager sees it read-only. Hidden entirely while the backend
 * says {@code visible:false} (until fees are charged) or when it can't be loaded — the rest of the tab is unchanged.
 */
export default function CustomerFeesSection() {
  const { t } = useTranslation()
  const [settings, setSettings] = useState<CustomerFeeSettings | null>(null)
  const owner = getRoleFromToken() === 'owner'

  useEffect(() => {
    let cancelled = false
    getCustomerFees().then(s => { if (!cancelled) setSettings(s) }).catch(() => {})
    return () => { cancelled = true }
  }, [])

  if (!settings?.visible) return null

  return (
    <div className="space-y-4" data-testid="customer-fees-section">
      <section className="card divide-y divide-line max-w-3xl" aria-labelledby="exchange-pricing-title">
        <div className="p-6">
          <h2 id="exchange-pricing-title" className="text-h3 text-primary">{t('settings.portal.fees.pricing.title')}</h2>
          <p className="text-small text-muted mt-1">{t('settings.portal.fees.pricing.description')}</p>
          {!owner && <p className="text-small text-warning-text mt-1.5">{t('settings.portal.fees.ownerOnly')}</p>}
        </div>
        <ExchangePricingEditor value={settings.exchangePricing} owner={owner} onSaved={setSettings} />
      </section>

      <section className="card divide-y divide-line max-w-3xl" aria-labelledby="customer-fees-title">
        <div className="p-6">
          <h2 id="customer-fees-title" className="text-h3 text-primary">{t('settings.portal.fees.title')}</h2>
          <p className="text-small text-muted mt-1">{t('settings.portal.fees.description')}</p>
          {!owner && <p className="text-small text-warning-text mt-1.5">{t('settings.portal.fees.ownerOnly')}</p>}
        </div>
        {KINDS.map(k => (
          <RuleEditor key={k} kind={k} rule={settings.rules[k]} owner={owner} onSaved={setSettings} />
        ))}
        <ReasonsEditor value={settings.merchantFaultReasons} owner={owner} onSaved={setSettings} />
        <AreaOverrides overrides={settings.overrides} owner={owner} onSaved={setSettings} />
      </section>
    </div>
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

/** '' → null; anything else → a number (the backend validates it). */
function num(s: string): number | null {
  const v = s.trim()
  return v === '' ? null : Number(v)
}

function str(n: number | null | undefined) { return n == null ? '' : String(n) }

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
    <div role="radiogroup" className="inline-flex rounded-xl border border-line overflow-hidden">
      {(['fixed', 'percent'] as FeeMode[]).map(m => (
        <label key={m} className={cn('px-3 py-1.5 text-small cursor-pointer select-none',
          value === m ? 'bg-elevated text-primary font-medium' : 'text-muted', disabled && 'cursor-not-allowed opacity-60')}>
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
  if (f.mode === 'fixed') return t('settings.portal.fees.fixedLabel', { amount: f.amount ?? 0 })
  const base = t('settings.portal.fees.percentLabel', { percent: f.percent ?? 0 })
  const parts: string[] = []
  if (f.minAmount != null) parts.push(t('settings.portal.fees.minLabel', { amount: f.minAmount }))
  if (f.maxAmount != null) parts.push(t('settings.portal.fees.maxLabel', { amount: f.maxAmount }))
  return parts.length ? `${base} (${parts.join(', ')})` : base
}

// ── exchange pricing ─────────────────────────────────────────────────────────

function ExchangePricingEditor({ value, owner, onSaved }: {
  value: ExchangePricing; owner: boolean; onSaved: (s: CustomerFeeSettings) => void
}) {
  const { t } = useTranslation()
  const [anyProduct, setAnyProduct] = useState(value.anyProduct)
  const [lowerOnly, setLowerOnly] = useState(value.sameOrLowerOnly)
  const [maxDiff, setMaxDiff] = useState(str(value.maxDifference))
  const { saving, error, run } = useSave(onSaved)
  const dirty = anyProduct !== value.anyProduct || lowerOnly !== value.sameOrLowerOnly || num(maxDiff) !== value.maxDifference
  const ro = !owner || saving

  return (
    <div className="p-6 space-y-4" data-testid="exchange-pricing">
      <div className="flex items-start gap-4">
        <div className="flex-1 min-w-0">
          <p className="text-body font-medium text-primary">{t('settings.portal.fees.pricing.anyProduct')}</p>
          <p className="text-small text-muted mt-0.5">{t('settings.portal.fees.pricing.anyProductHelp')}</p>
        </div>
        <Toggle checked={anyProduct} onChange={setAnyProduct} ariaLabel={t('settings.portal.fees.pricing.anyProduct')} disabled={ro} />
      </div>
      <div className="flex items-start gap-4">
        <div className="flex-1 min-w-0">
          <p className="text-body font-medium text-primary">{t('settings.portal.fees.pricing.lowerOnly')}</p>
          <p className="text-small text-muted mt-0.5">{t('settings.portal.fees.pricing.lowerOnlyHelp')}</p>
        </div>
        <Toggle checked={lowerOnly} onChange={setLowerOnly} ariaLabel={t('settings.portal.fees.pricing.lowerOnly')} disabled={ro} />
      </div>
      {!lowerOnly && (
        <div className="space-y-1">
          <MoneyInput id="exchange-max-difference" label={t('settings.portal.fees.pricing.maxDifference')} value={maxDiff}
            onChange={setMaxDiff} disabled={ro} testId="max-difference" />
          <p className="text-small text-muted">{t('settings.portal.fees.pricing.maxDifferenceHelp')}</p>
        </div>
      )}
      <ErrorLine error={error} testId="error-exchange-pricing" />
      {owner && (
        <div className="flex justify-end">
          <Button size="sm" disabled={!dirty || saving}
            onClick={() => run(() => saveExchangePricing({
              anyProduct, sameOrLowerOnly: lowerOnly, maxDifference: lowerOnly ? null : num(maxDiff),
            }))}>
            {t('settings.portal.fees.save')}
          </Button>
        </div>
      )}
    </div>
  )
}

// ── fee rules ────────────────────────────────────────────────────────────────

function RuleEditor({ kind, rule, owner, onSaved }: {
  kind: FeeKind; rule: FeeRule | null; owner: boolean; onSaved: (s: CustomerFeeSettings) => void
}) {
  const { t } = useTranslation()
  const [enabled, setEnabled] = useState(rule?.enabled ?? false)
  const [mode, setMode] = useState<FeeMode>(rule?.mode ?? 'fixed')
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
  const dirty = !rule
    ? enabled || amount !== '' || percent !== ''
    : input.enabled !== rule.enabled || input.mode !== rule.mode || input.waiveMerchantFault !== rule.waiveMerchantFault
      || input.amount !== rule.amount || input.percent !== rule.percent
      || input.minAmount !== rule.minAmount || input.maxAmount !== rule.maxAmount

  const title = t(`settings.portal.fees.${kind}.title`)
  return (
    <div className="p-6 space-y-4" data-testid={`fee-rule-${kind}`}>
      <div className="flex items-start gap-4">
        <div className="flex-1 min-w-0">
          <p className="text-body font-medium text-primary">{title}</p>
          <p className="text-small text-muted mt-0.5">{t(`settings.portal.fees.${kind}.description`)}</p>
        </div>
        <span className={cn('text-small font-medium mt-0.5', enabled ? 'text-primary' : 'text-muted')} aria-hidden="true">
          {enabled ? t('settings.portal.on') : t('settings.portal.off')}
        </span>
        <Toggle checked={enabled} onChange={setEnabled} ariaLabel={title} disabled={ro} />
      </div>
      <ModeChoice name={`fee-mode-${kind}`} value={mode} onChange={setMode} disabled={ro} />
      {mode === 'fixed' ? (
        <MoneyInput id={`fee-amount-${kind}`} label={t('settings.portal.fees.amount')} value={amount} onChange={setAmount}
          disabled={ro} testId={`fee-amount-${kind}`} />
      ) : (
        <div className="flex flex-wrap gap-4">
          <label htmlFor={`fee-percent-${kind}`} className="flex flex-col gap-1 text-small text-muted">
            {t('settings.portal.fees.percent')}
            <span className="flex items-center gap-2">
              <span className="w-24">
                <Input id={`fee-percent-${kind}`} type="number" min={0} max={100} step={0.01} inputMode="decimal"
                  value={percent} disabled={ro} data-testid={`fee-percent-${kind}`} onChange={e => setPercent(e.target.value)} />
              </span>
              <span className="text-body text-muted">%</span>
            </span>
          </label>
          <MoneyInput id={`fee-min-${kind}`} label={t('settings.portal.fees.min')} value={min} onChange={setMin} disabled={ro} />
          <MoneyInput id={`fee-max-${kind}`} label={t('settings.portal.fees.max')} value={max} onChange={setMax} disabled={ro} />
        </div>
      )}
      <label className="flex items-start gap-2 text-body text-primary">
        <input type="checkbox" className="mt-1" checked={waive} disabled={ro} onChange={e => setWaive(e.target.checked)}
          data-testid={`fee-waive-${kind}`} />
        <span>
          {t('settings.portal.fees.waive')}
          <span className="block text-small text-muted">
            {t(mode === 'fixed' ? 'settings.portal.fees.waiveFixedHelp' : 'settings.portal.fees.waivePercentHelp')}
          </span>
        </span>
      </label>
      <ErrorLine error={error} testId={`error-fee-${kind}`} />
      {owner && (
        <div className="flex justify-end">
          <Button size="sm" disabled={!dirty || saving}
            onClick={() => run(() => saveFeeRule(kind, input))}>
            {t('settings.portal.fees.save')}
          </Button>
        </div>
      )}
    </div>
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
    <fieldset className="p-6 space-y-3" data-testid="merchant-fault-reasons">
      <legend className="sr-only">{t('settings.portal.fees.reasons.title')}</legend>
      <div>
        <p className="text-body font-medium text-primary">{t('settings.portal.fees.reasons.title')}</p>
        <p className="text-small text-muted mt-0.5">{t('settings.portal.fees.reasons.description')}</p>
      </div>
      <div className="grid sm:grid-cols-2 gap-2">
        {REASON_CODES.map(r => (
          <label key={r} className="flex items-center gap-2 text-body text-primary">
            <input type="checkbox" checked={picked.includes(r)} disabled={!owner || saving} data-testid={`reason-${r}`}
              onChange={e => setPicked(p => e.target.checked ? [...p, r] : p.filter(x => x !== r))} />
            {t(`exchangesRefunds.requests.reasons.${r}`)}
          </label>
        ))}
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
    <div className="p-6 space-y-3" data-testid="fee-area-overrides">
      <div>
        <p className="text-body font-medium text-primary">{t('settings.portal.fees.areas.title')}</p>
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
