import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { I18nextProvider } from 'react-i18next'
import '../portal/portal.css'
import PortalApp from '../portal/PortalApp'
import { createPortalI18n, PortalLang } from '../portal/i18n'

// P4d — the pre-connect lookup states in a REAL browser (Chromium + WebKit), EN/LTR and AR/RTL, at a
// phone-width column: the document direction, nothing wider than the column, the countdown's digits
// left-to-right inside RTL text, and the button not changing width while the countdown ticks.

let answer: { status: number; body: unknown; retryAfter?: string }

const json = (a: typeof answer) => Promise.resolve(new Response(JSON.stringify(a.body), {
  status: a.status,
  headers: { 'content-type': 'application/json', ...(a.retryAfter ? { 'retry-after': a.retryAfter } : {}) },
}))

beforeEach(() => {
  localStorage.clear()
  vi.stubGlobal('fetch', vi.fn((url: string) => {
    if (url.endsWith('/config')) return json({ status: 200, body: { storeName: 'The Snouts', returnWindowDays: 14, reasonCodes: ['wrong_size'],
      logoUrl: null, brandColor: '#0F766E', policyText: 'Returns within 14 days.', autoApprove: false, pickupBooking: false } })
    if (url.endsWith('/lookup')) return json(answer)
    return json({ status: 404, body: {} })
  }))
})

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  document.documentElement.dir = 'ltr'
  document.documentElement.lang = 'en'
})

const L = {
  en: { title: 'Start a return', order: 'Order number', phone: 'Phone number', find: 'Find my order' },
  ar: { title: 'ابدأ الإرجاع', order: 'رقم الطلب', phone: 'رقم الهاتف', find: 'ابحث عن طلبي' },
}

async function lookUp(lang: PortalLang) {
  const user = userEvent.setup()
  render(<div style={{ width: '390px' }}><I18nextProvider i18n={createPortalI18n(lang)}><PortalApp slug="thesnouts" /></I18nextProvider></div>)
  await screen.findByRole('heading', { name: L[lang].title })
  await user.type(screen.getByLabelText(L[lang].order), '#1042')
  await user.type(screen.getByLabelText(L[lang].phone), '010 5550 1234')
  await user.click(screen.getByRole('button', { name: L[lang].find }))
  return screen.findByTestId('start-banner')
}

function fitsColumn(el: Element) {
  const column = document.querySelector('.pp-column') ?? document.body
  const c = column.getBoundingClientRect(), r = el.getBoundingClientRect()
  return r.left >= c.left - 0.5 && r.right <= c.right + 0.5
}

describe('pre-connect lookup states (real browser)', () => {
  for (const lang of ['en', 'ar'] as PortalLang[]) {
    test(`${lang} — too old: direction, banner + policy link inside the column`, async () => {
      answer = { status: 404, body: { code: 'TOO_OLD', date: '2026-08-12' } }
      const b = await lookUp(lang)
      expect(document.documentElement.dir).toBe(lang === 'ar' ? 'rtl' : 'ltr')
      expect(getComputedStyle(b).direction).toBe(lang === 'ar' ? 'rtl' : 'ltr')
      expect(b.textContent).toContain(lang === 'ar' ? '12 أغسطس 2026' : '12 August 2026')
      expect(fitsColumn(b)).toBe(true)
      expect(b.querySelector('button')).not.toBeNull()
    })

    test(`${lang} — busy: countdown digits LTR, the button keeps its width while it ticks`, async () => {
      answer = { status: 503, body: { code: 'BUSY' }, retryAfter: '12' }
      const b = await lookUp(lang)
      expect(fitsColumn(b)).toBe(true)
      const button = screen.getByTestId('find-button')
      const count = button.querySelector('.pp-countdown') as HTMLElement
      expect(getComputedStyle(count).direction).toBe('ltr')
      expect(count.textContent).toBe('0:12')
      const w1 = button.getBoundingClientRect().width
      await waitFor(() => expect(count.textContent).toBe('0:11'), { timeout: 2500 })
      expect(button.getBoundingClientRect().width).toBe(w1)
      expect(fitsColumn(button)).toBe(true)
    })
  }
})
