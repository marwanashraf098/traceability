import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { render, screen, within } from '@testing-library/react'
import { I18nextProvider } from 'react-i18next'
import PortalApp from '../portal/PortalApp'
import { createPortalI18n, PortalLang } from '../portal/i18n'
import type { LookupLine, LookupResult, PortalConfig } from '../portal/api'
import { normalizeMobile, refundErrors, refundHint, validEgyptianIban, EMPTY_REFUND_FIELDS } from '../portal/refund'

// P2 — the portal's "How should we refund you?" step (design/Traced_portal_refund_method_dc.html, b).

const BASE: PortalConfig = {
  storeName: 'The Snouts', returnWindowDays: 14, reasonCodes: ['wrong_size', 'other'],
  logoUrl: null, brandColor: '#0F766E', policyText: null, autoApprove: false, pickupBooking: false,
}

const SHIRT: LookupLine = {
  variantId: 'v-white-m', productTitle: 'Linen Shirt', variantTitle: 'White / M', imageUrl: null,
  deliveredQuantity: 1, returnableQuantity: 1, nonReturnable: false,
  optionAxes: [{ name: null, kind: 'colour' }, { name: null, kind: 'size' }], currentOptions: ['White', 'M'],
  exchangeOptions: [{ variantId: 'v-white-l', title: 'White / L', options: ['White', 'L'], inStock: true }],
}

const LOOKUP: LookupResult = { token: 'tok.sig', orderNumber: '#1047', deliveredAt: '2026-09-18T10:00:00Z', lines: [SHIRT], pickup: null }

function respond(status: number, body?: unknown) {
  return Promise.resolve({
    ok: status >= 200 && status < 300, status,
    headers: { get: (k: string) => (k.toLowerCase() === 'content-type' && body !== undefined ? 'application/json' : null) },
    json: async () => structuredClone(body),
    text: async () => JSON.stringify(body ?? ''),
  })
}

let config: PortalConfig
let calls: Array<{ url: string; init: RequestInit }>

beforeEach(() => {
  config = BASE
  calls = []
  localStorage.clear()
  vi.stubGlobal('fetch', vi.fn((url: string, init: RequestInit = {}) => {
    calls.push({ url, init })
    if (url.endsWith('/config')) return respond(200, config)
    if (url.endsWith('/lookup')) return respond(200, LOOKUP)
    if (url.endsWith('/requests')) return respond(201, { reference: 'RR-7K3F9M', status: 'requested' })
    return respond(404)
  }))
})

afterEach(() => {
  vi.unstubAllGlobals()
  document.documentElement.dir = 'ltr'
  document.documentElement.lang = 'en'
})

function submitted() {
  const c = calls.find(x => x.url.endsWith('/requests'))!
  return JSON.parse(c.init.body as string)
}

async function toItems(lang: PortalLang = 'en') {
  const user = userEvent.setup()
  render(<I18nextProvider i18n={createPortalI18n(lang)}><PortalApp slug="thesnouts" /></I18nextProvider>)
  await screen.findByRole('heading', { name: lang === 'ar' ? 'ابدأ الإرجاع' : 'Start a return' })
  await user.type(screen.getByLabelText(lang === 'ar' ? 'رقم الطلب' : 'Order number'), '#1047')
  await user.type(screen.getByLabelText(lang === 'ar' ? 'رقم الهاتف' : 'Phone number'), '010 1234 5678')
  await user.click(screen.getByRole('button', { name: lang === 'ar' ? 'ابحث عن طلبي' : 'Find my order' }))
  return user
}

async function chooseShirtAndContinue(user: ReturnType<typeof userEvent.setup>) {
  await screen.findByRole('heading', { name: 'What would you like to return?' })
  const card = screen.getByText('Linen Shirt').closest('section')!
  await user.click(within(card).getByRole('button', { name: 'Increase quantity' }))
  await user.selectOptions(within(card).getByLabelText('Why are you returning it?'), 'wrong_size')
  await user.click(screen.getByRole('button', { name: 'Continue' }))
}

