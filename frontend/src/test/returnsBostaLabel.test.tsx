import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { render, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { I18nextProvider } from 'react-i18next'
import { renderWithProviders, screen, waitFor } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import { ToastProvider } from '../components/ui'
import { StationProvider } from '../components/StationProvider'
import Returns from '../pages/Returns'
import * as api from '../api'
import i18n from '../i18n'

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return { ...actual, getRoleFromToken: vi.fn() }
})

/**
 * Bug 2 / 3 (approved 2026-11-16) — Scan returns reads Bosta's label lines (bostaLabel, from BostaLabelLines):
 * untracked units on the label first, the rest under "Not on the Bosta label"; marking past the label total or a
 * unit not on it asks first (never blocks); a tracked parcel no request holds says "up to N could come back" and
 * highlights the label's pieces. D3: the "Back on shelf" feedback for an unpacked piece. EN + AR.
 */

function jsonOk(data: unknown, status = 200) {
  return Promise.resolve({
    ok: true, status,
    headers: { get: (k: string) => (k === 'content-length' ? '1' : 'application/json') },
    json: async () => structuredClone(data),
  })
}
function noContent() {
  return Promise.resolve({ ok: true, status: 204, headers: { get: (k: string) => (k === 'content-length' ? '0' : null) }, json: async () => null })
}

const SESSION = 'aaaaaaaa-0000-0000-0000-00000000000b'
const SHIP = 'bbbbbbbb-0000-0000-0000-000000047431'

function unit(sku: string, title: string, o: Record<string, unknown> = {}) {
  return {
    orderItemId: `oi-${sku}`, unitNo: 1, units: 1, productTitle: title, variantTitle: 'M', sku,
    intakeId: null, condition: null, viaPhone: false, markedBy: null, markedAt: null, markedInSession: null, ...o,
  }
}

function crpParcel(o: Record<string, unknown> = {}) {
  return {
    shipmentId: SHIP, awb: '7139234160', leg: 'return', orderNumber: 'BRK-47431-EG', customerShortName: 'Mona A.',
    returnedAt: '2026-11-14T10:00:00Z',
    bosta: { itemsCount: 1, description: 'x1 Boxy Oversized Crewneck – Burgundy - M - SKU: BRK-RGC-2627-BRG-M', descriptionAr: null },
    tracked: false, intakeOutcome: null, markedBy: null, markedAt: null, markedInThisSession: false,
    expectedPieces: [], scannedItems: [], counts: { expected: 0, scanned: 0 }, complete: false,
    returnedToSender: false, unitsIn: 0, canMarkReceived: true,
    bostaLabel: { total: 1, lines: [{ sku: 'BRK-RGC-2627-BRG-M', quantity: 1, name: 'Boxy Oversized Crewneck – Burgundy - M' }] },
    untrackedUnits: [
      unit('BRK-RGC-2627-BRG-M', 'Boxy Oversized Crewneck – Burgundy', { onLabel: true, labelQuantity: 1 }),
      unit('BROEK-Raw-Denim-RFD-1', 'Raw Denim Relaxed Fit Jeans', { onLabel: false, labelQuantity: 0 }),
      unit('BROEK-Gray-RFD-1', 'Gray Relaxed Fit Denim Jeans', { onLabel: false, labelQuantity: 0 }),
    ],
    ...o,
  }
}

function piece(id: string, sku: string, onLabel: boolean | undefined) {
  return { id, barcode: `PC-${id}`, status: 'delivered', variant_title: '32', product_title: `Jeans ${sku}`, sku, awb: '4226201959',
    ...(onLabel === undefined ? {} : { onLabel, labelQuantity: onLabel ? 1 : 0 }) }
}

