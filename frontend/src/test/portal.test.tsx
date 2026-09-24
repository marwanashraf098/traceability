import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { render, screen, waitFor, within } from '@testing-library/react'
import { I18nextProvider } from 'react-i18next'
import PortalApp, { maskEmail, slugFromPath } from '../portal/PortalApp'
import { createPortalI18n, detectLanguage, LANG_KEY, PortalLang } from '../portal/i18n'
import { contrast, palette } from '../portal/brand'
import type { LookupResult, PortalConfig } from '../portal/api'

// Returns portal Step 4e-B — the customer flow (P1–P7). Fakes match the Step 0 report's
// response shapes: config 200 / 404 empty; lookup 200 / 404 {message} / 429 {message} or
// nginx HTML; requests 201 {reference,status} / 400 / 401 / 409 {message}.

const CONFIG: PortalConfig = {
  storeName: 'Nour Studio', returnWindowDays: 30,
  reasonCodes: ['wrong_size', 'damaged', 'not_as_pictured', 'wrong_item', 'changed_mind', 'other'],
  logoUrl: null, brandColor: '#1F5C4A', policyText: 'Unworn items only.\nTags attached.',
  autoApprove: false, pickupBooking: false,
}

const LOOKUP: LookupResult = {
  token: 'tok.sig', orderNumber: '#1047', deliveredAt: '2026-09-18T10:00:00Z',
  lines: [
    { variantId: 'v-shirt', productTitle: 'Linen Shirt', variantTitle: 'White · M', imageUrl: null, deliveredQuantity: 1, returnableQuantity: 1, nonReturnable: false },
    { variantId: 'v-pants', productTitle: 'Flipped Pants', variantTitle: 'Black · XL', imageUrl: null, deliveredQuantity: 2, returnableQuantity: 2, nonReturnable: false },
    { variantId: 'v-scarf', productTitle: 'Silk Scarf', variantTitle: 'Sand', imageUrl: null, deliveredQuantity: 1, returnableQuantity: 0, nonReturnable: true },
    { variantId: 'v-hat', productTitle: 'Bucket Hat', variantTitle: null, imageUrl: null, deliveredQuantity: 1, returnableQuantity: 0, nonReturnable: false },
  ],
}

type Reply = { status: number; body?: unknown; html?: string }

function respond({ status, body, html }: Reply) {
  const type = html != null ? 'text/html' : body !== undefined ? 'application/json' : null
  return Promise.resolve({
    ok: status >= 200 && status < 300,
    status,
    headers: { get: (k: string) => (k.toLowerCase() === 'content-type' ? type : null) },
    json: async () => structuredClone(body),
    text: async () => html ?? JSON.stringify(body ?? ''),
  })
}

let config: Reply
let lookupReplies: Reply[]
let submitReplies: Reply[]
let calls: Array<{ url: string; init: RequestInit }>

function fakeFetch(url: string, init: RequestInit = {}) {
  calls.push({ url, init })
  if (url.endsWith('/config')) return respond(config)
  if (url.endsWith('/lookup')) return respond(lookupReplies.shift() ?? { status: 200, body: LOOKUP })
  if (url.endsWith('/requests')) return respond(submitReplies.shift() ?? { status: 201, body: { reference: 'RR-7K3F9M', status: 'requested' } })
  return respond({ status: 404 })
}

beforeEach(() => {
  config = { status: 200, body: CONFIG }
  lookupReplies = []
  submitReplies = []
  calls = []
  localStorage.clear()
  vi.stubGlobal('fetch', vi.fn(fakeFetch))
})

afterEach(() => {
  vi.unstubAllGlobals()
  document.documentElement.dir = 'ltr'
  document.documentElement.lang = 'en'
})

function renderPortal(slug: string | null = 'nourstudio', lang: PortalLang = 'en') {
  const i18n = createPortalI18n(lang)
  return render(<I18nextProvider i18n={i18n}><PortalApp slug={slug} /></I18nextProvider>)
}

async function findOrder(user = userEvent.setup()) {
  renderPortal()
  await screen.findByRole('heading', { name: 'Start a return' })
  await user.type(screen.getByLabelText('Order number'), '#1047')
  await user.type(screen.getByLabelText('Phone number'), '010 1234 5678')
  await user.click(screen.getByRole('button', { name: 'Find my order' }))
  return user
}

