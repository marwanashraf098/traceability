import { describe, test, expect, beforeEach, afterEach, vi } from 'vitest'
import { screen, render, cleanup, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Routes, Route } from 'react-router-dom'
import { I18nextProvider, initReactI18next } from 'react-i18next'
import i18next from 'i18next'
import en from '../locales/en.json'
import ar from '../locales/ar.json'
import BazarCustomerDetails from '../pages/bazar/BazarCustomerDetails'
import { ToastProvider } from '../components/ui'
import { clearAccessToken, setAccessToken } from '../auth'

/** Bazar customer details on request — /bazar/customers: search, tick, export, delete (owner only, preview first). */

const testI18n = i18next.createInstance()
testI18n.use(initReactI18next).init({
  lng: 'en', fallbackLng: 'en', initImmediate: false,
  resources: { en: { translation: en } }, interpolation: { escapeValue: false },
})

function fakeJwt(role: 'owner' | 'manager' | 'worker'): string {
  return `h.${btoa(JSON.stringify({ role, sub: `u-${role}` }))}.s`
}

function json(status: number, body: unknown) {
  return Promise.resolve({
    ok: status >= 200 && status < 300, status, statusText: '',
    headers: { get: (k: string) => (k.toLowerCase() === 'content-type' ? 'application/json' : null) },
    json: async () => body,
    blob: async () => new Blob(['bazar,day\n']),
  } as unknown as Response)
}

type Handler = (url: string, init: RequestInit | undefined) => Promise<Response> | undefined
let handlers: Handler[] = []
let calls: { url: string; method: string; body: unknown }[] = []

function install(...hs: Handler[]) {
  handlers = hs
  calls = []
  vi.stubGlobal('fetch', vi.fn((url: string, init?: RequestInit) => {
    const method = (init?.method ?? 'GET').toUpperCase()
    let body: unknown = undefined
    try { body = init?.body ? JSON.parse(String(init.body)) : undefined } catch { body = init?.body }
    calls.push({ url, method, body })
    for (const h of handlers) {
      const r = h(url, init)
      if (r) return r
    }
    return json(404, {})
  }))
}

const on = (method: string, frag: string, fn: (init: RequestInit | undefined) => Promise<Response>): Handler =>
  (url, init) => ((init?.method ?? 'GET').toUpperCase() === method && url.includes(frag) ? fn(init) : undefined)
const posts = (frag: string) => calls.filter(c => c.method === 'POST' && c.url.includes(frag))

const MONA = {
  key: '01012345678', name: 'Mona Ali', names: ['Mona Ali', 'Mona'], phone: '01012345678', bazars: 1, sales: 4,
  pieces: 3, total: '2350.00', last_visit: '2026-11-20T12:00:00Z',
}
const MONA_NAME = {
  key: 'name:mona ali', name: 'Mona Ali', names: ['Mona Ali'], phone: null, bazars: 1, sales: 1, pieces: 1,
  total: '500.00', last_visit: '2026-11-18T12:00:00Z',
}

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/bazar/customers']}>
      <I18nextProvider i18n={testI18n}>
        <ToastProvider>
          <Routes>
            <Route path="/bazar/customers" element={<BazarCustomerDetails />} />
            <Route path="/overview" element={<div data-testid="overview" />} />
          </Routes>
        </ToastProvider>
      </I18nextProvider>
    </MemoryRouter>,
  )
}

async function search(user: ReturnType<typeof userEvent.setup>, q: string) {
  await user.type(await screen.findByTestId('customer-search'), q)
  await user.click(screen.getByRole('button', { name: 'Search' }))
}

beforeEach(() => {
  clearAccessToken()
  setAccessToken(fakeJwt('owner'))
  URL.createObjectURL = vi.fn(() => 'blob:x')
  URL.revokeObjectURL = vi.fn()
})

afterEach(() => {
  vi.unstubAllGlobals()
  cleanup()
})

