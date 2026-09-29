import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import { I18nextProvider } from 'react-i18next'
import { renderWithProviders, screen } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import Layout from '../components/Layout'
import i18n from '../i18n'
import { setAccessToken, clearAccessToken } from '../auth'

// Returns & exchanges Step 2 — the renames in the sidebar: "Exchanges & Refunds" → "Returns & exchanges"
// (owner / manager), the scanning page "Returns" → "Scan returns" (every role), EN + AR. URLs unchanged.

/** Minimal fake JWT — getRoleFromToken() only reads the middle segment's `role` claim. */
function fakeJwt(role: string): string {
  return `h.${btoa(JSON.stringify({ role }))}.s`
}

function ok(data: unknown) {
  return Promise.resolve({
    ok: true, status: 200, statusText: '',
    headers: { get: (k: string) => (k.toLowerCase() === 'content-type' ? 'application/json' : null) },
    json: async () => data, text: async () => JSON.stringify(data),
  })
}

beforeEach(() => {
  stubFetchWithShellDefaults(vi.fn((url: string) =>
    ok(url.includes('/onboarding/status') ? { steps: [], allDone: true, dismissed: true } : {})))
})

afterEach(async () => {
  clearAccessToken()
  await i18n.changeLanguage('en')
})

function renderAs(role: string) {
  setAccessToken(fakeJwt(role))
  renderWithProviders(<I18nextProvider i18n={i18n}><Layout><div /></Layout></I18nextProvider>)
}

describe('Sidebar renames', () => {
  for (const role of ['owner', 'manager']) {
    test(`${role}: "Scan returns" → /returns and "Returns & exchanges" → /exchanges; old names gone (EN)`, async () => {
      renderAs(role)
      expect(await screen.findByRole('link', { name: 'Scan returns' })).toHaveAttribute('href', '/returns')
      expect(screen.getByRole('link', { name: 'Returns & exchanges' })).toHaveAttribute('href', '/exchanges')
      expect(screen.queryByText('Exchanges & Refunds')).toBeNull()
      expect(screen.queryByRole('link', { name: 'Returns' })).toBeNull()
    })

    test(`${role}: Arabic names`, async () => {
      await i18n.changeLanguage('ar')
      renderAs(role)
      expect(await screen.findByRole('link', { name: 'مسح المرتجعات' })).toHaveAttribute('href', '/returns')
      expect(screen.getByRole('link', { name: 'المرتجعات والاستبدال' })).toHaveAttribute('href', '/exchanges')
      expect(screen.queryByText('الاستبدال والاسترجاع')).toBeNull()
    })
  }

  test('worker: "Scan returns" only (no Returns & exchanges), EN and AR', async () => {
    renderAs('worker')
    expect(await screen.findByRole('link', { name: 'Scan returns' })).toHaveAttribute('href', '/returns')
    expect(screen.queryByRole('link', { name: 'Returns & exchanges' })).toBeNull()
  })

  test('worker: Arabic', async () => {
    await i18n.changeLanguage('ar')
    renderAs('worker')
    expect(await screen.findByRole('link', { name: 'مسح المرتجعات' })).toHaveAttribute('href', '/returns')
  })
})
