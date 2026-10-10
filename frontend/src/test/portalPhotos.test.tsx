import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { render, screen, waitFor, within } from '@testing-library/react'
import { I18nextProvider } from 'react-i18next'
import PortalApp from '../portal/PortalApp'
import { createPortalI18n, PortalLang } from '../portal/i18n'
import type { LookupResult, PortalConfig } from '../portal/api'
import { imgAllowed, imgSrcOf } from './cspContract'

// P3 — photos of each item on the portal (design/Traced_portal_photos_dc.html, b). preparePhoto
// (createImageBitmap + canvas — not in jsdom) and the XHR upload are replaced; everything else is real.

let uploads: Array<{ resolve: (v: { photoId: string; width: number; height: number }) => void; reject: (e: unknown) => void }>
let prepFails: Record<string, 'type' | 'tooBig'>

vi.mock('../portal/photos', async importOriginal => {
  const actual = await importOriginal<typeof import('../portal/photos')>()
  return {
    ...actual,
    preparePhoto: vi.fn(async (file: File) => {
      if (prepFails[file.name]) throw new actual.PhotoPrepError(prepFails[file.name])
      return { blob: new Blob(['x'], { type: 'image/jpeg' }), dataUrl: `data:image/jpeg;base64,${btoa(file.name)}` }
    }),
    uploadPhoto: vi.fn(() => {
      let resolve!: (v: { photoId: string; width: number; height: number }) => void, reject!: (e: unknown) => void
      const promise = new Promise<{ photoId: string; width: number; height: number }>((res, rej) => { resolve = res; reject = rej })
      uploads.push({ resolve, reject })
      return { promise, abort: () => {} }
    }),
  }
})

const BASE: PortalConfig = {
  storeName: 'The Snouts', returnWindowDays: 14, reasonCodes: ['wrong_size', 'other'],
  logoUrl: null, brandColor: '#0F766E', policyText: null, autoApprove: false, pickupBooking: false, requirePhotos: true,
}

const LOOKUP: LookupResult = {
  token: 'tok.sig', orderNumber: '#1047', deliveredAt: '2026-09-18T10:00:00Z', pickup: null,
  lines: [
    { variantId: 'v-shirt', productTitle: 'Linen Shirt', variantTitle: 'White / M', imageUrl: null, deliveredQuantity: 1, returnableQuantity: 1, nonReturnable: false },
    { variantId: 'v-pants', productTitle: 'Flipped Pants', variantTitle: 'Black / XL', imageUrl: null, deliveredQuantity: 2, returnableQuantity: 2, nonReturnable: false },
  ],
}

function respond(status: number, body?: unknown) {
  return Promise.resolve({
    ok: status >= 200 && status < 300, status,
    headers: { get: (k: string) => (k.toLowerCase() === 'content-type' && body !== undefined ? 'application/json' : null) },
    json: async () => structuredClone(body), text: async () => JSON.stringify(body ?? ''),
  })
}

let config: PortalConfig
let calls: Array<{ url: string; init: RequestInit }>

beforeEach(() => {
  config = BASE
  calls = []
  uploads = []
  prepFails = {}
  localStorage.clear()
  vi.stubGlobal('fetch', vi.fn((url: string, init: RequestInit = {}) => {
    calls.push({ url, init })
    if (url.endsWith('/config')) return respond(200, config)
    if (url.endsWith('/lookup')) return respond(200, LOOKUP)
    if (url.endsWith('/requests')) return respond(201, { reference: 'RR-7K3F9M', status: 'requested' })
    return respond(404)
  }))
})

afterEach(() => {
  vi.unstubAllGlobals()
  document.documentElement.dir = 'ltr'
  document.documentElement.lang = 'en'
})

const jpg = (name: string) => new File(['x'], name, { type: 'image/jpeg' })

async function toItems(lang: PortalLang = 'en') {
  const user = userEvent.setup({ applyAccept: false })
  render(<I18nextProvider i18n={createPortalI18n(lang)}><PortalApp slug="thesnouts" /></I18nextProvider>)
  await screen.findByRole('heading', { name: lang === 'ar' ? 'ابدأ الإرجاع' : 'Start a return' })
  await user.type(screen.getByLabelText(lang === 'ar' ? 'رقم الطلب' : 'Order number'), '#1047')
  await user.type(screen.getByLabelText(lang === 'ar' ? 'رقم الهاتف' : 'Phone number'), '010 1234 5678')
  await user.click(screen.getByRole('button', { name: lang === 'ar' ? 'ابحث عن طلبي' : 'Find my order' }))
  await screen.findByRole('heading', { name: lang === 'ar' ? 'ما الذي تريد إرجاعه؟' : 'What would you like to return?' })
  return user
}

async function select(user: ReturnType<typeof userEvent.setup>, title: string, times = 1) {
  const card = screen.getByText(title).closest('section')!
  for (let i = 0; i < times; i++) await user.click(within(card).getAllByRole('button')[1])
  await user.selectOptions(within(card).getByRole('combobox'), 'wrong_size')
  return card
}