describe('Bazar customer details', () => {
  test('search sends the phone in the body, never the URL, and lists every match', async () => {
    install(on('POST', '/bazar-customers/search', () => json(200, { customers: [MONA, MONA_NAME], more: false })))
    renderPage()
    const user = userEvent.setup()
    await search(user, '+20 10 1234 5678')
    const rows = await screen.findAllByTestId('customer-row')
    expect(rows).toHaveLength(2)
    expect(rows[0]).toHaveTextContent('Mona Ali')
    expect(rows[0]).toHaveTextContent('01012345678')
    expect(rows[0]).toHaveTextContent('Also saved as: Mona Ali, Mona')
    expect(rows[0]).toHaveTextContent('4 sales')
    expect(posts('/bazar-customers/search')[0].body).toEqual({ q: '+20 10 1234 5678' })
    expect(calls.every(c => !c.url.includes('?'))).toBe(true)
    expect(calls.every(c => !c.url.includes('1234'))).toBe(true)
  })

  test('no match says so; a refused query shows the server message', async () => {
    install(on('POST', '/bazar-customers/search', init => {
      const q = JSON.parse(String(init?.body)).q
      return q === '010'
        ? json(400, { code: 'BAZAR_INVALID_PHONE', message_en: 'Enter an Egyptian mobile number, like 01012345678, or a name.' })
        : json(200, { customers: [], more: false })
    }))
    renderPage()
    const user = userEvent.setup()
    await search(user, 'Nobody')
    expect(await screen.findByTestId('customer-none')).toHaveTextContent('No bazar customer matches “Nobody”.')
    await user.clear(screen.getByTestId('customer-search'))
    await search(user, '010')
    expect(await screen.findByTestId('customer-error')).toHaveTextContent('Enter an Egyptian mobile number')
  })

  test('export sends every ticked key', async () => {
    install(
      on('POST', '/bazar-customers/search', () => json(200, { customers: [MONA, MONA_NAME], more: false })),
      on('POST', '/bazar-customers/export', () => json(200, {})),
    )
    renderPage()
    const user = userEvent.setup()
    await search(user, 'mona')
    const exportBtn = await screen.findByRole('button', { name: /Export CSV/ })
    expect(exportBtn).toBeDisabled()
    const rows = await screen.findAllByTestId('customer-row')
    await user.click(within(rows[0]).getByRole('checkbox'))
    await user.click(within(rows[1]).getByRole('checkbox'))
    expect(exportBtn).toHaveTextContent('Export CSV (2)')
    await user.click(exportBtn)
    expect(posts('/bazar-customers/export')[0].body).toEqual({ keys: ['01012345678', 'name:mona ali'] })
    expect(URL.createObjectURL).toHaveBeenCalled()
  })

  test('owner: delete shows the preview count, sends it back, then reports the result', async () => {
    let deleted = false
    install(
      on('POST', '/bazar-customers/search', () => json(200, { customers: deleted ? [] : [MONA], more: false })),
      on('POST', '/bazar-customers/delete-preview', () => json(200, { rows: 4, receipts: 1, bazars: 1 })),
      on('POST', '/bazar-customers/delete', () => { deleted = true; return json(200, { rows: 4, receipts: 1, bazars: 1 }) }),
    )
    renderPage()
    const user = userEvent.setup()
    await search(user, '01012345678')
    await user.click(within((await screen.findAllByTestId('customer-row'))[0]).getByRole('checkbox'))
    await user.click(screen.getByRole('button', { name: /Delete details/ }))
    const dialog = await screen.findByTestId('customer-delete-dialog')
    expect(await within(dialog).findByTestId('customer-delete-count'))
      .toHaveTextContent('This removes the name and phone from 4 sales in 1 bazar(s) (1 cart receipt(s)).')
    expect(dialog).toHaveTextContent("This can't be undone.")
    expect(posts('/bazar-customers/delete').filter(c => c.url.endsWith('/delete'))).toHaveLength(0)
    await user.click(within(dialog).getByRole('button', { name: 'Delete details' }))
    expect(await screen.findByTestId('customer-notice')).toHaveTextContent('Name and phone removed from 4 sales.')
    expect(calls.find(c => c.url.endsWith('/bazar-customers/delete'))?.body)
      .toEqual({ keys: ['01012345678'], expected_rows: 4 })
    expect(await screen.findByTestId('customer-none')).toBeInTheDocument()
  })

  test('a stale preview (409) refreshes the count instead of deleting', async () => {
    let previews = 0
    install(
      on('POST', '/bazar-customers/search', () => json(200, { customers: [MONA], more: false })),
      on('POST', '/bazar-customers/delete-preview', () => { previews += 1; return json(200, { rows: previews === 1 ? 4 : 5, receipts: 1, bazars: 1 }) }),
      on('POST', '/bazar-customers/delete', () => json(409, { code: 'BAZAR_CUSTOMER_CHANGED', message_en: 'changed' })),
    )
    renderPage()
    const user = userEvent.setup()
    await search(user, 'mona')
    await user.click(within((await screen.findAllByTestId('customer-row'))[0]).getByRole('checkbox'))
    await user.click(screen.getByRole('button', { name: /Delete details/ }))
    const dialog = await screen.findByTestId('customer-delete-dialog')
    await within(dialog).findByText(/4 sales/)
    await user.click(within(dialog).getByRole('button', { name: 'Delete details' }))
    expect(await within(dialog).findByText(/5 sales/)).toBeInTheDocument()
    expect(within(dialog).getByRole('alert')).toHaveTextContent('Their sales changed since you opened this.')
    expect(screen.queryByTestId('customer-notice')).not.toBeInTheDocument()
  })

  test('manager: can export but sees no Delete', async () => {
    setAccessToken(fakeJwt('manager'))
    install(on('POST', '/bazar-customers/search', () => json(200, { customers: [MONA], more: false })))
    renderPage()
    const user = userEvent.setup()
    await search(user, 'mona')
    await screen.findAllByTestId('customer-row')
    expect(screen.getByRole('button', { name: /Export CSV/ })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Delete details/ })).not.toBeInTheDocument()
    expect(screen.getByTestId('bazar-customer-details')).toHaveTextContent('Only the owner can delete')
  })

  test('bazar off for the store → back to Overview', async () => {
    install(on('POST', '/bazar-customers/search', () => json(404, { code: 'BAZAR_NOT_ENABLED' })))
    renderPage()
    const user = userEvent.setup()
    await search(user, 'mona')
    expect(await screen.findByTestId('overview')).toBeInTheDocument()
  })

  test('every string has an Arabic copy', () => {
    const e = (en as { bazar: { customerData: Record<string, string> } }).bazar.customerData
    const a = (ar as { bazar: { customerData: Record<string, string> } }).bazar.customerData
    expect(Object.keys(a).sort()).toEqual(Object.keys(e).sort())
  })
})