async function toItems() {
  const user = await findOrder()
  await screen.findByRole('heading', { name: 'What would you like to return?' })
  return user
}

function lineCard(title: string) {
  return screen.getByText(title).closest('section')!
}

async function chooseShirt(user: ReturnType<typeof userEvent.setup>) {
  const shirt = lineCard('Linen Shirt')
  await user.click(within(shirt).getByRole('button', { name: 'Increase quantity' }))
  await user.selectOptions(within(shirt).getByLabelText('Why are you returning it?'), 'wrong_size')
}

async function toDetails() {
  const user = await toItems()
  await chooseShirt(user)
  await user.click(screen.getByRole('button', { name: 'Continue' }))
  await screen.findByRole('heading', { name: 'Almost done' })
  return user
}

describe('portal helpers', () => {
  test('slug is the first path segment', () => {
    expect(slugFromPath('/nourstudio')).toBe('nourstudio')
    expect(slugFromPath('/nourstudio/')).toBe('nourstudio')
    expect(slugFromPath('/')).toBeNull()
  })

  test('email masking', () => {
    expect(maskEmail('mariam@example.com')).toBe('m•••@example.com')
  })

  test('button text meets 4.5:1 or falls back to the default blue with white', () => {
    const dark = palette('#1F5C4A')
    expect(dark.onBrand).toBe('#FFFFFF')
    const light = palette('#F5D90A')
    expect(light.onBrand).toBe('#141821')
    expect(contrast(light.brand, light.onBrand)).toBeGreaterThanOrEqual(4.5)
    // Mid grey: neither white (3.9:1) nor ink (4.47:1) reaches 4.5:1 → default blue with white.
    expect(contrast('#808080', '#FFFFFF')).toBeLessThan(4.5)
    expect(contrast('#808080', '#141821')).toBeLessThan(4.5)
    expect(palette('#808080')).toMatchObject({ brand: '#3656E0', onBrand: '#FFFFFF' })
    expect(palette(null).brand).toBe('#3656E0')
    expect(palette('not-a-colour').brand).toBe('#3656E0')
  })
})

describe('config and availability', () => {
  test('no slug → unavailable, no request made', async () => {
    renderPortal(null)
    expect(await screen.findByRole('heading', { name: "This returns page isn't available" })).toBeInTheDocument()
    expect(calls).toHaveLength(0)
  })

  test('unknown slug (config 404, empty body) → unavailable', async () => {
    config = { status: 404 }
    renderPortal('nope')
    expect(await screen.findByRole('heading', { name: "This returns page isn't available" })).toBeInTheDocument()
  })

  test('store name wordmark when no logo; logo image when logoUrl is set', async () => {
    const first = renderPortal()
    expect(await screen.findByText('Nour Studio')).toBeInTheDocument()
    first.unmount()
    config = { status: 200, body: { ...CONFIG, logoUrl: 'https://cdn.shopify.com/logo.png' } }
    renderPortal()
    expect(await screen.findByRole('img', { name: 'Nour Studio' })).toHaveAttribute('src', 'https://cdn.shopify.com/logo.png')
  })

  test('the P1 window note uses returnWindowDays and the pre-4c pickup sentence', async () => {
    config = { status: 200, body: { ...CONFIG, returnWindowDays: 14 } }
    renderPortal()
    expect(await screen.findByText(/Returns are accepted within 14 days of delivery\./)).toBeInTheDocument()
    expect(screen.getByText(/The store will arrange a courier/)).toBeInTheDocument()
    expect(screen.queryByText(/Bosta/)).not.toBeInTheDocument()
  })
})

