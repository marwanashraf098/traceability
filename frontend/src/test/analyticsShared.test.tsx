import { test, expect, describe, vi } from 'vitest'
import userEvent from '@testing-library/user-event'
import { render, screen, within } from '@testing-library/react'
import { DataTable, type DataTableColumn } from '../components/ui'
import { Drawer } from '../components/Drawer'

type Row = { id: string; name: string; n: number }
const rows: Row[] = [{ id: '1', name: 'A', n: 2 }, { id: '2', name: 'B', n: 1 }]
const cols: DataTableColumn<Row>[] = [
  { key: 'name', header: 'Name', render: r => r.name },
  { key: 'n', header: 'Count', render: r => r.n, sortable: true, align: 'end' },
]

describe('DataTable sort prop (optional)', () => {
  test('t1 — without onSort, headers stay plain text (existing callers unchanged)', () => {
    render(<DataTable columns={cols} rows={rows} />)
    expect(screen.queryAllByRole('button')).toHaveLength(0)
    expect(screen.getByRole('columnheader', { name: 'Count' })).not.toHaveAttribute('aria-sort')
  })

  test('t2 — with onSort, sortable columns are buttons; the active one carries aria-sort; rows are never reordered by the table', async () => {
    const onSort = vi.fn()
    render(<DataTable columns={cols} rows={rows} sort={{ key: 'n', dir: 'desc' }} onSort={onSort} />)
    const header = screen.getByRole('columnheader', { name: /Count/ })
    expect(header).toHaveAttribute('aria-sort', 'descending')
    expect(within(screen.getByRole('columnheader', { name: 'Name' })).queryByRole('button')).toBeNull()
    await userEvent.click(within(header).getByRole('button'))
    expect(onSort).toHaveBeenCalledWith('n')
    expect(screen.getAllByRole('row')[1]).toHaveTextContent('A2')
  })
})

describe('Drawer', () => {
  test('d1 — open: title, focus on close, Escape and the scrim close it', async () => {
    const onClose = vi.fn()
    const { container } = render(<Drawer open onClose={onClose} title="SKU" closeLabel="Close"><p>body</p></Drawer>)
    expect(screen.getByRole('dialog')).toHaveTextContent('SKU')
    expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Close' }))
    await userEvent.keyboard('{Escape}')
    expect(onClose).toHaveBeenCalledTimes(1)
    await userEvent.click(container.firstElementChild as HTMLElement)
    expect(onClose).toHaveBeenCalledTimes(2)
  })

  test('d2 — closed: nothing inside is rendered', () => {
    render(<Drawer open={false} onClose={() => {}} title="SKU" closeLabel="Close"><p>body</p></Drawer>)
    expect(screen.queryByText('body')).toBeNull()
  })
})