function trackedParcel(label: boolean) {
  const skus = ['BRK-JEANS-BLK-RF-002', 'BRK-JEANS-BGE-RF-002', 'BRK-JEANS-WHT-RF-002', 'BROEK-DARK-WASHED-RFD-001', 'BROEK-Gray-RFD-1']
  return {
    shipmentId: 'bbbbbbbb-0000-0000-0000-000000047247', awb: '4226201959', leg: 'return', orderNumber: 'BRK-47247-EG',
    customerShortName: 'Sara M.', returnedAt: '2026-11-14T10:00:00Z',
    bosta: { itemsCount: 3, description: 'x1 Washed Black Relaxed Fit Denim Jeans - 32 - SKU: BRK-JEANS-BLK-RF-002 // …', descriptionAr: null },
    tracked: true, intakeOutcome: null, markedBy: null, markedAt: null, markedInThisSession: false,
    expectedPieces: skus.map((s, i) => piece(`p${i}`, s, label ? i < 3 : undefined)),
    scannedItems: [{
      id: 'item-1', piece_id: 'p9', barcode: 'PC-p9', short_code: 'P000999', status: 'return_pending_inspection',
      variant_title: '32', product_title: 'Jeans', sku: 'BROEK-Raw-Denim-RFD-1', disposition: 'pending', unexpected: false,
      scan_source: 'barcode', damage_reason: null, scanned_at: '2026-11-14T16:00:00Z', disposition_at: null,
      ...(label ? { onLabel: false, labelQuantity: 0 } : {}),
    }],
    counts: { expected: 6, scanned: 1 }, complete: false,
    ...(label ? { bostaLabel: { total: 3, lines: [] } } : {}),
  }
}

function detail(o: Record<string, unknown> = {}) {
  return {
    id: SESSION, status: 'open', opened_by: 'user-1', opened_at: new Date().toISOString(), closed_by: null, closed_at: null,
    note: null, items: [], expectedPieces: [], courierReturns: [], parcels: [], otherItems: [], lastScan: null, ...o,
  }
}

let mockFetch: ReturnType<typeof vi.fn>

async function openWith(after: ReturnType<typeof detail>, arabic = false) {
  const user = userEvent.setup()
  mockFetch.mockReturnValueOnce(jsonOk({ sessionId: SESSION }, 201)).mockReturnValueOnce(jsonOk(detail()))
  if (arabic) {
    await i18n.changeLanguage('ar')
    render(<StationProvider><MemoryRouter><I18nextProvider i18n={i18n}><ToastProvider><Returns /></ToastProvider></I18nextProvider></MemoryRouter></StationProvider>)
  } else {
    renderWithProviders(<Returns />)
  }
  await user.click(await screen.findByTestId('open-session-button'))
  await waitFor(() => screen.getByTestId('open-session-screen'))
  mockFetch.mockReturnValueOnce(jsonOk({ scanType: 'awb' })).mockReturnValueOnce(jsonOk(after))
  await user.type(screen.getByTestId('scan-input'), `7139234160{Enter}`)
  return user
}

function unitPosts() {
  return mockFetch.mock.calls.filter(([u, o]) => String(u).includes('/units/arrived') && (o as RequestInit)?.method === 'POST')
}

