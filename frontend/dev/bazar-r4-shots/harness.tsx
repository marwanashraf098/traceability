// Dev-only screenshot harness (never part of the production build — vite builds index.html only).
// Renders the REAL App on a live bazar (Bazar R4: Sell, Return, Exchange), fetch answered from the fixture below:
//   harness.html?lang=en|ar&path=sell|return|exchange
import '@fontsource-variable/geist'
import '@fontsource/geist-mono'
import '@fontsource/cairo/400.css'
import '@fontsource/cairo/600.css'
import '@fontsource/cairo/700.css'
import { createRoot } from 'react-dom/client'
import '../../src/index.css'
import i18n from '../../src/i18n'
import App from '../../src/App'
import { setAccessToken } from '../../src/auth'

const params = new URLSearchParams(location.search)
const lang = params.get('lang') === 'ar' ? 'ar' : 'en'
const path = params.get('path') ?? 'sell'
localStorage.setItem('lang', lang)
await i18n.changeLanguage(lang)
document.documentElement.dir = lang === 'ar' ? 'rtl' : 'ltr'
document.documentElement.lang = lang

const payload = btoa(JSON.stringify({ role: 'owner', sub: 'u-1', exp: Math.floor(Date.now() / 1000) + 3600 }))
setAccessToken(`h.${payload}.s`)

const EVENT = 'e-1'
const TODAY = new Date().toISOString().slice(0, 10)
const LIVE = { eventId: EVENT, name: 'Zamalek Winter Bazar', day: 2, dayCount: 3, businessDay: TODAY,
               paymentMethods: ['cash', 'instapay', 'card'] }

const at = (hm: string) => { const d = new Date(); const [h, m] = hm.split(':').map(Number); d.setHours(h, m, 0, 0); return d.toISOString() }

type P = { n: number; title: string; colour: string; cat: string; sizes: [string, string, number][] }
const PRODUCTS: P[] = [
  { n: 12, title: 'Linen shirt', colour: 'White', cat: 'Shirts', sizes: [['S', '960', 0], ['M', '960', 3], ['L', '960', 2], ['XL', '960', 1]] },
  { n: 14, title: 'Wool cardigan', colour: 'Beige', cat: 'Knitwear', sizes: [['S', '800', 2], ['M', '800', 1]] },
  { n: 17, title: 'Cargo pants', colour: 'Olive', cat: 'Bottoms', sizes: [['32', '880', 1], ['34', '880', 2]] },
  { n: 21, title: 'Knit scarf', colour: 'Grey', cat: 'Knitwear', sizes: [['One size', '320', 4]] },
  { n: 23, title: 'Denim jacket', colour: 'Indigo', cat: 'Outerwear', sizes: [['M', '1240', 1]] },
  { n: 25, title: 'Pleated skirt', colour: 'Black', cat: 'Bottoms', sizes: [['S', '760', 2]] },
  { n: 27, title: 'Cotton tee', colour: 'Off-white', cat: 'Shirts', sizes: [['M', '360', 3], ['L', '360', 0]] },
]
const vid = (p: P, s: string) => `v-${p.n}-${s}`
const CATALOG = {
  event_id: EVENT, event_name: LIVE.name, event_status: 'live',
  categories: ['Shirts', 'Knitwear', 'Bottoms', 'Outerwear'].map(name => ({ name, count: PRODUCTS.filter(p => p.cat === name).length })),
  products: PRODUCTS.map(p => ({
    number: p.n, product_id: `p-${p.n}`, title: p.title, colour: p.colour, image_url: null, category: p.cat,
    left: p.sizes.reduce((a, s) => a + s[2], 0), price_min: p.sizes[0][1], price_max: p.sizes[0][1],
    sizes: p.sizes.map(([size, price, left]) => ({ variant_id: vid(p, size), title: `${p.colour} / ${size}`,
      size: size === 'One size' ? null : size, price, left })),
  })),
}

