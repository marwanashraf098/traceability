import { FormEvent, ReactNode, useEffect, useMemo, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { Check, Clock, Globe, Info, LogOut, Store } from 'lucide-react'
import { Logo } from '../../components/Logo'
import { Button, Input, Spinner, cn } from '../../components/ui'
import StoreFinder, { ErrorAlert } from '../settings/connections/StoreFinder'
import { callbackErrorKey } from '../settings/connections/ShopifyConnectionCard'
import {
  getConnections, getRoleFromToken, getSetupAddress, logoutThisDevice, saveSetupAddress,
  ServerMessageError, type SetupAddressView, type SetupStep,
} from '../../api'
import { clearAccessToken } from '../../auth'
import { stationDeviceId } from '../../phone/PhoneScanProvider'
import { refreshSetupStep } from '../../setupGate'

// Onboarding gate (design/Traced_onboarding_exchange_dc.html screens 1–2): a full-screen page shown in
// place of any owner / manager screen while GET /me's setupStep is not null. Outside Layout (no sidebar):
// its own top bar with the language toggle and Log out. Owner: step 1 connects Shopify (the Settings
// StoreFinder), step 2 saves the main warehouse address. Manager: a "waiting for the owner" screen.
// A failed Shopify connect comes back to /settings?tab=connections&shopify_error=… — which is behind this
// gate — so the gate shows that error itself.

function toggleLang(i18n: { language: string; changeLanguage: (lng: string) => void }) {
  const next = i18n.language === 'en' ? 'ar' : 'en'
  i18n.changeLanguage(next)
  try { localStorage.setItem('lang', next) } catch { /* private mode */ }
  document.documentElement.dir  = next === 'ar' ? 'rtl' : 'ltr'
  document.documentElement.lang = next
}

export function GateFrame({ children, onExit, exitLabel }: {
  children: ReactNode
  /** Banner mode: a "Back to Traced" link instead of Log out. */
  onExit?: () => void
  exitLabel?: string
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
      <header className="h-14 border-b border-line flex items-center justify-between px-[18px] max-[640px]:px-4 flex-shrink-0">
        <Logo variant="mark" size={22} className="text-primary" />
        <div className="flex items-center gap-1">
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
      <main className="flex-1 flex justify-center px-4 py-10 max-[640px]:py-6">
        <div className="w-full max-w-[520px] space-y-6">{children}</div>
      </main>
    </div>
  )
}

function Stepper({ step, shopDomain }: { step: 'shopify' | 'address'; shopDomain: string | null }) {
  const { t } = useTranslation()
  const first = step === 'shopify'
  return (
    <ol className="space-y-2" aria-label={t('setupGate.stepsLabel')}>
      <li className={cn('flex items-center gap-3 rounded-xl border px-3.5 py-2.5',
        first ? 'border-trace-blue/40 bg-trace-blue/[0.06]' : 'border-success/25 bg-success/[0.06]')}
        aria-current={first ? 'step' : undefined}>
        <span className={cn('w-6 h-6 rounded-full flex items-center justify-center text-caption font-bold flex-shrink-0',
          first ? 'bg-trace-blue text-white' : 'bg-success text-white')}>
          {first ? '1' : <Check size={14} strokeWidth={3} />}
        </span>
        <span className="flex-1 min-w-0">
          <span className="block text-small font-semibold text-primary">
            {first ? t('setupGate.step1') : t('setupGate.step1Done')}
          </span>
          {!first && shopDomain && (
            <span className="block text-caption text-muted font-mono truncate" dir="ltr">{shopDomain}</span>
          )}
        </span>
      </li>
      <li className={cn('flex items-center gap-3 rounded-xl border px-3.5 py-2.5',
        first ? 'border-line bg-surface' : 'border-trace-blue/40 bg-trace-blue/[0.06]')}
        aria-current={!first ? 'step' : undefined}>
        <span className={cn('w-6 h-6 rounded-full flex items-center justify-center text-caption font-bold flex-shrink-0',
          first ? 'bg-elevated text-muted border border-line' : 'bg-trace-blue text-white')}>2</span>
        <span className={cn('text-small font-semibold', first ? 'text-muted' : 'text-primary')}>{t('setupGate.step2')}</span>
      </li>
    </ol>
  )
}

function ConnectStep() {
  const { t } = useTranslation()
  const [searchParams, setSearchParams] = useSearchParams()
  const [callbackError] = useState<string | null>(() => searchParams.get('shopify_error'))
  const [linkedShop, setLinkedShop] = useState<string | null>(null)

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

  return (
    <div className="card p-5 space-y-4" data-testid="onboarding-step-shopify">
      <div>
        <h1 className="text-h3 font-semibold text-primary">{t('setupGate.connectTitle')}</h1>
        <p className="text-body text-muted mt-1">{t('setupGate.connectBody')}</p>
      </div>
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
    </div>
  )
}

export function AddressStep({ mode, onSaved }: { mode: 'gate' | 'banner'; onSaved: (step: SetupStep) => void }) {
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

  useEffect(() => {
    let cancelled = false
    getSetupAddress()
      .then(v => {
        if (cancelled) return
        setView(v)
        if (v.address) {
          setProvince(v.address.provinceCode)
          setCity(v.address.city)
          setStreet(v.address.street)
          setBuilding(v.address.building ?? '')
          setFloor(v.address.floor ?? '')
        }
      })
      .catch(() => { if (!cancelled) setLoadError(true) })
    return () => { cancelled = true }
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

  const fieldLabel = 'block text-small text-muted font-medium mb-1.5'
  return (
    <form onSubmit={e => void submit(e)} className="card p-5 space-y-4" data-testid="onboarding-step-address" noValidate>
      <div>
        <h1 className="text-h3 font-semibold text-primary">{t('setupGate.address.title')}</h1>
        <p className="text-body text-muted mt-1">{t('setupGate.address.body')}</p>
      </div>

      <div>
        <label htmlFor="setupGovernorate" className={fieldLabel}>{t('setupGate.address.governorate')}</label>
        <select id="setupGovernorate" value={province} className={cn('input w-full', error?.code === 'GOVERNORATE_REQUIRED' && 'border-critical')}
          onChange={e => { setProvince(e.target.value); setCity(''); setError(null) }}>
          <option value="">{t('setupGate.address.governoratePlaceholder')}</option>
          {view.provinces.map(p => (
            <option key={p.code} value={p.code}>{isAr ? p.nameAr : p.nameEn}</option>
          ))}
        </select>
      </div>

      <div>
        <label htmlFor="setupCity" className={fieldLabel}>{t('setupGate.address.city')}</label>
        <Input id="setupCity" value={city} list={areas.length ? 'setupCityAreas' : undefined}
          onChange={e => { setCity(e.target.value); setError(null) }}
          placeholder={t('setupGate.address.cityPlaceholder')} autoComplete="off" maxLength={100}
          disabled={!province} invalid={error?.code === 'CITY_REQUIRED'} />
        {areas.length > 0 && (
          <datalist id="setupCityAreas">
            {areas.map(a => <option key={a.nameEn} value={isAr && a.nameAr ? a.nameAr : a.nameEn} />)}
          </datalist>
        )}
      </div>

      <div>
        <label htmlFor="setupStreet" className={fieldLabel}>{t('setupGate.address.street')}</label>
        <Input id="setupStreet" value={street} onChange={e => { setStreet(e.target.value); setError(null) }}
          placeholder={t('setupGate.address.streetPlaceholder')} autoComplete="street-address" maxLength={200}
          invalid={error?.code === 'STREET_REQUIRED'} />
      </div>

      <div className="grid grid-cols-2 gap-3 max-[420px]:grid-cols-1">
        <div>
          <label htmlFor="setupBuilding" className={fieldLabel}>
            {t('setupGate.address.building')} <span className="font-normal">{t('setupGate.address.optional')}</span>
          </label>
          <Input id="setupBuilding" value={building} onChange={e => setBuilding(e.target.value)} maxLength={50} autoComplete="off" />
        </div>
        <div>
          <label htmlFor="setupFloor" className={fieldLabel}>
            {t('setupGate.address.floor')} <span className="font-normal">{t('setupGate.address.optional')}</span>
          </label>
          <Input id="setupFloor" value={floor} onChange={e => setFloor(e.target.value)} maxLength={50} autoComplete="off" />
        </div>
      </div>

      <p className="flex items-start gap-2 text-small text-muted">
        <Store size={15} className="flex-shrink-0 mt-0.5" />{t('setupGate.address.shopifyNote')}
      </p>

      {error && <ErrorAlert title={error.text} />}

      <Button type="submit" variant="primary" className="w-full" loading={saving}>
        {mode === 'gate' ? t('setupGate.address.finish') : t('setupGate.address.save')}
      </Button>
    </form>
  )
}

function ManagerWaiting({ step }: { step: 'shopify' | 'address' }) {
  const { t } = useTranslation()
  return (
    <div className="card p-6 text-center space-y-3" data-testid="onboarding-manager-waiting">
      <span className="mx-auto w-11 h-11 rounded-full bg-trace-blue/10 text-trace-blue flex items-center justify-center">
        <Clock size={20} strokeWidth={2} />
      </span>
      <h1 className="text-h3 font-semibold text-primary">{t('setupGate.manager.title')}</h1>
      <p className="text-body text-muted">
        {step === 'shopify' ? t('setupGate.manager.bodyShopify') : t('setupGate.manager.bodyAddress')}
      </p>
      <Button variant="secondary" onClick={() => window.location.reload()}>{t('setupGate.manager.check')}</Button>
    </div>
  )
}

/** The gate itself, for a tenant whose setupStep is not null. */
export default function OnboardingGate({ step, onDone }: { step: 'shopify' | 'address'; onDone: () => void }) {
  const role = getRoleFromToken()
  const [shopDomain, setShopDomain] = useState<string | null>(null)

  useEffect(() => {
    if (step !== 'address' || role !== 'owner') return
    let cancelled = false
    getConnections().then(c => { if (!cancelled) setShopDomain(c.shopify.shopDomain) }).catch(() => {})
    return () => { cancelled = true }
  }, [step, role])

  if (role !== 'owner') {
    return <GateFrame><ManagerWaiting step={step} /></GateFrame>
  }
  return (
    <GateFrame>
      <Stepper step={step} shopDomain={shopDomain} />
      {step === 'shopify'
        ? <ConnectStep />
        : <AddressStep mode="gate" onSaved={next => { if (next === null) onDone() }} />}
    </GateFrame>
  )
}