describe('P1 lookup errors', () => {
  test('empty fields are flagged locally and nothing is sent', async () => {
    const user = userEvent.setup()
    renderPortal()
    await screen.findByRole('heading', { name: 'Start a return' })
    await user.click(screen.getByRole('button', { name: 'Find my order' }))
    expect(screen.getByLabelText('Order number')).toHaveAttribute('aria-invalid', 'true')
    expect(screen.getByLabelText('Order number')).toHaveFocus()
    expect(screen.getByText('Enter your order number.')).toBeInTheDocument()
    expect(calls.some(c => c.url.endsWith('/lookup'))).toBe(false)
  })

  test('404 → not-found banner (role=alert), inputs keep their values, button reads Try again', async () => {
    lookupReplies = [{ status: 404, body: { message: "We couldn't find a returnable order with those details." } }]
    await findOrder()
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent("We couldn't find a returnable order with those details. Check the order number and phone, or contact Nour Studio.")
    expect(screen.getByLabelText('Order number')).toHaveValue('#1047')
    expect(screen.getByLabelText('Phone number')).toHaveValue('010 1234 5678')
    expect(screen.getByRole('button', { name: 'Try again' })).toBeEnabled()
  })

  test('429 JSON → too-many-attempts banner and a disabled button until the order number changes', async () => {
    lookupReplies = [{ status: 429, body: { message: 'Too many attempts. Please try again later.' } }]
    const user = await findOrder()
    expect(await screen.findByRole('alert')).toHaveTextContent('Too many attempts for this order. Please try again in an hour, or contact Nour Studio.')
    const button = screen.getByRole('button', { name: 'Find my order' })
    expect(button).toBeDisabled()
    await user.type(screen.getByLabelText('Order number'), '8')
    expect(button).toBeEnabled()
  })

  test('429 from nginx (HTML body) → the same banner', async () => {
    lookupReplies = [{ status: 429, html: '<html><body>429 Too Many Requests</body></html>' }]
    await findOrder()
    expect(await screen.findByRole('alert')).toHaveTextContent('Too many attempts for this order.')
  })

  test('500 with no body → generic message', async () => {
    lookupReplies = [{ status: 500 }]
    await findOrder()
    expect(await screen.findByRole('alert')).toHaveTextContent('Something went wrong. Please try again.')
  })
})

describe('P2 choose items', () => {
  test('stepper is bounded 0..returnableQuantity', async () => {
    const user = await toItems()
    const pants = lineCard('Flipped Pants')
    const dec = within(pants).getByRole('button', { name: 'Decrease quantity' })
    const inc = within(pants).getByRole('button', { name: 'Increase quantity' })
    expect(dec).toBeDisabled()
    await user.click(inc)
    await user.click(inc)
    expect(inc).toBeDisabled()
    expect(within(pants).getByText('2')).toBeInTheDocument()
    expect(within(pants).getByText('of 2')).toBeInTheDocument()
    await user.click(dec)
    expect(within(pants).getByText('1')).toBeInTheDocument()
  })

  test('the reason is required: Continue stays disabled until one is chosen', async () => {
    const user = await toItems()
    const continueBtn = screen.getByRole('button', { name: 'Continue' })
    expect(continueBtn).toBeDisabled()
    const shirt = lineCard('Linen Shirt')
    await user.click(within(shirt).getByRole('button', { name: 'Increase quantity' }))
    const select = within(shirt).getByLabelText('Why are you returning it?')
    expect(select).toBeRequired()
    expect(continueBtn).toBeDisabled()
    expect(within(select).getAllByRole('option').map(o => o.textContent)).toEqual([
      'Choose a reason', 'Wrong size', 'Damaged or faulty', 'Not as pictured', 'Wrong item received', 'Changed my mind', 'Other',
    ])
    await user.selectOptions(select, 'damaged')
    expect(continueBtn).toBeEnabled()
    expect(screen.getByText('1 item selected')).toBeInTheDocument()
  })

  test('non-returnable and already-requested lines are disabled with their labels and no stepper', async () => {
    await toItems()
    const scarf = lineCard('Silk Scarf')
    expect(scarf).toHaveTextContent("Can't be returned")
    expect(within(scarf).queryByRole('button')).not.toBeInTheDocument()
    const hat = lineCard('Bucket Hat')
    expect(hat).toHaveTextContent('Already in a return request')
    expect(within(hat).queryByRole('button')).not.toBeInTheDocument()
  })

  test('focus moves to the step heading on step change', async () => {
    await toItems()
    await waitFor(() => expect(screen.getByRole('heading', { name: 'What would you like to return?' })).toHaveFocus())
  })
})

