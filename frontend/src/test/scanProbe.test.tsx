import { test, expect, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { Route, Routes } from 'react-router-dom'
import { fireEvent, renderWithProviders, screen, waitFor, within } from './renderWithProviders'
import ScanProbe from '../pages/ScanProbe'
import { OwnerOnlyRoute } from '../App'
import * as api from '../api'

// S2 — /scan-probe: a real useScanner input that logs each keydown and shows, per scan, what was
// captured next to what the keyboard layout typed. No backend call of any kind.

beforeEach(() => {
  vi.stubGlobal('AudioContext', undefined)
  vi.stubGlobal('fetch', vi.fn(() => { throw new Error('the probe must not call the backend') }))
})
afterEach(() => { vi.unstubAllGlobals(); vi.unstubAllEnvs(); vi.restoreAllMocks(); window.history.replaceState({}, '', '/') })

test('a Windows Arabic 101 scan of P000123 is captured as P000123, with the layout text alongside', async () => {
  vi.stubEnv('VITE_SCAN_KEY_CAPTURE', 'false')                // the probe captures whatever the build flag says
  window.history.replaceState({}, '', '/scan-probe')
  renderWithProviders(<ScanProbe />)
  const input = screen.getByPlaceholderText('Scan a barcode, then Enter') as HTMLInputElement
  input.focus()
  // Shift+P types ؛ on Windows Arabic 101; the number row is Latin.
  const user = userEvent.setup({ keyboardMap: [
    { code: 'KeyP', key: '؛' }, { code: 'ShiftLeft', key: 'Shift' },
    ...'0123'.split('').map(d => ({ code: 'Digit' + d, key: d })),
  ] })
  await user.keyboard('{Shift>}[KeyP]{/Shift}[Digit0][Digit0][Digit0][Digit1][Digit2][Digit3]')
  fireEvent.keyDown(input, { code: 'Enter', key: 'Enter' })

  await waitFor(() => expect(screen.getByTestId('probe-captured')).toHaveTextContent('P000123'))
  expect(screen.getByTestId('probe-raw')).toHaveTextContent('؛000123')
  const keys = within(screen.getByTestId('probe-keys'))
  expect(keys.getAllByText('handled').length).toBeGreaterThanOrEqual(7)     // P + 6 digits
  expect(keys.getAllByText('browser default').length).toBeGreaterThanOrEqual(2) // Shift, Enter
  expect(keys.getAllByText(/U\+061B/).length).toBeGreaterThan(0)

  await user.click(screen.getByRole('button', { name: 'Clear' }))
  expect(screen.queryByTestId('probe-captured')).toBeNull()
  expect(fetch).not.toHaveBeenCalled()
})

test('a worker is sent away from /scan-probe', () => {
  vi.spyOn(api, 'getRoleFromToken').mockReturnValue('worker')
  renderWithProviders(
    <Routes>
      <Route path="/scan-probe" element={<OwnerOnlyRoute><ScanProbe /></OwnerOnlyRoute>} />
      <Route path="/worker-home" element={<div>worker home</div>} />
    </Routes>,
    { initialEntries: ['/scan-probe'] },
  )
  expect(screen.getByText('worker home')).toBeInTheDocument()
})