describe('portal photos', () => {
  test('required: Continue waits for a photo on every line and for uploads; photo ids go with the line', async () => {
    const user = await toItems()
    const shirt = await select(user, 'Linen Shirt')
    const pants = await select(user, 'Flipped Pants', 2)
    const next = screen.getByRole('button', { name: 'Continue' })
    expect(next).toBeDisabled()
    expect(screen.getByText('Add a photo of each item')).toBeInTheDocument()
    expect(within(pants).getByText('Add at least 1 photo. One set covers all 2 pieces.')).toBeInTheDocument()

    await user.upload(within(shirt).getByTestId('photo-input'), [jpg('a.jpg'), jpg('b.jpg')])
    await waitFor(() => expect(uploads).toHaveLength(1))
    expect(screen.getByText('Uploading photos…')).toBeInTheDocument()
    uploads[0].resolve({ photoId: 'p-a', width: 1600, height: 1200 })
    await waitFor(() => expect(uploads).toHaveLength(2))
    uploads[1].resolve({ photoId: 'p-b', width: 1600, height: 1200 })
    await waitFor(() => expect(within(shirt).getAllByTestId('photo-tile').map(t => t.dataset.status)).toEqual(['done', 'done']))
    expect(within(shirt).getByText('2 of 3')).toBeInTheDocument()
    expect(next).toBeDisabled()                                   // pants still has none

    await user.upload(within(pants).getByTestId('photo-input'), jpg('c.jpg'))
    await waitFor(() => expect(uploads).toHaveLength(3))
    uploads[2].resolve({ photoId: 'p-c', width: 1600, height: 1200 })
    await waitFor(() => expect(next).toBeEnabled())

    await user.click(next)
    await screen.findByRole('heading', { name: 'Almost done' })
    await user.click(screen.getByRole('button', { name: 'Send return request' }))
    await screen.findByText('RR-7K3F9M')
    const body = JSON.parse(calls.find(c => c.url.endsWith('/requests'))!.init.body as string)
    expect(body.lines).toEqual([
      expect.objectContaining({ variantId: 'v-shirt', photoIds: ['p-a', 'p-b'] }),
      expect.objectContaining({ variantId: 'v-pants', quantity: 2, photoIds: ['p-c'] }),
    ])
  })

  test('errors: wrong type / too big under the line; more than 3 skipped; failed upload → Retry; remove', async () => {
    const user = await toItems()
    const shirt = await select(user, 'Linen Shirt')
    prepFails = { 'invoice.pdf': 'type', 'IMG_2041.HEIC': 'tooBig' }
    await user.upload(within(shirt).getByTestId('photo-input'), new File(['x'], 'invoice.pdf', { type: 'application/pdf' }))
    expect(await within(shirt).findByRole('alert')).toHaveTextContent('“invoice.pdf” isn\'t a photo. Use JPG, PNG, WebP or HEIC.')
    await user.upload(within(shirt).getByTestId('photo-input'), new File(['x'.repeat(10)], 'IMG_2041.HEIC', { type: 'image/heic' }))
    expect(await within(shirt).findByRole('alert')).toHaveTextContent('“IMG_2041.HEIC” is')

    await user.upload(within(shirt).getByTestId('photo-input'), [jpg('1.jpg'), jpg('2.jpg'), jpg('3.jpg'), jpg('4.jpg'), jpg('5.jpg')])
    expect(await within(shirt).findByRole('alert')).toHaveTextContent('You can add up to 3 photos per item. 2 weren\'t added.')
    await waitFor(() => expect(uploads).toHaveLength(1))
    uploads[0].reject(new (await import('../portal/photos')).PhotoPrepError('failed'))
    await waitFor(() => expect(uploads).toHaveLength(2))
    uploads[1].resolve({ photoId: 'p2', width: 1, height: 1 })
    await waitFor(() => expect(uploads).toHaveLength(3))
    uploads[2].resolve({ photoId: 'p3', width: 1, height: 1 })
    await waitFor(() => expect(within(shirt).getAllByTestId('photo-tile').map(t => t.dataset.status)).toEqual(['failed', 'done', 'done']))
    expect(within(shirt).queryByRole('button', { name: 'Add' })).toBeNull()                 // full
    expect(within(shirt).getByRole('alert')).toHaveTextContent("Couldn't upload this photo. Check your connection and tap Retry.")

    await user.click(within(shirt).getByRole('button', { name: /Retry/ }))
    await waitFor(() => expect(uploads).toHaveLength(4))
    uploads[3].resolve({ photoId: 'p1', width: 1, height: 1 })
    await waitFor(() => expect(within(shirt).getAllByTestId('photo-tile').every(t => t.dataset.status === 'done')).toBe(true))
    await user.click(within(shirt).getByRole('button', { name: 'Remove photo 2' }))
    expect(within(shirt).getAllByTestId('photo-tile')).toHaveLength(2)
    expect(within(shirt).getByRole('button', { name: 'Add' })).toBeInTheDocument()
  })

  test('optional when the store does not require photos', async () => {
    config = { ...BASE, requirePhotos: false }
    const user = await toItems()
    const shirt = await select(user, 'Linen Shirt')
    expect(within(shirt).getByText('Optional · up to 3')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Continue' })).toBeEnabled()
  })

  test('AR / RTL: Arabic labels; every thumbnail src is allowed by the returns host CSP (img-src)', async () => {
    const sources = imgSrcOf('returns.tracedtech.com')
    const user = await toItems('ar')
    const card = screen.getByText('Linen Shirt').closest('section')!
    await user.click(within(card).getAllByRole('button')[1])
    await user.selectOptions(within(card).getByRole('combobox'), 'wrong_size')
    expect(within(card).getByText('الصور')).toBeInTheDocument()
    expect(within(card).getByText('مطلوبة')).toBeInTheDocument()
    await user.upload(within(card).getByTestId('photo-input'), jpg('ar.jpg'))
    const img = await within(card).findByRole('img')
    expect(imgAllowed('returns.tracedtech.com', sources, img.getAttribute('src')!), `img-src ${sources.join(' ')}`).toBe(true)
    expect(img.getAttribute('src')!.startsWith('data:image/jpeg;base64,')).toBe(true)
  })
})
