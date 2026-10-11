import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { render, screen, waitFor, within } from '@testing-library/react'
import { I18nextProvider } from 'react-i18next'
import PortalApp from '../portal/PortalApp'
import { createPortalI18n, PortalLang } from '../portal/i18n'
import { retryAfterSeconds, type LookupResult, type PortalConfig } from '../portal/api'

// P4d — the portal's answers for an order from before the store joined Traced
// (design/Traced_portal_preconnect_dc.html): finding (a), too old (b), unavailable (c), busy with a
// Retry-After countdown (d); the generic not-found stays as it was (e).

const BASE: PortalConfig = {
  storeName: 'The Snouts', returnWindowDays: 14, reasonCodes: ['wrong_size', 'other'],
  logoUrl: null, brandColor: '#0F766E', policyText: null, autoApprove: false, pickupBooking: false,
}

const FOUND: LookupResult = {
  token: 'tok.sig', orderNumber: '#1188', deliveredAt: '2026-10-08T10:00:00Z', pickup: null,
  lines: [{ variantId: 'v1', productTitle: 'Linen Shirt', variantTitle: 'White / M', imageUrl: null,
    deliveredQuantity: 1, returnableQuantity: 1, nonReturnable: false }],
}

interface Answer { status: number; body?: unknown; retryAfter?: string; delayMs?: number }

function respond(a: Answer) {
  const headers: Record<string, string | null> = {
    'content-type': a.body !== undefined ? 'application/json' : null,
    'retry-after': a.retryAfter ?? null,
  }
  const res = {
    ok: a.status >= 200 && a.status < 300, status: a.status,
    headers: { get: (k: string) => headers[k.toLowerCase()] ?? null },
    json: async () => structuredClone(a.body),
    text: async () => JSON.stringify(a.body ?? ''),
  }
  return a.delayMs ? new Promise(r => setTimeout(() => r(res), a.delayMs)) : Promise.resolve(res)
}

let config: PortalConfig
let answers: Answer[]
let lookups: Array<Record<string, string>>

beforeEach(() => {
  config = BASE
  answers = []
  lookups = []
  localStorage.clear()
  vi.stubGlobal('fetch', vi.fn((url: string, init: RequestInit = {}) => {
    if (url.endsWith('/config')) return respond({ status: 200, body: config })
    if (url.endsWith('/lookup')) {
      lookups.push(JSON.parse(init.body as string))
      return respond(answers.shift() ?? { status: 200, body: FOUND })
    }
    return respond({ status: 404 })
  }))
})

afterEach(() => {
  vi.unstubAllGlobals()
  document.documentElement.dir = 'ltr'
  document.documentElement.lang = 'en'
})

const L = {
  en: { title: 'Start a return', order: 'Order number', phone: 'Phone number', find: 'Find my order', tryAgain: 'Try again' },
  ar: { title: 'ابدأ الإرجاع', order: 'رقم الطلب', phone: 'رقم الهاتف', find: 'ابحث عن طلبي', tryAgain: 'حاول مرة أخرى' },
}

async function lookUp(lang: PortalLang = 'en') {
  const user = userEvent.setup()
  render(<I18nextProvider i18n={createPortalI18n(lang)}><PortalApp slug="thesnouts" /></I18nextProvider>)
  await screen.findByRole('heading', { name: L[lang].title })
  await user.type(screen.getByLabelText(L[lang].order), '#1042')
  await user.type(screen.getByLabelText(L[lang].phone), '010 5550 1234')
  await user.click(screen.getByRole('button', { name: L[lang].find }))
  return user
}

const banner = () => screen.findByTestId('start-banner')

