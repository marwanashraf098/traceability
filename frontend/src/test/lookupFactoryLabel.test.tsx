import { test, expect, vi, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { renderWithProviders, screen } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import { setAccessToken, clearAccessToken } from '../auth'
import { resetCapabilitiesCache } from '../capabilities'
import * as api from '../api'
import LookupPage from '../pages/Lookup'

/**
 * Flexible labeling slice 6a — Lookup on an issued label code.
 *   lf1  a FACTORY code: "Factory label" + "Factory label not received yet — receive it in its factory run first"
 *   lf2  a TAG code keeps "Label not used yet — tag it in Tag stock first"
 */

afterEach(() => { clearAccessToken(); resetCapabilitiesCache(); vi.unstubAllGlobals(); vi.restoreAllMocks() })

function setup(kind: 'factory' | 'tag') {
  resetCapabilitiesCache()
  setAccessToken(`h.${btoa(JSON.stringify({ role: 'owner' }))}.s`)
  stubFetchWithShellDefaults(vi.fn(() => Promise.reject(new Error('unexpected fetch'))),
    { me: { name: 'O', role: 'owner', scanHelpers: false, demoMode: false, genericCourier: false, flexibleLabeling: true } })
  vi.spyOn(api, 'lookup').mockResolvedValue({
    type: 'label_code', code: 'P900003', status: 'issued', kind,
    variant: { id: 'v1', productTitle: 'Hydrogen', title: 'Blue', sku: 'H-1' },
    messageEn: '', messageAr: '',
  } as api.LabelCodeLookupResult)
}

test('lf1 — a factory code says to receive it in its factory run', async () => {
  setup('factory')
  renderWithProviders(<LookupPage />)
  await userEvent.type(screen.getByRole('textbox'), 'P900003{Enter}')
  expect(await screen.findByText('Factory label not received yet — receive it in its factory run first')).toBeTruthy()
  expect(screen.getByText('Factory label')).toBeTruthy()
  expect(screen.queryByText(/Tag stock first/)).toBeNull()
})

test('lf2 — a tag code keeps the Tag stock message', async () => {
  setup('tag')
  renderWithProviders(<LookupPage />)
  await userEvent.type(screen.getByRole('textbox'), 'P900003{Enter}')
  expect(await screen.findByText('Label not used yet — tag it in Tag stock first')).toBeTruthy()
})
