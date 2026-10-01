import { test, expect, vi, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { render, screen } from '@testing-library/react'
import ScanSpike, { visible } from '../pages/ScanSpike'

// TEMPORARY — camera-scan spike (delete with pages/ScanSpike.tsx after S6).
// jsdom has no camera and no BarcodeDetector; this only proves the page renders standalone,
// makes no API calls, and reports a missing camera instead of crashing.

afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks() })

test('visible() shows spaces and control characters', () => {
  expect(visible('G - 02')).toBe('G·-·02')
  expect(visible('a\tb\n\x1dc d')).toBe('a⇥b↵\\x1dc⟨U+00A0⟩d')
})

test('renders without auth or API calls; no camera → a clear message', async () => {
  const fetchSpy = vi.fn()
  vi.stubGlobal('fetch', fetchSpy)
  render(<ScanSpike />)
  expect(screen.getByTestId('engines')).toHaveTextContent('not available on this browser')
  expect(screen.getByTestId('engines')).toHaveTextContent('@zxing/browser: available')
  expect(screen.getByRole('radio', { name: 'Native' })).toBeDisabled()
  expect(screen.getByRole('radio', { name: 'zxing' })).toHaveAttribute('aria-checked', 'true')

  await userEvent.setup().click(screen.getByRole('button', { name: 'Start camera' }))
  expect(await screen.findByRole('alert')).toHaveTextContent(/Camera needs HTTPS|no camera API/)
  expect(fetchSpy).not.toHaveBeenCalled()
})
