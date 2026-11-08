import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import userEvent from '@testing-library/user-event'
import { cleanup, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import i18next from 'i18next'
import { I18nextProvider, initReactI18next } from 'react-i18next'
import en from '../locales/en.json'
import ar from '../locales/ar.json'
import { StationProvider } from '../components/StationProvider'
import { ToastProvider } from '../components/ui'
import { setAccessToken, clearAccessToken } from '../auth'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import FulfillRoute from '../pages/fulfill/FulfillRoute'

/**
 * Pick & Pack — an order that can't be loaded (2026-11-08). GET /fulfill/{id} answering 404 / 500 (an error body, no
 * items) used to be stored as the order and crash the render ("reading 'filter'") — reachable from an Unpack Required
 * alert for an order that is gone. Now the screen says it couldn't open the order and offers Back.
 *   l1 404 from the alert link → the message + Back (EN); Back leaves ?guidedUnpack
 *   l2 a body with no items (200) → the same message (AR)
 */

function i18nFor(lng: 'en' | 'ar') {
  const inst = i18next.createInstance()
  inst.use(initReactI18next).init({ lng, fallbackLng: 'en', initImmediate: false,
    resources: { en: { translation: en }, ar: { translation: ar } }, interpolation: { escapeValue: false } })
  return inst
}
function token(role: string) {
  return 'h.' + btoa(JSON.stringify({ role, tenant: 't', exp: Math.floor(Date.now() / 1000) + 3600 })).replace(/=+$/, '') + '.s'
}
const reply = (status: number, data: unknown) => Promise.resolve({ ok: status < 400, status, statusText: '',
  headers: { get: (k: string) => (k.toLowerCase() === 'content-type' ? 'application/json' : null) },
  json: async () => structuredClone(data), text: async () => JSON.stringify(data) })

function Where() { const l = useLocation(); return <div data-testid="where">{l.pathname + l.search}</div> }

function renderAt(lng: 'en' | 'ar', order: () => ReturnType<typeof reply>) {
  stubFetchWithShellDefaults((url: string) => {
    if (url.endsWith('/fulfill/mode')) return reply(200, { mode: 'piece_scan' })
    if (url.includes('/fulfill/queue')) return reply(200, [])
    if (url.endsWith('/api/v1/fulfill/o7')) return order()
    return reply(200, {})
  })
  render(<StationProvider><MemoryRouter initialEntries={['/fulfill?guidedUnpack=o7']}><I18nextProvider i18n={i18nFor(lng)}><ToastProvider>
    <Routes><Route path="/fulfill" element={<><FulfillRoute /><Where /></>} /></Routes>
  </ToastProvider></I18nextProvider></MemoryRouter></StationProvider>)
}

beforeEach(() => { setAccessToken(token('owner')) })
afterEach(() => { cleanup(); clearAccessToken(); vi.unstubAllGlobals() })

describe('Pick & Pack — order that cannot be loaded', () => {
  test('l1 404 from the alert link → message + Back, no crash', async () => {
    const user = userEvent.setup()
    renderAt('en', () => reply(404, { code: 'NOT_FOUND', message: 'Order not found' }))
    expect(await screen.findByTestId('fulfill-order-load-failed')).toHaveTextContent("This order couldn't be opened.")
    await user.click(screen.getByRole('button', { name: 'Back to Queue' }))
    expect(screen.getByTestId('where')).not.toHaveTextContent('guidedUnpack')
  })

  test('l2 a body with no items → the same message (AR)', async () => {
    renderAt('ar', () => reply(200, {}))
    expect(await screen.findByTestId('fulfill-order-load-failed')).toHaveTextContent('تعذّر فتح هذا الطلب.')
  })
})