describe('P3 details and send', () => {
  test('happy path P1 → P4: one request with the right body, lookup token as Bearer, no cookies', async () => {
    const user = await toDetails()
    const summary = screen.getAllByTestId('summary-line')
    expect(summary).toHaveLength(1)
    expect(summary[0]).toHaveTextContent('Linen Shirt · White · M')
    expect(summary[0]).toHaveTextContent('1 × Wrong size')
    expect(screen.getByText('The store will arrange a courier to collect the item from the address this order was delivered to.')).toBeInTheDocument()
    expect(screen.queryByLabelText(/City/)).not.toBeInTheDocument()

    await user.type(screen.getByLabelText(/Anything the store should know/), 'Too small')
    expect(screen.getByText('9 / 300')).toBeInTheDocument()
    await user.type(screen.getByLabelText(/Email for updates/), 'mariam@example.com')
    await user.click(screen.getByRole('button', { name: 'Send return request' }))

    expect(await screen.findByRole('heading', { name: 'Request sent' })).toBeInTheDocument()
    expect(screen.getByTestId('reference')).toHaveTextContent('RR-7K3F9M')
    expect(screen.getByTestId('sent-lead')).toHaveTextContent('Nour Studio will review your return request.')
    expect(screen.getByTestId('sent-email')).toHaveTextContent('m•••@example.com')
    expect(screen.queryByText(/Back to/)).not.toBeInTheDocument()

    const req = calls.find(c => c.url.endsWith('/requests'))!
    expect(req.url).toBe('/api/v1/portal/nourstudio/requests')
    expect(req.init.credentials).toBe('omit')
    expect((req.init.headers as Record<string, string>).Authorization).toBe('Bearer tok.sig')
    expect(JSON.parse(req.init.body as string)).toEqual({
      lines: [{ variantId: 'v-shirt', quantity: 1, reasonCode: 'wrong_size' }],
      email: 'mariam@example.com', note: 'Too small',
    })
    // No request of any kind carries cookies or an Authorization header except submit.
    for (const c of calls) {
      expect(c.init.credentials).toBe('omit')
      if (!c.url.endsWith('/requests')) expect((c.init.headers as Record<string, string> | undefined)?.Authorization).toBeUndefined()
    }
  })

  test('Edit goes back to P2 with the selections kept', async () => {
    const user = await toDetails()
    await user.click(screen.getByRole('button', { name: 'Edit' }))
    await screen.findByRole('heading', { name: 'What would you like to return?' })
    const shirt = lineCard('Linen Shirt')
    expect(within(shirt).getByText('1')).toBeInTheDocument()
    expect(within(shirt).getByLabelText('Why are you returning it?')).toHaveValue('wrong_size')
  })

  test('a double tap sends exactly one request and shows Sending…', async () => {
    let release!: () => void
    const pending = new Promise<void>(r => { release = r })
    const user = await toDetails()
    vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit = {}) => {
      calls.push({ url, init })
      await pending
      return respond({ status: 201, body: { reference: 'RR-7K3F9M', status: 'requested' } })
    }))
    const send = screen.getByRole('button', { name: 'Send return request' })
    await user.dblClick(send)
    await user.click(send)
    expect(screen.getByRole('button', { name: 'Sending…' })).toBeDisabled()
    release()
    await screen.findByRole('heading', { name: 'Request sent' })
    expect(calls.filter(c => c.url.endsWith('/requests'))).toHaveLength(1)
  })

  test('401 → back to P1 with the session-expired message', async () => {
    submitReplies = [{ status: 401, body: { message: 'Your session has expired. Please look up your order again.' } }]
    const user = await toDetails()
    await user.click(screen.getByRole('button', { name: 'Send return request' }))
    expect(await screen.findByRole('heading', { name: 'Start a return' })).toBeInTheDocument()
    expect(screen.getByRole('alert')).toHaveTextContent('Your session has expired. Please look up your order again.')
    expect(screen.getByLabelText('Order number')).toHaveValue('#1047')
  })

  test('409 → back to P1 with the conflict message', async () => {
    submitReplies = [{ status: 409, body: { message: 'Some items are no longer available. Please start again.' } }]
    const user = await toDetails()
    await user.click(screen.getByRole('button', { name: 'Send return request' }))
    expect(await screen.findByRole('heading', { name: 'Start a return' })).toBeInTheDocument()
    expect(screen.getByRole('alert')).toHaveTextContent('Some items are no longer available. Please start again.')
  })

  test('400 → stays on P3 with the message', async () => {
    submitReplies = [{ status: 400, body: { message: "We couldn't accept this return request. Please start again." } }]
    const user = await toDetails()
    await user.click(screen.getByRole('button', { name: 'Send return request' }))
    expect(await screen.findByTestId('send-banner')).toHaveTextContent("We couldn't accept this return request. Please start again.")
    expect(screen.getByRole('heading', { name: 'Almost done' })).toBeInTheDocument()
  })

  test('an invalid email blocks sending (same rule as the backend)', async () => {
    const user = await toDetails()
    await user.type(screen.getByLabelText(/Email for updates/), 'not-an-email')
    await user.click(screen.getByRole('button', { name: 'Send return request' }))
    expect(screen.getByRole('alert')).toHaveTextContent('Enter a valid email address, or leave it empty.')
    expect(calls.some(c => c.url.endsWith('/requests'))).toBe(false)
  })

  test('approved status shows the approved text; no email line when none was given', async () => {
    submitReplies = [{ status: 201, body: { reference: 'RR-ABCD23', status: 'approved' } }]
    const user = await toDetails()
    await user.click(screen.getByRole('button', { name: 'Send return request' }))
    expect(await screen.findByTestId('sent-lead')).toHaveTextContent('Your return is approved. A courier will contact you to collect it.')
    expect(screen.queryByText(/will review your return request/)).not.toBeInTheDocument()
    expect(screen.queryByTestId('sent-email')).not.toBeInTheDocument()
  })

  test('Copy writes the reference to the clipboard', async () => {
    const user = await toDetails()
    await user.click(screen.getByRole('button', { name: 'Send return request' }))
    await screen.findByRole('heading', { name: 'Request sent' })
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true })
    await user.click(screen.getByRole('button', { name: 'Copy' }))
    expect(writeText).toHaveBeenCalledWith('RR-7K3F9M')
    expect(screen.getByRole('button', { name: 'Copied' })).toBeInTheDocument()
  })

  test('policy dialog opens, closes with Escape and the close button, and returns focus', async () => {
    const user = await toDetails()
    const trigger = screen.getByRole('button', { name: 'return policy' })
    await user.click(trigger)
    const dialog = screen.getByRole('dialog', { name: 'Return policy' })
    expect(dialog).toHaveAttribute('aria-modal', 'true')
    expect(dialog).toHaveTextContent('Unworn items only.')
    expect(within(dialog).getByRole('button', { name: 'Close' })).toHaveFocus()
    await user.keyboard('{Escape}')
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(trigger).toHaveFocus()
    await user.click(trigger)
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Close' }))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  test('no policy text → the policy sentence is omitted', async () => {
    config = { status: 200, body: { ...CONFIG, policyText: null } }
    await toDetails()
    expect(screen.queryByText(/By sending, you agree/)).not.toBeInTheDocument()
  })
})

