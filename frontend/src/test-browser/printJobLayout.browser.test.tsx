import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { page } from 'vitest/browser'
import { cleanup } from '@testing-library/react'
import '../index.css'
import { renderWithProviders } from '../test/renderWithProviders'
import { stubFetchWithShellDefaults } from '../test/mockShellFetch'
import PrintWaybillsDialog from '../pages/fulfill/PrintWaybillsDialog'
import { setPrintJobPollMs } from '../pages/fulfill/PrintJobPanel'

// Merged-PDF slice C — the print dialog's progress and result in a real browser with the app's CSS
// (Chromium + WebKit) at desktop 1280×800, station tablet 768×1024 and phone 390×844 (16 px from each
// edge): the dialog fits the viewport, nothing scrolls sideways, and no two buttons overlap.

const json = (data: unknown, status = 200) => Promise.resolve({ ok: status < 300, status, statusText: '',
  headers: { get: (k: string) => (k.toLowerCase() === 'content-type' ? 'application/json' : null) },
  json: async () => structuredClone(data) })

const base = {
  jobId: 'job-1', kind: 'print', total: 490, candidateCount: 600, remainingCount: 110, batchId: 'batch-12',
  batchNo: 12, reprintBatchId: null, reprintBatchNo: null, orderGuaranteed: false, pdfBytes: 1000,
  createdAt: new Date().toISOString(), expiresAt: null,
}
const RUNNING = { ...base, status: 'running', done: 98, printedCount: 98, excluded: [], pdfAvailable: true,
  message: null, finishedAt: null }
const PARTIAL = {
  ...base, status: 'partial', done: 490, printedCount: 147, pdfAvailable: true, finishedAt: new Date().toISOString(),
  message: { code: 'BOSTA_BUSY', messageEn: 'Bosta is busy — the rest weren’t printed.', messageAr: 'x' },
  excluded: Array.from({ length: 12 }, (_, i) => ({
    orderNumber: `#10${String(i).padStart(3, '0')}`, trackingNumber: String(7_000_000_000 + i * 7919),
    reason: i === 0 ? 'DELETED_IN_BOSTA' : 'BOSTA_BUSY',
  })),
}

let jobState: unknown
function backend() {
  stubFetchWithShellDefaults((url: string, opts?: RequestInit) => {
    if (url.endsWith('/fulfill/print-batches/options')) return json({ defaultPaper: 'A6' })
    if (url.endsWith('/fulfill/print-jobs/latest')) return json(jobState)
    if (url.endsWith('/fulfill/print-jobs') && opts?.method === 'POST') return json({ jobId: 'job-1' }, 202)
    if (url.endsWith('/fulfill/print-jobs/job-1')) return json(jobState)
    return json({})
  })
}

/** Every pair of visible buttons whose boxes overlap. */
function overlappingButtons(root: HTMLElement): string[] {
  const buttons = [...root.querySelectorAll<HTMLElement>('button')].filter(b => b.offsetParent !== null)
  const out: string[] = []
  for (let i = 0; i < buttons.length; i++) {
    for (let j = i + 1; j < buttons.length; j++) {
      const a = buttons[i].getBoundingClientRect(), b = buttons[j].getBoundingClientRect()
      if (a.left < b.right - 1 && b.left < a.right - 1 && a.top < b.bottom - 1 && b.top < a.bottom - 1) {
        out.push(`${buttons[i].textContent} × ${buttons[j].textContent}`)
      }
    }
  }
  return out
}

const SIZES: [string, number, number][] = [['desktop', 1280, 800], ['tablet', 768, 1024], ['phone', 390, 844]]

describe('print job dialog layout', () => {
  beforeEach(() => { setPrintJobPollMs(20) })
  afterEach(async () => {
    cleanup()
    vi.unstubAllGlobals()
    await page.viewport(1280, 800)
  })

  for (const [name, w, h] of SIZES) {
    for (const [state, data, marker] of [['progress', RUNNING, 'print-job-progress'], ['result', PARTIAL, 'print-batch-result']] as const) {
      test(`${state} fits at ${name} ${w}×${h}`, async () => {
        await page.viewport(w, h)
        jobState = data
        backend()
        renderWithProviders(<PrintWaybillsDialog newCount={600} allCount={600} onClose={() => {}} />)
        if (state === 'result') {
          // A finished print is offered as "Last print … Show" on reopening; Show opens its result.
          await expect.poll(() => document.querySelector('[data-testid="last-print"]')).toBeTruthy()
          const show = [...document.querySelectorAll<HTMLElement>('[data-testid="last-print"] button')]
            .find(b => b.textContent === 'Show')!
          show.click()
        }
        await expect.poll(() => document.querySelector(`[data-testid="${marker}"]`)).toBeTruthy()
        const panel = document.querySelector<HTMLElement>(`[data-testid="${marker}"]`)!
        const dialog = panel.closest<HTMLElement>('.rounded-2xl')!
        const box = dialog.getBoundingClientRect()

        expect(document.documentElement.scrollWidth).toBeLessThanOrEqual(window.innerWidth)
        expect(box.left).toBeGreaterThanOrEqual(w < 640 ? 15 : 0)
        expect(box.right).toBeLessThanOrEqual(w - (w < 640 ? 15 : 0))
        expect(overlappingButtons(dialog)).toEqual([])
        for (const b of dialog.querySelectorAll<HTMLElement>('button')) {
          expect(b.getBoundingClientRect().right).toBeLessThanOrEqual(box.right + 0.5)
        }
      })
    }
  }
})
