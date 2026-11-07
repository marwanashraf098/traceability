import { FormEvent, useEffect, useMemo, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { Check, Clock, Info, Store } from 'lucide-react'
import { Button, Input, Spinner, cn } from '../../components/ui'
import StoreFinder, { ErrorAlert } from '../settings/connections/StoreFinder'
import { callbackErrorKey } from '../settings/connections/ShopifyConnectionCard'
import {
  getConnections, getRoleFromToken, getSetupAddress, getSetupShopifyLocations, saveSetupAddress,
  ServerMessageError, type ConnectionsStatus, type SetupAddressView, type SetupGovernorate, type SetupStep,
  type ShopifyLocationOption,
} from '../../api'
import { anyFilled, prefillFrom, withTimeout } from './addressPrefill'
import { clearShopifyStepSeen, markShopifyStepSeen, refreshSetupStep, shopifyStepSeen } from '../../setupGate'
import { GateFrame, SetupCard, SetupColumns, SetupHeading, SetupStepper, SidePanel } from './SetupLayout'

export { GateFrame }

// Onboarding gate (design/onboarding/Traced_onboarding_Flow-1-Shopify, Flow-2-Address): a full-screen page
// shown in place of any owner / manager screen while GET /me's setupStep is not null. Outside Layout (no
// sidebar): SetupLayout's frame with the stepper, "Need help?", the language toggle and Log out.
// Owner: step 1 connects Shopify (the Settings StoreFinder), step 2 saves the main warehouse address.
// Manager: a "waiting for the owner" screen. A failed Shopify connect comes back to
// /settings?tab=connections&shopify_error=… — which is behind this gate — so the gate shows that error itself.
// Restyle only: who is gated, what is required and what is saved are unchanged.

/** O2b: how long the address step waits for the Shopify locations before showing the empty form. */
export const PREFILL_TIMEOUT_MS = 3000

function firstName(name: string | null | undefined) {
  return name?.trim().split(/\s+/)[0] || null
}

function ConnectStep({ name }: { name: string | null }) {
  const { t } = useTranslation()
  const [searchParams, setSearchParams] = useSearchParams()
  const [callbackError] = useState<string | null>(() => searchParams.get('shopify_error'))
  const [linkedShop, setLinkedShop] = useState<string | null>(null)

  // This device saw the Shopify step: once the store is connected, the gate shows "Connected" first (Flow-1).
  useEffect(() => { markShopifyStepSeen() }, [])

  // Shown once, then stripped from the URL (replace — Back / refresh don't re-show it).
  useEffect(() => {
    if (!searchParams.has('shopify_error')) return
    setSearchParams(prev => { const n = new URLSearchParams(prev); n.delete('shopify_error'); return n }, { replace: true })
  }, [searchParams, setSearchParams])

  // Shop binding rule: a tenant that was connected before can only reconnect that same shop.
  useEffect(() => {
    let cancelled = false
    getConnections()
      .then(c => {
        if (cancelled) return
        if (!c.shopify.connected && c.shopify.status === 'disconnected' && c.shopify.shopDomain) setLinkedShop(c.shopify.shopDomain)
      })
      .catch(() => { /* the finder still works */ })
    return () => { cancelled = true }
  }, [])

  const who = firstName(name)
  return (
    <SetupColumns aside={
      <SidePanel testId="setup-shopify-panel" title={t('setupGate.shopifyPanel.title')}
        lines={[t('setupGate.shopifyPanel.reads'), t('setupGate.shopifyPanel.stock'), t('setupGate.shopifyPanel.never')]} />
    }>
      <SetupCard data-testid="onboarding-step-shopify">
        <SetupHeading eyebrow={t('setupGate.eyebrow1')}
          title={who ? t('setupGate.connectTitleNamed', { name: who }) : t('setupGate.connectTitle')}
          lead={t('setupGate.connectBody')} />
        {callbackError !== null && (
          <ErrorAlert title={t(callbackErrorKey(callbackError))} />
        )}
        {linkedShop && (
          <p className="flex items-start gap-2 text-small text-muted" data-testid="onboarding-linked-shop">
            <Info size={15} className="flex-shrink-0 mt-0.5" />
            <span>{t('connections.shopify.linkedShop', { shop: linkedShop })}</span>
          </p>
        )}
        <StoreFinder initialInput={linkedShop ?? undefined} hideIntro />
        <p className="text-small text-muted border-t border-line pt-3">{t('setupGate.connectFooter')}</p>
      </SetupCard>
    </SetupColumns>
  )
}

/** Flow-1 after the store is connected — read-only, from /connections (no Shopify call). */
function ConnectedSummary({ name, onContinue }: { name: string | null; onContinue: () => void }) {
  const { t } = useTranslation()
  const [conn, setConn] = useState<ConnectionsStatus['shopify'] | null>(null)

  useEffect(() => {
    let cancelled = false
    getConnections().then(c => { if (!cancelled) setConn(c.shopify) }).catch(() => {})
    return () => { cancelled = true }
  }, [])

  const imported = conn?.importStatus === 'completed'
  const location = conn?.locationState ?? null
  const rows: { label: string; value: string | null; testId: string }[] = [
    { label: t('setupGate.connected.products'), testId: 'setup-row-products',
      value: conn ? (imported ? t('setupGate.connected.imported') : t('setupGate.connected.importing')) : null },
    // No count without a Shopify call (O2): the line alone.
    { label: t('setupGate.connected.locations'), value: null, testId: 'setup-row-locations' },
    { label: t('setupGate.connected.tracedMain'), testId: 'setup-row-traced-main',
      value: location === 'active' ? t('setupGate.connected.ready')
        : location === null ? (conn ? t('setupGate.connected.settingUp') : null) : t('setupGate.connected.notActive') },
  ]

  return (
    <SetupColumns aside={
      <SidePanel title={t('setupGate.shopifyPanel.title')}
        lines={[t('setupGate.shopifyPanel.reads'), t('setupGate.shopifyPanel.stock'), t('setupGate.shopifyPanel.never')]} />
    }>
      <SetupCard data-testid="onboarding-shopify-connected">
        <SetupHeading eyebrow={t('setupGate.eyebrow1')}
          title={firstName(name) ? t('setupGate.connectTitleNamed', { name: firstName(name) }) : t('setupGate.connectTitle')} />
        <div className="flex items-center gap-3 bg-success/[0.07] rounded-xl px-4 py-3.5">
          <span className="w-7 h-7 rounded-full bg-success-text text-white flex items-center justify-center flex-shrink-0">
            <Check size={15} strokeWidth={3} aria-hidden />
          </span>
          <span className="flex flex-col gap-0.5 min-w-0">
            <span className="text-[15px] font-semibold text-primary">
              {t('setupGate.connected.to')}{' '}
              {conn?.shopDomain && <span className="font-mono break-all" dir="ltr">{conn.shopDomain}</span>}
            </span>
            <span className="text-body text-primary/80">
              {imported ? t('setupGate.connected.subDone') : t('setupGate.connected.sub')}
            </span>
          </span>
        </div>
        <ul className="border border-line rounded-xl divide-y divide-line">
          {rows.map(r => (
            <li key={r.testId} data-testid={r.testId} className="flex justify-between gap-4 px-4 py-3 text-body">
              <span className="text-primary">{r.label}</span>
              {r.value && <b className="font-semibold text-primary text-end">{r.value}</b>}
            </li>
          ))}
        </ul>
        <Button variant="primary" size="lg" className="w-full" onClick={onContinue}>{t('setupGate.connected.continue')}</Button>
      </SetupCard>
    </SetupColumns>
  )
}

export function AddressStep({ mode, onSaved, shopDomain }: {
  mode: 'gate' | 'banner'
  onSaved: (step: SetupStep) => void
  /** Gate only: the connected store, shown as done. */
  shopDomain?: string | null
}) {
  const { t, i18n } = useTranslation()
  const isAr = i18n.language === 'ar'
  const [view, setView] = useState<SetupAddressView | null>(null)
  const [loadError, setLoadError] = useState(false)
  const [province, setProvince] = useState('')
  const [city, setCity] = useState('')
  const [street, setStreet] = useState('')
  const [building, setBuilding] = useState('')
  const [floor, setFloor] = useState('')
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<{ code?: string; text: string } | null>(null)
  // O2b — pre-filled from the store's own Shopify locations (gate only, only while no address is saved). Nothing is
  // saved until "Save and continue"; every field stays editable.
  const [options, setOptions] = useState<ShopifyLocationOption[]>([])
  const [sourceId, setSourceId] = useState('')
  const [filledFrom, setFilledFrom] = useState<string | null>(null)

  function fillFrom(loc: ShopifyLocationOption, provinces: SetupGovernorate[]) {
    const a = prefillFrom(loc, provinces, i18n.language)
    setProvince(a.province); setCity(a.city); setStreet(a.street); setBuilding(a.building)
    setSourceId(loc.id)
    setFilledFrom(anyFilled(a) ? (loc.name ?? '') : null)
    setError(null)
  }

  useEffect(() => {
    let cancelled = false
    getSetupAddress()
      .then(async v => {
        if (cancelled) return
        if (v.address) {
          setProvince(v.address.provinceCode)
          setCity(v.address.city)
          setStreet(v.address.street)
          setBuilding(v.address.building ?? '')
          setFloor(v.address.floor ?? '')
        } else if (mode === 'gate') {
          // A failed, empty or slow (> PREFILL_TIMEOUT_MS) read shows today's empty form — silently.
          const locs = await withTimeout(
            getSetupShopifyLocations().then(r => (Array.isArray(r?.locations) ? r.locations : [])),
            PREFILL_TIMEOUT_MS, [] as ShopifyLocationOption[])
          if (cancelled) return
          if (locs.length > 0) { setOptions(locs); fillFrom(locs[0], v.provinces) }
        }
        setView(v)
      })
      .catch(() => { if (!cancelled) setLoadError(true) })
    return () => { cancelled = true }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const areas = useMemo(() => view?.provinces.find(p => p.code === province)?.areas ?? [], [view, province])

  async function submit(e: FormEvent) {
    e.preventDefault()
    setError(null)
    if (!province) { setError({ code: 'GOVERNORATE_REQUIRED', text: t('setupGate.address.errGovernorate') }); return }
    if (!city.trim()) { setError({ code: 'CITY_REQUIRED', text: t('setupGate.address.errCity') }); return }
    if (street.trim().length < 3) { setError({ code: 'STREET_REQUIRED', text: t('setupGate.address.errStreet') }); return }
    setSaving(true)
    try {
      const res = await saveSetupAddress({ provinceCode: province, city, street, building: building || null, floor: floor || null })
      refreshSetupStep()
      onSaved(res.setupStep)
    } catch (err) {
      setError(err instanceof ServerMessageError
        ? { code: err.code, text: err.messageFor(i18n.language) }
        : { text: t('setupGate.address.errSave') })
      setSaving(false)
    }
  }

  if (loadError) return <ErrorAlert title={t('setupGate.address.errLoad')} />
  if (!view) return <div className="flex justify-center py-10"><Spinner /></div>

  const gate = mode === 'gate'
  const fieldLabel = 'block text-body font-semibold text-primary mb-1.5'
  const form = (
    <form onSubmit={e => void submit(e)} noValidate data-testid="onboarding-step-address"
      className="bg-surface border border-line rounded-[18px] p-8 max-[640px]:p-5 flex flex-col gap-5">
      <SetupHeading eyebrow={gate ? t('setupGate.eyebrow2') : undefined}
        title={t('setupGate.address.title')} lead={t('setupGate.address.body')} />

      {gate && shopDomain && (
        <p className="flex items-center gap-2 text-small text-muted" data-testid="setup-shop-done">
          <Check size={14} strokeWidth={3} className="text-success-text flex-shrink-0" aria-hidden />
          <span>{t('setupGate.step1Done')}</span>
          <span className="font-mono truncate" dir="ltr">{shopDomain}</span>
        </p>
      )}

      {filledFrom !== null && (
        <p className="flex items-start gap-2 text-small text-primary bg-trace-blue/[0.06] rounded-lg px-3 py-2.5" data-testid="setup-address-prefilled">
          <Store size={15} className="flex-shrink-0 mt-0.5 text-trace-blue" aria-hidden />
          <span>{filledFrom ? t('setupGate.address.prefilled', { name: filledFrom }) : t('setupGate.address.prefilledUnnamed')}</span>
        </p>
      )}
      {options.length > 1 && (
        <label className="flex flex-wrap items-center gap-2 text-small text-muted" data-testid="setup-address-fill-from">
          {t('setupGate.address.fillFrom')}
          <select className="input h-9 py-0 w-auto max-w-full" value={sourceId}
            onChange={e => { const loc = options.find(o => o.id === e.target.value); if (loc) fillFrom(loc, view.provinces) }}>
            {options.map(o => (
              <option key={o.id} value={o.id}>{o.name ?? o.address1 ?? o.id}</option>
            ))}
          </select>
        </label>
      )}

      <div className="grid grid-cols-2 gap-3.5 max-[520px]:grid-cols-1">
        <div>
          <label htmlFor="setupGovernorate" className={fieldLabel}>{t('setupGate.address.governorate')}</label>
          <select id="setupGovernorate" value={province}
            className={cn('input w-full h-[46px]', error?.code === 'GOVERNORATE_REQUIRED' && 'border-critical')}
            onChange={e => { setProvince(e.target.value); setCity(''); setError(null) }}>
            <option value="">{t('setupGate.address.governoratePlaceholder')}</option>
            {view.provinces.map(p => (
              <option key={p.code} value={p.code}>{isAr ? p.nameAr : p.nameEn}</option>
            ))}
          </select>
        </div>

        <div>
          <label htmlFor="setupCity" className={fieldLabel}>{t('setupGate.address.city')}</label>
          <Input id="setupCity" className="h-[46px]" value={city} list={areas.length ? 'setupCityAreas' : undefined}
            onChange={e => { setCity(e.target.value); setError(null) }}
            placeholder={t('setupGate.address.cityPlaceholder')} autoComplete="off" maxLength={100}
            disabled={!province} invalid={error?.code === 'CITY_REQUIRED'} />
          {areas.length > 0 && (
            <datalist id="setupCityAreas">
              {areas.map(a => <option key={a.nameEn} value={isAr && a.nameAr ? a.nameAr : a.nameEn} />)}
            </datalist>
          )}
        </div>

        <div className="col-span-2 max-[520px]:col-span-1">
          <label htmlFor="setupStreet" className={fieldLabel}>{t('setupGate.address.street')}</label>
          <Input id="setupStreet" className="h-[46px]" value={street} onChange={e => { setStreet(e.target.value); setError(null) }}
            placeholder={t('setupGate.address.streetPlaceholder')} autoComplete="street-address" maxLength={200}
            invalid={error?.code === 'STREET_REQUIRED'} />
        </div>

        <div>
          <label htmlFor="setupBuilding" className={fieldLabel}>
            {t('setupGate.address.building')} <span className="font-normal text-muted">{t('setupGate.address.optional')}</span>
          </label>
          <Input id="setupBuilding" className="h-[46px]" value={building} onChange={e => setBuilding(e.target.value)} maxLength={50} autoComplete="off" />
        </div>
        <div>
          <label htmlFor="setupFloor" className={fieldLabel}>
            {t('setupGate.address.floor')} <span className="font-normal text-muted">{t('setupGate.address.optional')}</span>
          </label>
          <Input id="setupFloor" className="h-[46px]" value={floor} onChange={e => setFloor(e.target.value)} maxLength={50} autoComplete="off" />
        </div>
      </div>

      {!gate && (
        <p className="flex items-start gap-2 text-small text-muted">
          <Store size={15} className="flex-shrink-0 mt-0.5" />{t('setupGate.address.shopifyNote')}
        </p>
      )}

      {error && <ErrorAlert title={error.text} />}

      <div className={cn('flex pt-1.5', gate ? 'justify-end' : '')}>
        <Button type="submit" variant="primary" size="lg" className={gate ? 'px-7 max-[520px]:w-full' : 'w-full'} loading={saving}>
          {gate ? t('setupGate.address.finish') : t('setupGate.address.save')}
        </Button>
      </div>
    </form>
  )

  if (!gate) return form
  return (
    <SetupColumns aside={<>
      <SidePanel testId="setup-address-panel" title={t('setupGate.addressPanel.title')} lines={[t('setupGate.addressPanel.why')]} />
      <SidePanel title={t('setupGate.addressPanel.moreTitle')} lines={[t('setupGate.addressPanel.more')]} />
    </>}>
      {form}
    </SetupColumns>
  )
}

function ManagerWaiting({ step }: { step: 'shopify' | 'address' }) {
  const { t } = useTranslation()
  return (
    <SetupCard className="items-center text-center gap-3" data-testid="onboarding-manager-waiting">
      <span className="w-11 h-11 rounded-full bg-trace-blue/10 text-trace-blue flex items-center justify-center">
        <Clock size={20} strokeWidth={2} />
      </span>
      <h1 className="text-h3 font-semibold text-primary">{t('setupGate.manager.title')}</h1>
      <p className="text-body text-muted">
        {step === 'shopify' ? t('setupGate.manager.bodyShopify') : t('setupGate.manager.bodyAddress')}
      </p>
      <Button variant="secondary" onClick={() => window.location.reload()}>{t('setupGate.manager.check')}</Button>
    </SetupCard>
  )
}

/** The gate itself, for a tenant whose setupStep is not null. */
export default function OnboardingGate({ step, onDone, name = null }: {
  step: 'shopify' | 'address'
  onDone: () => void
  name?: string | null
}) {
  const role = getRoleFromToken()
  const [shopDomain, setShopDomain] = useState<string | null>(null)
  // Flow-1's "Connected" view — only on the device that showed the Shopify step (an App Store signup,
  // which arrives connected, starts at the address).
  const [showConnected, setShowConnected] = useState(() => step === 'address' && shopifyStepSeen())

  useEffect(() => {
    if (step !== 'address' || role !== 'owner') return
    let cancelled = false
    getConnections().then(c => { if (!cancelled) setShopDomain(c.shopify.shopDomain) }).catch(() => {})
    return () => { cancelled = true }
  }, [step, role])

  if (role !== 'owner') {
    return <GateFrame><ManagerWaiting step={step} /></GateFrame>
  }
  if (step === 'shopify') {
    return <GateFrame width="wide" stepper={<SetupStepper current={0} />}><ConnectStep name={name} /></GateFrame>
  }
  if (showConnected) {
    return (
      <GateFrame width="wide" stepper={<SetupStepper current={1} />}>
        <ConnectedSummary name={name} onContinue={() => { clearShopifyStepSeen(); setShowConnected(false) }} />
      </GateFrame>
    )
  }
  return (
    <GateFrame width="wide" stepper={<SetupStepper current={1} />}>
      <AddressStep mode="gate" shopDomain={shopDomain} onSaved={next => { if (next === null) onDone() }} />
    </GateFrame>
  )
}
