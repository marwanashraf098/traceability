import { test, expect, describe, vi, beforeEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { renderWithProviders, screen, waitFor } from './renderWithProviders'
import * as api from '../api'
import { WaitingForReleaseSection } from '../pages/fulfill/ShadowExchange'
import en from '../locales/en.json'
import ar from '../locales/ar.json'

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return { ...actual, getRoleFromToken: vi.fn(() => 'owner' as const), releaseShopifyExchange: vi.fn() }
})

const row = (over: Partial<api.WaitingForReleaseOrder> = {}): api.WaitingForReleaseOrder => ({
  id: 'x-1', number: '#1009-X1', placed_at: '2026-10-25T10:00:00Z', status: 'confirmed', unexplained: true,
  exchangeFor: { orderId: 'o-1', number: '#1009' }, shortBy: 0,
  items: [{ variant_id: 'v', product_title: 'LINEN SHIRT', variant_title: 'M', sku: 'S', quantity: 1 }],
  ...over,
})

// S3d: "Release reservation" on a Shopify exchange waiting for release (owner / manager), and the unexplained note.
describe('Waiting for release — Release reservation', () => {
  beforeEach(() => {
    vi.mocked(api.getRoleFromToken).mockReturnValue('owner')
    vi.mocked(api.releaseShopifyExchange).mockReset()
  })

  test('owner: two steps, then the row leaves', async () => {
    vi.mocked(api.releaseShopifyExchange).mockResolvedValue({ orderId: 'x-1', number: '#1009-X1', state: 'ended' })
    renderWithProviders(<WaitingForReleaseSection rows={[row()]} />)
    expect(screen.getByTestId('unexplained-x-1')).toHaveTextContent("Traced can't see why")
    await userEvent.click(screen.getByTestId('release-x-1'))
    expect(api.releaseShopifyExchange).not.toHaveBeenCalled()
    await userEvent.click(screen.getByTestId('release-yes-x-1'))
    expect(api.releaseShopifyExchange).toHaveBeenCalledWith('x-1')
    await waitFor(() => expect(screen.queryByTestId('waiting-for-release')).not.toBeInTheDocument())
  })

  test('a refusal is shown and the row stays', async () => {
    vi.mocked(api.releaseShopifyExchange).mockRejectedValue(new Error('A piece is already scanned or packed for this exchange — unscan it first'))
    renderWithProviders(<WaitingForReleaseSection rows={[row()]} />)
    await userEvent.click(screen.getByTestId('release-x-1'))
    await userEvent.click(screen.getByTestId('release-yes-x-1'))
    expect(await screen.findByRole('alert')).toHaveTextContent('unscan it first')
    expect(screen.getByTestId('waiting-row-x-1')).toBeInTheDocument()
  })

  test('worker: no release action; an explained wait has no note', () => {
    vi.mocked(api.getRoleFromToken).mockReturnValue('worker')
    renderWithProviders(<WaitingForReleaseSection rows={[row({ unexplained: false })]} />)
    expect(screen.queryByTestId('release-x-1')).not.toBeInTheDocument()
    expect(screen.queryByTestId('unexplained-x-1')).not.toBeInTheDocument()
  })

  test('Arabic copy for every new key', () => {
    for (const k of ['release', 'releaseConfirm', 'releaseYes', 'unexplained']) {
      expect((ar as any).fulfill.shadow[k], k).toBeTruthy()
      expect((en as any).fulfill.shadow[k], k).toBeTruthy()
    }
    expect((ar as any).fulfill.shadow.releaseConfirm).toContain('{{number}}')
  })
})
