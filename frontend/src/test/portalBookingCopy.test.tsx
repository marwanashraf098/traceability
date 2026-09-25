import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { render, screen, within } from '@testing-library/react'
import { I18nextProvider } from 'react-i18next'
import PortalApp from '../portal/PortalApp'
import { createPortalI18n, PortalLang } from '../portal/i18n'
import type { LookupResult, PortalConfig } from '../portal/api'

// Returns portal Step 4c-3 — customer copy when the store books Bosta pickups (config
// pickupBooking true): P1 note, P4 "what happens next", and the auto-approved P4 lead.
// Fakes match PortalService (config, lookup with `pickup`, requests 201 {reference,status}).

const CONFIG: PortalConfig = {
  storeName: 'Nour Studio', returnWindowDays: 14, reasonCodes: ['wrong_size'],
  logoUrl: null, brandColor: '#1F5C4A', policyText: null, autoApprove: true, pickupBooking: true,
}

const LOOKUP: LookupResult = {
  token: 'tok', orderNumber: '#1047', deliveredAt: '2026-09-18T10:00:00Z',
  lines: [{ variantId: 'v1', productTitle: 'Linen Shirt', variantTitle: null, imageUrl: null,
    deliveredQuantity: 1, returnableQuantity: 1, nonReturnable: false }],
  pickup: {
    cityId: 'c', cityName: 'Cairo', cityNameAr: 'القاهرة', preselectedDistrictId: 'd1',
    districts: [{ id: 'd1', name: 'Nasr City - 7th District', nameAr: 'مدينة نصر - الحي السابع', zoneName: 'Nasr City', zoneNameAr: 'مدينة نصر' }],
  },
}

let submitStatus: 'approved' | 'requested'

function respond(status: number, body?: unknown) {
  return Promise.resolve({
    ok: status >= 200 && status < 300, status,
    headers: { get: (k: string) => (k.toLowerCase() === 'content-type' ? 'application/json' : null) },
    json: async () => structuredClone(body), text: async () => JSON.stringify(body),
  })
}

beforeEach(() => {
  submitStatus = 'approved'
  localStorage.clear()
  vi.stubGlobal('fetch', vi.fn((url: string) => {
    if (url.endsWith('/config')) return respond(200, CONFIG)
    if (url.endsWith('/lookup')) return respond(200, LOOKUP)
    if (url.endsWith('/requests')) return respond(201, { reference: 'RR-7K3F9M', status: submitStatus })
    return respond(404)
  }))
})

afterEach(() => {
  vi.unstubAllGlobals()
  document.documentElement.dir = 'ltr'
  document.documentElement.lang = 'en'
})

function renderPortal(lang: PortalLang) {
  return render(<I18nextProvider i18n={createPortalI18n(lang)}><PortalApp slug="nourstudio" /></I18nextProvider>)
}

async function sendRequest(lang: PortalLang) {
  const user = userEvent.setup()
  renderPortal(lang)
  await screen.findByRole('heading', { level: 1 })
  await user.type(document.getElementById('pp-order')!, '#1047')
  await user.type(document.getElementById('pp-phone')!, '01012345678')
  await user.click(document.querySelector('form button[type=submit]') as HTMLElement)
  await screen.findAllByTestId('portal-line')
  const line = screen.getAllByTestId('portal-line')[0]
  await user.click(within(line).getAllByRole('button')[1])
  await user.selectOptions(within(line).getByRole('combobox'), 'wrong_size')
  await user.click(document.querySelector('.pp-bar button') as HTMLElement)
  await user.click(await screen.findByRole('button', { name: lang === 'ar' ? 'إرسال طلب الإرجاع' : 'Send return request' }))
  await screen.findByTestId('reference')
}

describe('portal copy when Bosta pickups are booked', () => {
  test('P1 note (EN / AR)', async () => {
    renderPortal('en')
    expect(await screen.findByText(/After approval, a Bosta courier collects the item from the area you choose\./)).toBeInTheDocument()
  })

  test('P1 note in Arabic', async () => {
    renderPortal('ar')
    expect(await screen.findByText(/بعد الموافقة، يستلم مندوب بوسطة المنتج من المنطقة التي تختارها\./)).toBeInTheDocument()
  })

  test('auto-approved P4: approved + booking lead, and the collect step', async () => {
    await sendRequest('en')
    expect(screen.getByTestId('sent-lead')).toHaveTextContent("Your return is approved. We're booking a Bosta courier to collect it.")
    expect(screen.getByText('After approval, a Bosta courier collects the item from the area you chose.')).toBeInTheDocument()
  })

  test('auto-approved P4 in Arabic', async () => {
    await sendRequest('ar')
    expect(screen.getByTestId('sent-lead')).toHaveTextContent('تمت الموافقة على طلب الإرجاع. نحجز الآن مندوب بوسطة لاستلامه.')
    expect(screen.getByText('بعد الموافقة، يستلم مندوب بوسطة المنتج من المنطقة التي اخترتها.')).toBeInTheDocument()
  })

  test('not auto-approved: the review lead stays, the booking step shows', async () => {
    submitStatus = 'requested'
    await sendRequest('en')
    expect(screen.getByTestId('sent-lead')).not.toHaveTextContent('booking a Bosta courier')
    expect(screen.getByText('After approval, a Bosta courier collects the item from the area you chose.')).toBeInTheDocument()
  })
})
