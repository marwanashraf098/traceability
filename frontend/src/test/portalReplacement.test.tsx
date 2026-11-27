import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { render, screen, within } from '@testing-library/react'
import { I18nextProvider } from 'react-i18next'
import PortalApp, { replaceOnly } from '../portal/PortalApp'
import { createPortalI18n } from '../portal/i18n'
import type { LookupLine, LookupResult, PortalConfig } from '../portal/api'

// Defect replacement (V246) — "Replace with the same item" in the returns portal.
//   defect     — after "Damaged or faulty": the same-item option; chosen → review "→ Replacement (same item)", the
//                submission's replacement is the customer's own variant, "Replacement requested"
//   other      — after "Wrong size": no same-item option (the size picker as before)
//   soldOut    — the same item out of stock: disabled with the reason; another size can still be picked
//   oneSize    — a product with no other size or colour: selectable for a defect reason only; no size picker
//   arabic     — the same flow in Arabic, right to left

const CONFIG: PortalConfig = {
  storeName: 'Nour Studio', returnWindowDays: 30,
  reasonCodes: ['wrong_size', 'damaged', 'not_as_pictured', 'wrong_item', 'changed_mind', 'other'],
  logoUrl: null, brandColor: '#1F5C4A', policyText: null, autoApprove: false, pickupBooking: false,
  exchangesEnabled: true, replacementReasons: ['damaged', 'wrong_item'],
}

const SHIRT: LookupLine = {
  variantId: 'v-white-m', productTitle: 'Linen Shirt', variantTitle: 'White / M', imageUrl: null,
  deliveredQuantity: 1, returnableQuantity: 1, nonReturnable: false,
  optionAxes: [{ name: null, kind: 'colour' }, { name: null, kind: 'size' }],
  currentOptions: ['White', 'M'],
  exchangeOptions: [{ variantId: 'v-white-l', title: 'White / L', options: ['White', 'L'], inStock: true }],
  sameItem: { inStock: true },
}

const SCARF: LookupLine = {
  variantId: 'v-scarf', productTitle: 'Silk Scarf', variantTitle: 'One size', imageUrl: null,
  deliveredQuantity: 1, returnableQuantity: 1, nonReturnable: false,
  optionAxes: [], currentOptions: [], exchangeOptions: [], sameItem: { inStock: true },
}

let lookupBody: LookupResult
let calls: Array<{ url: string; init: RequestInit }>

function respond(status: number, body?: unknown) {
  return Promise.resolve({
    ok: status >= 200 && status < 300, status,
    headers: { get: (k: string) => (k.toLowerCase() === 'content-type' && body !== undefined ? 'application/json' : null) },
    json: async () => structuredClone(body),
    text: async () => JSON.stringify(body ?? ''),
  })
}

beforeEach(() => {
  localStorage.clear()
  calls = []
  lookupBody = { token: 'tok.sig', orderNumber: '#1047', deliveredAt: '2026-09-18T10:00:00Z', lines: [SHIRT], pickup: null }
  vi.stubGlobal('fetch', vi.fn((url: string, init: RequestInit = {}) => {
    calls.push({ url, init })
    if (url.endsWith('/config')) return respond(200, CONFIG)
    if (url.endsWith('/lookup')) return respond(200, lookupBody)
    if (url.endsWith('/requests')) return respond(201, { reference: 'RR-7K3F9M', status: 'requested' })
    return respond(404)
  }))
})

afterEach(() => {
  vi.unstubAllGlobals()
  document.documentElement.dir = 'ltr'
  document.documentElement.lang = 'en'
})

const EN = {
  start: 'Start a return', order: 'Order number', phone: 'Phone number', find: 'Find my order', what: 'What would you like to do?',
  exchange: /Exchange/, why: 'Why are you exchanging it?', next: 'Continue', send: 'Send return request',
}

async function toChoice(reason: string, lang: 'en' | 'ar' = 'en', labels = EN) {
  const user = userEvent.setup()
  render(<I18nextProvider i18n={createPortalI18n(lang)}><PortalApp slug="nourstudio" /></I18nextProvider>)
  await screen.findByRole('heading', { name: labels.start })
  await user.type(screen.getByLabelText(labels.order), '#1047')
  await user.type(screen.getByLabelText(labels.phone), '010 1234 5678')
  await user.click(screen.getByRole('button', { name: labels.find }))
  await screen.findByRole('heading', { name: labels.what })
  await user.click(screen.getByRole('radio', { name: labels.exchange }))
  await user.click(within(screen.getAllByTestId('exchange-line')[0]).getByRole('radio'))
  await user.selectOptions(screen.getByLabelText(labels.why), reason)
  await user.click(screen.getByRole('button', { name: labels.next }))
  return user
}

const submitted = () => JSON.parse(calls.find(c => c.url.endsWith('/requests'))!.init.body as string)

