import { describe, test, expect, beforeAll, beforeEach, afterEach, vi } from 'vitest'
import { screen, render, cleanup, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AppProvider as PolarisProvider } from '@shopify/polaris'
import polarisEn from '@shopify/polaris/locales/en.json'
import EmbeddedApp from '../embedded/EmbeddedApp'
import { embeddedLang } from '../embedded/embeddedLocale'
import { onboardingCopy } from '../embedded/onboardingCopy'
import { isEgyptianMobile } from '../embedded/Onboarding'

// Build D — onboarding inside the embedded Shopify app (design/Traced_embedded_onboarding_dc.html):
//   O1 welcome, S1 signup (prefill), S2 field errors, S3 email taken, L1 existing account (top-level
//   navigation, never a pop-up), C1 connected (after signup / after a confirmed pending link),
//   X1 linked elsewhere, X2 failed — each in EN and AR (Shopify's `locale` parameter).

beforeAll(() => {
  Object.defineProperty(window, 'matchMedia', {
    writable: true,
    value: (query: string) => ({
      matches: false, media: query, onchange: null,
      addListener: () => {}, removeListener: () => {}, addEventListener: () => {}, removeEventListener: () => {},
      dispatchEvent: () => false,
    }),
  })
})

const SHOP = 'abc123.myshopify.com'

function res(status: number, body: unknown) {
  return Promise.resolve({
    ok: status >= 200 && status < 300, status, statusText: '',
    headers: { get: () => 'application/json' },
    json: async () => body,
  } as unknown as Response)
}

type Handler = (url: string, init?: RequestInit) => Promise<Response> | undefined

/** Cold install (NOT_PROVISIONED) unless a handler answers first. */
function stubFetch(...handlers: Handler[]) {
  const fn = vi.fn((url: string, init?: RequestInit) => {
    for (const h of handlers) {
      const r = h(url, init)
      if (r) return r
    }
    if (url.includes('/embedded/token-exchange')) return res(401, { error: 'NOT_PROVISIONED' })
    if (url.includes('/onboarding/prefill')) return res(200, { shopDomain: SHOP, shopName: "Mona's Linen", email: 'mona@monaslinen.com' })
    return res(401, {})
  })
  vi.stubGlobal('fetch', fn)
  return fn
}

function setUrl(search: string) {
  window.history.replaceState(null, '', '/' + search)
}

function renderApp() {
  return render(<PolarisProvider i18n={polarisEn}><EmbeddedApp /></PolarisProvider>)
}

beforeEach(() => {
  ;(globalThis as unknown as { shopify: { idToken(): Promise<string> } }).shopify = { idToken: async () => 'session-token' }
})

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
  delete (globalThis as unknown as { shopify?: unknown }).shopify
  setUrl('')
  document.documentElement.dir = 'ltr'
  document.documentElement.lang = 'en'
})

async function fillValidForm(user: ReturnType<typeof userEvent.setup>, lang: 'en' | 'ar') {
  const c = onboardingCopy[lang]
  await user.type(screen.getByLabelText(c.yourName), 'Mona Adel')
  await user.type(screen.getByLabelText(c.mobile), '010 1234 5678')
  await user.type(screen.getByLabelText(c.password), 'pass1234')
  await user.click(screen.getByRole('checkbox'))
}

describe('locale', () => {
  test('Shopify locale → language', () => {
    expect(embeddedLang('?locale=ar')).toBe('ar')
    expect(embeddedLang('?locale=ar-EG')).toBe('ar')
    expect(embeddedLang('?locale=en-US')).toBe('en')
    expect(embeddedLang('?shop=x')).toBe('en')
  })
  test('Egyptian mobile shapes (mirror of the server rule)', () => {
    for (const ok of ['01012345678', '010 1234 5678', '+201012345678', '00201012345678', '1012345678']) expect(isEgyptianMobile(ok)).toBe(true)
    for (const bad of ['0123', '02012345678', '', '+44 7700 900123']) expect(isEgyptianMobile(bad)).toBe(false)
  })
})

