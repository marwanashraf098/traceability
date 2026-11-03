import { test, expect, vi, beforeEach, afterEach } from 'vitest'
import i18next from 'i18next'
import { initReactI18next, I18nextProvider } from 'react-i18next'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import en from '../locales/en.json'
import ar from '../locales/ar.json'
import { ToastProvider } from '../components/ui'
import { StationProvider } from '../components/StationProvider'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import { resetCapabilitiesCache } from '../capabilities'
import { clearAccessToken, setAccessToken } from '../auth'
import { PrintedNotPacked } from '../pages/fulfill/PackLists'
import type { NotPackedRow } from '../api'

// S4 — Printed but not packed: a printed waybill that is no longer the order's live one shows "Reprint needed" with
// the new waybill, in EN and AR; every other status renders as before.

const ROWS: NotPackedRow[] = [
  { orderId: 'o-1', orderNumber: '#17700', customerName: 'Habiba Ali', shipmentId: 's-1', trackingNumber: '7500000001',
    batchId: 'b-1', batchNo: 3, batchPrintedAt: '2026-10-31T10:00:00Z', status: 'reprint_needed', packerName: null,
    setAsideReason: null, exceptionType: null, subjectKey: null, reprintTrackingNumber: '7500000002' },
  { orderId: 'o-2', orderNumber: '#17701', customerName: 'Mona Adel', shipmentId: 's-2', trackingNumber: '7500000011',
    batchId: 'b-1', batchNo: 3, batchPrintedAt: '2026-10-31T10:00:00Z', status: 'waiting', packerName: null,
    setAsideReason: null, exceptionType: null, subjectKey: null, reprintTrackingNumber: null },
]

const fakeJwt = (role: string) => `h.${btoa(JSON.stringify({ role }))}.s`

function json(data: unknown) {
  return Promise.resolve({
    ok: true, status: 200, statusText: '',
    headers: { get: (k: string) => (k.toLowerCase() === 'content-type' ? 'application/json' : null) },
    json: async () => structuredClone(data), text: async () => JSON.stringify(data),
  })
}

beforeEach(() => {
  vi.stubGlobal('localStorage', { getItem: vi.fn().mockReturnValue(null), setItem: vi.fn(), removeItem: vi.fn() })
  resetCapabilitiesCache()
  setAccessToken(fakeJwt('owner'))
  stubFetchWithShellDefaults(vi.fn((url: string) => json(url.includes('/printed-not-packed') ? ROWS : {})), {
    me: { name: 'Ahmed', role: 'owner', scanHelpers: false, demoMode: false, genericCourier: false,
          flexibleLabeling: false, places: false } })
})
afterEach(() => { clearAccessToken(); vi.unstubAllGlobals() })

function renderIn(lang: 'en' | 'ar') {
  const i18n = i18next.createInstance()
  i18n.use(initReactI18next).init({
    lng: lang, fallbackLng: 'en', initImmediate: false,
    resources: { en: { translation: en }, ar: { translation: ar } }, interpolation: { escapeValue: false },
  })
  return render(
    <StationProvider>
      <MemoryRouter>
        <I18nextProvider i18n={i18n}>
          <ToastProvider><PrintedNotPacked refreshKey={0} /></ToastProvider>
        </I18nextProvider>
      </MemoryRouter>
    </StationProvider>
  )
}

test('pr1 en: Reprint needed names the new waybill; a waiting row is unchanged', async () => {
  renderIn('en')
  expect(await screen.findByTestId('status-reprint_needed')).toHaveTextContent('Reprint needed · new waybill 7500000002')
  expect(screen.getByTestId('status-waiting')).toHaveTextContent('Waiting')
  expect(screen.queryByText('reprint_needed')).toBeNull()
})

test('pr2 ar: the same rows in Arabic', async () => {
  renderIn('ar')
  expect(await screen.findByTestId('status-reprint_needed')).toHaveTextContent('يلزم إعادة الطباعة · البوليصة الجديدة 7500000002')
  expect(screen.queryByText(/statusReprintNeeded/)).toBeNull()
})