describe('replace with the same item', () => {
  test('defect: the option, the review line, the submission and the sent screen', async () => {
    const user = await toChoice('damaged')
    await screen.findByRole('heading', { name: 'Choose your new size or colour' })
    const same = screen.getByTestId('same-item')
    expect(same).toHaveTextContent('Replace with the same item')
    expect(same).toHaveTextContent('Same size and colour — White · M')
    expect(within(same).getByTestId('same-item-in-stock')).toHaveTextContent('In stock · no price difference')
    expect(screen.getByTestId('or-another')).toHaveTextContent('Or choose another size or colour')
    expect(screen.getByRole('button', { name: 'Continue' })).toBeDisabled()
    await user.click(within(same).getByRole('radio'))
    await user.click(screen.getByRole('button', { name: 'Continue' }))
    expect(await screen.findByTestId('summary-line')).toHaveTextContent('Linen Shirt · White · M → Replacement (same item)')
    expect(screen.getByText('Replacing')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Send return request' }))
    expect(await screen.findByRole('heading', { name: 'Replacement requested' })).toBeInTheDocument()
    expect(screen.getByTestId('sent-lead')).toHaveTextContent('Linen Shirt · White · M → Replacement (same item)')
    expect(submitted()).toMatchObject({ mode: 'exchange', replacementVariantId: 'v-white-m',
      lines: [{ variantId: 'v-white-m', quantity: 1, reasonCode: 'damaged' }] })
  })

  test('picking another size after the same item sends the size', async () => {
    const user = await toChoice('wrong_item')
    await user.click(within(screen.getByTestId('same-item')).getByRole('radio'))
    await user.click(within(screen.getByRole('group', { name: 'Size' })).getByRole('radio', { name: 'L' }))
    expect(within(screen.getByTestId('same-item')).getByRole('radio')).not.toBeChecked()
    await user.click(screen.getByRole('button', { name: 'Continue' }))
    expect(await screen.findByTestId('summary-line')).toHaveTextContent('→ White · L')
  })

  test('other: a non-defect reason offers no same item', async () => {
    await toChoice('wrong_size')
    await screen.findByRole('heading', { name: 'Choose your new size or colour' })
    expect(screen.queryByTestId('same-item')).toBeNull()
    expect(screen.queryByTestId('or-another')).toBeNull()
    expect(screen.getByRole('group', { name: 'Size' })).toBeInTheDocument()
  })

  test('soldOut: disabled with the reason; another size can still be chosen', async () => {
    lookupBody = { ...lookupBody, lines: [{ ...SHIRT, sameItem: { inStock: false } }] }
    const user = await toChoice('damaged')
    const same = await screen.findByTestId('same-item')
    expect(within(same).getByRole('radio')).toBeDisabled()
    expect(within(same).getByTestId('same-item-out-of-stock')).toHaveTextContent('Out of stock right now')
    expect(screen.getByTestId('same-item-sold-out')).toHaveTextContent(
      'This exact item is out of stock right now. Choose another size or colour, or go back and choose a refund.')
    await user.click(within(screen.getByRole('group', { name: 'Size' })).getByRole('radio', { name: 'L' }))
    expect(screen.getByRole('button', { name: 'Continue' })).toBeEnabled()
  })

  test('oneSize: selectable, defect reasons only, no size picker', async () => {
    lookupBody = { ...lookupBody, lines: [SCARF] }
    expect(replaceOnly(SCARF, ['damaged', 'wrong_item'])).toBe(true)
    expect(replaceOnly(SCARF, [])).toBe(false)
    expect(replaceOnly(SHIRT, ['damaged'])).toBe(false)
    const user = userEvent.setup()
    render(<I18nextProvider i18n={createPortalI18n('en')}><PortalApp slug="nourstudio" /></I18nextProvider>)
    await screen.findByRole('heading', { name: 'Start a return' })
    await user.type(screen.getByLabelText('Order number'), '#1047')
    await user.type(screen.getByLabelText('Phone number'), '010 1234 5678')
    await user.click(screen.getByRole('button', { name: 'Find my order' }))
    await user.click(await screen.findByRole('radio', { name: /Exchange/ }))
    const line = screen.getAllByTestId('exchange-line')[0]
    expect(within(line).getByTestId('replace-only-note')).toHaveTextContent('Only for a damaged or wrong item')
    await user.click(within(line).getByRole('radio'))
    const reasons = within(screen.getByLabelText('Why are you exchanging it?')).getAllByRole('option').map(o => o.getAttribute('value'))
    expect(reasons).toEqual(['', 'damaged', 'wrong_item'])
    await user.selectOptions(screen.getByLabelText('Why are you exchanging it?'), 'damaged')
    await user.click(screen.getByRole('button', { name: 'Continue' }))
    await screen.findByRole('heading', { name: 'Get the same item again' })
    expect(screen.queryByRole('group', { name: 'Size' })).toBeNull()
    expect(screen.queryByTestId('or-another')).toBeNull()
    await user.click(within(screen.getByTestId('same-item')).getByRole('radio'))
    await user.click(screen.getByRole('button', { name: 'Continue' }))
    await user.click(await screen.findByRole('button', { name: 'Send return request' }))
    await screen.findByRole('heading', { name: 'Replacement requested' })
    expect(submitted()).toMatchObject({ mode: 'exchange', replacementVariantId: 'v-scarf' })
  })

  test('arabic: the same flow, right to left', async () => {
    const AR = {
      start: 'ابدأ الإرجاع', order: 'رقم الطلب', phone: 'رقم الهاتف', find: 'ابحث عن طلبي', what: 'ماذا تريد أن تفعل؟',
      exchange: /استبدال/, why: 'لماذا تريد استبداله؟', next: 'متابعة', send: 'إرسال طلب الإرجاع',
    }
    const ar = createPortalI18n('ar')
    const t = ar.getFixedT('ar')
    const labels = { ...AR, start: t('p1.title'), order: t('p1.orderLabel'), phone: t('p1.phoneLabel'), find: t('p1.find'),
      next: t('p2.continue') }
    const user = await toChoice('damaged', 'ar', labels)
    expect(document.documentElement.dir).toBe('rtl')
    const same = await screen.findByTestId('same-item')
    expect(same).toHaveTextContent('استبدال بنفس المنتج')
    expect(within(same).getByTestId('same-item-in-stock')).toHaveTextContent('متوفر · بدون فرق في السعر')
    await user.click(within(same).getByRole('radio'))
    await user.click(screen.getByRole('button', { name: labels.next }))
    expect(await screen.findByTestId('summary-line')).toHaveTextContent('استبدال بنفس المنتج')
  })
})