describe('too old (b)', () => {
  test('EN — the date, amber, the policy link only when the store has policy text; editing clears it', async () => {
    answers.push({ status: 404, body: { code: 'TOO_OLD', date: '2026-08-12', message: 'x' } })
    const user = await lookUp('en')
    const b = await banner()
    expect(b).toHaveAttribute('data-kind', 'tooOld')
    expect(b).toHaveClass('pp-banner--warn')
    expect(b).toHaveTextContent("Orders placed before 12 August 2026 can't be looked up online. Contact The Snouts about returning it.")
    expect(within(b).getByText('12 August 2026').tagName).toBe('B')
    expect(within(b).queryByRole('button')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Find my order' })).toBeEnabled()

    await user.type(screen.getByLabelText('Order number'), '7')
    expect(screen.queryByTestId('start-banner')).not.toBeInTheDocument()
  })

  test('EN — with policy text: "Read The Snouts\'s return policy" opens the policy dialog', async () => {
    config = { ...BASE, policyText: 'Returns within 14 days, unworn, with tags.' }
    answers.push({ status: 404, body: { code: 'TOO_OLD', date: '2026-08-12' } })
    const user = await lookUp('en')
    const b = await banner()
    await user.click(within(b).getByRole('button', { name: "Read The Snouts's return policy" }))
    expect(await screen.findByRole('dialog')).toHaveTextContent('Returns within 14 days, unworn, with tags.')
  })

  test('AR — Arabic month name, RTL copy', async () => {
    answers.push({ status: 404, body: { code: 'TOO_OLD', date: '2026-08-12' } })
    await lookUp('ar')
    const b = await banner()
    expect(b).toHaveTextContent('لا يمكن البحث عبر الإنترنت عن الطلبات التي تمت قبل 12 أغسطس 2026. تواصل مع The Snouts بخصوص إرجاعه.')
  })
})

describe('unavailable (c)', () => {
  test('EN — amber, "Try again"', async () => {
    answers.push({ status: 404, body: { code: 'UNAVAILABLE' } })
    await lookUp('en')
    const b = await banner()
    expect(b).toHaveAttribute('data-kind', 'unavailable')
    expect(b).toHaveClass('pp-banner--warn')
    expect(b).toHaveTextContent("We can't look up this order online right now. Contact The Snouts about your return.")
    expect(screen.getByRole('button', { name: 'Try again' })).toBeEnabled()
  })

  test('AR', async () => {
    answers.push({ status: 404, body: { code: 'UNAVAILABLE' } })
    await lookUp('ar')
    expect(await banner()).toHaveTextContent('لا يمكننا البحث عن هذا الطلب عبر الإنترنت الآن. تواصل مع The Snouts بخصوص الإرجاع.')
    expect(screen.getByRole('button', { name: 'حاول مرة أخرى' })).toBeEnabled()
  })
})

describe('busy (d)', () => {
  test('EN — countdown from Retry-After, disabled until 0, then "Try again" re-submits the same details', async () => {
    answers.push({ status: 503, body: { code: 'BUSY' }, retryAfter: '2' })
    const user = await lookUp('en')
    const b = await banner()
    expect(b).toHaveAttribute('data-kind', 'busy')
    expect(b).toHaveTextContent("We're busy checking orders right now. Please try again in a minute.")
    const button = screen.getByTestId('find-button')
    expect(button).toBeDisabled()
    expect(button).toHaveTextContent('Try again in 0:02')
    await waitFor(() => expect(button).toHaveTextContent('Try again in 0:01'), { timeout: 2000 })
    await waitFor(() => expect(button).toBeEnabled(), { timeout: 3000 })
    expect(button).toHaveTextContent('Try again')

    await user.click(button)
    await screen.findByText('Linen Shirt')
    expect(lookups).toHaveLength(2)
    expect(lookups[1]).toEqual(lookups[0])
  })

  test('editing a field does not skip the wait', async () => {
    answers.push({ status: 503, body: { code: 'BUSY' }, retryAfter: '30' })
    const user = await lookUp('en')
    await banner()
    await user.type(screen.getByLabelText('Order number'), '9')
    expect(screen.getByTestId('find-button')).toBeDisabled()
    expect(screen.getByTestId('start-banner')).toHaveAttribute('data-kind', 'busy')
  })

  test('no Retry-After → one minute', async () => {
    answers.push({ status: 503, body: { code: 'BUSY' } })
    await lookUp('en')
    await banner()
    expect(screen.getByTestId('find-button')).toHaveTextContent('Try again in 1:00')
    expect(retryAfterSeconds(null)).toBe(60)
    expect(retryAfterSeconds('Wed, 21 Oct 2026 07:28:00 GMT')).toBe(60)
    expect(retryAfterSeconds(' 45 ')).toBe(45)
  })

  test('AR — the countdown digits stay left-to-right', async () => {
    answers.push({ status: 503, body: { code: 'BUSY' }, retryAfter: '47' })
    await lookUp('ar')
    expect(await banner()).toHaveTextContent('نتحقق من طلبات كثيرة الآن. حاول مرة أخرى بعد دقيقة.')
    const button = screen.getByTestId('find-button')
    expect(button).toHaveTextContent('حاول مرة أخرى بعد 0:47')
    expect(button.querySelector('.pp-countdown')).toHaveAttribute('dir', 'ltr')
  })

  test('a 503 that is not BUSY (e.g. the proxy) is the generic error, not a countdown', async () => {
    answers.push({ status: 503 })
    await lookUp('en')
    expect(await banner()).toHaveTextContent('Something went wrong. Please try again.')
    expect(screen.getByTestId('find-button')).toBeEnabled()
  })
})

describe('finding (a) and not found (e)', () => {
  test('a slow lookup: spinner + "Finding your order…", then the reassurance line in its reserved slot', async () => {
    answers.push({ status: 200, body: FOUND, delayMs: 1500 })
    await lookUp('en')
    const button = screen.getByTestId('find-button')
    expect(button).toHaveTextContent('Finding your order…')
    expect(button).toHaveAttribute('aria-busy', 'true')
    expect(button.querySelector('.pp-spinner')).not.toBeNull()
    const status = screen.getByTestId('find-status')
    expect(status).toHaveTextContent('')
    await waitFor(() => expect(status).toHaveTextContent('This can take a few seconds.'), { timeout: 1400 })
    await screen.findByText('Linen Shirt')
  })

  test('AR — finding copy', async () => {
    answers.push({ status: 200, body: FOUND, delayMs: 1200 })
    await lookUp('ar')
    expect(screen.getByTestId('find-button')).toHaveTextContent('جارٍ البحث عن طلبك…')
    await waitFor(() => expect(screen.getByTestId('find-status')).toHaveTextContent('قد يستغرق ذلك بضع ثوانٍ.'), { timeout: 1200 })
  })

  test('a fast lookup never shows the line', async () => {
    answers.push({ status: 404, body: { message: 'x' } })
    await lookUp('en')
    await banner()
    expect(screen.getByTestId('find-status')).toHaveTextContent('')
  })

  test('generic not-found unchanged: red, the old copy, "Try again"', async () => {
    answers.push({ status: 404, body: { message: "We couldn't find a returnable order with those details." } })
    await lookUp('en')
    const b = await banner()
    expect(b).toHaveAttribute('data-kind', 'notFound')
    expect(b).toHaveClass('pp-banner--error')
    expect(b).toHaveTextContent("We couldn't find a returnable order with those details. Check the order number and phone, or contact The Snouts.")
    expect(screen.getByRole('button', { name: 'Try again' })).toBeEnabled()
  })
})
