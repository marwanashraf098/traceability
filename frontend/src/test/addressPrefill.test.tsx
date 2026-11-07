import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import userEvent from '@testing-library/user-event'
import { act, cleanup, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import i18next from 'i18next'
import { I18nextProvider, initReactI18next } from 'react-i18next'
import en from '../locales/en.json'
import ar from '../locales/ar.json'
import { setAccessToken, clearAccessToken } from '../auth'
import { AddressStep, PREFILL_TIMEOUT_MS } from '../pages/onboarding/OnboardingGate'
import { governorateFor, prefillFrom } from '../pages/onboarding/addressPrefill'
import type { ShopifyLocationOption } from '../api'

/**
 * Onboarding O2b — the warehouse-address step pre-filled from the store's own Shopify locations.
 *   p1  one location: governorate / city / street / building filled, the note names it; nothing saved yet
 *   p2  several: "Fill from" picker (fulfils-online first, as the server orders them); choosing one refills
 *   p3  governorate by code, else by exact name (EN / AR); unmatched (unknown name, another country) stays empty with
 *       no city; zip / phone never used
 *   p3c Shopify's legacy codes (merged in 2011): SU → Giza with "6th of October" when it's in Giza's area list, HU →
 *       Cairo with "Helwan" when it's in Cairo's; otherwise the governorate with Shopify's own city
 *   p4  a failed, empty or slow (> 3 s) read → today's empty form, no note, no error
 *   p5  nothing is saved without "Save and continue"; the save sends what's in the fields (the existing path)
 *   p6  only the locations the server offered are shown (it never offers Traced Main or inactive ones — backend
 *       ShopifyAddressPrefillTest); the banner mode and a saved address never read Shopify
 *   p7  Arabic
 */

const PROVINCES = [
  { code: 'C', nameEn: 'Cairo', nameAr: 'القاهرة', areas: [{ nameEn: 'Nasr City', nameAr: 'مدينة نصر' }] },
  { code: 'GZ', nameEn: 'Giza', nameAr: 'الجيزة', areas: [] },
]

function loc(over: Partial<ShopifyLocationOption> = {}): ShopifyLocationOption {
  return { id: 'gid://shopify/Location/1', name: 'Shop', fulfillsOnlineOrders: true, address1: '12 Abbas El Akkad',
    address2: 'Building 4', city: 'Nasr City', province: 'Cairo', provinceCode: 'C', countryCode: 'EG', zip: '11371',
    phone: '+201000000000', ...over }
}

function i18nFor(lng: 'en' | 'ar') {
  const inst = i18next.createInstance()
  inst.use(initReactI18next).init({ lng, fallbackLng: 'en', initImmediate: false,
    resources: { en: { translation: en }, ar: { translation: ar } }, interpolation: { escapeValue: false } })
  return inst
}
const json = (status: number, body: unknown) => Promise.resolve({ ok: status < 300, status, statusText: '',
  headers: { get: (k: string) => (k.toLowerCase() === 'content-type' ? 'application/json' : null) },
  json: async () => structuredClone(body) })

let calls: { url: string; method: string; body?: unknown }[]
function stub(locations: (() => Promise<unknown>) | ShopifyLocationOption[] | 'fail', address: unknown = null) {
  vi.stubGlobal('fetch', (url: string, init: RequestInit = {}) => {
    const method = init.method ?? 'GET'
    calls.push({ url, method, body: init.body ? JSON.parse(String(init.body)) : undefined })
    if (url.endsWith('/setup/address/shopify-locations')) {
      if (locations === 'fail') return json(500, { message_en: 'x' })
      if (typeof locations === 'function') return locations()
      return json(200, { locations })
    }
    if (url.endsWith('/setup/address') && method === 'PUT') return json(200, { setupStep: null })
    if (url.endsWith('/setup/address')) return json(200, { address, provinces: PROVINCES })
    return json(200, {})
  })
}

const onSaved = vi.fn()
function renderStep(mode: 'gate' | 'banner' = 'gate', lng: 'en' | 'ar' = 'en') {
  return render(<MemoryRouter><I18nextProvider i18n={i18nFor(lng)}>
    <AddressStep mode={mode} onSaved={onSaved} shopDomain="shop.myshopify.com" />
  </I18nextProvider></MemoryRouter>)
}
const field = (id: string) => document.getElementById(id) as HTMLInputElement | HTMLSelectElement
const shopifyReads = () => calls.filter(c => c.url.includes('/shopify-locations')).length
const writes = () => calls.filter(c => c.method !== 'GET')

beforeEach(() => {
  calls = []; onSaved.mockReset()
  setAccessToken('h.' + btoa(JSON.stringify({ role: 'owner', tenant: 't', exp: Math.floor(Date.now() / 1000) + 3600 })) + '.s')
})
afterEach(() => { cleanup(); clearAccessToken(); vi.unstubAllGlobals(); vi.useRealTimers() })

describe('p1 / p2 pre-fill', () => {
  test('p1 one location fills the fields and names it; nothing saved', async () => {
    stub([loc()])
    renderStep()
    await screen.findByTestId('onboarding-step-address')
    expect(field('setupGovernorate').value).toBe('C')
    expect(field('setupCity').value).toBe('Nasr City')
    expect(field('setupStreet').value).toBe('12 Abbas El Akkad')
    expect(field('setupBuilding').value).toBe('Building 4')
    expect(field('setupFloor').value).toBe('')
    expect(screen.getByTestId('setup-address-prefilled')).toHaveTextContent('We filled this in from your Shopify location “Shop”. Just check it.')
    expect(screen.queryByTestId('setup-address-fill-from')).toBeNull()
    expect(writes()).toEqual([])
  })

  test('p2 several: the picker; choosing another refills; fields stay editable', async () => {
    const user = userEvent.setup()
    stub([loc(), loc({ id: 'gid://shopify/Location/2', name: 'Giza store', fulfillsOnlineOrders: false,
      address1: '3 Haram St', address2: null, city: 'Haram', province: 'Giza', provinceCode: 'GZ' })])
    renderStep()
    const picker = await screen.findByTestId('setup-address-fill-from')
    const select = picker.querySelector('select')!
    expect(Array.from(select.options).map(o => o.textContent)).toEqual(['Shop', 'Giza store'])
    expect(select.value).toBe('gid://shopify/Location/1')
    await user.selectOptions(select, 'gid://shopify/Location/2')
    expect(field('setupGovernorate').value).toBe('GZ')
    expect(field('setupCity').value).toBe('Haram')
    expect(field('setupStreet').value).toBe('3 Haram St')
    expect(field('setupBuilding').value).toBe('')
    expect(screen.getByTestId('setup-address-prefilled')).toHaveTextContent('“Giza store”')
    await user.clear(field('setupStreet') as HTMLInputElement)
    await user.type(field('setupStreet') as HTMLInputElement, '7 New St')
    expect(field('setupStreet').value).toBe('7 New St')
    expect(writes()).toEqual([])
  })
})

describe('p3 the governorate mapping', () => {
  test('by code, else exact name (EN / AR); otherwise empty, never invented', () => {
    expect(governorateFor(loc({ provinceCode: 'GZ' }), PROVINCES)).toBe('GZ')
    expect(governorateFor(loc({ provinceCode: null, province: ' giza ' }), PROVINCES)).toBe('GZ')
    expect(governorateFor(loc({ provinceCode: null, province: 'القاهرة' }), PROVINCES)).toBe('C')
    expect(governorateFor(loc({ provinceCode: null, province: 'Cairo Governorate' }), PROVINCES)).toBe('')
    expect(governorateFor(loc({ countryCode: 'AE', provinceCode: 'C', province: 'Cairo' }), PROVINCES)).toBe('')
    const a = prefillFrom(loc({ provinceCode: 'XX', province: 'Atlantis' }), PROVINCES)
    expect(a).toEqual({ province: '', city: '', street: '12 Abbas El Akkad', building: 'Building 4' })
    expect(JSON.stringify(prefillFrom(loc(), PROVINCES))).not.toMatch(/11371|\+20/)
  })

  test('an unmatched governorate stays empty on the form (no city), the rest is filled', async () => {
    stub([loc({ provinceCode: 'XX', province: 'Atlantis', city: 'Sheikh Zayed' })])
    renderStep()
    await screen.findByTestId('onboarding-step-address')
    expect(field('setupGovernorate').value).toBe('')
    expect(field('setupCity').value).toBe('')
    expect(field('setupStreet').value).toBe('12 Abbas El Akkad')
    expect(screen.getByTestId('setup-address-prefilled')).toBeInTheDocument()
  })
})

describe('p3c legacy codes', () => {
  const WITH_AREAS = [
    { code: 'C', nameEn: 'Cairo', nameAr: 'القاهرة', areas: [{ nameEn: 'Nasr City', nameAr: 'مدينة نصر' }, { nameEn: 'Helwan', nameAr: 'حلوان' }] },
    { code: 'GZ', nameEn: 'Giza', nameAr: 'الجيزة', areas: [{ nameEn: 'Sheikh Zayed', nameAr: 'الشيخ زايد' }, { nameEn: '6th of October', nameAr: '6 أكتوبر' }] },
  ]

  test('SU → Giza with "6th of October" from the list (Arabic name on an Arabic page)', () => {
    const su = loc({ provinceCode: 'SU', province: '6th of October', city: 'October City' })
    expect(prefillFrom(su, WITH_AREAS)).toMatchObject({ province: 'GZ', city: '6th of October' })
    expect(prefillFrom(su, WITH_AREAS, 'ar')).toMatchObject({ province: 'GZ', city: '6 أكتوبر' })
    // Not in Giza's list → Giza, with Shopify's own city.
    expect(prefillFrom(su, PROVINCES)).toMatchObject({ province: 'GZ', city: 'October City' })
  })

  test('HU → Cairo with "Helwan" from the list; otherwise Cairo with Shopify\'s city', () => {
    const hu = loc({ provinceCode: 'HU', province: 'Helwan', city: 'Helwan City' })
    expect(prefillFrom(hu, WITH_AREAS)).toMatchObject({ province: 'C', city: 'Helwan' })
    expect(prefillFrom(hu, WITH_AREAS, 'ar')).toMatchObject({ province: 'C', city: 'حلوان' })
    expect(prefillFrom(hu, PROVINCES)).toMatchObject({ province: 'C', city: 'Helwan City' })
  })

  test('on the form: an SU location fills Giza and 6th of October', async () => {
    vi.stubGlobal('fetch', (url: string, init: RequestInit = {}) => {
      calls.push({ url, method: init.method ?? 'GET' })
      if (url.endsWith('/shopify-locations')) return json(200, { locations: [loc({ provinceCode: 'SU', province: '6th of October', city: 'x' })] })
      return json(200, { address: null, provinces: WITH_AREAS })
    })
    renderStep()
    await screen.findByTestId('setup-address-prefilled')
    expect(field('setupGovernorate').value).toBe('GZ')
    expect(field('setupCity').value).toBe('6th of October')
  })
})

describe('p3b nothing to fill', () => {
  test('a location whose address maps to nothing → no note, empty form', async () => {
    stub([loc({ address1: null, address2: null, city: null, provinceCode: null, province: 'Somewhere' })])
    renderStep()
    await screen.findByTestId('onboarding-step-address')
    expect(field('setupGovernorate').value).toBe('')
    expect(field('setupStreet').value).toBe('')
    expect(screen.queryByTestId('setup-address-prefilled')).toBeNull()
  })
})

describe('p4 failure, empty, timeout', () => {
  test.each([['a failed read', 'fail' as const], ['no locations', [] as ShopifyLocationOption[]]])('%s → the empty form, silently', async (_n, locs) => {
    stub(locs)
    renderStep()
    await screen.findByTestId('onboarding-step-address')
    expect(field('setupGovernorate').value).toBe('')
    expect(field('setupStreet').value).toBe('')
    expect(screen.queryByTestId('setup-address-prefilled')).toBeNull()
    expect(screen.queryByRole('alert')).toBeNull()
  })

  test('a read slower than 3 s → the empty form at 3 s; a late answer changes nothing', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    let release: (v: unknown) => void = () => {}
    stub(() => new Promise(r => { release = r }))
    renderStep()
    await act(async () => { await vi.advanceTimersByTimeAsync(PREFILL_TIMEOUT_MS - 100) })
    expect(screen.queryByTestId('onboarding-step-address')).toBeNull()
    await act(async () => { await vi.advanceTimersByTimeAsync(200) })
    expect(screen.getByTestId('onboarding-step-address')).toBeInTheDocument()
    expect(field('setupStreet').value).toBe('')
    await act(async () => { release(await json(200, { locations: [loc()] })) })
    expect(field('setupStreet').value).toBe('')
    expect(screen.queryByTestId('setup-address-prefilled')).toBeNull()
  })
})

