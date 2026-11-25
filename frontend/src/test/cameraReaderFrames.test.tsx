import { describe, test, expect, beforeEach, afterEach, vi } from 'vitest'
import { render, cleanup, waitFor } from '@testing-library/react'
import { I18nextProvider, initReactI18next } from 'react-i18next'
import i18next from 'i18next'
import en from '../locales/en.json'

// Bazar C1 — CameraReader's only change is the optional onFrame prop. Without it (ScanPairPage, the warehouse phone
// scanner) every decode still goes to onRead, two codes in one frame included — exactly as before. With it, each read
// attempt reports all its codes ([] = nothing in view) and onRead is not called. Real component, a fake native
// BarcodeDetector and a fake camera stream.

const testI18n = i18next.createInstance()
testI18n.use(initReactI18next).init({
  lng: 'en', fallbackLng: 'en', initImmediate: false,
  resources: { en: { translation: en } }, interpolation: { escapeValue: false },
})

/** What the fake detector "sees", one entry per detect() call; then nothing. */
let frames: string[][] = []

class FakeDetector {
  static async getSupportedFormats() { return ['code_128', 'qr_code'] }
  async detect() {
    const f = frames.shift() ?? []
    return f.map(rawValue => ({ rawValue, format: 'code_128' }))
  }
}

async function loadZxingReader() {
  vi.resetModules()
  vi.stubGlobal('BarcodeDetector', undefined)
  const lib = await import('@zxing/library')
  vi.doMock('@zxing/browser', async () => {
    const real = await vi.importActual<typeof import('@zxing/browser')>('@zxing/browser')
    class FakeReader {
      async decodeFromVideoElement(_v: unknown, cb: (r: unknown, e: unknown) => void) {
        cb({ getText: () => 'A' }, undefined)
        cb(undefined, new lib.NotFoundException())
        cb(undefined, new lib.ChecksumException())          // a label half-read: neither a code nor an empty view
        cb({ getText: () => 'B' }, undefined)
        return { stop() {} }
      }
    }
    return { ...real, BrowserMultiFormatReader: FakeReader }
  })
  return (await import('../pages/scanpair/CameraReader')).default
}

async function loadReader() {
  vi.resetModules()
  vi.stubGlobal('BarcodeDetector', FakeDetector)
  return (await import('../pages/scanpair/CameraReader')).default
}

beforeEach(() => {
  const track = { stop() {}, getCapabilities: () => ({}), applyConstraints: async () => {} }
  const stream = { getTracks: () => [track], getVideoTracks: () => [track] }
  Object.defineProperty(window, 'isSecureContext', { value: true, configurable: true })
  Object.defineProperty(navigator, 'mediaDevices', {
    value: { getUserMedia: vi.fn(async () => stream) }, configurable: true,
  })
  Object.defineProperty(HTMLMediaElement.prototype, 'readyState', { get: () => 4, configurable: true })
  vi.spyOn(HTMLMediaElement.prototype, 'play').mockResolvedValue(undefined)
})

afterEach(() => {
  vi.doUnmock('@zxing/browser')
  cleanup()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('CameraReader — onFrame is optional; without it nothing changes', () => {
  test('without onFrame (warehouse): two codes in one frame → onRead twice, as before', async () => {
    const CameraReader = await loadReader()
    frames = [['A', 'B'], [], ['C']]
    const onRead = vi.fn()
    render(<I18nextProvider i18n={testI18n}><CameraReader active onRead={onRead} onError={vi.fn()} /></I18nextProvider>)
    await waitFor(() => expect(onRead.mock.calls).toEqual([['A'], ['B'], ['C']]))
  })

  test('with onFrame (bazar): one call per attempt with every code, [] for an empty view; onRead not called', async () => {
    const CameraReader = await loadReader()
    frames = [['A', 'B'], [], ['C']]
    const onRead = vi.fn()
    const onFrame = vi.fn()
    render(<I18nextProvider i18n={testI18n}>
      <CameraReader active onRead={onRead} onFrame={onFrame} onError={vi.fn()} />
    </I18nextProvider>)
    await waitFor(() => expect(onFrame.mock.calls.length).toBeGreaterThanOrEqual(4))
    expect(onFrame.mock.calls.slice(0, 4)).toEqual([[['A', 'B']], [[]], [['C']], [[]]])
    expect(onRead).not.toHaveBeenCalled()
  })

  test('ZXing (iPhone) with onFrame: a result → [code], NotFound → [], a half-read label → nothing', async () => {
    const CameraReader = await loadZxingReader()
    const onRead = vi.fn()
    const onFrame = vi.fn()
    render(<I18nextProvider i18n={testI18n}>
      <CameraReader active onRead={onRead} onFrame={onFrame} onError={vi.fn()} />
    </I18nextProvider>)
    await waitFor(() => expect(onFrame.mock.calls).toEqual([[['A']], [[]], [['B']]]))
    expect(onRead).not.toHaveBeenCalled()
  })

  test('ZXing without onFrame (warehouse): every result to onRead, as before', async () => {
    const CameraReader = await loadZxingReader()
    const onRead = vi.fn()
    render(<I18nextProvider i18n={testI18n}><CameraReader active onRead={onRead} onError={vi.fn()} /></I18nextProvider>)
    await waitFor(() => expect(onRead.mock.calls).toEqual([['A'], ['B']]))
  })
})
