import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { renderWithProviders, screen, waitFor, within } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import Fulfill from '../pages/Fulfill'
import PackSessionScreen from '../pages/fulfill/PackSessionScreen'
import type { PackOrderCard, PackSessionView, WaitingForReleaseOrder } from '../api'
import en from '../locales/en.json'
import ar from '../locales/ar.json'

// S3c — Shopify-native exchange "shadow" orders (#1010-X1) in Pick & Pack: the "Waiting for release" list beside
// the queue, the shadow's "Exchange for #…" / "Short by N", the held notice, and the HELD_FOR_RELEASE refusals.
// URL-routed fetch mocks (not a sequential queue), so the extra waiting-for-release call can't desync anything.

function json(data: unknown, status = 200) {
  return Promise.resolve({
    ok: status >= 200 && status < 300, status, statusText: '',
    headers: { get: (k: string) => (k.toLowerCase() === 'content-type' ? 'application/json' : null) },
    json: async () => structuredClone(data),
  })
}

const WAITING: WaitingForReleaseOrder[] = [{
  id: 'shadow-held', number: '#1010-X1', placed_at: '2026-11-05T10:00:00Z', status: 'new',
  exchangeFor: { orderId: 'orig-1010', number: '#1010' }, shortBy: 2,
  items: [{ variant_id: 'v-9', product_title: 'Linen Shirt', variant_title: 'Blue / L', sku: 'LS-BL-L', quantity: 1 }],
}]

const SHADOW_ROW = {
  id: 'shadow-1', number: '#1020-X1', customer_name: 'Mariam', status: 'new', payment_method: null, cod_amount: null,
  total_units: 1, scanned_units: 0, locked_by: null, locked_at: null, is_self_pickup: false, is_exchange: true,
  exchangeFor: { orderId: 'orig-1020', number: '#1020' }, shortBy: 1,
}
const PLAIN_ROW = {
  id: 'plain-1', number: '#1030', customer_name: 'Omar', status: 'new', payment_method: null, cod_amount: null,
  total_units: 1, scanned_units: 0, locked_by: null, locked_at: null, is_self_pickup: false, is_exchange: false,
}

function shadowDetail(heldForRelease: boolean) {
  return {
    id: 'shadow-1', number: '#1020-X1', customer_name: 'Mariam', customer_phone: null, status: 'new',
    payment_method: null, cod_amount: null, locked_by: null, is_self_pickup: false, is_exchange: true,
    cancel_requested_at: null, shipment_id: 'sh-1', tracking_number: '123456789', shipment_has_courier: true,
    exchangeFor: { orderId: 'orig-1020', number: '#1020' }, shortBy: 0, heldForRelease,
    items: [{ id: 'item-1', variant_id: 'v-1', sku: 'SKU-1', variant_title: 'Red / M', product_title: 'Wrap Dress',
      quantity: 1, imageUrl: null, allocated: 0, allocatedPieces: [] }],
  }
}

let waiting: unknown
let queue: unknown[]
let scanResponse: unknown

beforeEach(() => {
  waiting = []
  queue = [SHADOW_ROW, PLAIN_ROW]
  scanResponse = null
  vi.stubGlobal('localStorage', { getItem: vi.fn().mockReturnValue(null), setItem: vi.fn(), removeItem: vi.fn() })
  stubFetchWithShellDefaults(vi.fn((url: string) => {
    if (url.endsWith('/fulfill/waiting-for-release')) return json(waiting)
    if (url.endsWith('/fulfill/queue')) return json(queue)
    if (url.endsWith('/fulfill/shadow-1')) return json(shadowDetail(true))
    if (url.endsWith('/fulfill/shadow-1/scan')) return json(scanResponse)
    return json({})
  }), { passWaitingForRelease: true })
})
afterEach(() => { vi.unstubAllGlobals() })

