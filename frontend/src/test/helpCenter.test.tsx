import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import userEvent from '@testing-library/user-event'
import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
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
import { parseArticle, type HelpLibrary } from '../help/articles'
import { normalise, searchHelp } from '../help/search'
import { ALERT_ARTICLES, ALERT_GROUPS, HELP_REGISTRY, articleForAlert, articleForTour } from '../help/helpRegistry'
import { ARTICLE_ORDER, START_HERE, TOPICS } from '../help/topics'
import { ALERT_TYPES } from '../pages/Exceptions'
import HelpCenter from '../pages/help/HelpCenter'
import HelpArticle from '../pages/help/HelpArticle'
import Layout from '../components/Layout'
import { TourCard } from '../tours/TourCard'
import { forgetTours, startTour } from '../tours/tourState'
import { TOURS, type TourId } from '../tours/tours'

/**
 * Onboarding O6 — the help center.
 *   h1  search: title + body, EN and AR (either language's words find the article), Arabic normalisation (alef forms,
 *       ta marbuta, alef maqsura, diacritics, tatweel, Arabic-Indic digits); empty result → "No answer matches" + WhatsApp
 *   h2  topic chips filter the sections; "Start here" has the five steps
 *   h3  article page: breadcrumb, title, short-answer box, markdown body, Related links, feedback (Yes → thanks only,
 *       No → WhatsApp; nothing sent anywhere)
 *   h4  sidebar: "Help center" for owner and manager, none for a worker
 *   h5  guards: every Alerts-page alert type → an article in EN and AR; every helpRegistry article in EN and AR;
 *       every related id exists; each article's alerts list = its group; EN / AR pairs agree; no internal names
 *   h6  the TourCard's "Read more" (owner / manager only) and the Alerts page's "What does this mean?"
 *   h7  hidden articles (feature not rolled out): never in search, topics or Related, not openable by URL, and nothing
 *       links to them (registry, alerts, Start here, related[], body links)
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
function renderAt(path: string, lng: 'en' | 'ar' = 'en', withLayout = false) {
  const wrap = (el: React.ReactElement) => (withLayout ? <Layout>{el}</Layout> : el)
  return render(
    <StationProvider><MemoryRouter initialEntries={[path]}><I18nextProvider i18n={i18nFor(lng)}><ToastProvider>
      <Routes>
        <Route path="/help" element={wrap(<HelpCenter />)} />
        <Route path="/help/:id" element={wrap(<HelpArticle />)} />
        <Route path="*" element={wrap(<div data-testid="other" />)} />
      </Routes>
    </ToastProvider></I18nextProvider></MemoryRouter></StationProvider>)
}

let calls: { url: string; method: string }[]
beforeEach(() => {
  calls = []
  setAccessToken(token('owner'))
  stubFetchWithShellDefaults((url: string, opts: RequestInit = {}) => {
    calls.push({ url, method: (opts.method ?? 'GET').toUpperCase() })
    return Promise.resolve({ ok: true, status: 200, headers: { get: () => 'application/json' }, json: async () => ({}) })
  })
  Object.defineProperty(window, 'matchMedia', { writable: true,
    value: (q: string) => ({ matches: false, media: q, addEventListener: () => {}, removeEventListener: () => {} }) })
})
afterEach(() => { cleanup(); clearAccessToken(); vi.unstubAllGlobals(); forgetTours(); document.documentElement.dir = 'ltr' })

// A small fixed library for the search rules (independent of the real articles' wording).
const FIXTURE: HelpLibrary = {
  en: {
    a: parseArticle('---\nid: a\ntopic: pack\ntitle: Print waybills for a batch\nroles: [owner]\n---\nPrint them all at once.\n\nThen scan 3 pieces.'),
    b: parseArticle('---\nid: b\ntopic: returns\ntitle: Scan returned parcels\nroles: [owner]\n---\nOpen a session.\n\nGrade each piece.'),
  },
  ar: {
    a: parseArticle('---\nid: a\ntopic: pack\ntitle: اطبع بوالص الدفعة\nroles: [owner]\n---\nاطبعها مرة واحدة.\n\nثم امسح ٣ قطع.'),
    b: parseArticle('---\nid: b\ntopic: returns\ntitle: مسح الطرود المرتجعة\nroles: [owner]\n---\nافتح جلسةً جديدة.\n\nقيّم كل قطعة في المكتبة.'),
  },
}

describe('h1 search', () => {
  test('normalise: alef forms, ta marbuta, alef maqsura, diacritics, tatweel, digits, case', () => {
    expect(normalise('أإآٱا')).toBe('ااااا')
    expect(normalise('جلسة')).toBe('جلسه')
    expect(normalise('مستشفى')).toBe('مستشفي')
    expect(normalise('جَلْسَةٌ')).toBe('جلسه')
    expect(normalise('مـــسح')).toBe('مسح')
    expect(normalise('٣٠ ۴')).toBe('30 4')
    expect(normalise('  Bosta   WAYBILL ')).toBe('bosta waybill')
  })

  test('EN and AR, title and body, either language; title hits first', () => {
    expect(searchHelp(FIXTURE, 'en', 'waybills')).toEqual(['a'])
    expect(searchHelp(FIXTURE, 'en', 'grade')).toEqual(['b'])                 // body
    expect(searchHelp(FIXTURE, 'en', 'جلسه')).toEqual(['b'])                  // Arabic query on an English page
    expect(searchHelp(FIXTURE, 'ar', 'Scan returned')).toEqual(['b'])         // English query on an Arabic page
    expect(searchHelp(FIXTURE, 'ar', 'جلسة')).toEqual(['b'])                   // ta marbuta vs the stored "جلسةً"
    expect(searchHelp(FIXTURE, 'ar', 'امسح 3')).toEqual(['a'])                 // Arabic-Indic digit in the body
    expect(searchHelp(FIXTURE, 'ar', 'المكتبه')).toEqual(['b'])
    expect(searchHelp(FIXTURE, 'en', 'piece')).toEqual(['a', 'b'])            // substring: "pieces" (a) and "piece" (b), no title hit
    expect(searchHelp(FIXTURE, 'en', 'nothing like this')).toEqual([])
    expect(searchHelp(FIXTURE, 'en', '')).toHaveLength(2)
  })

  test('the page: typing filters; no match → "No answer matches" + WhatsApp', async () => {
    const user = userEvent.setup()
    renderAt('/help')
    const box = await screen.findByTestId('help-search')
    expect(screen.getByTestId('help-start-here')).toBeInTheDocument()
    await user.type(box, 'pickup manifest')
    expect(screen.queryByTestId('help-start-here')).toBeNull()
    expect(screen.getByTestId('help-link-hand-over-parcels')).toBeInTheDocument()
    await user.clear(box)
    await user.type(box, 'zzqx nothing')
    const empty = screen.getByTestId('help-no-match')
    expect(empty).toHaveTextContent('No answer matches “zzqx nothing”')
    expect(within(empty).getByRole('link', { name: /WhatsApp/ })).toHaveAttribute('href', 'https://wa.me/201037371204')
  })

  test('the page in Arabic finds an article by an Arabic word written with a different alef', async () => {
    const user = userEvent.setup()
    renderAt('/help', 'ar')
    const box = await screen.findByTestId('help-search')
    const title = LIBRARY.ar['hand-over-parcels'].title
    const word = title.split(' ').find(w => /[أإآ]/.test(w)) ?? title.split(' ')[0]
    await user.type(box, word.replace(/[أإآ]/g, 'ا'))
    expect(screen.getByTestId('help-link-hand-over-parcels')).toHaveTextContent(title)
  })
})

describe('h2 topics', () => {
  test('a chip shows only its topic; All topics brings everything back', async () => {
    const user = userEvent.setup()
    renderAt('/help')
    await screen.findByTestId('help-topics')
    await user.click(screen.getByTestId('help-topic-pickup'))
    expect(screen.getByTestId('help-topic-pickup')).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByTestId('help-section-pickup')).toBeInTheDocument()
    expect(screen.queryByTestId('help-section-pack')).toBeNull()
    expect(screen.queryByTestId('help-start-here')).toBeNull()
    await user.click(screen.getByTestId('help-topic-all'))
    expect(screen.getByTestId('help-section-pack')).toBeInTheDocument()
  })

  test('Start here: Connect → Receive → Pack → Pickup → Returns, each opening its article', async () => {
    renderAt('/help')
    const start = await screen.findByTestId('help-start-here')
    const links = within(start).getAllByRole('link')
    expect(links.map(l => l.getAttribute('href'))).toEqual(START_HERE.map(s => `/help/${s.articleId}`))
    expect(links.map(l => l.textContent)).toEqual(['1ConnectShopify and your courier', '2ReceiveLabel your first pieces',
      '3PackScan pieces into an order', '4PickupHand parcels to the courier', '5ReturnsScan and grade what comes back'])
  })
})

describe('h3 article page', () => {
  test('breadcrumb, title, short answer, markdown body, related links; Yes → thanks only, No → WhatsApp', async () => {
    const user = userEvent.setup()
    const a = LIBRARY.en['order-not-in-pick-pack']
    renderAt('/help/order-not-in-pick-pack')
    const page = await screen.findByTestId('help-article')
    expect(within(page).getByRole('heading', { level: 1 })).toHaveTextContent(a.title)
    expect(within(page).getByRole('link', { name: 'Help center' })).toHaveAttribute('href', '/help')
    expect(screen.getByTestId('help-short')).toHaveTextContent('Short answer:')
    const body = screen.getByTestId('help-body')
    expect(body.querySelectorAll('ol li, ul li, h2, strong').length).toBeGreaterThan(0)
    const related = screen.getByTestId('help-related')
    expect(within(related).getAllByRole('link').map(l => l.getAttribute('href'))).toEqual(
      a.related.map(r => `/help/${r}`))
    expect(screen.queryByTestId('help-video')).toBeNull()
    expect(screen.getByTestId('help-no')).toHaveAttribute('href', 'https://wa.me/201037371204')
    const before = calls.length
    await user.click(screen.getByTestId('help-yes'))
    expect(screen.getByTestId('help-thanks')).toHaveTextContent('Thanks — glad it helped.')
    expect(calls.length).toBe(before)
  })

  test('Arabic article, right to left', async () => {
    document.documentElement.dir = 'rtl'
    renderAt('/help/scan-returns', 'ar')
    const page = await screen.findByTestId('help-article')
    expect(within(page).getByRole('heading', { level: 1 })).toHaveTextContent(LIBRARY.ar['scan-returns'].title)
    expect(screen.getByTestId('help-short')).toHaveTextContent('الإجابة المختصرة:')
    expect(screen.getByTestId('help-yes')).toHaveTextContent('نعم')
  })

  test('an unknown id says so', async () => {
    renderAt('/help/no-such-article')
    expect(await screen.findByTestId('help-article-missing')).toBeInTheDocument()
  })
})

describe('h4 sidebar', () => {
  test.each([['owner', true], ['manager', true], ['worker', false]] as const)('%s → Help center item: %s', async (role, shown) => {
    setAccessToken(token(role))
    renderAt('/somewhere', 'en', true)
    await screen.findByTestId('other')
    const link = screen.queryAllByRole('link').find(l => l.getAttribute('href') === '/help')
    expect(!!link).toBe(shown)
    if (link) expect(link).toHaveTextContent('Help center')
  })
})

describe('h5 guards', () => {
  const ids = (lang: 'en' | 'ar') => Object.keys(LIBRARY[lang]).sort()

  test('every article exists in BOTH languages with the same id, topic, roles, related, alerts and screens', () => {
    expect(ids('en').length).toBeGreaterThan(30)
    expect(ids('ar')).toEqual(ids('en'))
    for (const id of ids('en')) {
      const e = LIBRARY.en[id], a = LIBRARY.ar[id]
      expect(a.topic, id).toBe(e.topic)
      expect(a.roles, id).toEqual(e.roles)
      expect(a.related, id).toEqual(e.related)
      expect(a.alerts, id).toEqual(e.alerts)
      expect(a.screens, id).toEqual(e.screens)
      expect(a.hidden, id).toBe(e.hidden)
      expect(e.short.length, id).toBeGreaterThan(10)
      expect(a.short.length, id).toBeGreaterThan(10)
      expect((TOPICS as readonly string[]).includes(e.topic), `${id} topic ${e.topic}`).toBe(true)
    }
  })

  test('every Alerts-page alert type maps to an existing article (EN and AR)', () => {
    for (const type of ALERT_TYPES) {
      const article = ALERT_ARTICLES[type]
      expect(article, `alert ${type} has no article`).toBeTruthy()
      expect(LIBRARY.en[article], `${type} → ${article} (en)`).toBeTruthy()
      expect(LIBRARY.ar[article], `${type} → ${article} (ar)`).toBeTruthy()
    }
    expect(LIBRARY.en[articleForAlert('some_new_type')]).toBeTruthy()
  })

  test("each alert article's own alerts list is exactly its group", () => {
    for (const [article, types] of Object.entries(ALERT_GROUPS)) {
      expect([...LIBRARY.en[article].alerts].sort(), article).toEqual([...types].sort())
    }
    for (const id of ids('en')) {
      if (!ALERT_GROUPS[id]) expect(LIBRARY.en[id].alerts, id).toEqual([])
    }
  })

  test('every helpRegistry article (and every tour\'s Read more, Start here step) exists in BOTH languages', () => {
    const wanted = [
      ...Object.values(HELP_REGISTRY).map(e => e.articleId),
      ...(Object.keys(TOURS) as TourId[]).map(articleForTour),
      ...START_HERE.map(s => s.articleId),
    ]
    for (const id of wanted) {
      expect(id).toBeTruthy()
      expect(LIBRARY.en[id!], `${id} en`).toBeTruthy()
      expect(LIBRARY.ar[id!], `${id} ar`).toBeTruthy()
    }
  })

  test("every article's related ids exist; the article order lists every article", () => {
    for (const lang of ['en', 'ar'] as const) {
      for (const a of Object.values(LIBRARY[lang])) {
        for (const r of a.related) expect(LIBRARY[lang][r], `${lang}:${a.id} → ${r}`).toBeTruthy()
        expect(a.related, a.id).not.toContain(a.id)
      }
    }
    expect([...ids('en')].filter(id => !ARTICLE_ORDER.includes(id))).toEqual([])
  })

  test('no internal names or old mode names in any article', () => {
    const BANNED = [/\bcount_only\b/, /\bprint_stick\b/, /\/api\//, /\bRLS\b/, /\bpiece_events\b/, /\bmerchant_orders\b/,
      /Print & stick/i, /Factory-labeled/i, /\bCount only\b/, /\btenant_id\b/, /\bJobRunr\b/]
    for (const lang of ['en', 'ar'] as const) {
      for (const a of Object.values(LIBRARY[lang])) {
        const text = `${a.title}\n${a.short}\n${a.rest}`
        for (const re of BANNED) expect(re.test(text), `${lang}:${a.id} contains ${re}`).toBe(false)
      }
    }
  })
})

describe('h6 links', () => {
  test('the TourCard has "Read more" for an owner / manager, not for a worker', async () => {
    render(<MemoryRouter><I18nextProvider i18n={i18nFor('en')}><TourCard tourId="pickups" /></I18nextProvider></MemoryRouter>)
    startTour('pickups')
    expect(await screen.findByTestId('tour-read-more')).toHaveAttribute('href', '/help/hand-over-parcels')
    cleanup(); forgetTours()
    setAccessToken(token('worker'))
    render(<MemoryRouter><I18nextProvider i18n={i18nFor('en')}><TourCard tourId="pickups" /></I18nextProvider></MemoryRouter>)
    startTour('pickups')
    await screen.findByTestId('tour-pickups')
    expect(screen.queryByTestId('tour-read-more')).toBeNull()
  })

  test('an alert row links to its article', async () => {
    const { default: ExceptionsPage } = await import('../pages/Exceptions')
    stubFetchWithShellDefaults((url: string) => Promise.resolve({ ok: true, status: 200, headers: { get: () => 'application/json' },
      json: async () => (url.includes('/exceptions?') || url.includes('/exceptions')
        ? { total: 2, page: 0, size: 50, items: ['pack_set_aside', 'some_brand_new_type'].map(type => ({
          type, severity: 'HIGH', subject_key: `${type}:o`, subject_type: 'order', ageSeconds: 60,
          descriptionEn: `desc ${type}`, descriptionAr: `وصف ${type}`, suggestedAction: 'x', actionUrl: '/orders/o1' })) }
        : {}) }))
    render(<StationProvider><MemoryRouter initialEntries={['/exceptions']}><I18nextProvider i18n={i18nFor('en')}><ToastProvider>
      <Routes><Route path="/exceptions" element={<ExceptionsPage />} /></Routes>
    </ToastProvider></I18nextProvider></MemoryRouter></StationProvider>)
    await waitFor(() => expect(screen.getByTestId('alert-help-pack_set_aside')).toBeInTheDocument())
    expect(screen.getByTestId('alert-help-pack_set_aside')).toHaveAttribute('href', '/help/alerts-packing')
    expect(screen.getByTestId('alert-help-pack_set_aside')).toHaveTextContent('What does this mean?')
    expect(screen.getByTestId('alert-help-some_brand_new_type')).toHaveAttribute('href', '/help/alerts-overview')
  })
})

describe('h7 hidden articles', () => {
  const hidden = () => Object.values(LIBRARY.en).filter(a => a.hidden).map(a => a.id)

  test('shopify-native-exchanges is hidden in both languages', () => {
    expect(hidden()).toContain('shopify-native-exchanges')
    expect(LIBRARY.ar['shopify-native-exchanges'].hidden).toBe(true)
  })

  test('nothing links to a hidden article', () => {
    const h = new Set(hidden())
    const wanted = [
      ...Object.values(HELP_REGISTRY).map(e => e.articleId!), ...Object.values(ALERT_ARTICLES), 'alerts-overview',
      ...(Object.keys(TOURS) as TourId[]).map(id => articleForTour(id)!), ...START_HERE.map(x => x.articleId),
    ]
    for (const id of wanted) expect(h.has(id), `${id} is hidden but linked`).toBe(false)
    for (const lang of ['en', 'ar'] as const) {
      for (const a of Object.values(LIBRARY[lang])) {
        for (const r of a.related) expect(h.has(r), `${lang}:${a.id} related → hidden ${r}`).toBe(false)
        for (const m of `${a.short}\n${a.rest}`.matchAll(/\]\(\/help\/([a-z0-9-]+)\)/g)) {
          expect(LIBRARY[lang][m[1]], `${lang}:${a.id} links to unknown ${m[1]}`).toBeTruthy()
          expect(h.has(m[1]), `${lang}:${a.id} links to hidden ${m[1]}`).toBe(false)
        }
      }
    }
  })

  test('never in search, topic sections or the count; its URL says not found', async () => {
    const user = userEvent.setup()
    const a = LIBRARY.en['shopify-native-exchanges']
    expect(searchHelp(LIBRARY, 'en', a.title)).not.toContain(a.id)
    expect(searchHelp(LIBRARY, 'en', '')).not.toContain(a.id)
    renderAt('/help')
    const box = await screen.findByTestId('help-search')
    const visible = Object.values(LIBRARY.en).filter(x => !x.hidden).length
    expect(screen.getByText(`${visible} answers · search works in Arabic and English`)).toBeInTheDocument()
    expect(screen.queryByTestId(`help-link-${a.id}`)).toBeNull()
    await user.click(screen.getByTestId(`help-topic-${a.topic}`))
    expect(screen.queryByTestId(`help-link-${a.id}`)).toBeNull()
    await user.type(box, a.title)
    expect(screen.queryByTestId(`help-link-${a.id}`)).toBeNull()
    cleanup()
    renderAt(`/help/${a.id}`)
    expect(await screen.findByTestId('help-article-missing')).toBeInTheDocument()
  })
})
