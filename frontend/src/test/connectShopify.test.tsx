import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { render, screen, waitFor, cleanup } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { I18nextProvider, initReactI18next } from 'react-i18next'
import i18next from 'i18next'
import en from '../locales/en.json'
import ar from '../locales/ar.json'
import * as api from '../api'
import ConnectShopify from '../pages/connect/ConnectShopify'

// Build D — /connect/shopify?link=<nonce>: a signed-in owner confirms the store the embedded app parked
// ("I already have a Traced account"); the browser then goes back to the app inside Shopify admin.

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return { ...actual, shopifyPendingLinkPreview: vi.fn(), shopifyPendingLinkConfirm: vi.fn() }
})

const preview = vi.mocked(api.shopifyPendingLinkPreview)
const confirm = vi.mocked(api.shopifyPendingLinkConfirm)

function i18n(lng: 'en' | 'ar') {
  const inst = i18next.createInstance()
  inst.use(initReactI18next).init({
    lng, fallbackLng: 'en', initImmediate: false,
    resources: { en: { translation: en }, ar: { translation: ar } }, interpolation: { escapeValue: false },
  })
  return inst
}

function renderPage(lng: 'en' | 'ar', onNavigate = vi.fn()) {
  render(
    <MemoryRouter>
      <I18nextProvider i18n={i18n(lng)}>
        <ConnectShopify onNavigate={onNavigate} />
      </I18nextProvider>
    </MemoryRouter>,
  )
  return onNavigate
}

const cmd = (code: string, en = 'backend message', arMsg = 'رسالة') =>
  new api.TransferCommandError({ code, message_en: en, message_ar: arMsg })

beforeEach(() => {
  window.history.replaceState(null, '', '/connect/shopify?link=nonce-1')
  preview.mockReset()
  confirm.mockReset()
})
afterEach(() => cleanup())

for (const lng of ['en', 'ar'] as const) {
  const t = (lng === 'en' ? en : ar).connectShopify
  describe(`connect shopify — ${lng.toUpperCase()}`, () => {
    test('confirm: shows the store and the business, links, then returns to Shopify admin', async () => {
      preview.mockResolvedValue({ shopDomain: 'abc123.myshopify.com', businessName: "Mona's Linen" })
      confirm.mockResolvedValue({ shopDomain: 'abc123.myshopify.com',
        redirectUrl: 'https://admin.shopify.com/store/abc123/apps/trace-3?traced_connected=1' })
      const nav = renderPage(lng)
      expect(await screen.findByTestId('connect-shopify-shop')).toHaveTextContent('abc123.myshopify.com')
      expect(screen.getByText(t.question.replace('{{shop}}', 'abc123.myshopify.com').replace('{{business}}', "Mona's Linen")))
        .toBeInTheDocument()
      expect(preview).toHaveBeenCalledWith('nonce-1')
      expect(screen.queryByRole('textbox')).not.toBeInTheDocument()   // nothing typed
      await userEvent.setup().click(screen.getByRole('button', { name: t.connect }))
      await waitFor(() => expect(nav).toHaveBeenCalledWith('https://admin.shopify.com/store/abc123/apps/trace-3?traced_connected=1'))
      expect(confirm).toHaveBeenCalledWith('nonce-1')
    })

    test('expired or used link → "Start again from Shopify"', async () => {
      preview.mockRejectedValue(cmd('PENDING_LINK_INVALID'))
      renderPage(lng)
      expect(await screen.findByText(t.invalidTitle)).toBeInTheDocument()
      expect(screen.getByRole('link', { name: t.startAgain })).toHaveAttribute('href', 'https://admin.shopify.com')
    })

    test('no link in the URL → the same message, no API call', async () => {
      window.history.replaceState(null, '', '/connect/shopify')
      renderPage(lng)
      expect(await screen.findByText(t.invalidTitle)).toBeInTheDocument()
      expect(preview).not.toHaveBeenCalled()
    })

    test('bound to another shop → the server message (names the linked shop)', async () => {
      preview.mockRejectedValue(cmd('SHOPIFY_SHOP_MISMATCH', 'This account is linked to other.myshopify.com.', 'هذا الحساب مرتبط بالمتجر other.myshopify.com.'))
      renderPage(lng)
      expect(await screen.findByText(lng === 'en' ? 'This account is linked to other.myshopify.com.' : 'هذا الحساب مرتبط بالمتجر other.myshopify.com.'))
        .toBeInTheDocument()
    })

    test('not the owner → "Only the account owner can connect a store."', async () => {
      preview.mockRejectedValue(new Error('403: Forbidden'))
      renderPage(lng)
      expect(await screen.findByText(t.notOwnerTitle)).toBeInTheDocument()
    })

    test('a confirm that fails after preview (link used meanwhile) → start again', async () => {
      preview.mockResolvedValue({ shopDomain: 'abc123.myshopify.com', businessName: 'Co' })
      confirm.mockRejectedValue(cmd('PENDING_LINK_INVALID'))
      const nav = renderPage(lng)
      await userEvent.setup().click(await screen.findByRole('button', { name: t.connect }))
      expect(await screen.findByText(t.invalidTitle)).toBeInTheDocument()
      expect(nav).not.toHaveBeenCalled()
    })
  })
}
