import { test, expect, describe, vi, beforeEach } from 'vitest'
import { renderWithProviders, screen } from './renderWithProviders'
import * as api from '../api'
import Onboarding from '../pages/Onboarding'

// Mock the two api functions used by Onboarding.tsx.
// vi.mock is hoisted above imports by Vitest's transformer; the imported `api`
// object is already the mocked version when tests run.
vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return {
    ...actual,
    getOnboardingStatus: vi.fn(),
    getRoleFromToken: vi.fn(() => 'owner' as const),
  }
})

// ── Helpers ───────────────────────────────────────────────────────────────────

type StepKey = api.OnboardingStep['key']

function mkStep(key: StepKey, status: 'done' | 'pending'): api.OnboardingStep {
  return { key, label: key, status }
}

const ALL_KEYS: StepKey[] = [
  'connect_shopify',
  'connect_bosta',
  'initial_import',
  'test_label',
  'first_receiving',
]

function allPending(): api.OnboardingStatus {
  return { steps: ALL_KEYS.map(k => mkStep(k, 'pending')), allDone: false }
}

function allDone(): api.OnboardingStatus {
  return { steps: ALL_KEYS.map(k => mkStep(k, 'done')), allDone: true }
}

// ── Tests ─────────────────────────────────────────────────────────────────────

describe('Onboarding wizard', () => {
  beforeEach(() => vi.clearAllMocks())

  // 1. All-pending: all 5 step rows render, each marked pending
  test('all-pending status renders all 5 steps as pending with action links', async () => {
    vi.mocked(api.getOnboardingStatus).mockResolvedValue(allPending())

    renderWithProviders(<Onboarding />)

    for (const key of ALL_KEYS) {
      const row = await screen.findByTestId(`step-${key}`)
      expect(row).toHaveAttribute('data-status', 'pending')
    }

    // Each pending step must have a link (the "action" button)
    const links = screen.getAllByRole('link')
    expect(links.length).toBeGreaterThanOrEqual(ALL_KEYS.length)
  })

  // 2. Partial status: ①② done, ③④⑤ pending
  test('partial status renders correct done/pending split', async () => {
    vi.mocked(api.getOnboardingStatus).mockResolvedValue({
      steps: [
        mkStep('connect_shopify', 'done'),
        mkStep('connect_bosta',   'done'),
        mkStep('initial_import',  'pending'),
        mkStep('test_label',      'pending'),
        mkStep('first_receiving', 'pending'),
      ],
      allDone: false,
    })

    renderWithProviders(<Onboarding />)

    expect(await screen.findByTestId('step-connect_shopify')).toHaveAttribute('data-status', 'done')
    expect(screen.getByTestId('step-connect_bosta')).toHaveAttribute('data-status', 'done')
    expect(screen.getByTestId('step-initial_import')).toHaveAttribute('data-status', 'pending')
    expect(screen.getByTestId('step-test_label')).toHaveAttribute('data-status', 'pending')
    expect(screen.getByTestId('step-first_receiving')).toHaveAttribute('data-status', 'pending')

    // Completed state must NOT appear
    expect(screen.queryByTestId('onboarding-complete')).toBeNull()
  })

  // 3. All-done: "set up" completed state appears, step rows do not
  test('all-done status shows the completed state', async () => {
    vi.mocked(api.getOnboardingStatus).mockResolvedValue(allDone())

    renderWithProviders(<Onboarding />)

    expect(await screen.findByTestId('onboarding-complete')).toBeInTheDocument()

    // Individual step rows should not be present
    for (const key of ALL_KEYS) {
      expect(screen.queryByTestId(`step-${key}`)).toBeNull()
    }
  })

  // 4. Step ④ pending → signal-lag helper text is visible
  test('step test_label pending shows the signal-lag hint', async () => {
    vi.mocked(api.getOnboardingStatus).mockResolvedValue(allPending())

    renderWithProviders(<Onboarding />)

    const hint = await screen.findByTestId('step-test_label-hint')
    expect(hint).toBeInTheDocument()
    // Hint must be non-empty (locale key resolved)
    expect(hint.textContent?.trim().length).toBeGreaterThan(0)
  })

  // 5. API error → inline error shown, no crash
  test('api error shows inline error and does not crash', async () => {
    vi.mocked(api.getOnboardingStatus).mockRejectedValue(new Error('network failure'))

    renderWithProviders(<Onboarding />)

    const alert = await screen.findByRole('alert')
    expect(alert).toBeInTheDocument()

    // No step rows or completed state on error
    expect(screen.queryByTestId('onboarding-complete')).toBeNull()
    for (const key of ALL_KEYS) {
      expect(screen.queryByTestId(`step-${key}`)).toBeNull()
    }
  })
})