describe('S3c — queue mode', () => {
  test('the "Waiting for release" section renders from its endpoint, beside the queue and not in it', async () => {
    waiting = WAITING
    renderWithProviders(<Fulfill />)
    const section = await screen.findByTestId('waiting-for-release')
    expect(section).toHaveTextContent('Waiting for release (1)')
    const row = within(section).getByTestId('waiting-row-shadow-held')
    expect(row).toHaveTextContent('#1010-X1')
    expect(row).toHaveTextContent('Exchange')
    expect(row).toHaveTextContent('Exchange for #1010')
    expect(row).toHaveTextContent('Linen Shirt · Blue / L')
    expect(row).toHaveTextContent('Waiting for release in Shopify')
    expect(row).toHaveTextContent('Short by 2')
    // The queue rows are still the queue's own; the held shadow is not one of them.
    expect(screen.getByText('#1030')).toBeTruthy()
    const queueRowsWithHeld = screen.getAllByText('#1010-X1')
    expect(queueRowsWithHeld).toHaveLength(1)
  })

  test('the section is absent when nothing is waiting', async () => {
    renderWithProviders(<Fulfill />)
    await screen.findByText('#1030')
    await waitFor(() => expect(screen.queryByTestId('waiting-for-release')).not.toBeInTheDocument())
  })

  test('the section is absent when the endpoint fails', async () => {
    stubFetchWithShellDefaults(vi.fn((url: string) => {
      if (url.endsWith('/fulfill/waiting-for-release')) return json({ error: 'nope' }, 500)
      if (url.endsWith('/fulfill/queue')) return json(queue)
      return json({})
    }), { passWaitingForRelease: true })
    renderWithProviders(<Fulfill />)
    await screen.findByText('#1030')
    expect(screen.queryByTestId('waiting-for-release')).not.toBeInTheDocument()
  })

  test('a waiting row does not open the pick screen', async () => {
    waiting = WAITING
    const user = userEvent.setup()
    renderWithProviders(<Fulfill />)
    const row = await screen.findByTestId('waiting-row-shadow-held')
    await user.click(row)
    expect(screen.queryByTestId('fulfill-pick')).not.toBeInTheDocument()
    expect(screen.getByTestId('fulfill-queue')).toBeTruthy()
  })

  test('a shadow queue row shows "Exchange for #…" and "Short by N"; a plain row shows neither', async () => {
    renderWithProviders(<Fulfill />)
    await screen.findByText('#1020-X1')
    expect(screen.getByText('Exchange for #1020')).toBeTruthy()
    expect(screen.getByText('Short by 1')).toBeTruthy()
    expect(screen.getAllByTestId('shadow-exchange-for')).toHaveLength(1)
    expect(screen.getAllByTestId('shadow-short-by')).toHaveLength(1)
  })

  test('shortBy 0 shows no "Short by" chip', async () => {
    queue = [{ ...SHADOW_ROW, shortBy: 0 }]
    renderWithProviders(<Fulfill />)
    await screen.findByText('Exchange for #1020')
    expect(screen.queryByTestId('shadow-short-by')).not.toBeInTheDocument()
  })
})

describe('S3c — pick screen (queue mode)', () => {
  test('a held shadow shows "Exchange for #…" and the waiting-for-release notice', async () => {
    const user = userEvent.setup()
    renderWithProviders(<Fulfill />)
    await user.click(await screen.findByText('#1020-X1'))
    await screen.findByTestId('fulfill-pick')
    const notice = await screen.findByTestId('shadow-held-notice')
    expect(notice).toHaveTextContent('Waiting for release in Shopify')
    expect(notice).toHaveTextContent("Shopify hasn't released this exchange for fulfilment yet.")
    expect(screen.getAllByText('Exchange for #1020').length).toBeGreaterThan(0)
  })

  test('a HELD_FOR_RELEASE scan refusal shows its copy (EN)', async () => {
    scanResponse = {
      success: false, code: 'HELD_FOR_RELEASE',
      message: 'This exchange is waiting for release in Shopify — keep the piece aside and pack it once Shopify releases it',
      messageAr: 'هذا الاستبدال في انتظار الإفراج عنه في شوبيفاي — احتفظ بالقطعة جانباً وجهّزها بعد الإفراج عنه',
      pieceId: null, barcode: null, allocatedCount: 0, requiredQuantity: 0, allComplete: false,
    }
    const user = userEvent.setup()
    renderWithProviders(<Fulfill />)
    await user.click(await screen.findByText('#1020-X1'))
    await screen.findByTestId('fulfill-pick')
    await user.type(screen.getByPlaceholderText(/Scan or type barcode/i), 'PC-77{Enter}')
    // The pick screen shows the scan feedback in both its mobile and desktop panels.
    expect((await screen.findAllByText(/✗ This exchange is waiting for release in Shopify — keep the piece aside/)).length)
      .toBeGreaterThan(0)
  })

  test('a HELD_FOR_RELEASE refusal without server text falls back to the house copy', async () => {
    scanResponse = { success: false, code: 'HELD_FOR_RELEASE', message: null, pieceId: null, barcode: null,
      allocatedCount: 0, requiredQuantity: 0, allComplete: false }
    const user = userEvent.setup()
    renderWithProviders(<Fulfill />)
    await user.click(await screen.findByText('#1020-X1'))
    await screen.findByTestId('fulfill-pick')
    await user.type(screen.getByPlaceholderText(/Scan or type barcode/i), 'PC-77{Enter}')
    expect((await screen.findAllByText(`✗ ${en.fulfill.rejection.HELD_FOR_RELEASE}`)).length).toBeGreaterThan(0)
  })
})

