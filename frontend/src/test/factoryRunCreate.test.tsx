import { test, expect, describe, vi, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { fireEvent } from '@testing-library/react'
import { renderWithProviders, screen, waitFor, within } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import { setAccessToken, clearAccessToken } from '../auth'
import { resetCapabilitiesCache } from '../capabilities'
import { ToastProvider } from '../components/ui'
import Receiving from '../pages/Receiving'

/**
 * Factory runs — Create run is never silent (live test 2026-10-28: an empty factory name did nothing on screen).
 *   rc1  the factory name is marked required, first and focused; Create run with it empty → the inline error under
 *        the field, scrolled to and focused, no request
 *   rc2  Create run with no products → the inline error by the button, no request
 *   rc3  more than 20,000 pieces → the inline error, no request
 *   rc4  the server refuses (400 with a message) → that message in a banner, scrolled to
 *   rc5  the server fails (500, no body) → a readable banner, never "500: …"
 */

function json(data: unknown, status = 200) {
  return Promise.resolve({
    ok: status >= 200 && status < 300, status, statusText: status === 500 ? 'Internal Server Error' : '',
    headers: { get: (k: string) => (k.toLowerCase() === 'content-type' ? 'application/json' : null) },
    json: async () => (data === undefined ? Promise.reject(new Error('no body')) : structuredClone(data)),
    text: async () => JSON.stringify(data ?? ''),
    blob: async () => new Blob(['x']),
  })
}

const CATALOG = {
  products: [{ id: 'p-mug', title: 'Mug', status: 'active', imageUrl: null,
    variants: [{ id: 'v-mug', title: 'Default Title', sku: 'MUG-1', price: 90 }] }],
  nextCursor: null,
}

let calls: { method: string; url: string; body?: unknown }[]
let createAnswer: 'ok' | 'refused' | 'crash'

function backend(url: string, opts: RequestInit = {}) {
  const method = (opts.method ?? 'GET').toUpperCase()
  calls.push({ method, url, body: typeof opts.body === 'string' ? JSON.parse(opts.body) : opts.body })
  if (url.endsWith('/label-runs') && method === 'POST') {
    if (createAnswer === 'refused') {
      return json({ code: 'INVALID_RUN', message_en: 'This product is archived in Shopify', message_ar: 'هذا المنتج مؤرشف' }, 400)
    }
    if (createAnswer === 'crash') return json(undefined, 500)
    return json({ id: 'run-1' }, 201)
  }
  if (url.endsWith('/label-runs')) return json([])
  if (url.includes('/catalog')) return json(CATALOG)
  if (url.includes('/receiving/sessions')) return json([])
  if (url.includes('/locations')) return json([])
  return json({})
}

let scrolled: Element[]

function setup(answer: typeof createAnswer = 'ok') {
  calls = []
  createAnswer = answer
  scrolled = []
  Element.prototype.scrollIntoView = vi.fn(function (this: Element) { scrolled.push(this) })
  resetCapabilitiesCache()
  setAccessToken(`h.${btoa(JSON.stringify({ role: 'owner' }))}.s`)
  stubFetchWithShellDefaults(vi.fn(backend),
    { me: { name: 'Mona', role: 'owner', scanHelpers: false, demoMode: false, genericCourier: false, flexibleLabeling: true } })
}

afterEach(() => { clearAccessToken(); resetCapabilitiesCache(); vi.unstubAllGlobals(); vi.restoreAllMocks() })

async function openCreate(user: ReturnType<typeof userEvent.setup>) {
  renderWithProviders(<ToastProvider><Receiving /></ToastProvider>)
  await user.click(await screen.findByRole('button', { name: 'Factory runs' }))
  // An empty list offers the button twice (header + empty state) — either opens the form.
  await user.click((await screen.findAllByRole('button', { name: '+ New factory run' }))[0])
  return screen.findByTestId('factory-run-create')
}

async function pickMug(user: ReturnType<typeof userEvent.setup>, qty: string) {
  await user.click(await screen.findByTestId('product-card-p-mug'))
  await screen.findByTestId('variant-modal-body')
  await user.click(within(screen.getByTestId('variant-row-v-mug')).getByRole('checkbox'))
  const input = screen.getByTestId('qty-input-v-mug')
  await waitFor(() => expect(input).toBeEnabled())
  await user.type(input, qty)
  fireEvent.blur(input)
  await user.click(screen.getByRole('button', { name: 'Done' }))
}

const posted = () => calls.some(c => c.method === 'POST' && c.url.endsWith('/label-runs'))

describe('Factory runs — Create run is never silent', () => {
  test('rc1 — empty factory name: inline error under the field, scrolled to and focused, no request', async () => {
    setup()
    const user = userEvent.setup()
    await openCreate(user)
    const name = screen.getByTestId('run-factory')
    expect(name).toHaveFocus()
    expect(name).toBeRequired()
    expect(screen.getByText('Factory name').parentElement?.textContent).toContain('*')

    await pickMug(user, '40')
    expect(name).not.toHaveFocus()
    await user.click(await screen.findByRole('button', { name: 'Create run · 40 pieces' }))

    expect(await screen.findByText('Enter the factory name')).toBeTruthy()
    expect(name).toHaveAttribute('aria-invalid', 'true')
    await waitFor(() => expect(name).toHaveFocus())
    expect(scrolled.some(el => el.contains(name))).toBe(true)
    expect(posted()).toBe(false)

    await user.type(name, 'Nile')
    expect(screen.queryByText('Enter the factory name')).toBeNull()
  })

  test('rc2 — no products: inline error by the button, no request', async () => {
    setup()
    const user = userEvent.setup()
    await openCreate(user)
    await user.type(screen.getByTestId('run-factory'), 'Nile')
    const submit = screen.getByRole('button', { name: 'Create run · 0 pieces' })
    expect(submit).toBeEnabled()
    await user.click(submit)
    const err = await screen.findByTestId('run-lines-error')
    expect(err.textContent).toBe('Add at least one product with the number of pieces you expect')
    expect(scrolled.some(el => el.contains(err))).toBe(true)
    expect(posted()).toBe(false)
  })

  test('rc3 — more than 20,000 pieces: inline error, no request', async () => {
    setup()
    const user = userEvent.setup()
    await openCreate(user)
    await user.type(screen.getByTestId('run-factory'), 'Nile')
    await pickMug(user, '20001')
    await user.click(await screen.findByRole('button', { name: 'Create run · 20001 pieces' }))
    expect((await screen.findByTestId('run-lines-error')).textContent)
      .toBe('A factory run can have at most 20,000 pieces — split it into two runs.')
    expect(posted()).toBe(false)
  })

  test('rc4 — the server refuses: its message in a banner, scrolled to', async () => {
    setup('refused')
    const user = userEvent.setup()
    await openCreate(user)
    await user.type(screen.getByTestId('run-factory'), 'Nile')
    await pickMug(user, '5')
    await user.click(await screen.findByRole('button', { name: 'Create run · 5 pieces' }))
    const banner = await screen.findByTestId('run-create-error')
    expect(banner.textContent).toContain('This product is archived in Shopify')
    expect(scrolled).toContain(banner)
    expect(posted()).toBe(true)
  })

  test('rc5 — the server fails (500, no body): a readable banner', async () => {
    setup('crash')
    const user = userEvent.setup()
    await openCreate(user)
    await user.type(screen.getByTestId('run-factory'), 'Nile')
    await pickMug(user, '5')
    await user.click(await screen.findByRole('button', { name: 'Create run · 5 pieces' }))
    const banner = await screen.findByTestId('run-create-error')
    expect(banner.textContent).toContain("Couldn't create the factory run — try again.")
    expect(banner.textContent).not.toContain('500')
  })
})
