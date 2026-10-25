import { test, expect, describe, vi, beforeAll, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, cleanup, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Routes, Route, useLocation } from 'react-router-dom'
import { I18nextProvider, initReactI18next } from 'react-i18next'
import i18next from 'i18next'
import { AppProvider as PolarisProvider } from '@shopify/polaris'
import polarisEn from '@shopify/polaris/locales/en.json'
import en from '../locales/en.json'
import ar from '../locales/ar.json'
import { setAccessToken, clearAccessToken } from '../auth'
import { OwnerOnlyRoute, StrictOwnerRoute } from '../App'
import { SetupGate, OnboardingRoute } from '../components/SetupGate'
import { StationProvider } from '../components/StationProvider'
import { ToastProvider } from '../components/ui'
import WarehouseAddressBanner from '../components/WarehouseAddressBanner'
import { refreshSetupStep } from '../setupGate'
import { FinishSetupCard } from '../embedded/EmbeddedApp'
import type { Me } from '../api'

/**
 * Onboarding gate (V185) — frontend.
 *   og1  owner, setupStep 'shopify' → the full-screen gate (step 1, the store finder, its own Log out), never the screen
 *   og2  a failed connect lands on /settings?tab=connections&shopify_error=… (behind the gate) → the gate shows it
 *   og3  a previously connected tenant → the linked shop is named and prefilled
 *   og4  owner, setupStep 'address' → step 2; client checks; the save sends the address and opens the screen
 *   og5  a server refusal is shown in the page's language
 *   og6  manager → "waiting for the owner" (no finder, no form); StrictOwnerRoute too
 *   og7  not gated: setupStep null, a worker, station mode, a failed /me
 *   og8  AR / RTL: step 2 with Arabic governorates
 *   og9  /onboarding: grandfathered owner → the address form alone, Back to Traced; anyone else → Overview
 *   og10 the soft banner: owner + warehouseAddressNeeded only
 *   og11 embedded "Finish setup in Traced" card links to /onboarding (EN + AR)
 */

function i18nFor(lng: 'en' | 'ar') {
  const inst = i18next.createInstance()
  inst.use(initReactI18next).init({
    lng, fallbackLng: 'en', initImmediate: false,
    resources: { en: { translation: en }, ar: { translation: ar } }, interpolation: { escapeValue: false },
  })
  return inst
}

function token(role: string) {
  return 'h.' + btoa(JSON.stringify({ role, tenant: 't', exp: Math.floor(Date.now() / 1000) + 3600 })) + '.s'
}

function json(status: number, body: unknown) {
  return Promise.resolve({
    ok: status >= 200 && status < 300, status, statusText: '',
    headers: { get: (k: string) => (k.toLowerCase() === 'content-type' ? 'application/json' : null) },
    json: async () => structuredClone(body),
  })
}

const PROVINCES = [
  { code: 'C',  nameEn: 'Cairo', nameAr: 'القاهرة', areas: [{ nameEn: 'Nasr City', nameAr: 'مدينة نصر' }] },
  { code: 'GZ', nameEn: 'Giza',  nameAr: 'الجيزة',  areas: [] },
]

const DISCONNECTED_CONNECTIONS = {
  shopify: { connected: false, storeId: null, shopDomain: null, connectionType: null, status: 'disconnected', importStatus: null, lastSyncAt: null },
  bosta: { connected: false, businessName: null, pickupMode: null, awbFormat: null, awbLang: null },
  customAppAvailable: false, oauthAvailable: true,
  shopifySetup: { appUrl: '', redirectUrl: '', webhookApiVersion: '', scopes: [] },
}

type Calls = { url: string; init?: RequestInit }[]

function stubApi(opts: {
  me?: unknown
  meStatus?: number
  connections?: unknown
  address?: unknown
  saveStatus?: number
  saveBody?: unknown
}): Calls {
  const calls: Calls = []
  vi.stubGlobal('fetch', vi.fn((url: string, init?: RequestInit) => {
    calls.push({ url, init })
    if (url.endsWith('/me')) return json(opts.meStatus ?? 200, opts.me ?? {})
    if (url.includes('/connections')) return json(200, opts.connections ?? DISCONNECTED_CONNECTIONS)
    if (url.includes('/setup/address')) {
      if (init?.method === 'PUT') return json(opts.saveStatus ?? 200, opts.saveBody ?? { setupStep: null })
      return json(200, opts.address ?? { address: null, provinces: PROVINCES })
    }
    return json(200, {})
  }))
  return calls
}

