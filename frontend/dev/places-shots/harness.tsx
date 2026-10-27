// Dev-only screenshot harness (never part of the production build — vite builds index.html only).
// Renders the REAL App at Settings › Places as an owner with places switched on, fetch answered from the fixture
// below:  harness.html?lang=en|ar
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
import type { PlaceNode } from '../../src/api'

const lang = new URLSearchParams(location.search).get('lang') === 'ar' ? 'ar' : 'en'
localStorage.setItem('lang', lang)
await i18n.changeLanguage(lang)
document.documentElement.dir = lang === 'ar' ? 'rtl' : 'ltr'
document.documentElement.lang = lang

const payload = btoa(JSON.stringify({ role: 'owner', exp: Math.floor(Date.now() / 1000) + 3600 }))
setAccessToken(`h.${payload}.s`)

let seq = 100
const nodes: PlaceNode[] = []
const add = (n: Partial<PlaceNode> & Pick<PlaceNode, 'kind' | 'code' | 'label'>) => {
  const node: PlaceNode = { id: `n${seq}`, name: null, purpose: null, parentId: null, scanCode: `Q${String(seq++).padStart(6, '0')}`,
    path: [], depth: 0, pieces: 0, totalPieces: 0, archived: false, createdAt: '2026-10-26T10:00:00Z', ...n }
  nodes.push(node)
  return node
}
const z1 = add({ kind: 'zone', code: 'Z1', label: 'Z1' })
const counts = [[38, 24, 15, 0], [41, 30, 21, 12], [44, 36, 40, 0], [50, 30, 0, 0]]
counts.forEach((shelves, r) => {
  const rack = add({ kind: 'rack', code: `Z1-R0${r + 1}`, label: `R0${r + 1}`, parentId: z1.id, path: ['Z1'], depth: 1 })
  shelves.forEach((c, s) => {
    const shelf = add({ kind: 'shelf', code: `${rack.code}-S${s + 1}`, label: `S${s + 1}`, parentId: rack.id,
      path: ['Z1', rack.label], depth: 2, pieces: c, totalPieces: c })
    if ((r === 0 && s === 1) || (r === 1 && s === 2)) {
      const box = add({ kind: 'box', code: r === 0 ? 'BOX-12' : 'BOX-14', label: r === 0 ? 'BOX-12' : 'BOX-14', parentId: shelf.id,
        path: ['Z1', rack.label, shelf.label], depth: 3, pieces: 15, totalPieces: 15 })
      shelf.totalPieces += box.totalPieces
    }
    rack.totalPieces += shelf.totalPieces
  })
  z1.totalPieces += rack.totalPieces
})
for (const [code, c] of [['BOX-07', 22], ['BOX-08', 9]] as const) {
  add({ kind: 'box', code, label: code, parentId: z1.id, path: ['Z1'], depth: 1, pieces: c, totalPieces: c })
  z1.totalPieces += c
}
const z2 = add({ kind: 'zone', code: 'Z2', label: 'Z2', totalPieces: 140 })
for (let r = 1; r <= 2; r++) {
  const rack = add({ kind: 'rack', code: `Z2-R0${r}`, label: `R0${r}`, parentId: z2.id, path: ['Z2'], depth: 1, totalPieces: 70 })
  for (let s = 1; s <= 4; s++) add({ kind: 'shelf', code: `${rack.code}-S${s}`, label: `S${s}`, parentId: rack.id, path: ['Z2', rack.label], depth: 2 })
}
const z3 = add({ kind: 'zone', code: 'Z3', label: 'Z3', purpose: 'damaged', totalPieces: 11 })
add({ kind: 'box', code: 'BOX-20', label: 'BOX-20', parentId: z3.id, path: ['Z3'], depth: 1, pieces: 8, totalPieces: 8 })
add({ kind: 'box', code: 'BOX-21', label: 'BOX-21', parentId: z3.id, path: ['Z3'], depth: 1, pieces: 3, totalPieces: 3 })

const tree = { locationId: 'loc-main', locationName: lang === 'ar' ? 'المخزن الرئيسي' : 'Main warehouse', nextZoneCode: 'Z4', noPlaceYet: 45, nodes }
const selected = nodes.find(n => n.code === 'Z1-R01-S2')!

const json = (body: unknown) => Promise.resolve(new Response(JSON.stringify(body), { status: 200, headers: { 'content-type': 'application/json' } }))
window.fetch = ((input: RequestInfo | URL, init?: RequestInit) => {
  const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
  if (url.endsWith('/me')) return json({ name: 'Demo Owner', email: 'owner@example.com', role: 'owner', places: true })
  if (url.includes('/exceptions/count')) return json({ count: 0 })
  if (url.includes('/onboarding/status')) return json({ steps: [], allDone: true, dismissed: true })
  if (url.includes('/locations')) return json([{ id: 'loc-main', name: tree.locationName, is_fulfillment: true }, { id: 'loc-2', name: 'Warehouse 2', is_fulfillment: false }])
  if (url.includes('/places/bulk/preview')) {
    const body = JSON.parse(String(init?.body ?? '{}'))
    return json({ zoneCode: 'Z1', newZone: false, racks: body.racks, shelves: body.racks * body.shelvesPerRack, boxes: 0,
      total: body.racks * (1 + body.shelvesPerRack), firstShelf: 'Z1-R05-S1',
      lastShelf: `Z1-R${String(4 + body.racks).padStart(2, '0')}-S${body.shelvesPerRack}`, conflicts: [] })
  }
  const one = nodes.find(n => url.endsWith(`/places/${n.id}`))
  if (one && one !== selected) {
    return json({ node: one, locationName: tree.locationName, history: [], contents: [], boxes: nodes.filter(n => n.parentId === one.id && n.kind === 'box') })
  }
  if (one) {
    return json({ node: selected, locationName: tree.locationName, history: [],
      contents: [{ variantId: 'v1', productTitle: lang === 'ar' ? 'تيشيرت أسود' : 'Black T-shirt', variantTitle: 'M', sku: 'TS-M', count: 6 },
                 { variantId: 'v2', productTitle: lang === 'ar' ? 'هودي أبيض' : 'White Hoodie', variantTitle: 'L', sku: 'HD-L', count: 3 }],
      boxes: nodes.filter(n => n.parentId === selected.id) })
  }
  if (url.includes('/places')) return json(tree)
  return Promise.resolve(new Response('{}', { status: 404 }))
}) as typeof fetch

history.replaceState(null, '', '/settings?tab=places')
createRoot(document.getElementById('root')!).render(<App />)