describe('refund method step', () => {
  test('no methods on: skipped — items → Almost done, step 2 of 2, nothing sent', async () => {
    const user = await toItems()
    expect(screen.getByText('Step 1 of 2')).toBeInTheDocument()
    await chooseShirtAndContinue(user)
    await screen.findByRole('heading', { name: 'Almost done' })
    expect(screen.getByText('Step 2 of 2')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Send return request' }))
    await screen.findByText('RR-7K3F9M')
    expect(submitted()).not.toHaveProperty('refundMethod')
    expect(submitted()).not.toHaveProperty('refundDetails')
  })

  test('methods on: required, wallet number validated, summary on Almost done, sent in the body', async () => {
    config = { ...BASE, refundMethods: ['instapay', 'wallet', 'cash'] }
    const user = await toItems()
    expect(screen.getByText('Step 1 of 3')).toBeInTheDocument()
    await chooseShirtAndContinue(user)

    await screen.findByRole('heading', { name: 'How should we refund you?' })
    expect(screen.getByText('Step 2 of 3')).toBeInTheDocument()
    expect(screen.getByText('The Snouts sends your refund after your return arrives.')).toBeInTheDocument()
    expect(screen.getByText('Shared only with The Snouts. Deleted 30 days after your refund.')).toBeInTheDocument()
    const methods = within(screen.getByTestId('refund-methods')).getAllByRole('radio')
    expect(methods).toHaveLength(3)                                   // only the offered ones, in order

    await user.click(screen.getByRole('button', { name: 'Continue' }))  // nothing chosen
    expect(screen.getByText("Choose how you'd like your refund")).toBeInTheDocument()

    await user.click(screen.getByRole('radio', { name: /Mobile wallet/ }))
    await user.click(screen.getByRole('radio', { name: 'Vodafone Cash' }))
    await user.type(screen.getByLabelText('Wallet number'), '0150 123 45')
    await user.click(screen.getByRole('button', { name: 'Continue' }))
    expect(screen.getByText('Enter an Egyptian mobile number, like 010 1234 5678.')).toBeInTheDocument()
    expect(screen.getByLabelText('Wallet number')).toHaveAttribute('aria-invalid', 'true')

    await user.clear(screen.getByLabelText('Wallet number'))
    await user.type(screen.getByLabelText('Wallet number'), '+20 10 1234 4521')
    await user.click(screen.getByRole('button', { name: 'Continue' }))
    await screen.findByRole('heading', { name: 'Almost done' })
    expect(screen.getByText('Step 3 of 3')).toBeInTheDocument()
    expect(screen.getByTestId('refund-summary')).toHaveTextContent('Refund to Mobile wallet · ••••4521')

    await user.click(screen.getByRole('button', { name: 'Send return request' }))
    await screen.findByText('RR-7K3F9M')
    expect(submitted()).toMatchObject({
      refundMethod: 'wallet', refundDetails: { provider: 'vodafone_cash', walletNumber: '+20 10 1234 4521' },
    })
  })

  test('cash: no fields; plain exchange skips the step; fallback exchange asks with its own heading', async () => {
    config = { ...BASE, exchangesEnabled: true, refundMethods: ['cash'] }
    const user = await toItems()
    await screen.findByRole('heading', { name: 'What would you like to do?' })
    await user.click(screen.getByRole('radio', { name: /Exchange/ }))
    await user.click(within(screen.getAllByTestId('exchange-line')[0]).getByRole('radio'))
    await user.selectOptions(screen.getByLabelText('Why are you exchanging it?'), 'wrong_size')
    await user.click(screen.getByRole('button', { name: 'Continue' }))
    await screen.findByRole('heading', { name: 'Choose your new size or colour' })
    expect(screen.getByText('Step 2 of 4')).toBeInTheDocument()      // fallback pre-ticked → asked
    await user.click(within(screen.getByRole('group', { name: 'Size' })).getByRole('radio', { name: 'L' }))
    await user.click(screen.getByRole('button', { name: 'Continue' }))

    await screen.findByRole('heading', { name: "If we can't exchange it" })
    expect(screen.getByText('Step 3 of 4')).toBeInTheDocument()
    await user.click(screen.getByRole('radio', { name: /Cash/ }))
    expect(screen.queryByRole('textbox')).toBeNull()
    await user.click(screen.getByRole('button', { name: 'Continue' }))
    await screen.findByRole('heading', { name: 'Almost done' })
    expect(screen.getByText('Step 4 of 4')).toBeInTheDocument()
    expect(screen.getByTestId('refund-summary')).toHaveTextContent('Refund to Cash')

    // Back to the size step, untick the fallback → the refund step disappears.
    await user.click(screen.getByRole('button', { name: 'Back' }))
    await user.click(screen.getByRole('button', { name: 'Back' }))
    await screen.findByRole('heading', { name: 'Choose your new size or colour' })
    await user.click(screen.getByRole('checkbox', { name: /refund instead/ }))
    expect(screen.getByText('Step 2 of 3')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Continue' }))
    await screen.findByRole('heading', { name: 'Almost done' })
    expect(screen.queryByTestId('refund-summary')).toBeNull()
    await user.click(screen.getByRole('button', { name: 'Send return request' }))
    await screen.findByRole('heading', { name: 'Exchange requested' })
    expect(submitted()).not.toHaveProperty('refundMethod')
  })

  test('AR / RTL: bank transfer with a failing IBAN shows the Arabic error; privacy line names the store', async () => {
    config = { ...BASE, refundMethods: ['bank_transfer'] }
    const user = userEvent.setup()
    render(<I18nextProvider i18n={createPortalI18n('ar')}><PortalApp slug="thesnouts" /></I18nextProvider>)
    await screen.findByRole('heading', { name: 'ابدأ الإرجاع' })
    await user.type(screen.getByLabelText('رقم الطلب'), '#1047')
    await user.type(screen.getByLabelText('رقم الهاتف'), '010 1234 5678')
    await user.click(screen.getByRole('button', { name: 'ابحث عن طلبي' }))
    await screen.findByRole('heading', { name: 'ما الذي تريد إرجاعه؟' })
    const card = screen.getByText('Linen Shirt').closest('section')!
    await user.click(within(card).getAllByRole('button')[1])
    await user.selectOptions(within(card).getByRole('combobox'), 'wrong_size')
    await user.click(screen.getByRole('button', { name: 'متابعة' }))

    await screen.findByRole('heading', { name: 'كيف تريد استرداد المبلغ؟' })
    expect(document.documentElement.dir).toBe('rtl')
    expect(screen.getByText('تُشارك مع The Snouts فقط. وتُحذف بعد 30 يومًا من استرداد المبلغ.')).toBeInTheDocument()
    await user.click(screen.getByRole('radio', { name: /تحويل بنكي/ }))
    await user.type(screen.getByLabelText('اسم صاحب الحساب'), 'أحمد كمال')
    await user.type(screen.getByLabelText('اسم البنك'), 'البنك الأهلي')
    await user.type(screen.getByLabelText('رقم الآيبان أو رقم الحساب'), 'EG38 0019 0005 0000 0000 2631 8000 3')
    await user.click(screen.getByRole('button', { name: 'متابعة' }))
    expect(screen.getByText('رقم الآيبان غير صحيح. راجعه، أو اكتب رقم حسابك بدلًا منه.')).toBeInTheDocument()
    expect(screen.getByLabelText('رقم الآيبان أو رقم الحساب')).toHaveAttribute('dir', 'ltr')
  })
})

describe('validation mirror (same vectors as RefundDetailsTest)', () => {
  test('mobile, IBAN mod-97, account, InstaPay, hint', () => {
    expect(normalizeMobile('+20 10 1234 5678')).toBe('01012345678')
    expect(normalizeMobile('00201012345678')).toBe('01012345678')
    expect(normalizeMobile('010-1234-5678')).toBe('01012345678')
    expect(normalizeMobile('01312345678')).toBeNull()
    expect(normalizeMobile('0101234567')).toBeNull()
    expect(validEgyptianIban('EG380019000500000000263180002')).toBe(true)
    expect(validEgyptianIban('eg38 0019 0005 0000 0000 2631 8000 2')).toBe(true)
    expect(validEgyptianIban('EG380019000500000000263180003')).toBe(false)
    const bank = { ...EMPTY_REFUND_FIELDS, holderName: 'Mona', bankName: 'CIB' }
    expect(refundErrors('bank_transfer', { ...bank, account: '123456' })).toEqual({})
    expect(refundErrors('bank_transfer', { ...bank, account: '12345' })).toEqual({ account: 'accountInvalid' })
    expect(refundErrors('bank_transfer', { ...bank, account: 'EG380019000500000000263180003' })).toEqual({ account: 'ibanInvalid' })
    expect(refundErrors('bank_transfer', { ...bank, holderName: 'x'.repeat(101), account: '123456' })).toEqual({ holderName: 'holderLong' })
    expect(refundErrors('instapay', { ...EMPTY_REFUND_FIELDS, instapay: 'Ahmed.K@InstaPay' })).toEqual({})
    expect(refundErrors('instapay', { ...EMPTY_REFUND_FIELDS, instapay: 'ahmed@gmail.com' })).toEqual({ instapay: 'instapayInvalid' })
    expect(refundErrors('wallet', { ...EMPTY_REFUND_FIELDS, walletNumber: '01012345678' })).toEqual({ provider: 'providerMissing' })
    expect(refundErrors('cash', EMPTY_REFUND_FIELDS)).toEqual({})
    expect(refundHint('bank_transfer', { ...bank, account: 'EG38 0019 0005 0000 0000 2631 8000 2' })).toBe('••••0002')
    expect(refundHint('instapay', { ...EMPTY_REFUND_FIELDS, instapay: 'ahmed.k@instapay' })).toBe('••••ed.k')
  })
})