describe('Scan returns — Bosta label lines (Bug 2 / 3)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockFetch = vi.fn()
    stubFetchWithShellDefaults((url: string, opts?: RequestInit) =>
      String(url).includes('/returns/awaiting-scan') ? jsonOk({ count: 0, items: [] }) : mockFetch(url, opts))
    vi.mocked(api.getRoleFromToken).mockReturnValue('worker')
    vi.stubGlobal('localStorage', { getItem: vi.fn().mockReturnValue(null), setItem: vi.fn(), removeItem: vi.fn() })
  })
  afterEach(async () => {
    vi.unstubAllGlobals()
    await i18n.changeLanguage('en')
  })

  test('bl1 untracked parcel: label line + count from the label, label rows first, the rest under "Not on the Bosta label"', async () => {
    await openWith(detail({ parcels: [crpParcel()] }))
    const card = await screen.findByTestId('parcel-card-7139234160')
    expect(within(card).getByTestId('parcel-bosta-label')).toHaveTextContent('Bosta label: 1 item')
    expect(within(card).getByTestId('parcel-pill')).toHaveTextContent('0 of 1 in')
    expect(within(card).getByTestId('untracked-unit-on-label')).toHaveTextContent("On Bosta's label × 1")
    const off = within(card).getByTestId('parcel-units-off-label')
    expect(off).toHaveTextContent('Not on the Bosta label (2)')
    expect(within(off).getByTestId('untracked-unit-oi-BROEK-Gray-RFD-1-1')).toBeInTheDocument()
    expect(within(card).getAllByTestId(/^untracked-unit-oi-/)[0]).toHaveAttribute('data-testid', 'untracked-unit-oi-BRK-RGC-2627-BRG-M-1')
  })

  test('bl2 a unit not on the label asks first: Cancel sends nothing, "Mark it" sends the mark', async () => {
    const user = await openWith(detail({ parcels: [crpParcel()] }))
    const card = await screen.findByTestId('parcel-card-7139234160')
    const raw = within(card).getByTestId('untracked-unit-oi-BROEK-Raw-Denim-RFD-1-1')
    await user.click(within(raw).getByTestId('untracked-unit-damaged'))
    expect(await screen.findByTestId('label-confirm')).toHaveTextContent("Bosta's label says 1 item — mark another?")
    expect(unitPosts()).toHaveLength(0)
    await user.click(within(screen.getByTestId('label-confirm')).getByRole('button', { name: 'Cancel' }))
    expect(screen.queryByTestId('label-confirm')).toBeNull()
    expect(unitPosts()).toHaveLength(0)

    await user.click(within(raw).getByTestId('untracked-unit-damaged'))
    mockFetch.mockReturnValueOnce(noContent()).mockReturnValueOnce(jsonOk(detail({ parcels: [crpParcel()] })))
    await user.click(await screen.findByTestId('label-confirm-yes'))
    await waitFor(() => expect(unitPosts()).toHaveLength(1))
    expect(JSON.parse((unitPosts()[0][1] as RequestInit).body as string))
      .toEqual({ orderItemId: 'oi-BROEK-Raw-Denim-RFD-1', unitNo: 1, condition: 'damaged' })
  })

  test('bl3 past the label total asks first even on the label; within it, no question', async () => {
    const crewTwo = (marked: boolean) => crpParcel({
      unitsIn: marked ? 1 : 0,
      untrackedUnits: [
        unit('BRK-RGC-2627-BRG-M', 'Crewneck', { units: 2, onLabel: true, labelQuantity: 1,
          ...(marked ? { intakeId: 'i-1', condition: 'sellable', markedInSession: SESSION } : {}) }),
        unit('BRK-RGC-2627-BRG-M', 'Crewneck', { unitNo: 2, units: 2, onLabel: true, labelQuantity: 1 }),
      ],
    })
    const user = await openWith(detail({ parcels: [crewTwo(false)] }))
    let card = await screen.findByTestId('parcel-card-7139234160')
    mockFetch.mockReturnValueOnce(noContent()).mockReturnValueOnce(jsonOk(detail({ parcels: [crewTwo(true)] })))
    await user.click(within(within(card).getByTestId('untracked-unit-oi-BRK-RGC-2627-BRG-M-1')).getByTestId('untracked-unit-sellable'))
    await waitFor(() => expect(unitPosts()).toHaveLength(1))
    expect(screen.queryByTestId('label-confirm')).toBeNull()

    card = await screen.findByTestId('parcel-card-7139234160')
    await waitFor(() => within(card).getByTestId('untracked-unit-oi-BRK-RGC-2627-BRG-M-2'))
    await user.click(within(within(card).getByTestId('untracked-unit-oi-BRK-RGC-2627-BRG-M-2')).getByTestId('untracked-unit-sellable'))
    expect(await screen.findByTestId('label-confirm')).toHaveTextContent("Bosta's label says 1 item — mark another?")
    expect(unitPosts()).toHaveLength(1)
  })

  test('bl4 tracked parcel with no request: "up to N could come back", the label line, the label pieces highlighted', async () => {
    await openWith(detail({ parcels: [trackedParcel(true)] }))
    const card = await screen.findByTestId('parcel-card-4226201959')
    expect(within(card).getByTestId('parcel-pill')).toHaveTextContent('1 scanned · up to 6 could come back')
    expect(within(card).getByTestId('parcel-bosta-label')).toHaveTextContent('Bosta label: 3 items')
    expect(within(card).getAllByTestId('piece-on-label')).toHaveLength(3)
  })

  test('bl5 no readable label: no label line, no grouping, no highlight, no question', async () => {
    const user = await openWith(detail({ parcels: [crpParcel({ bostaLabel: undefined, untrackedUnits: [
      unit('BRK-RGC-2627-BRG-M', 'Crewneck'), unit('BROEK-Gray-RFD-1', 'Gray')] })] }))
    const card = await screen.findByTestId('parcel-card-7139234160')
    expect(within(card).queryByTestId('parcel-bosta-label')).toBeNull()
    expect(within(card).queryByTestId('parcel-units-off-label')).toBeNull()
    expect(within(card).getByTestId('parcel-pill')).toHaveTextContent('0 of 2 in')
    mockFetch.mockReturnValueOnce(noContent()).mockReturnValueOnce(jsonOk(detail({ parcels: [crpParcel()] })))
    await user.click(within(within(card).getByTestId('untracked-unit-oi-BROEK-Gray-RFD-1-1')).getByTestId('untracked-unit-damaged'))
    await waitFor(() => expect(unitPosts()).toHaveLength(1))
    expect(screen.queryByTestId('label-confirm')).toBeNull()
  })

  test('bl6 Arabic: the label line, "up to", "not on the label" and the question read in Arabic with بوسطة', async () => {
    const user = await openWith(detail({ parcels: [crpParcel(), trackedParcel(true)] }), true)
    const crp = await screen.findByTestId('parcel-card-7139234160')
    expect(within(crp).getByTestId('parcel-bosta-label')).toHaveTextContent('ملصق بوسطة: قطعة واحدة')
    expect(within(crp).getByTestId('parcel-units-off-label')).toHaveTextContent('ليست على ملصق بوسطة (2)')
    await user.click(within(within(crp).getByTestId('untracked-unit-oi-BROEK-Gray-RFD-1-1')).getByTestId('untracked-unit-damaged'))
    expect(await screen.findByTestId('label-confirm')).toHaveTextContent('ملصق بوسطة يقول قطعة واحدة — تسجيل قطعة أخرى؟')
    await user.click(within(screen.getByTestId('label-confirm')).getByRole('button', { name: 'إلغاء' }))
    expect(unitPosts()).toHaveLength(0)
  })

  test('bl6b Arabic "up to" on a tracked parcel with no request', async () => {
    await openWith(detail({ parcels: [trackedParcel(true)] }), true)
    const card = await screen.findByTestId('parcel-card-4226201959')
    expect(within(card).getByTestId('parcel-pill')).toHaveTextContent('تم مسح 1 · حتى 6 قطع ممكن ترجع')
    expect(within(card).getByTestId('parcel-bosta-label')).toHaveTextContent('ملصق بوسطة: 3 قطع')
  })

  test('bl7 D3: an unpacked piece — "Back on shelf — order X needs repacking", EN and AR', async () => {
    await openWith(detail({ lastScan: { kind: 'unpacked', code: 'P004711', label: 'BRK-47903-EG', at: new Date().toISOString() } }))
    expect(await screen.findByTestId('scan-feedback-unpacked'))
      .toHaveTextContent('Back on shelf — order BRK-47903-EG needs repacking · P004711')
  })

  test('bl7b D3 in Arabic', async () => {
    await openWith(detail({ lastScan: { kind: 'unpacked', code: 'P004711', label: 'BRK-47903-EG', at: new Date().toISOString() } }), true)
    expect(await screen.findByTestId('scan-feedback-unpacked')).toHaveTextContent('رجعت للرف — الطلب BRK-47903-EG يحتاج إعادة تجهيز')
  })
})