let n = 0
function sale(title: string, variant: string, price: string, method: string, time: string, over: Record<string, unknown> = {}) {
  n += 1
  const p = PRODUCTS.find(x => x.title === title)!
  return {
    kind: 'sale', id: `s-${n}`, sale_number: `B-${String(60 + n).padStart(4, '0')}`, sold_at: at(time), business_day: TODAY,
    status: 'active', price, price_source: 'bulk', method, piece_id: `pc-${n}`, piece_code: `P000${200 + n}`,
    variant_id: vid(p, variant.split(' / ')[1] ?? 'One size'), product_title: title, variant_title: variant,
    seller_id: 'u-1', seller_name: 'Hana', customer_name: null, customer_phone: null, undone_at: null,
    discount: '0', net_price: price, ...over,
  }
}
const SALES = [
  sale('Wool cardigan', 'Beige / S', '800.00', 'instapay', '18:42'),
  sale('Denim jacket', 'Indigo / M', '1240.00', 'card', '18:31'),
  sale('Cargo pants', 'Olive / 32', '880.00', 'cash', '18:22'),
  sale('Pleated skirt', 'Black / S', '760.00', 'instapay', '18:15'),
]

const PIECES: Record<string, { title: string; variant: string; price: string }> = {
  P000231: { title: 'Linen shirt', variant: 'White / M', price: '960.00' },
  P000228: { title: 'Cargo pants', variant: 'Olive / 32', price: '880.00' },
  P000240: { title: 'Cotton tee', variant: 'Off-white / M', price: '360.00' },
  P000227: { title: 'Knit scarf', variant: 'Grey', price: '320.00' },
  P000244: { title: 'Denim jacket', variant: 'Indigo / M', price: '1240.00' },
}
const SOLD_BACK = sale('Cargo pants', 'Olive / 32', '880.00', 'cash', '18:22', { piece_code: 'P000228', seller_name: 'Hana',
  customer_name: 'Nour', customer_phone: '010*****678' })

const json = (body: unknown, status = 200) =>
  Promise.resolve(new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } }))

window.fetch = ((input: RequestInfo | URL, init?: RequestInit) => {
  const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
  const method = (init?.method ?? 'GET').toUpperCase()
  const body = init?.body ? JSON.parse(String(init.body)) : null
  if (url.endsWith('/me')) return json({ name: 'Marawan', email: 'owner@example.com', role: 'owner', bazar: true, bazarLive: LIVE })
  if (url.includes('/exceptions/count')) return json({ count: 0 })
  if (url.includes('/catalog')) return json(CATALOG)
  if (url.includes(`/bazar-events/${EVENT}/sales/find`)) return json({ sales: [SOLD_BACK, sale('Cargo pants', 'Olive / 32', '880.00', 'card', '16:05', { seller_name: 'Omar', customer_phone: '011*****321' })], count: 2 })
  if (url.includes(`/bazar-events/${EVENT}/sales`)) return json({ items: SALES, next_cursor: null })
  if (url.includes(`/bazar-events/${EVENT}/today`)) return json({ business_day: TODAY, total: '18920.00', sale_count: 23, returns: 2,
    exchanges: 1, by_method: { cash: '0' }, per_seller: [] })
  if (url.includes('/piece?')) {
    const code = new URL(url, location.origin).searchParams.get('code') ?? ''
    if (code === 'P000228') return json({ piece_id: 'pc-228', piece_code: code, product_title: 'Cargo pants', variant_title: 'Olive / 32',
      price: '880.00', state: 'sold', sale: SOLD_BACK })
    const p = PIECES[code]
    if (p) return json({ piece_id: `pc-${code}`, piece_code: code, product_title: p.title, variant_title: p.variant, price: p.price,
      state: 'for_sale', sale: null })
    return json({ code: 'BAZAR_PIECE_NOT_FOUND', message_en: 'Unknown code', message_ar: 'كود غير معروف' }, 404)
  }
  if (url.includes('/cart/preview')) {
    const codes: string[] = body.codes ?? []
    return json({ lines: codes.map((c, index) => {
      const p = PIECES[c]
      return { index, code: c, piece_id: `pc-${c}`, piece_code: c, variant_id: 'v', product_title: p?.title, variant_title: p?.variant,
               price: p?.price, error: null }
    }), subtotal: '0', ok: true })
  }
  if (url.endsWith('/sell') && method === 'POST') {
    return json(sale('Linen shirt', 'White / M', '960.00', 'cash', `${new Date().getHours()}:${String(new Date().getMinutes()).padStart(2, '0')}`,
      { piece_code: 'P000231', sale_number: 'B-0084', sold_at: new Date().toISOString() }))
  }
  return json({})
}) as typeof fetch

history.replaceState(null, '', `/bazar/${EVENT}/${path}`)
createRoot(document.getElementById('root')!).render(<App />)
