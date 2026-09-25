import { describe, test, expect, afterEach, vi } from 'vitest'
import { screen, render, cleanup } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import ErrorBoundary from '../components/ErrorBoundary'

/** A render error inside the boundary must show the fallback, never a blank page. */

function Boom(): never {
  throw new TypeError("Cannot read properties of null (reading 'replace')")
}

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
  document.documentElement.lang = 'en'
})

describe('ErrorBoundary', () => {
  test('renders children untouched when nothing throws', () => {
    render(<ErrorBoundary><p>all good</p></ErrorBoundary>)
    expect(screen.getByText('all good')).toBeInTheDocument()
    expect(screen.queryByTestId('error-boundary-fallback')).toBeNull()
  })

  test('catches a throwing child and renders the EN fallback with a working Reload button', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => {})
    const reload = vi.fn()
    vi.stubGlobal('location', { ...window.location, reload })

    render(<ErrorBoundary><Boom /></ErrorBoundary>)

    const fallback = screen.getByTestId('error-boundary-fallback')
    expect(fallback).toHaveAttribute('dir', 'ltr')
    expect(screen.getByText('Something went wrong')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Reload' }))
    expect(reload).toHaveBeenCalledTimes(1)
    vi.unstubAllGlobals()
  })

  test('follows <html lang="ar"> — Arabic copy, RTL', () => {
    vi.spyOn(console, 'error').mockImplementation(() => {})
    document.documentElement.lang = 'ar'

    render(<ErrorBoundary><Boom /></ErrorBoundary>)

    expect(screen.getByTestId('error-boundary-fallback')).toHaveAttribute('dir', 'rtl')
    expect(screen.getByText('حدث خطأ ما')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'إعادة التحميل' })).toBeInTheDocument()
  })
})
