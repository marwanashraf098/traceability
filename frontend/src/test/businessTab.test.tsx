import { test, expect, describe, vi, beforeEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { renderWithProviders, screen, waitFor } from './renderWithProviders'
import * as api from '../api'
import BusinessTab from '../pages/settings/BusinessTab'

// ── Mock ─────────────────────────────────────────────────────────────────────

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return {
    ...actual,
    getTenantSettings:   vi.fn(),
    updateTenantSettings: vi.fn(),
    getConnections:      vi.fn(),
    bostaUpdateSettings: vi.fn(),
  }
})

// ── Fixtures ──────────────────────────────────────────────────────────────────

function tenantSettingsFixture(): api.TenantSettings {
  return {
    name: 'Test Co',
    pickupAddress: '1 Test St',
    labelSize: '40x25',
    defaultLanguage: 'ar',
    timezone: 'Africa/Cairo',
    consentPrivacyVersion: null,
    consentTermsVersion: null,
    consentAcceptedAt: null,
  }
}

// Bosta connected (as it is for the pilots) so AWB controls are enabled.
function connectionsFixture(): api.ConnectionsStatus {
  return {
    shopify: { connected: false, shopDomain: null, importStatus: null, lastSyncAt: null },
    bosta: { connected: true, businessName: 'Acme Logistics', pickupMode: null, awbFormat: 'A4', awbLang: 'ar' },
    shopifyCustomApp: { connected: false, shopDomain: null, importStatus: null, lastSyncAt: null },
    customAppAvailable: false,
  }
}

async function renderLoaded() {
  const utils = renderWithProviders(<BusinessTab isOwner={true} />)
  await screen.findByTestId('business-save')
  return utils
}

describe('BusinessTab — Save fan-out', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(api.getTenantSettings).mockResolvedValue(tenantSettingsFixture())
    vi.mocked(api.getConnections).mockResolvedValue(connectionsFixture())
    vi.mocked(api.updateTenantSettings).mockResolvedValue(undefined)
    vi.mocked(api.bostaUpdateSettings).mockResolvedValue(undefined)
  })

  // bt1 (a): awb_lang-only change fires ONLY PUT /bosta/settings, never PUT /tenant/settings.
  test('bt1 — awb_lang-only change fires only bostaUpdateSettings', async () => {
    const user = userEvent.setup()
    await renderLoaded()

    await user.click(screen.getByTestId('awbLang-en'))
    await user.click(screen.getByTestId('business-save'))

    await waitFor(() => expect(api.bostaUpdateSettings).toHaveBeenCalledTimes(1))
    expect(api.bostaUpdateSettings).toHaveBeenCalledWith({ awbLang: 'en' })
    expect(api.updateTenantSettings).not.toHaveBeenCalled()
  })

  // bt2 (b): a tenant-field change AND an awb change together fire both calls.
  test('bt2 — tenant field + awb both changed fires both calls', async () => {
    const user = userEvent.setup()
    await renderLoaded()

    const nameInput = screen.getByLabelText('Business name') as HTMLInputElement
    await user.clear(nameInput)
    await user.type(nameInput, 'Renamed Co')
    await user.click(screen.getByTestId('awbLang-en'))
    await user.click(screen.getByTestId('business-save'))

    await waitFor(() => expect(api.updateTenantSettings).toHaveBeenCalledTimes(1))
    expect(api.updateTenantSettings).toHaveBeenCalledWith(
      expect.objectContaining({ name: 'Renamed Co' })
    )
    // awb_lang must never leak into the tenant payload.
    expect(api.updateTenantSettings).not.toHaveBeenCalledWith(
      expect.objectContaining({ awbLang: expect.anything() })
    )
    expect(api.bostaUpdateSettings).toHaveBeenCalledTimes(1)
    expect(api.bostaUpdateSettings).toHaveBeenCalledWith({ awbLang: 'en' })
  })

  // bt3 (c): one leg rejects — that section shows its own error, the other leg's
  // success persists (its snapshot advances, Save re-disables with no more diff).
  test('bt3 — awb leg rejects while tenant leg succeeds: independent per-section outcomes', async () => {
    vi.mocked(api.updateTenantSettings).mockResolvedValue(undefined)
    vi.mocked(api.bostaUpdateSettings).mockRejectedValue(new Error('500'))

    const user = userEvent.setup()
    await renderLoaded()

    const nameInput = screen.getByLabelText('Business name') as HTMLInputElement
    await user.clear(nameInput)
    await user.type(nameInput, 'Renamed Co')
    await user.click(screen.getByTestId('awbLang-en'))
    await user.click(screen.getByTestId('business-save'))

    // Tenant section: success.
    await screen.findByTestId('business-result-saved')
    // AWB section: its own error, independent of the tenant section's success.
    await screen.findByTestId('awb-result-error')
    expect(screen.queryByTestId('business-result-error')).toBeNull()

    // Save re-disables: the tenant snapshot advanced (no more diff on that field);
    // the failed awb leg's snapshot did NOT advance, but awbLang is back to matching
    // awbSnapshot's value only if the user hasn't changed it further — here the
    // control still reads 'en' while the snapshot never moved off 'ar', so a diff
    // would remain UNLESS the button reflects that correctly.
    const saveBtn = screen.getByTestId('business-save') as HTMLButtonElement
    // The awb leg failed, so its snapshot is still 'ar' and the control is still 'en' —
    // hasChanges must therefore still be true (retry is available), Save stays enabled.
    expect(saveBtn.disabled).toBe(false)
  })

  // bt4: no changes at all — Save stays disabled, no calls fire.
  test('bt4 — no diff means Save is disabled and neither endpoint is called', async () => {
    await renderLoaded()
    const saveBtn = screen.getByTestId('business-save') as HTMLButtonElement
    expect(saveBtn.disabled).toBe(true)
    expect(api.updateTenantSettings).not.toHaveBeenCalled()
    expect(api.bostaUpdateSettings).not.toHaveBeenCalled()
  })
})