describe('language', () => {
  test('Arabic renders right-to-left with Arabic strings', async () => {
    renderPortal('nourstudio', 'ar')
    expect(await screen.findByRole('heading', { name: 'ابدأ الإرجاع' })).toBeInTheDocument()
    await waitFor(() => expect(document.documentElement.dir).toBe('rtl'))
    expect(document.documentElement.lang).toBe('ar')
    expect(screen.getByRole('button', { name: 'التبديل إلى الإنجليزية' })).toHaveTextContent('English')
  })

  test('the toggle switches language, sets dir/lang and the choice persists', async () => {
    const user = userEvent.setup()
    renderPortal('nourstudio', 'en')
    await screen.findByRole('heading', { name: 'Start a return' })
    await user.click(screen.getByRole('button', { name: 'Switch to Arabic' }))
    expect(await screen.findByRole('heading', { name: 'ابدأ الإرجاع' })).toBeInTheDocument()
    expect(document.documentElement.dir).toBe('rtl')
    expect(localStorage.getItem(LANG_KEY)).toBe('ar')
    expect(detectLanguage()).toBe('ar')
  })

  test('detection: saved choice → browser language starting with "ar" → English', () => {
    const langs = vi.spyOn(navigator, 'languages', 'get')
    langs.mockReturnValue(['ar-EG', 'en'])
    expect(detectLanguage()).toBe('ar')
    langs.mockReturnValue(['en-US'])
    expect(detectLanguage()).toBe('en')
    localStorage.setItem(LANG_KEY, 'en')
    langs.mockReturnValue(['ar'])
    expect(detectLanguage()).toBe('en')
    langs.mockRestore()
  })
})
