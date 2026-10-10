import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { I18nextProvider } from 'react-i18next'
import '../portal/portal.css'
import PortalApp from '../portal/PortalApp'
import { createPortalI18n } from '../portal/i18n'
import { preparePhoto, PhotoPrepError } from '../portal/photos'

// P3 — the portal's photo prep in a REAL browser (Chromium + WebKit): createImageBitmap + canvas
// re-encode to JPEG (long edge ≤ 2400), a data: URL thumbnail, and picking three photos at once
// on a line → three uploaded tiles. jsdom has neither createImageBitmap nor a canvas encoder.

function canvasFile(w: number, h: number, name: string, type: 'image/png' | 'image/jpeg' = 'image/png'): Promise<File> {
  const c = document.createElement('canvas')
  c.width = w
  c.height = h
  const g = c.getContext('2d')!
  g.fillStyle = '#9C7B5A'
  g.fillRect(0, 0, w, h)
  g.fillStyle = '#FFFFFF'
  g.fillRect(w / 4, h / 4, w / 2, h / 2)
  return new Promise(res => c.toBlob(b => res(new File([b!], name, { type })), type, 0.9))
}

class FakeXhr {
  static sent = 0
  status = 0
  responseText = ''
  upload: { onprogress: ((e: ProgressEvent) => void) | null } = { onprogress: null }
  onload: (() => void) | null = null
  onerror: (() => void) | null = null
  onabort: (() => void) | null = null
  open() {}
  setRequestHeader() {}
  abort() {}
  send() {
    const n = ++FakeXhr.sent
    setTimeout(() => {
      this.status = 201
      this.responseText = JSON.stringify({ photoId: `p${n}`, width: 1600, height: 1200 })
      this.onload?.()
    }, 20)
  }
}

const json = (d: unknown, status = 200) => Promise.resolve(new Response(JSON.stringify(d), { status, headers: { 'content-type': 'application/json' } }))

beforeEach(() => {
  FakeXhr.sent = 0
  vi.stubGlobal('XMLHttpRequest', FakeXhr)
  vi.stubGlobal('fetch', vi.fn((url: string) => {
    if (url.endsWith('/config')) return json({ storeName: 'The Snouts', returnWindowDays: 14, reasonCodes: ['wrong_size'], logoUrl: null,
      brandColor: '#0F766E', policyText: null, autoApprove: false, pickupBooking: false, requirePhotos: true })
    if (url.endsWith('/lookup')) return json({ token: 't', orderNumber: '#1047', deliveredAt: '2026-09-18T10:00:00Z', pickup: null,
      lines: [{ variantId: 'v1', productTitle: 'Linen Shirt', variantTitle: 'White / M', imageUrl: null, deliveredQuantity: 1,
        returnableQuantity: 1, nonReturnable: false }] })
    return json({}, 404)
  }))
})

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe('portal photo prep (real browser)', () => {
  test('re-encodes to JPEG, long edge ≤ 2400, data: thumbnail; a non-image is refused', async () => {
    const big = await canvasFile(3600, 2400, 'big.png')
    const p = await preparePhoto(big)
    expect(p.blob.type).toBe('image/jpeg')
    expect(p.dataUrl.startsWith('data:image/jpeg;base64,')).toBe(true)
    const bm = await createImageBitmap(p.blob)
    expect(Math.max(bm.width, bm.height)).toBe(2400)
    expect(bm.width).toBe(2400)
    expect(bm.height).toBe(1600)

    const small = await preparePhoto(await canvasFile(640, 480, 'small.jpg', 'image/jpeg'))
    const sb = await createImageBitmap(small.blob)
    expect([sb.width, sb.height]).toEqual([640, 480])

    await expect(preparePhoto(new File(['%PDF-1.4'], 'invoice.pdf', { type: 'application/pdf' })))
      .rejects.toBeInstanceOf(PhotoPrepError)
    await expect(preparePhoto(new File(['not really'], 'fake.jpg', { type: 'image/jpeg' })))
      .rejects.toMatchObject({ code: 'type' })
  })

  test('three photos picked at once on a line → three uploaded tiles, Continue enabled', async () => {
    const user = userEvent.setup()
    render(<I18nextProvider i18n={createPortalI18n('en')}><PortalApp slug="thesnouts" /></I18nextProvider>)
    await screen.findByRole('heading', { name: 'Start a return' })
    await user.type(screen.getByLabelText('Order number'), '#1047')
    await user.type(screen.getByLabelText('Phone number'), '01012345678')
    await user.click(screen.getByRole('button', { name: 'Find my order' }))
    const card = (await screen.findByText('Linen Shirt')).closest('section')!
    await user.click(within(card).getByRole('button', { name: 'Increase quantity' }))
    await user.selectOptions(within(card).getByRole('combobox'), 'wrong_size')
    expect(screen.getByRole('button', { name: 'Continue' })).toBeDisabled()

    const files = await Promise.all([canvasFile(1200, 900, 'a.png'), canvasFile(900, 1200, 'b.png'), canvasFile(800, 800, 'c.png')])
    await user.upload(within(card).getByTestId('photo-input'), files)
    await waitFor(() => expect(within(card).getAllByTestId('photo-tile').map(t => t.dataset.status)).toEqual(['done', 'done', 'done']),
      { timeout: 15_000 })
    for (const img of within(card).getAllByRole('img')) {
      expect(img.getAttribute('src')!.startsWith('data:image/jpeg;base64,')).toBe(true)
      expect((img as HTMLImageElement).naturalWidth).toBeGreaterThan(0)       // it actually decoded
    }
    expect(FakeXhr.sent).toBe(3)
    expect(screen.getByRole('button', { name: 'Continue' })).toBeEnabled()
  })
})
