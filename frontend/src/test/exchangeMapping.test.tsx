import { test, expect, describe, vi, beforeEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { Routes, Route } from 'react-router-dom'
import { renderWithProviders, screen, waitFor, within } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import ExchangeMapping from '../pages/exchanges/ExchangeMapping'

// ── Fixtures ──────────────────────────────────────────────────────────────────

const CATALOG = {
  products: [
    {
      id: 'p-hat', title: 'Bucket Hat', status: 'active', imageUrl: null,
      variants: [
        { id: 'v-hat-yellow', title: 'Yellow stripes', sku: 'HAT-YEL', price: 300 },
        { id: 'v-hat-red', title: 'Red checkered', sku: 'HAT-RED', price: 300 },
      ],
    },
  ],
}

const EXCHANGE = {
  id: 'exc-1',
  tracking_number: '877468285',
  outbound_description: 'Yellow stripes bucket hat size XS/S/M/L   ',
  inbound_description: 'red checkered bucket hat',
  inbound_description_ar: 'قبعة دلو مربعة حمراء',
  cod: 0,
  goods_value: 600,
  outbound_items_count: 1,
  inbound_items_count: 1,
  customer_name: 'Maya Mostafa',
  customer_phone: '+201000301512',
}

function fakeResponse(data: unknown, status = 200) {
  return Promise.resolve({
    ok: status >= 200 && status < 300,
    status,
    headers: { get: (k: string) => (k === 'content-type' ? 'application/json' : null) },
    // Deep-clone on every read — matches real fetch() Response semantics; a shared
    // reference here would mask prop-identity bugs across separate responses.
    json: async () => structuredClone(data),
    text: async () => JSON.stringify(data),
  })
}

let mapCallBody: unknown = null

function backendFetch(url: string, opts: RequestInit = {}) {
  const method = (opts.method ?? 'GET').toUpperCase()

  if (url.includes('/api/v1/catalog')) return fakeResponse(CATALOG)
  if (url.includes('/exchanges') && method === 'GET') return fakeResponse([EXCHANGE])
  if (url.endsWith('/exchanges/exc-1/map') && method === 'POST') {
    mapCallBody = JSON.parse(opts.body as string)
    return fakeResponse({ exchangeId: 'exc-1', orderId: 'order-99', status: 'mapped' })
  }
  return fakeResponse({})
}

let mockFetch: ReturnType<typeof vi.fn>

function renderScreen() {
  return renderWithProviders(
    <Routes>
      <Route path="/exchanges/:id" element={<ExchangeMapping />} />
    </Routes>,
    { initialEntries: ['/exchanges/exc-1'] },
  )
}

beforeEach(() => {
  vi.clearAllMocks()
  mapCallBody = null
  mockFetch = vi.fn(backendFetch)
  stubFetchWithShellDefaults(mockFetch)
})

describe('Exchange mapping screen', () => {
  // em1: raw descriptions render read-only, trailing whitespace trimmed, EN + AR both shown
  test('em1 renders both legs with trimmed raw descriptions, inbound AR included', async () => {
    renderScreen()

    await screen.findByTestId('exchange-leg-outbound')
    const outboundCard = screen.getByTestId('exchange-leg-outbound')
    // getByText() normalizes whitespace (collapses/trims) by default, which would make
    // this assertion pass even if trimTrailing() were a no-op — a custom normalizer
    // that returns the string AS-IS is required to actually catch untrimmed trailing
    // whitespace (confirmed by reverting trimTrailing() to identity: the getByText-only
    // version of this assertion still passed).
    const outboundText = within(outboundCard).getByText(
      (_content, node) => node?.textContent === 'Yellow stripes bucket hat size XS/S/M/L',
      { normalizer: s => s },
    )
    expect(outboundText).toBeInTheDocument()

    const inboundCard = screen.getByTestId('exchange-leg-inbound')
    expect(within(inboundCard).getByText('red checkered bucket hat')).toBeInTheDocument()
    expect(within(inboundCard).getByText('قبعة دلو مربعة حمراء')).toBeInTheDocument()
  })

  // em2: Confirm is disabled until both legs are selected
  test('em2 confirm button disabled until both legs selected, enabled after both', async () => {
    const user = userEvent.setup()
    renderScreen()
    await screen.findByTestId('exchange-leg-outbound')

    const confirmBtn = screen.getByRole('button', { name: /Confirm mapping/i })
    expect(confirmBtn).toBeDisabled()

    // Select outbound
    const outboundCard = screen.getByTestId('exchange-leg-outbound')
    await user.click(within(outboundCard).getByRole('button', { name: /Select variant/i }))
    await screen.findByTestId('exchange-variant-picker')
    await user.click(screen.getByTestId('exchange-picker-product-p-hat'))
    await screen.findByTestId('exchange-picker-variant-list')
    await user.click(screen.getByTestId('exchange-picker-variant-v-hat-yellow'))

    expect(confirmBtn).toBeDisabled()
    expect(within(outboundCard).getByText('Bucket Hat')).toBeInTheDocument()
    expect(within(outboundCard).getByText('Yellow stripes')).toBeInTheDocument()

    // Select inbound
    const inboundCard = screen.getByTestId('exchange-leg-inbound')
    await user.click(within(inboundCard).getByRole('button', { name: /Select variant/i }))
    await screen.findByTestId('exchange-variant-picker')
    await user.click(screen.getByTestId('exchange-picker-product-p-hat'))
    await screen.findByTestId('exchange-picker-variant-list')
    await user.click(screen.getByTestId('exchange-picker-variant-v-hat-red'))

    expect(confirmBtn).not.toBeDisabled()
  })

  // em3: confirming posts both variant ids and shows the success state
  test('em3 confirm posts outboundVariantId/inboundVariantId and shows success', async () => {
    const user = userEvent.setup()
    renderScreen()
    await screen.findByTestId('exchange-leg-outbound')

    async function selectVariant(legTestId: string, productId: string, variantId: string) {
      const card = screen.getByTestId(legTestId)
      await user.click(within(card).getByRole('button', { name: /Select variant|Change/i }))
      await screen.findByTestId('exchange-variant-picker')
      await user.click(screen.getByTestId(`exchange-picker-product-${productId}`))
      await screen.findByTestId('exchange-picker-variant-list')
      await user.click(screen.getByTestId(`exchange-picker-variant-${variantId}`))
    }

    await selectVariant('exchange-leg-outbound', 'p-hat', 'v-hat-yellow')
    await selectVariant('exchange-leg-inbound', 'p-hat', 'v-hat-red')

    await user.click(screen.getByRole('button', { name: /Confirm mapping/i }))

    await waitFor(() => screen.getByTestId('exchange-mapped-success'))
    expect(mapCallBody).toEqual({
      outboundVariantId: 'v-hat-yellow',
      inboundVariantId: 'v-hat-red',
    })
  })

  // em4: picker search never mutates the raw description text (no auto-fill/parsing)
  test('em4 picker search box starts empty — raw description never fed into search', async () => {
    const user = userEvent.setup()
    renderScreen()
    await screen.findByTestId('exchange-leg-outbound')

    const outboundCard = screen.getByTestId('exchange-leg-outbound')
    await user.click(within(outboundCard).getByRole('button', { name: /Select variant/i }))
    await screen.findByTestId('exchange-variant-picker')

    const searchInput = screen.getByPlaceholderText(/Search products or SKU/i)
    expect(searchInput).toHaveValue('')
  })
})
