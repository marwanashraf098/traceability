import { describe, expect, test } from 'vitest'
import { render, screen } from '@testing-library/react'
import { Tabs } from '../components/ui'

// Orders tabs fix (2026-10-30): a disabled tab must not look clickable — no hover affordance.
describe('Tabs', () => {
  test('a disabled tab keeps no hover style; an enabled inactive tab does', () => {
    render(
      <Tabs activeKey="a" onChange={() => {}} tabs={[
        { key: 'a', label: 'Active' },
        { key: 'b', label: 'Enabled' },
        { key: 'c', label: 'Disabled', disabled: true },
      ]} />,
    )
    expect(screen.getByRole('button', { name: 'Enabled' }).className).toContain('hover:text-primary')
    const disabled = screen.getByRole('button', { name: 'Disabled' })
    expect(disabled).toBeDisabled()
    expect(disabled.className).not.toContain('hover:')
  })
})
