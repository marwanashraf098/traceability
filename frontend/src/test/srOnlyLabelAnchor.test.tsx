import { test, expect, vi, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { screen } from '@testing-library/react'
import { renderWithProviders } from './renderWithProviders'
import { Checkbox, Radio } from '../components/ui'
import CustomerFeesSection from '../pages/settings/CustomerFeesSection'

// Prod 2026-11-06 — an sr-only (position:absolute) input inside a label that isn't positioned is laid out against a
// far ancestor; clicking the label focuses it and the browser scrolls the page away (Settings › Customer fees,
// "Percentage"). jsdom has no layout, so this guards the cause: every such label is `relative`. The real-browser
// check (scrollY unchanged) is src/test-browser/srOnlyLabelScroll.browser.test.tsx.

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return { ...actual, getRoleFromToken: vi.fn(() => 'owner') }
})

afterEach(() => { vi.unstubAllGlobals() })

function unanchored(root: HTMLElement): string[] {
  return [...root.querySelectorAll<HTMLInputElement>('label input.sr-only')]
    .filter(i => !i.closest('label')!.classList.contains('relative'))
    .map(i => `${i.type} ${i.name || i.value}`)
}

test('fee mode and area-override mode labels are positioned', async () => {
  const rule = (kind: string) => ({ kind, enabled: true, mode: 'fixed', amount: 100, percent: null, minAmount: null,
    maxAmount: null, waiveMerchantFault: true })
  const settings = { visible: true, rules: { exchange: rule('exchange'), refund: null }, merchantFaultReasons: [],
    overrides: [], exchangePricing: { anyProduct: false, sameOrLowerOnly: false, maxDifference: null, freeVariantSwaps: true } }
  vi.stubGlobal('fetch', vi.fn((url: string) => Promise.resolve({
    ok: true, status: 200, statusText: '', headers: { get: () => 'application/json' },
    json: async () => (url.includes('/areas') ? [] : structuredClone(settings)),
    text: async () => '' })))
  const { container } = renderWithProviders(<CustomerFeesSection />)
  await screen.findByTestId('fee-rule-refund')
  await userEvent.click(screen.getByRole('button', { name: 'Add an area fee' }))
  await screen.findByTestId('add-area-form')
  expect(container.querySelectorAll('label input.sr-only').length).toBe(6)
  expect(unanchored(container)).toEqual([])
})

test('shared Checkbox and Radio labels are positioned', () => {
  const { container } = renderWithProviders(<div>
    <Checkbox checked={false} onChange={() => {}} label="c" />
    <Radio value="a" checked onChange={() => {}} label="a" />
  </div>)
  expect(container.querySelectorAll('label input.sr-only').length).toBe(2)
  expect(unanchored(container)).toEqual([])
})
