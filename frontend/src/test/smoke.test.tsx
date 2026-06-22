import { test, expect } from 'vitest'
import { renderWithProviders, screen } from './renderWithProviders'

// Proves: jsdom renders, jest-dom matchers work, MemoryRouter + I18nextProvider
// are in place, and the test runner exits cleanly.
test('smoke: renders a labelled element', () => {
  renderWithProviders(<p data-testid="hello">traced</p>)
  expect(screen.getByTestId('hello')).toBeInTheDocument()
  expect(screen.getByTestId('hello')).toHaveTextContent('traced')
})
