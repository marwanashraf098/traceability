import { describe, test, expect, beforeEach, afterEach, vi } from 'vitest'
import userEvent from '@testing-library/user-event'
import { screen, render, waitFor } from '@testing-library/react'
import { MemoryRouter, Routes, Route } from 'react-router-dom'
import { I18nextProvider, initReactI18next } from 'react-i18next'
import i18next from 'i18next'
import en from '../locales/en.json'
import Signup from '../pages/Signup'
import { clearAccessToken } from '../auth'
import { __resetMetaPixelForTests } from '../metaPixel'

/**
 * Meta signup attribution, Build B: the pixel loads on the Signup page, the signup body
 * carries _fbp/_fbc/fbclid/utm_*, and CompleteRegistration fires exactly once after a
 * successful signup with eventID "reg-<tenant id>" — never for @tracedtech.com emails and
 * never when the signup fails.
 */
function makeI18n() {
  const instance = i18next.createInstance()
  instance.use(initReactI18next).init({
    lng: 'en', fallbackLng: 'en', initImmediate: false,
    resources: { en: { translation: en } }, interpolation: { escapeValue: false },
  })
  return instance
}

const TENANT = '11111111-2222-3333-4444-555555555555'
const fakeJwt = (claims: Record<string, unknown>) => `h.${btoa(JSON.stringify(claims))}.s`

function jsonResponse(status: number, body: unknown) {
  return Promise.resolve({
    ok: status >= 200 && status < 300, status, statusText: '',
    headers: new Headers({ 'content-type': 'application/json' }),
    json: () => Promise.resolve(body), text: () => Promise.resolve(JSON.stringify(body)),
  } as unknown as Response)
}

function renderSignup(search = '') {
  window.history.replaceState(null, '', '/signup' + search)
  return render(
    <I18nextProvider i18n={makeI18n()}>
      <MemoryRouter initialEntries={['/signup']}>
        <Routes>
          <Route path="/signup" element={<Signup />} />
          <Route path="/overview" element={<div>OVERVIEW</div>} />
        </Routes>
      </MemoryRouter>
    </I18nextProvider>,
  )
}

async function fillAndSubmit(email: string) {
  const u = userEvent.setup()
  await u.type(screen.getByLabelText('Business name'), 'Acme')
  await u.type(screen.getByLabelText('Your name'), 'Owner')
  await u.type(screen.getByLabelText('Email'), email)
  await u.type(screen.getByLabelText('Phone'), '1012345678')
  await u.type(screen.getByLabelText('Password'), 'password99')
  await u.click(screen.getByRole('checkbox'))
  await u.click(screen.getByRole('button', { name: 'Create account' }))
}

const pixelCalls = () => (window.fbq!.queue as unknown[][])
const registrations = () => pixelCalls().filter(c => c[0] === 'track' && c[1] === 'CompleteRegistration')

describe('Signup page Meta Pixel', () => {
  let fetchMock: ReturnType<typeof vi.fn>

  beforeEach(() => {
    __resetMetaPixelForTests()
    delete window.fbq
    delete window._fbq
    document.head.querySelectorAll('script[src*="fbevents"]').forEach(s => s.remove())
    document.cookie = '_fbp=fb.1.1727500000000.1234567890; path=/'
    document.cookie = '_fbc=fb.1.1727500000000.IwAR0abc; path=/'
    clearAccessToken()
    fetchMock = vi.fn(() => jsonResponse(201, { accessToken: fakeJwt({ tenant: TENANT, role: 'owner' }) }))
    vi.stubGlobal('fetch', fetchMock)
  })
  afterEach(() => {
    vi.unstubAllGlobals()
    document.cookie = '_fbp=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/'
    document.cookie = '_fbc=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/'
  })

  test('loads fbevents.js once with init + PageView on mount', () => {
    renderSignup()
    expect(document.head.querySelectorAll('script[src="https://connect.facebook.net/en_US/fbevents.js"]')).toHaveLength(1)
    expect(pixelCalls()).toEqual([['set', 'autoConfig', false, '1837033837461823'], ['init', '1837033837461823'], ['track', 'PageView']])
  })

  test('sends cookies + URL attribution and fires CompleteRegistration once with reg-<tenant>', async () => {
    renderSignup('?fbclid=IwAR0abc&utm_source=facebook&utm_campaign=launch')
    await fillAndSubmit('owner@acme.com')
    await screen.findByText('OVERVIEW')

    const body = JSON.parse(fetchMock.mock.calls[0][1].body)
    expect(body.attribution).toEqual({
      fbp: 'fb.1.1727500000000.1234567890',
      fbc: 'fb.1.1727500000000.IwAR0abc',
      fbclid: 'IwAR0abc',
      utmSource: 'facebook',
      utmCampaign: 'launch',
    })
    expect(registrations()).toEqual([['track', 'CompleteRegistration', {}, { eventID: `reg-${TENANT}` }]])
  })

  test('@tracedtech.com signups never fire CompleteRegistration', async () => {
    renderSignup()
    await fillAndSubmit('reviewer9@TracedTech.com')
    await screen.findByText('OVERVIEW')
    expect(registrations()).toHaveLength(0)
  })

  test('a failed signup fires nothing', async () => {
    fetchMock.mockImplementation(() => jsonResponse(409, { message: 'conflict' }))
    renderSignup()
    await fillAndSubmit('owner@acme.com')
    await waitFor(() => expect(screen.getByRole('alert')).toBeInTheDocument())
    expect(registrations()).toHaveLength(0)
  })
})
