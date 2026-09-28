import { test, expect, describe, vi, beforeEach } from 'vitest'
import { Routes, Route } from 'react-router-dom'
import userEvent from '@testing-library/user-event'
import { renderWithProviders, screen, waitFor, within } from './renderWithProviders'
import * as api from '../api'
import Transfers from '../pages/Transfers'

// One "+ New transfer" entry point replaces the Relocate / Return / New Transfer header
// buttons. The gating rules are unchanged: Send out and back always; Move to another
// location needs a destination; Bring back needs a destination AND a relocate_out transfer
// to have ever existed. One visible option → straight to its form, no chooser.

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return {
    ...actual,
    listOpenTransfers: vi.fn(),
    listTransferDestinations: vi.fn(),
    listLocations: vi.fn(),
    getRoleFromToken: vi.fn(),
  }
})

function summary(overrides: Partial<api.TransferSummary>): api.TransferSummary {
  return {
    id: 't1', transfer_type: 'other', transfer_mode: 'round_trip', status: 'preparing',
    note: null, expected_return_at: null, created_by: 'u1', created_at: new Date().toISOString(),
    destination_location_id: 'd1', destination_location_name: 'Vendor A', outstanding_count: 0,
    ...overrides,
  }
}

const DEST = { id: 'd1', name: 'Warehouse 2', is_fulfillment: false }

function setup({ destinations, all = [], open = [] }: {
  destinations: api.LocationOption[]; all?: api.TransferSummary[]; open?: api.TransferSummary[]
}) {
  vi.mocked(api.listTransferDestinations).mockResolvedValue(destinations)
  vi.mocked(api.listOpenTransfers).mockImplementation(async (view) => (view === 'all' ? all : open))
  vi.mocked(api.listLocations).mockResolvedValue([])
}

function renderPage() {
  return renderWithProviders(
    <Routes><Route path="/transfers" element={<Transfers />} /></Routes>,
    { initialEntries: ['/transfers'] },
  )
}

/** The header button — first in DOM order (the empty state, when shown, carries a second). */
async function headerButton() {
  const btn = (await screen.findAllByRole('button', { name: '+ New transfer' }))[0]
  await waitFor(() => expect(btn).not.toBeDisabled())
  return btn
}

describe('Transfers — "+ New transfer" chooser', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(api.getRoleFromToken).mockReturnValue('owner')
  })

  test('nc1 — only one option (no destinations): opens the round-trip form directly, no chooser', async () => {
    setup({ destinations: [] })
    const user = userEvent.setup()
    renderPage()

    await user.click(await headerButton())

    expect(screen.queryByTestId('new-transfer-chooser')).not.toBeInTheDocument()
    expect(await screen.findByText('Create Transfer')).toBeInTheDocument()
    expect(screen.getByTestId('type-showroom-btn')).toBeInTheDocument()
  })

  test('nc2 — destinations but never relocated: chooser shows exactly two cards', async () => {
    setup({ destinations: [DEST], all: [summary({ transfer_mode: 'round_trip' })] })
    const user = userEvent.setup()
    renderPage()

    await user.click(await headerButton())

    const chooser = await screen.findByTestId('new-transfer-chooser')
    expect(screen.getByText('What do you want to do?')).toBeInTheDocument()
    expect(within(chooser).getByTestId('chooser-option-create')).toHaveTextContent('Send out and back')
    expect(within(chooser).getByTestId('chooser-option-relocate')).toHaveTextContent('Move to another location')
    expect(within(chooser).queryByTestId('chooser-option-return')).not.toBeInTheDocument()
    expect(within(chooser).getAllByRole('button')).toHaveLength(2)
  })

  test('nc3 — destinations + a past relocate: three cards, each opens its own form', async () => {
    setup({ destinations: [DEST], all: [summary({ transfer_mode: 'relocate_out', status: 'closed' })] })
    const user = userEvent.setup()
    renderPage()

    const cases: [string, string, string][] = [
      ['create', 'Send out and back', 'Create Transfer'],
      ['relocate', 'Move to another location', 'Start move'],
      ['return', 'Bring back', 'Start bringing back'],
    ]
    for (const [opt, heading, submit] of cases) {
      await user.click(await headerButton())
      const chooser = await screen.findByTestId('new-transfer-chooser')
      expect(within(chooser).getAllByRole('button')).toHaveLength(3)
      await user.click(within(chooser).getByTestId(`chooser-option-${opt}`))

      expect(screen.queryByTestId('new-transfer-chooser')).not.toBeInTheDocument()
      expect(await screen.findByRole('heading', { level: 1, name: heading })).toBeInTheDocument()
      expect(screen.getByRole('button', { name: submit })).toBeInTheDocument()

      await user.click(screen.getByRole('button', { name: 'Cancel' }))
      await screen.findByTestId('transfers-summary')
    }
  })

  test('nc4 — button is disabled while the gating checks are still loading', async () => {
    vi.mocked(api.listTransferDestinations).mockReturnValue(new Promise(() => {}))
    vi.mocked(api.listOpenTransfers).mockResolvedValue([])
    const user = userEvent.setup()
    renderPage()

    await screen.findByTestId('transfers-summary')
    const btns = screen.getAllByRole('button', { name: '+ New transfer' })
    btns.forEach(b => expect(b).toBeDisabled())
    await user.click(btns[0])
    expect(screen.queryByTestId('new-transfer-chooser')).not.toBeInTheDocument()
    expect(screen.queryByText('Create Transfer')).not.toBeInTheDocument()
  })

  test('nc5 — empty-state button behaves like the header button (opens the chooser)', async () => {
    setup({ destinations: [DEST] })
    const user = userEvent.setup()
    renderPage()

    await screen.findByText('No transfers yet')
    const btns = screen.getAllByRole('button', { name: '+ New transfer' })
    expect(btns).toHaveLength(2)
    await waitFor(() => expect(btns[1]).not.toBeDisabled())
    await user.click(btns[1])
    expect(await screen.findByTestId('new-transfer-chooser')).toBeInTheDocument()
  })

  test('nc6 — Type column names the mode for relocate / bring back, the category for round trips', async () => {
    const rows = [
      summary({ id: 'a', transfer_mode: 'relocate_out', destination_location_name: 'Loc A' }),
      summary({ id: 'b', transfer_mode: 'relocate_return', destination_location_name: 'Loc B' }),
      summary({ id: 'c', transfer_mode: 'round_trip', transfer_type: 'repair', destination_location_name: 'Loc C' }),
      summary({ id: 'd', transfer_mode: 'round_trip', transfer_type: 'other', destination_location_name: 'Loc D' }),
    ]
    setup({ destinations: [DEST], all: rows, open: rows })
    renderPage()

    const typeCell = async (dest: string) =>
      (await screen.findByText(dest)).closest('tr')!.querySelectorAll('td')[1]
    expect(await typeCell('Loc A')).toHaveTextContent('Move to another location')
    expect(await typeCell('Loc B')).toHaveTextContent('Bring back')
    expect(await typeCell('Loc C')).toHaveTextContent('Repair')
    expect(await typeCell('Loc D')).toHaveTextContent('Other')
  })
})