function Where() {
  const l = useLocation()
  return <div data-testid="where">{l.pathname + l.search}</div>
}

function renderAt(path: string, lng: 'en' | 'ar' = 'en') {
  return render(
    <StationProvider>
      <MemoryRouter initialEntries={[path]}>
        <I18nextProvider i18n={i18nFor(lng)}>
          <ToastProvider>
            <Routes>
              <Route path="/overview" element={<OwnerOnlyRoute><div data-testid="screen">overview</div><Where /></OwnerOnlyRoute>} />
              <Route path="/settings" element={<OwnerOnlyRoute><div data-testid="screen">settings</div></OwnerOnlyRoute>} />
              <Route path="/analytics" element={<StrictOwnerRoute><div data-testid="screen">analytics</div></StrictOwnerRoute>} />
              <Route path="/fulfill" element={<SetupGate><div data-testid="screen">fulfill</div></SetupGate>} />
              <Route path="/onboarding" element={<OwnerOnlyRoute><OnboardingRoute /></OwnerOnlyRoute>} />
              <Route path="/worker-home" element={<div data-testid="worker-home" />} />
            </Routes>
          </ToastProvider>
        </I18nextProvider>
      </MemoryRouter>
    </StationProvider>,
  )
}

beforeAll(() => {
  Object.defineProperty(window, 'matchMedia', {
    writable: true,
    value: (query: string) => ({ matches: false, media: query, onchange: null, addListener: () => {},
      removeListener: () => {}, addEventListener: () => {}, removeEventListener: () => {}, dispatchEvent: () => false }),
  })
})

beforeEach(() => {
  refreshSetupStep()
  localStorage.removeItem('stationMode')
})

afterEach(() => {
  cleanup()
  clearAccessToken()
  vi.unstubAllGlobals()
  document.documentElement.dir = 'ltr'
})

