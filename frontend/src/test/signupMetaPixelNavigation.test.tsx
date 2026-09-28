import { describe, test, expect, beforeEach, afterEach, vi } from 'vitest'
import userEvent from '@testing-library/user-event'
import { screen, render } from '@testing-library/react'
import { MemoryRouter, Routes, Route, Link } from 'react-router-dom'
import { I18nextProvider, initReactI18next } from 'react-i18next'
import i18next from 'i18next'
import en from '../locales/en.json'
import Signup from '../pages/Signup'
import { clearAccessToken } from '../auth'
import { __resetMetaPixelForTests } from '../metaPixel'

/**
 * Live bug (2026-09-28): after signup the pixel stayed loaded as the SPA moved to /overview,
 * and fbevents.js fired a PageView on every history change plus automatic button-click events.
 * The pixel must be told, BEFORE init, to ignore history changes (disablePushState) and to run
 * no automatic events (autoConfig false); after CompleteRegistration nothing else may be sent
 * while the user moves around the signed-in app.
 *
 * jsdom never executes fbevents.js, so this proves (a) both switches are set before init and
 * (b) our own code makes no fbq call after signup; the auto-behaviour itself is Meta's.
 */
function makeI18n() {
  const instance = i18next.createInstance()
  instance.use(initReactI18next).init({
    lng: 'en', fallbackLng: 'en', initImmediate: false,
    resources: { en: { translation: en } }, interpolation: { escapeValue: false },
  })
  return instance
}

const PIXEL_ID = '1837033837461823'
const TENANT = '11111111-2222-3333-4444-555555555555'
const fakeJwt = (claims: Record<string, unknown>) => `h.${btoa(JSON.stringify(claims))}.s`

describe('Meta Pixel after signup, in the signed-in app', () => {
  beforeEach(() => {
    __resetMetaPixelForTests()
    delete window.fbq
    delete window._fbq
    document.head.querySelectorAll('script[src*="fbevents"]').forEach(s => s.remove())
    clearAccessToken()
    vi.stubGlobal('fetch', vi.fn(() => Promise.resolve({
      ok: true, status: 201, statusText: '',
      headers: new Headers({ 'content-type': 'application/json' }),
      json: () => Promise.resolve({ accessToken: fakeJwt({ tenant: TENANT, role: 'owner' }) }),
    } as unknown as Response)))
  })
  afterEach(() => vi.unstubAllGlobals())

  test('route-change PageViews and automatic events are switched off before init', () => {
    render(
      <I18nextProvider i18n={makeI18n()}>
        <MemoryRouter initialEntries={['/signup']}>
          <Routes><Route path="/signup" element={<Signup />} /></Routes>
        </MemoryRouter>
      </I18nextProvider>,
    )
    expect(window.fbq!.disablePushState).toBe(true)
    expect(window.fbq!.queue).toEqual([
      ['set', 'autoConfig', false, PIXEL_ID],
      ['init', PIXEL_ID],
      ['track', 'PageView'],
    ])
  })

  test('navigating away from /signup after a successful signup triggers no further fbq calls', async () => {
    const u = userEvent.setup()
    render(
      <I18nextProvider i18n={makeI18n()}>
        <MemoryRouter initialEntries={['/signup']}>
          <Routes>
            <Route path="/signup" element={<Signup />} />
            <Route path="/overview" element={<div>OVERVIEW <Link to="/orders">to orders</Link></div>} />
            <Route path="/orders" element={<div>ORDERS <button>Some app button</button></div>} />
          </Routes>
        </MemoryRouter>
      </I18nextProvider>,
    )
    await u.type(screen.getByLabelText('Business name'), 'Acme')
    await u.type(screen.getByLabelText('Your name'), 'Owner')
    await u.type(screen.getByLabelText('Email'), 'owner@acme.com')
    await u.type(screen.getByLabelText('Phone'), '1012345678')
    await u.type(screen.getByLabelText('Password'), 'password99')
    await u.click(screen.getByRole('checkbox'))
    await u.click(screen.getByRole('button', { name: 'Create account' }))
    await screen.findByText(/OVERVIEW/)

    const afterSignup = [...window.fbq!.queue]
    expect(afterSignup.at(-1)).toEqual(['track', 'CompleteRegistration', {}, { eventID: `reg-${TENANT}` }])

    await u.click(screen.getByText('to orders'))
    await screen.findByText(/ORDERS/)
    await u.click(screen.getByRole('button', { name: 'Some app button' }))
    window.history.pushState(null, '', '/inventory')
    window.dispatchEvent(new PopStateEvent('popstate'))

    expect(window.fbq!.queue).toEqual(afterSignup)
    expect(document.head.querySelectorAll('script[src*="fbevents"]')).toHaveLength(1)
  })
})
