import { describe, test, expect, vi, beforeAll, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, cleanup } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Routes, Route, useLocation } from 'react-router-dom'
import { I18nextProvider, initReactI18next } from 'react-i18next'
import i18next from 'i18next'
import { AppProvider as PolarisProvider } from '@shopify/polaris'
import polarisEn from '@shopify/polaris/locales/en.json'
import en from '../locales/en.json'
import ar from '../locales/ar.json'
import { setAccessToken, clearAccessToken } from '../auth'
import { OwnerOnlyRoute } from '../App'
import { StationProvider } from '../components/StationProvider'
import { ToastProvider } from '../components/ui'
import { SetupBazarPage, SetupCourierPage, SetupReadyPage } from '../pages/onboarding/SetupIntro'
import Signup from '../pages/Signup'
import EmbeddedApp from '../embedded/EmbeddedApp'
import { onboardingCopy } from '../embedded/onboardingCopy'
import { forgetIntroRedirects, refreshSetupStep } from '../setupGate'
import { resetCapabilitiesCache } from '../capabilities'
import { __resetMetaPixelForTests } from '../metaPixel'

/**
 * Bazar B9a — a store that signs up to sell at a bazar.
 *   b1  web /signup?for=bazar sends intent 'bazar' (bazar subtitle); plain /signup sends NO intent key (body as before)
 *   b2  an owner with setupIntro 'bazar' is sent once to /setup/bazar (never Flow-3 / Flow-4); the page writes
 *       setupIntroSeen; "Create your bazar" → /bazar/new only while bazar is on, else the "not on yet" note
 *   b3  managers are never sent there; a non-bazar intro never shows it; Flow-3 / Flow-4 send a bazar intro there
 *   b4  App Store onboarding: "What brings you to Traced?" — default sends no intent; "Sell at a bazar" sends 'bazar'
 *   b5  Arabic strings exist for every new key
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
    headers: new Headers({ 'content-type': 'application/json' }),
    json: async () => structuredClone(body), text: async () => JSON.stringify(body),
  } as unknown as Response)
}

type Calls = { url: string; method: string; body: unknown }[]

function stubApi(me: Record<string, unknown>): Calls {
  const calls: Calls = []
  vi.stubGlobal('fetch', vi.fn((url: string, init?: RequestInit) => {
    const method = init?.method ?? 'GET'
    calls.push({ url, method, body: init?.body ? JSON.parse(init.body as string) : null })
    if (url.endsWith('/me')) return json(200, me)
    if (url.endsWith('/auth/signup')) return json(201, { accessToken: token('owner') })
    return json(200, {})
  }))
  return calls
}

const visited: string[] = []

function Where() {
  const l = useLocation()
  visited.push(l.pathname)
  return <div data-testid="where">{l.pathname}</div>
}

function renderAt(path: string, lng: 'en' | 'ar' = 'en') {
  return render(
    <StationProvider>
      <MemoryRouter initialEntries={[path]}>
        <I18nextProvider i18n={i18nFor(lng)}>
          <ToastProvider>
            <Routes>
              <Route path="/overview" element={<OwnerOnlyRoute><div data-testid="screen">overview</div><Where /></OwnerOnlyRoute>} />
              <Route path="/orders" element={<OwnerOnlyRoute><div data-testid="screen">orders</div><Where /></OwnerOnlyRoute>} />
              <Route path="/setup/bazar" element={<><SetupBazarPage /><Where /></>} />
              <Route path="/setup/courier" element={<><SetupCourierPage /><Where /></>} />
              <Route path="/setup/ready" element={<><SetupReadyPage /><Where /></>} />
              <Route path="/bazar/new" element={<div data-testid="bazar-new" />} />
              <Route path="/signup" element={<Signup />} />
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
  visited.length = 0
  refreshSetupStep()
  forgetIntroRedirects()
  resetCapabilitiesCache()
  __resetMetaPixelForTests()
  localStorage.removeItem('stationMode')
})

afterEach(() => {
  cleanup()
  clearAccessToken()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
  window.history.replaceState(null, '', '/')
  document.documentElement.dir = 'ltr'
  document.documentElement.lang = 'en'
  delete (globalThis as unknown as { shopify?: unknown }).shopify
})

const OWNER_BAZAR = { role: 'owner', name: 'Mona Adel', setupStep: null, setupIntro: 'bazar', warehouseAddressNeeded: true }

async function fillSignup() {
  const u = userEvent.setup()
  await u.type(screen.getByLabelText('Business name'), 'Acme')
  await u.type(screen.getByLabelText('Your name'), 'Owner')
  await u.type(screen.getByLabelText('Email'), 'mona@acme.test')
  await u.type(screen.getByLabelText('Phone'), '1012345678')
  await u.type(screen.getByLabelText('Password'), 'password99')
  await u.click(screen.getByRole('checkbox'))
  await u.click(screen.getByRole('button', { name: 'Create account' }))
}

describe('b1 web signup', () => {
  test('?for=bazar sends intent bazar and shows the bazar subtitle', async () => {
    window.history.replaceState(null, '', '/signup?for=bazar')
    const calls = stubApi({})
    renderAt('/signup')
    expect(screen.getByText(en.signup.subtitleBazar)).toBeInTheDocument()
    await fillSignup()
    await waitFor(() => expect(calls.some(c => c.url.endsWith('/auth/signup'))).toBe(true))
    const body = calls.find(c => c.url.endsWith('/auth/signup'))!.body as Record<string, unknown>
    expect(body.intent).toBe('bazar')
  })

  test('plain /signup sends no intent key at all', async () => {
    window.history.replaceState(null, '', '/signup?for=something')
    const calls = stubApi({})
    renderAt('/signup')
    expect(screen.getByText(en.signup.subtitle)).toBeInTheDocument()
    await fillSignup()
    await waitFor(() => expect(calls.some(c => c.url.endsWith('/auth/signup'))).toBe(true))
    const body = calls.find(c => c.url.endsWith('/auth/signup'))!.body as Record<string, unknown>
    expect(Object.keys(body).sort()).toEqual(['attribution', 'consent', 'email', 'name', 'password', 'phone', 'tenantName'])
  })
})

describe('b2 / b3 the bazar intro', () => {
  test('b2 an owner with setupIntro bazar goes to /setup/bazar once; setupIntroSeen written; bazar on → Create your bazar', async () => {
    setAccessToken(token('owner'))
    const calls = stubApi({ ...OWNER_BAZAR, bazar: true })
    const user = userEvent.setup()
    renderAt('/overview')
    expect(await screen.findByTestId('setup-bazar')).toBeInTheDocument()
    expect(screen.getByTestId('where')).toHaveTextContent('/setup/bazar')
    expect(visited).not.toContain('/setup/ready')
    expect(visited).not.toContain('/setup/courier')
    expect(screen.getByText(en.setupIntro.bazar.title)).toBeInTheDocument()
    await waitFor(() => expect(calls.some(c => c.url.endsWith('/onboarding/prefs') && c.method === 'PATCH')).toBe(true))
    expect(calls.find(c => c.url.endsWith('/onboarding/prefs'))!.body).toEqual({ setupIntroSeen: true })
    expect(calls.some(c => c.url.endsWith('/onboarding/prefs') && JSON.stringify(c.body).includes('intent'))).toBe(false)
    await user.click(await screen.findByTestId('setup-bazar-new'))
    expect(await screen.findByTestId('bazar-new')).toBeInTheDocument()
    cleanup()
    // Same page load, another screen: not sent back.
    renderAt('/orders')
    expect(await screen.findByTestId('screen')).toHaveTextContent('orders')
  })

  test('b2 bazar not on yet → the note, no link; explore → overview', async () => {
    setAccessToken(token('owner'))
    stubApi(OWNER_BAZAR)
    const user = userEvent.setup()
    renderAt('/setup/bazar')
    expect(await screen.findByTestId('setup-bazar-not-on')).toHaveTextContent(en.setupIntro.bazar.notOnYet)
    expect(screen.queryByTestId('setup-bazar-new')).not.toBeInTheDocument()
    await user.click(screen.getByTestId('setup-bazar-explore'))
    expect(await screen.findByTestId('screen')).toHaveTextContent('overview')
  })

  test('b3 a manager is never sent there and is sent away from it', async () => {
    setAccessToken(token('manager'))
    stubApi({ ...OWNER_BAZAR, role: 'manager', setupIntro: null })
    renderAt('/setup/bazar')
    expect(await screen.findByTestId('screen')).toHaveTextContent('overview')
  })

  test('b3 a normal intro never shows the bazar page; Flow-3 / Flow-4 send a bazar intro there', async () => {
    setAccessToken(token('owner'))
    stubApi({ role: 'owner', name: 'M', setupStep: null, setupIntro: 'courier' })
    renderAt('/setup/bazar')
    // Sent to Overview, which then follows the normal intro (Flow-3) as before.
    expect(await screen.findByTestId('setup-courier')).toBeInTheDocument()
    expect(screen.queryByTestId('setup-bazar')).not.toBeInTheDocument()
    cleanup(); refreshSetupStep(); forgetIntroRedirects()

    stubApi(OWNER_BAZAR)
    renderAt('/setup/courier')
    expect(await screen.findByTestId('setup-bazar')).toBeInTheDocument()
    cleanup(); refreshSetupStep(); forgetIntroRedirects()

    stubApi(OWNER_BAZAR)
    renderAt('/setup/ready')
    expect(await screen.findByTestId('setup-bazar')).toBeInTheDocument()
  })

  test('b5 Arabic', async () => {
    setAccessToken(token('owner'))
    stubApi(OWNER_BAZAR)
    renderAt('/setup/bazar', 'ar')
    expect(await screen.findByText(ar.setupIntro.bazar.title)).toBeInTheDocument()
    for (const k of Object.keys(en.setupIntro.bazar) as (keyof typeof en.setupIntro.bazar)[]) {
      expect(ar.setupIntro.bazar[k], k).toBeTruthy()
      expect(ar.setupIntro.bazar[k], k).not.toBe(en.setupIntro.bazar[k])
    }
    expect(ar.signup.subtitleBazar).toBeTruthy()
    for (const k of ['purposeTitle', 'purposeOrders', 'purposeBazar'] as const) {
      expect(onboardingCopy.ar[k]).toBeTruthy()
      expect(onboardingCopy.ar[k]).not.toBe(onboardingCopy.en[k])
    }
  })
})

describe('b4 App Store onboarding form', () => {
  const SHOP = 'abc123.myshopify.com'
  const c = onboardingCopy.en

  function stubEmbedded(): Calls {
    const calls: Calls = []
    vi.stubGlobal('fetch', vi.fn((url: string, init?: RequestInit) => {
      calls.push({ url, method: init?.method ?? 'GET', body: init?.body ? JSON.parse(init.body as string) : null })
      if (url.includes('/embedded/token-exchange')) return json(401, { error: 'NOT_PROVISIONED' })
      if (url.includes('/onboarding/prefill')) return json(200, { shopDomain: SHOP, shopName: "Mona's Linen", email: 'mona@monaslinen.com' })
      if (url.includes('/onboarding/signup')) return json(200, { shopDomain: SHOP, email: 'mona@monaslinen.com', signInUrl: 'https://x/auth/magic?token=t', signInValidMinutes: 10 })
      return json(401, {})
    }))
    return calls
  }

  async function openAndFill(user: ReturnType<typeof userEvent.setup>) {
    window.history.replaceState(null, '', '/?shop=' + SHOP)
    ;(globalThis as unknown as { shopify: { idToken(): Promise<string> } }).shopify = { idToken: async () => 'session-token' }
    render(<PolarisProvider i18n={polarisEn}><EmbeddedApp /></PolarisProvider>)
    await user.click(await screen.findByRole('button', { name: c.createTitle }))
    await waitFor(() => expect(screen.getByLabelText(c.email)).toHaveValue('mona@monaslinen.com'))
    expect(screen.getByText(c.purposeTitle)).toBeInTheDocument()
    expect(screen.getByRole('radio', { name: c.purposeOrders })).toBeChecked()
    await user.type(screen.getByLabelText(c.yourName), 'Mona Adel')
    await user.type(screen.getByLabelText(c.mobile), '010 1234 5678')
    await user.type(screen.getByLabelText(c.password), 'pass1234')
    await user.click(screen.getByRole('checkbox'))
  }

  test('default: no intent in the body', async () => {
    const calls = stubEmbedded()
    const user = userEvent.setup()
    await openAndFill(user)
    await user.click(screen.getByRole('button', { name: c.createAndConnect }))
    await waitFor(() => expect(calls.some(x => x.url.includes('/onboarding/signup'))).toBe(true))
    expect(calls.find(x => x.url.includes('/onboarding/signup'))!.body).not.toHaveProperty('intent')
  })

  test('"Sell at a bazar" sends intent bazar', async () => {
    const calls = stubEmbedded()
    const user = userEvent.setup()
    await openAndFill(user)
    await user.click(screen.getByRole('radio', { name: c.purposeBazar }))
    await user.click(screen.getByRole('button', { name: c.createAndConnect }))
    await waitFor(() => expect(calls.some(x => x.url.includes('/onboarding/signup'))).toBe(true))
    expect(calls.find(x => x.url.includes('/onboarding/signup'))!.body).toMatchObject({ intent: 'bazar' })
  })
})