describe('p5 / p6 saving and scope', () => {
  test('p5 nothing saved until "Save and continue", which sends the fields through the existing save', async () => {
    const user = userEvent.setup()
    stub([loc()])
    renderStep()
    await screen.findByTestId('setup-address-prefilled')
    expect(writes()).toEqual([])
    await user.click(screen.getByRole('button', { name: en.setupGate.address.finish }))
    await waitFor(() => expect(onSaved).toHaveBeenCalledWith(null))
    expect(writes()).toEqual([{ url: expect.stringMatching(/\/setup\/address$/), method: 'PUT',
      body: { provinceCode: 'C', city: 'Nasr City', street: '12 Abbas El Akkad', building: 'Building 4', floor: null } }])
  })

  test('p6 a saved address and the banner mode never read Shopify', async () => {
    stub([loc()], { provinceCode: 'GZ', city: 'Dokki', street: '1 Tahrir', building: null, floor: null })
    renderStep()
    await screen.findByTestId('onboarding-step-address')
    expect(field('setupStreet').value).toBe('1 Tahrir')
    expect(shopifyReads()).toBe(0)
    cleanup(); calls = []
    stub([loc()])
    renderStep('banner')
    await screen.findByTestId('onboarding-step-address')
    expect(shopifyReads()).toBe(0)
    expect(screen.queryByTestId('setup-address-prefilled')).toBeNull()
  })
})

describe('p7 Arabic', () => {
  test('the note and the picker label in Arabic', async () => {
    stub([loc({ name: 'المحل' }), loc({ id: 'gid://shopify/Location/2', name: 'الفرع' })])
    renderStep('gate', 'ar')
    expect(await screen.findByTestId('setup-address-prefilled')).toHaveTextContent('ملأنا هذه البيانات من موقعك في Shopify «المحل». راجعها فقط.')
    expect(screen.getByTestId('setup-address-fill-from')).toHaveTextContent('املأ من:')
  })
})
