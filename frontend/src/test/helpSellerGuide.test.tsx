import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import userEvent from '@testing-library/user-event'
import { cleanup, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import i18next from 'i18next'
import { I18nextProvider, initReactI18next } from 'react-i18next'
import en from '../locales/en.json'
import ar from '../locales/ar.json'
import { StationProvider } from '../components/StationProvider'
import { ToastProvider } from '../components/ui'
import { setAccessToken, clearAccessToken } from '../auth'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import { LIBRARY } from '../help/content'
import { HELP_WHATSAPP } from '../help/topics'
import { SCREEN_ARTICLES, WORKER_ARTICLES, isWorkerArticle } from '../help/helpRegistry'
import { HelpLink } from '../help/HelpLink'
import { HelpArticleRoute, HelpCenterRoute } from '../App'
import { BazarMenu } from '../pages/bazar/phone/BazarMenu'

/**
 * Help center — the bazar seller's guide is the ONE article a worker may open (approved 2026-11-27). A worker is
 * refused (sent to /worker-home) on /help and on every other article; owners / managers keep every article. Plus the
 * phone bazar menu's "How to sell" link, and the screens' "Read more" links (HelpLink).
 */

function i18nFor(lng: 'en' | 'ar') {
  const inst = i18next.createInstance()
  inst.use(initReactI18next).init({ lng, fallbackLng: 'en', initImmediate: false,
    resources: { en: { translation: en }, ar: { translation: ar } }, interpolation: { escapeValue: false } })
  return inst
}

function token(role: string) {
  return 'h.' + btoa(JSON.stringify({ role, tenant: 't', exp: Math.floor(Date.now() / 1000) + 3600 })).replace(/=+$/, '') + '.s'
}

function renderAt(path: string, lng: 'en' | 'ar' = 'en') {
  return render(
    <StationProvider><MemoryRouter initialEntries={[path]}><I18nextProvider i18n={i18nFor(lng)}><ToastProvider>
      <Routes>
        <Route path="/help" element={<HelpCenterRoute />} />
        <Route path="/help/:id" element={<HelpArticleRoute />} />
        <Route path="/worker-home" element={<div data-testid="worker-home" />} />
      </Routes>
    </ToastProvider></I18nextProvider></MemoryRouter></StationProvider>,
  )
}

beforeEach(() => {
  stubFetchWithShellDefaults(() =>
    Promise.resolve({ ok: true, status: 200, headers: { get: () => 'application/json' }, json: async () => ({}) }),
  { me: { setupStep: null, setupIntro: null } })
  Object.defineProperty(window, 'matchMedia', { writable: true,
    value: (q: string) => ({ matches: false, media: q, addEventListener: () => {}, removeEventListener: () => {} }) })
})
afterEach(() => { cleanup(); clearAccessToken(); vi.unstubAllGlobals() })

describe('s1 a worker opens only the seller guide', () => {
  test.each(['en', 'ar'] as const)('the seller guide opens for a worker (%s), with no link to the help center', async lng => {
    setAccessToken(token('worker'))
    renderAt('/help/bazar-seller-guide', lng)
    const article = await screen.findByTestId('help-article')
    expect(article).toHaveAttribute('data-article', 'bazar-seller-guide')
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent(LIBRARY[lng]['bazar-seller-guide'].title)
    expect(screen.queryAllByRole('link').filter(l => l.getAttribute('href') === '/help')).toEqual([])
    expect(screen.queryByTestId('worker-home')).toBeNull()
  })

  test.each([['en', 'No', 'Ask the bazar owner.'], ['ar', 'لا', 'اسأل صاحب البازار.']] as const)(
    'a worker\'s "No" (%s) says ask the bazar owner, never Traced\'s WhatsApp', async (lng, no, ask) => {
      setAccessToken(token('worker'))
      const user = userEvent.setup()
      renderAt('/help/bazar-seller-guide', lng)
      const btn = await screen.findByTestId('help-no')
      expect(btn.tagName).toBe('BUTTON')
      expect(btn).not.toHaveAttribute('href')
      expect(btn).toHaveTextContent(no)
      expect(screen.queryAllByRole('link').filter(l => /wa\.me|whatsapp/i.test(l.getAttribute('href') ?? ''))).toEqual([])
      await user.click(btn)
      expect(screen.getByTestId('help-ask-owner')).toHaveTextContent(ask)
    })

  test.each(['owner', 'manager'] as const)('%s keeps "No, chat with us" → Traced\'s WhatsApp', async role => {
    setAccessToken(token(role))
    renderAt('/help/bazar-seller-guide')
    const link = await screen.findByTestId('help-no')
    expect(link.tagName).toBe('A')
    expect(link).toHaveAttribute('href', HELP_WHATSAPP)
    expect(link).toHaveAttribute('target', '_blank')
    expect(link).toHaveTextContent('No, chat with us')
  })

  test('a worker is refused on /help', async () => {
    setAccessToken(token('worker'))
    renderAt('/help')
    expect(await screen.findByTestId('worker-home')).toBeInTheDocument()
    expect(screen.queryByTestId('help-center')).toBeNull()
  })

  test('a worker is refused on every other article (and on an unknown id)', async () => {
    const others = [...Object.keys(LIBRARY.en).filter(id => !isWorkerArticle(id)), 'no-such-article']
    expect(others.length).toBeGreaterThan(80)
    for (const id of others) {
      setAccessToken(token('worker'))
      renderAt(`/help/${id}`)
      expect(await screen.findByTestId('worker-home'), id).toBeInTheDocument()
      expect(screen.queryByTestId('help-article'), id).toBeNull()
      cleanup()
    }
  })

  test.each(['owner', 'manager'] as const)('%s still opens the seller guide and any other article', async role => {
    for (const id of ['bazar-seller-guide', 'bazar-close-day', 'stock-take']) {
      setAccessToken(token(role))
      renderAt(`/help/${id}`)
      expect((await screen.findByTestId('help-article')).getAttribute('data-article'), id).toBe(id)
      cleanup()
    }
  })

  test('a worker article never sends a worker away: no Related, no /help links in its body (EN and AR)', () => {
    expect(WORKER_ARTICLES).toEqual(['bazar-seller-guide'])
    for (const id of WORKER_ARTICLES) {
      for (const lang of ['en', 'ar'] as const) {
        const a = LIBRARY[lang][id]
        expect(a, `${lang}:${id}`).toBeTruthy()
        expect(a.hidden).toBe(false)
        expect(a.related, `${lang}:${id}`).toEqual([])
        expect(`${a.short}\n${a.rest}`, `${lang}:${id}`).not.toMatch(/\]\(\/help/)
      }
    }
  })
})

describe('s2 "How to sell" in the phone bazar menu', () => {
  test.each([['worker', 'en', 'How to sell'], ['owner', 'en', 'How to sell'], ['worker', 'ar', 'كيف تبيع']] as const)(
    '%s (%s) → the seller guide, in a new tab', async (role, lng, label) => {
      setAccessToken(token(role))
      const user = userEvent.setup()
      render(<MemoryRouter><I18nextProvider i18n={i18nFor(lng)}><BazarMenu eventId="e1" /></I18nextProvider></MemoryRouter>)
      await user.click(screen.getByTestId('bazar-menu'))
      const link = screen.getByTestId('menu-how-to-sell')
      expect(link).toHaveAttribute('href', '/help/bazar-seller-guide')
      expect(link).toHaveAttribute('target', '_blank')
      expect(link).toHaveTextContent(label)
    })
})

describe('s3 screen "Read more" links', () => {
  test('every screen article exists in EN and AR and is not hidden', () => {
    for (const [screenKey, id] of Object.entries(SCREEN_ARTICLES)) {
      for (const lang of ['en', 'ar'] as const) {
        expect(LIBRARY[lang][id], `${screenKey} → ${lang}:${id}`).toBeTruthy()
        expect(LIBRARY[lang][id].hidden, `${screenKey} → ${id}`).toBe(false)
      }
    }
  })

  test.each(['owner', 'manager'] as const)('%s sees "Read more" → its article', role => {
    setAccessToken(token(role))
    render(<I18nextProvider i18n={i18nFor('en')}><HelpLink screen="bazarCloseDay" /></I18nextProvider>)
    const link = screen.getByTestId('help-link-bazarCloseDay')
    expect(link).toHaveAttribute('href', '/help/bazar-close-day')
    expect(link).toHaveAttribute('target', '_blank')
    expect(link).toHaveTextContent('Read more')
  })

  test('a worker sees no "Read more" link', () => {
    setAccessToken(token('worker'))
    render(<I18nextProvider i18n={i18nFor('en')}><HelpLink screen="bazarCloseDay" /></I18nextProvider>)
    expect(screen.queryByTestId('help-link-bazarCloseDay')).toBeNull()
  })
})