for (const lang of ['en', 'ar'] as const) {
  const c = onboardingCopy[lang]
  const loc = lang === 'ar' ? '?locale=ar&shop=' + SHOP : '?shop=' + SHOP

  describe(`onboarding — ${lang.toUpperCase()}`, () => {
    test('O1 welcome: store from Shopify, two choices, no payment language, lang/dir set', async () => {
      setUrl(loc)
      stubFetch()
      renderApp()
      expect(await screen.findByText(c.welcomeTitle)).toBeInTheDocument()
      expect(await screen.findByText("Mona's Linen")).toBeInTheDocument()
      expect(screen.getByTestId('onboarding-shop')).toHaveTextContent(SHOP)
      expect(screen.getByRole('button', { name: c.createTitle })).toBeInTheDocument()
      expect(screen.getByRole('button', { name: c.existingTitle })).toBeInTheDocument()
      expect(document.body.textContent).not.toMatch(/pay|price|pricing|\$|EGP|subscription/i)
      expect(document.documentElement.lang).toBe(lang)
      expect(document.documentElement.dir).toBe(lang === 'ar' ? 'rtl' : 'ltr')
      // No input for the store anywhere.
      expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
    })

    test('S1 + S2: prefilled form; invalid fields show errors and nothing is sent', async () => {
      setUrl(loc)
      const fetchFn = stubFetch()
      const user = userEvent.setup()
      renderApp()
      await user.click(await screen.findByRole('button', { name: c.createTitle }))
      expect(screen.getByText(c.signupTitle)).toBeInTheDocument()
      await waitFor(() => expect(screen.getByLabelText(c.businessName)).toHaveValue("Mona's Linen"))
      expect(screen.getByLabelText(c.email)).toHaveValue('mona@monaslinen.com')

      await user.clear(screen.getByLabelText(c.businessName))
      await user.clear(screen.getByLabelText(c.email))
      await user.type(screen.getByLabelText(c.email), 'mona@monaslinen')
      await user.type(screen.getByLabelText(c.mobile), '0123')
      await user.type(screen.getByLabelText(c.password), 'short')
      await user.click(screen.getByRole('button', { name: c.createAndConnect }))

      for (const e of [c.errBusinessName, c.errMobile, c.errEmail, c.errPassword, c.errConsent]) {
        expect(screen.getByText(e)).toBeInTheDocument()
      }
      expect(fetchFn.mock.calls.some(([u]) => String(u).includes('/onboarding/signup'))).toBe(false)
    })

    test('S3: email already registered → banner with the web signup wording; "Sign in instead" → L1', async () => {
      setUrl(loc)
      stubFetch(u => u.includes('/onboarding/signup') ? res(409, { code: 'EMAIL_TAKEN', message_en: 'x', message_ar: 'x' }) : undefined)
      const user = userEvent.setup()
      renderApp()
      await user.click(await screen.findByRole('button', { name: c.createTitle }))
      await waitFor(() => expect(screen.getByLabelText(c.email)).toHaveValue('mona@monaslinen.com'))
      await fillValidForm(user, lang)
      await user.click(screen.getByRole('button', { name: c.createAndConnect }))
      expect(await screen.findByText(c.emailTakenTitle)).toBeInTheDocument()
      await user.click(screen.getByRole('button', { name: c.signInInstead }))
      expect(screen.getByText(c.existingPageTitle)).toBeInTheDocument()
    })

    test('X2: Shopify refused / server error → "Nothing was created", form kept', async () => {
      setUrl(loc)
      stubFetch(u => u.includes('/onboarding/signup') ? res(502, {}) : undefined)
      const user = userEvent.setup()
      renderApp()
      await user.click(await screen.findByRole('button', { name: c.createTitle }))
      await waitFor(() => expect(screen.getByLabelText(c.email)).toHaveValue('mona@monaslinen.com'))
      await fillValidForm(user, lang)
      await user.click(screen.getByRole('button', { name: c.createAndConnect }))
      expect(await screen.findByText(c.failedTitle)).toBeInTheDocument()
      expect(screen.getByText(c.failedBody)).toBeInTheDocument()
      expect(screen.getByLabelText(c.yourName)).toHaveValue('Mona Adel')
    })

    test('X1: store already linked elsewhere', async () => {
      setUrl(loc)
      stubFetch(u => u.includes('/onboarding/prefill')
        ? res(409, { code: 'SHOP_LINKED_ELSEWHERE', message_en: 'x', message_ar: 'x' }) : undefined)
      renderApp()
      expect(await screen.findByText(c.linkedElsewhereTitle)).toBeInTheDocument()
      expect(screen.getByRole('link', { name: c.signInToTraced })).toHaveAttribute('href', 'https://app.tracedtech.com/login')
    })

    test('L1: existing account → pending link → TOP-LEVEL navigation, never a pop-up', async () => {
      setUrl(loc)
      const url = 'https://app.tracedtech.com/connect/shopify?link=nonce123'
      const fetchFn = stubFetch(u => u.includes('/onboarding/pending-link') ? res(200, { url }) : undefined)
      const open = vi.spyOn(window, 'open').mockImplementation(() => null)
      const user = userEvent.setup()
      renderApp()
      await user.click(await screen.findByRole('button', { name: c.existingTitle }))
      expect(screen.getByText(c.existingPageBody)).toBeInTheDocument()
      await user.click(screen.getByRole('button', { name: c.continueToTraced }))
      await waitFor(() => expect(open).toHaveBeenCalledWith(url, '_top'))
      expect(open).not.toHaveBeenCalledWith(expect.anything(), '_blank', expect.anything())
      const call = fetchFn.mock.calls.find(([u]) => String(u).includes('/onboarding/pending-link'))!
      expect((call[1] as RequestInit).method).toBe('POST')
      expect((call[1] as RequestInit).body).toBeUndefined()   // nothing typed, nothing sent
    })

    test('C1 after signup: steps, import progress → done, one-time "Open Traced" then the front door', async () => {
      setUrl(loc)
      let polls = 0          // counted only after signup (the dashboard fetches run at mount too)
      let signedUp = false
      const signInUrl = 'https://app.tracedtech.com/auth/magic?token=one-time'
      stubFetch(
        u => {
          if (!u.includes('/onboarding/signup')) return undefined
          signedUp = true
          return res(200, { shopDomain: SHOP, email: 'mona@monaslinen.com', signInUrl, signInValidMinutes: 10 })
        },
        u => u.includes('/stores/status') && signedUp
          ? res(200, [{ shop_domain: SHOP, status: 'connected', import_status: ++polls > 1 ? 'completed' : 'importing', last_sync_at: null }])
          : undefined,
      )
      const open = vi.spyOn(window, 'open').mockImplementation(() => null)
      const user = userEvent.setup()
      renderApp()
      await user.click(await screen.findByRole('button', { name: c.createTitle }))
      await waitFor(() => expect(screen.getByLabelText(c.email)).toHaveValue('mona@monaslinen.com'))
      await fillValidForm(user, lang)
      await user.click(screen.getByRole('button', { name: c.createAndConnect }))

      expect(await screen.findByText(c.connectedTitle)).toBeInTheDocument()
      expect(screen.getByText(c.stepAccount)).toBeInTheDocument()
      expect(screen.getByText(`${c.stepAccountSub} mona@monaslinen.com`)).toBeInTheDocument()
      expect(screen.getByText(c.stepImport)).toBeInTheDocument()
      expect(await screen.findByText(c.stepImportDone, {}, { timeout: 6000 })).toBeInTheDocument()

      await user.click(screen.getByRole('button', { name: c.openTraced }))
      await user.click(screen.getByRole('button', { name: c.openTraced }))
      expect(open).toHaveBeenNthCalledWith(1, signInUrl, '_blank', 'noopener,noreferrer')
      expect(open).toHaveBeenNthCalledWith(2, 'https://app.tracedtech.com', '_blank', 'noopener,noreferrer')
    }, 15000)

    test('C1 after a confirmed pending link (?traced_connected=1): no account step, plain "Open Traced"', async () => {
      setUrl(loc + '&traced_connected=1')
      stubFetch(
        u => u.includes('/embedded/token-exchange') ? res(204, {}) : undefined,
        u => u.includes('/stores/status')
          ? res(200, [{ shop_domain: SHOP, status: 'connected', import_status: 'importing', last_sync_at: null }]) : undefined,
      )
      const open = vi.spyOn(window, 'open').mockImplementation(() => null)
      const user = userEvent.setup()
      renderApp()
      expect(await screen.findByText(c.connectedTitle)).toBeInTheDocument()
      expect(screen.queryByText(c.stepAccount)).not.toBeInTheDocument()
      expect(screen.getByText(c.stepStore)).toBeInTheDocument()
      await user.click(screen.getByRole('button', { name: c.openTraced }))
      expect(open).toHaveBeenCalledWith('https://app.tracedtech.com', '_blank', 'noopener,noreferrer')
    })
  })
}

test('a linked store without ?traced_connected=1 still gets the dashboard, not onboarding', async () => {
  setUrl('?shop=' + SHOP)
  stubFetch(
    u => u.includes('/embedded/token-exchange') ? res(204, {}) : undefined,
    u => u.includes('/stores/status')
      ? res(200, [{ shop_domain: 'linked.myshopify.com', status: 'connected', import_status: 'idle', last_sync_at: null }]) : undefined,
    u => u.includes('/inventory/summary') ? res(200, { groupA: [], groupB: [] }) : undefined,
    u => u.includes('/orders/daily-counts') ? res(200, []) : undefined,
    u => u.includes('/exceptions') ? res(200, { count: 0, exceptions: [] }) : undefined,
  )
  renderApp()
  expect(await screen.findByText('linked.myshopify.com')).toBeInTheDocument()
  expect(screen.queryByText(onboardingCopy.en.welcomeTitle)).not.toBeInTheDocument()
  expect(screen.queryByText(onboardingCopy.en.connectedTitle)).not.toBeInTheDocument()
})