describe('owner gate', () => {
  test('og1 setupStep shopify → step 1 with the store finder and Log out, not the screen', async () => {
    setAccessToken(token('owner'))
    stubApi({ me: { role: 'owner', setupStep: 'shopify' } })
    renderAt('/overview')
    expect(await screen.findByTestId('onboarding-step-shopify')).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Connect your Shopify store')
    expect(screen.getByTestId('store-finder')).toBeInTheDocument()
    expect(screen.getByTestId('onboarding-logout')).toBeInTheDocument()
    expect(screen.getByText('Main warehouse address')).toBeInTheDocument()
    expect(screen.queryByTestId('screen')).toBeNull()
  })

  test('og2 the OAuth failure redirect target shows its error inside the gate', async () => {
    setAccessToken(token('owner'))
    stubApi({ me: { role: 'owner', setupStep: 'shopify' } })
    renderAt('/settings?tab=connections&shopify_error=SHOP_MISMATCH')
    await screen.findByTestId('onboarding-step-shopify')
    expect(screen.getByRole('alert')).toHaveTextContent('This Traced account is already connected to a different Shopify store.')
    expect(screen.queryByTestId('screen')).toBeNull()
  })

  test('og2b an unknown error code reads as the generic message', async () => {
    setAccessToken(token('owner'))
    stubApi({ me: { role: 'owner', setupStep: 'shopify' } })
    renderAt('/settings?tab=connections&shopify_error=<script>')
    await screen.findByTestId('onboarding-step-shopify')
    expect(screen.getByRole('alert')).toHaveTextContent("Couldn't connect your Shopify store. Please try again.")
  })

  test('og3 a previously connected tenant sees its linked shop, prefilled', async () => {
    setAccessToken(token('owner'))
    stubApi({
      me: { role: 'owner', setupStep: 'shopify' },
      connections: { ...DISCONNECTED_CONNECTIONS, shopify: { ...DISCONNECTED_CONNECTIONS.shopify, shopDomain: 'old-shop.myshopify.com' } },
    })
    renderAt('/overview')
    expect(await screen.findByTestId('onboarding-linked-shop')).toHaveTextContent('old-shop.myshopify.com')
    await waitFor(() => expect((screen.getByLabelText(/store address/i) as HTMLInputElement).value).toBe('old-shop.myshopify.com'))
  })

  test('og4 setupStep address → step 2; client checks, then the save opens the screen', async () => {
    setAccessToken(token('owner'))
    const calls = stubApi({
      me: { role: 'owner', setupStep: 'address' },
      connections: { ...DISCONNECTED_CONNECTIONS, shopify: { ...DISCONNECTED_CONNECTIONS.shopify, connected: true, status: 'connected', shopDomain: 'brand-eg.myshopify.com' } },
    })
    renderAt('/overview')
    expect(await screen.findByTestId('onboarding-step-address')).toBeInTheDocument()
    expect(await screen.findByText('brand-eg.myshopify.com')).toBeInTheDocument()
    expect(screen.getByText('Shopify connected')).toBeInTheDocument()

    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: 'Finish setup' }))
    expect(screen.getByRole('alert')).toHaveTextContent('Choose a governorate.')
    expect(calls.some(c => c.init?.method === 'PUT')).toBe(false)

    await user.selectOptions(screen.getByLabelText('Governorate'), 'C')
    await user.type(screen.getByLabelText('City / area'), 'Nasr City')
    await user.click(screen.getByRole('button', { name: 'Finish setup' }))
    expect(screen.getByRole('alert')).toHaveTextContent('Enter the street address.')

    await user.type(screen.getByLabelText('Street address'), '12 Abbas El Akkad St')
    await user.type(screen.getByLabelText(/^Floor/), '2')
    await user.click(screen.getByRole('button', { name: 'Finish setup' }))

    expect(await screen.findByTestId('screen')).toHaveTextContent('overview')
    const put = calls.find(c => c.init?.method === 'PUT')!
    expect(put.url).toContain('/setup/address')
    expect(JSON.parse(put.init!.body as string)).toEqual({
      provinceCode: 'C', city: 'Nasr City', street: '12 Abbas El Akkad St', building: null, floor: '2',
    })
  })

  test('og5 a server refusal shows its message in the page language', async () => {
    setAccessToken(token('owner'))
    stubApi({
      me: { role: 'owner', setupStep: 'address' },
      saveStatus: 400,
      saveBody: { code: 'STREET_REQUIRED', message_en: 'Enter the street address.', message_ar: 'أدخل عنوان الشارع.' },
      address: { address: { provinceCode: 'GZ', city: 'Dokki', street: 'Tahrir', building: null, floor: null }, provinces: PROVINCES },
    })
    renderAt('/overview', 'ar')
    const form = await screen.findByTestId('onboarding-step-address')
    await waitFor(() => expect((within(form).getByLabelText('المحافظة') as HTMLSelectElement).value).toBe('GZ'))
    await userEvent.setup().click(within(form).getByRole('button', { name: 'إنهاء الإعداد' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('أدخل عنوان الشارع.')
    expect(screen.queryByTestId('screen')).toBeNull()
  })
})

describe('managers, workers and the not-gated', () => {
  test('og6 a manager waits for the owner — no finder, no form', async () => {
    setAccessToken(token('manager'))
    stubApi({ me: { role: 'manager', setupStep: 'shopify' } })
    renderAt('/overview')
    expect(await screen.findByTestId('onboarding-manager-waiting')).toHaveTextContent('Waiting for the owner to finish setup')
    expect(screen.queryByTestId('store-finder')).toBeNull()
    expect(screen.getByTestId('onboarding-logout')).toBeInTheDocument()
    cleanup()
    refreshSetupStep()
    stubApi({ me: { role: 'manager', setupStep: 'address' } })
    renderAt('/fulfill')
    expect(await screen.findByTestId('onboarding-manager-waiting')).toHaveTextContent('add the main warehouse address')
    expect(screen.queryByTestId('onboarding-step-address')).toBeNull()
  })

  test('og6b StrictOwnerRoute is gated too', async () => {
    setAccessToken(token('owner'))
    stubApi({ me: { role: 'owner', setupStep: 'shopify' } })
    renderAt('/analytics')
    expect(await screen.findByTestId('onboarding-step-shopify')).toBeInTheDocument()
  })

  test('og7 setupStep null → the screen', async () => {
    setAccessToken(token('owner'))
    stubApi({ me: { role: 'owner', setupStep: null } })
    renderAt('/overview')
    expect(await screen.findByTestId('screen')).toHaveTextContent('overview')
    expect(screen.queryByTestId('onboarding-gate')).toBeNull()
  })

  test('og7b a worker is never gated (no /me asked) and still redirected from owner screens', async () => {
    setAccessToken(token('worker'))
    const calls = stubApi({ me: { role: 'worker', setupStep: 'shopify' } })
    renderAt('/fulfill')
    expect(screen.getByTestId('screen')).toHaveTextContent('fulfill')
    cleanup()
    renderAt('/overview')
    expect(screen.getByTestId('worker-home')).toBeInTheDocument()
    expect(calls.filter(c => c.url.endsWith('/me'))).toHaveLength(0)
  })

  test('og7c station mode is never gated', async () => {
    localStorage.setItem('stationMode', 'true')
    setAccessToken(token('owner'))
    stubApi({ me: { role: 'owner', setupStep: 'shopify' } })
    renderAt('/fulfill')
    expect(screen.getByTestId('screen')).toHaveTextContent('fulfill')
    localStorage.removeItem('stationMode')
  })

  test('og7d a failed /me never locks anyone out', async () => {
    setAccessToken(token('owner'))
    stubApi({ meStatus: 500 })
    renderAt('/overview')
    expect(await screen.findByTestId('screen')).toHaveTextContent('overview')
  })
})

describe('AR / RTL', () => {
  test('og8 step 2 in Arabic: Arabic governorates and labels', async () => {
    setAccessToken(token('owner'))
    stubApi({ me: { role: 'owner', setupStep: 'address' } })
    renderAt('/overview', 'ar')
    const form = await screen.findByTestId('onboarding-step-address')
    expect(within(form).getByText('أين مخزنك الرئيسي؟')).toBeInTheDocument()
    const select = within(form).getByLabelText('المحافظة') as HTMLSelectElement
    expect(Array.from(select.options).map(o => o.textContent)).toEqual(['اختر المحافظة', 'القاهرة', 'الجيزة'])
    expect(screen.getByText('تسجيل الخروج')).toBeInTheDocument()
  })
})

describe('/onboarding and the soft banner', () => {
  test('og9 grandfathered owner → the address form alone, with Back to Traced', async () => {
    setAccessToken(token('owner'))
    stubApi({ me: { role: 'owner', setupStep: null, warehouseAddressNeeded: true } })
    renderAt('/onboarding')
    const form = await screen.findByTestId('onboarding-step-address')
    expect(within(form).getByRole('button', { name: 'Save address' })).toBeInTheDocument()
    expect(screen.queryByTestId('onboarding-logout')).toBeNull()
    await userEvent.setup().click(screen.getByRole('button', { name: 'Back to Traced' }))
    expect(await screen.findByTestId('screen')).toHaveTextContent('overview')
  })

  test('og9b nothing to set up → /onboarding goes to Overview', async () => {
    setAccessToken(token('owner'))
    stubApi({ me: { role: 'owner', setupStep: null, warehouseAddressNeeded: false } })
    renderAt('/onboarding')
    expect(await screen.findByTestId('where')).toHaveTextContent('/overview')
  })

  test('og9c a manager at /onboarding with only the banner case → Overview', async () => {
    setAccessToken(token('manager'))
    stubApi({ me: { role: 'manager', setupStep: null, warehouseAddressNeeded: true } })
    renderAt('/onboarding')
    expect(await screen.findByTestId('where')).toHaveTextContent('/overview')
  })

  test('og10 the banner: owner with warehouseAddressNeeded only', () => {
    const me = (role: Me['role'], needed: boolean) => ({ name: 'A', email: null, role, warehouseAddressNeeded: needed }) as Me
    const r = (m: Me | null) => render(
      <MemoryRouter><I18nextProvider i18n={i18nFor('en')}><WarehouseAddressBanner me={m} /></I18nextProvider></MemoryRouter>)
    r(me('owner', true))
    expect(screen.getByTestId('warehouse-address-banner')).toHaveTextContent('Add your warehouse address')
    cleanup()
    r(me('manager', true)); expect(screen.queryByTestId('warehouse-address-banner')).toBeNull(); cleanup()
    r(me('owner', false));  expect(screen.queryByTestId('warehouse-address-banner')).toBeNull(); cleanup()
    r(null);                expect(screen.queryByTestId('warehouse-address-banner')).toBeNull()
  })
})

describe('embedded', () => {
  test('og11 Finish setup in Traced links to /onboarding (EN and AR)', () => {
    render(<PolarisProvider i18n={polarisEn}><FinishSetupCard step="address" /></PolarisProvider>)
    const card = screen.getByTestId('embedded-finish-setup')
    expect(card).toHaveTextContent('add your main warehouse address')
    expect(within(card).getByRole('link').getAttribute('href')).toMatch(/\/onboarding$/)
    cleanup()
    render(<PolarisProvider i18n={polarisEn}><FinishSetupCard step="shopify" lang="ar" /></PolarisProvider>)
    expect(screen.getByTestId('embedded-finish-setup')).toHaveTextContent('أكمل الإعداد في Traced')
  })
})
