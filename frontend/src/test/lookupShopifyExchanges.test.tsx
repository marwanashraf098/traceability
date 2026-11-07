import { test, expect, describe, vi } from 'vitest'
import userEvent from '@testing-library/user-event'
import { renderWithProviders, screen } from './renderWithProviders'
import { ShopifyExchangesNested } from '../pages/Lookup'
import en from '../locales/en.json'
import ar from '../locales/ar.json'

// S3d: Lookup's order result lists the original's Shopify-native exchanges (#…-X1) in its drawer.
describe('Lookup — Shopify exchanges nested under the original', () => {
  test('lists each exchange with its state; clicking one opens it', async () => {
    const onOpen = vi.fn()
    renderWithProviders(
      <ShopifyExchangesNested onOpen={onOpen} result={{
        type: 'order', orderId: 'o-1', orderNumber: '#1010',
        shopifyExchanges: [
          { orderId: 'x-1', orderNumber: '#1010-X1', status: 'confirmed', state: 'live' },
          { orderId: 'x-2', orderNumber: '#1010-X2', status: 'confirmed', state: 'ended' },
        ],
      }} />)
    const box = screen.getByTestId('shopify-exchanges')
    expect(box).toHaveTextContent('Exchanges made in Shopify')
    expect(box).toHaveTextContent('#1010-X1')
    expect(box).toHaveTextContent('Kept aside')
    expect(box).toHaveTextContent('Ended')
    await userEvent.click(screen.getByText('#1010-X2'))
    expect(onOpen).toHaveBeenCalledWith('x-2')
  })

  test('a plain order (no key) renders nothing', () => {
    renderWithProviders(<ShopifyExchangesNested onOpen={vi.fn()} result={{ type: 'order', orderId: 'o-2', orderNumber: '#1011' }} />)
    expect(screen.queryByTestId('shopify-exchanges')).not.toBeInTheDocument()
  })

  test('every new key has an Arabic twin', () => {
    const e = (en as any).lookup.shopifyExchanges, a = (ar as any).lookup.shopifyExchanges
    for (const k of Object.keys(e)) expect(a[k], k).toBeTruthy()
    expect(a.back).toContain('{{number}}')
  })
})