describe('S3c — waybill mode', () => {
  const CARD: PackOrderCard = {
    id: 'shadow-1', number: '#1020-X1', customer_name: 'Mariam', payment_method: null, cod_amount: null,
    tracking_number: '123456789', area: 'Maadi, Cairo', courierType: 'delivery', batchNo: 2, batchPrintedAt: null,
    items: [{ id: 'li-1', variant_id: 'v-1', sku: 'SKU-1', variant_title: 'Red / M', product_title: 'Wrap Dress',
      imageUrl: null, quantity: 1, allocated: 0, allocatedPieces: [] }],
    exchangeFor: { orderId: 'orig-1020', number: '#1020' }, shortBy: 3, heldForRelease: true,
  }
  const VIEW: PackSessionView = {
    id: 'sess-1', mode: 'waybill_scan', status: 'open', startedAt: '2026-11-07T07:05:00Z', workerName: 'Mona',
    counters: { packed: 0, setAside: 0, rejected: 0, left: 1 }, recent: [], openOrder: null,
  }

  test('the order card shows "Exchange for #…", "Short by N" and the held notice', async () => {
    renderWithProviders(<PackSessionScreen initial={{ ...VIEW, openOrder: CARD }} onEnded={() => {}} />)
    const card = await screen.findByTestId('order-card')
    expect(card).toHaveTextContent('Exchange for #1020')
    expect(card).toHaveTextContent('Short by 3')
    expect(within(card).getByTestId('shadow-held-notice')).toHaveTextContent('Waiting for release in Shopify')
  })

  test('a plain order card shows none of it', async () => {
    const plain: PackOrderCard = { ...CARD, number: '#1030', exchangeFor: undefined, shortBy: undefined, heldForRelease: undefined }
    renderWithProviders(<PackSessionScreen initial={{ ...VIEW, openOrder: plain }} onEnded={() => {}} />)
    const card = await screen.findByTestId('order-card')
    expect(within(card).queryByTestId('shadow-exchange-for')).not.toBeInTheDocument()
    expect(within(card).queryByTestId('shadow-short-by')).not.toBeInTheDocument()
    expect(within(card).queryByTestId('shadow-held-notice')).not.toBeInTheDocument()
  })

  test('a HELD_FOR_RELEASE waybill shows its title and the server message', async () => {
    stubFetchWithShellDefaults(vi.fn((url: string) => {
      if (url.endsWith('/pack-sessions/sess-1/waybill')) {
        return json({ result: 'rejected', order: null, code: 'HELD_FOR_RELEASE', subReason: null, orderNumber: '#1020-X1',
          who: null, at: null, state: null,
          messageEn: 'Exchange #1020-X1 is waiting for release in Shopify. Keep the waybill and the piece aside until it is released.',
          messageAr: 'الاستبدال #1020-X1 في انتظار الإفراج عنه في شوبيفاي.' })
      }
      if (url.endsWith('/pack-sessions/sess-1')) return json(VIEW)
      if (url.endsWith('/me')) return json({ name: 'Mona', email: null, role: 'worker' })
      return json({})
    }), { passWaitingForRelease: true })
    const user = userEvent.setup()
    renderWithProviders(<PackSessionScreen initial={VIEW} onEnded={() => {}} />)
    await user.type(screen.getByRole('textbox'), '123456789{Enter}')
    const rejected = await screen.findByTestId('session-rejected')
    expect(within(rejected).getByTestId('rejected-title')).toHaveTextContent('Waiting for release in Shopify')
    expect(rejected).toHaveTextContent('Keep the waybill and the piece aside until it is released.')
  })
})

describe('S3c — copy', () => {
  test('every new English key has an Arabic twin', () => {
    const enShadow = en.fulfill.shadow as Record<string, string>
    const arShadow = ar.fulfill.shadow as Record<string, string>
    for (const k of Object.keys(enShadow)) {
      expect(arShadow[k], `fulfill.shadow.${k}`).toBeTruthy()
      expect(arShadow[k]).not.toEqual(enShadow[k])
      expect(arShadow[k]).toMatch(/[؀-ۿ]/)
    }
    expect(ar.fulfill.rejection.HELD_FOR_RELEASE).toMatch(/[؀-ۿ]/)
    expect(ar.fulfill.waybill.rejected.HELD_FOR_RELEASE).toMatch(/[؀-ۿ]/)
    // Interpolations survive translation.
    expect(arShadow.exchangeFor).toContain('{{number}}')
    expect(arShadow.shortBy).toContain('{{count}}')
  })
})
