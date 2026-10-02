import { afterEach, expect, test } from 'vitest'
import { useCallback, useState } from 'react'
import { flushSync } from 'react-dom'
import { createRoot, type Root } from 'react-dom/client'
import { useScanner, type UseScannerResult } from '../hooks/useScanner'

// useScanner single flight, in a real browser (Chromium + WebKit), deterministically.
// flushSync(handleScan) is what React does for an Enter keydown: a Sync-priority update. The
// worker's own updates are made inside an effect, so React gives them at most Default priority
// and renders a Sync update first without them. Before the fix the worker guarded on the
// render-time `scanning`, still false in that render — two Enters right after a scan started
// (or one Enter plus any other screen state change, when onScan is a new function every render)
// started a second onScan while the first was in flight.

const LATENCY_MS = 100

let api!: UseScannerResult
let root: Root | null = null
let host: HTMLDivElement | null = null

function mount(node: React.ReactNode) {
  host = document.createElement('div')
  document.body.appendChild(host)
  root = createRoot(host)
  flushSync(() => root!.render(node))
}
afterEach(() => { root?.unmount(); host?.remove(); root = null; host = null })

const enter = (code: string) => flushSync(() => { void api.handleScan(code) })
const sleep = (ms: number) => new Promise(r => setTimeout(r, ms))
const microtasks = async (n = 20) => { for (let i = 0; i < n; i++) await Promise.resolve() }

function recorder() {
  const calls: string[] = []
  let inFlight = 0, max = 0
  const onScan = async (code: string) => {
    calls.push(code)
    inFlight++
    max = Math.max(max, inFlight)
    await sleep(LATENCY_MS)
    inFlight--
    return { success: true }
  }
  return { onScan, calls, max: () => max }
}
type Rec = ReturnType<typeof recorder>

/** Like PackSessionScreen: onScan is a useCallback whose identity a pending-only render keeps. */
function Stable({ rec }: { rec: Rec }) {
  const onScan = useCallback((c: string) => rec.onScan(c), [rec])
  api = useScanner({ onScan })
  return <input ref={api.inputRef} />
}

let bump!: () => void
/** Like StockTakeScan / TransferScanOut / TransferReconcile: a new onScan every render, plus
 *  screen state a discrete event can change (a dialog, TransferReconcile's quantity fields). */
function InlineWithState({ rec }: { rec: Rec }) {
  const [n, setN] = useState(0)
  bump = () => setN(x => x + 1)
  api = useScanner({ onScan: async (c) => rec.onScan(c) })
  return <input ref={api.inputRef} data-n={n} />
}

test('stable onScan: two Enters right after a scan starts → still one in flight, in order', async () => {
  const rec = recorder()
  mount(<Stable rec={rec} />)
  enter('A')                       // worker starts A
  enter('B')
  enter('C')                       // pending changes in a Sync render that lacks scanning=true
  await sleep(LATENCY_MS * 3 + 300)
  expect(rec.max()).toBe(1)
  expect(rec.calls).toEqual(['A', 'B', 'C'])
})

test('inline onScan: one Enter plus one screen state change → still one in flight, in order', async () => {
  const rec = recorder()
  mount(<InlineWithState rec={rec} />)
  enter('A')
  enter('B')
  flushSync(() => bump())          // a new onScan in a Sync render that lacks scanning=true
  await sleep(LATENCY_MS * 2 + 300)
  expect(rec.max()).toBe(1)
  expect(rec.calls).toEqual(['A', 'B'])
})

/** Each onScan records the `stage` it was built with; the previous scan's result bumps it. */
function Staged({ saw, gates }: { saw: Array<[string, number]>; gates: Array<() => void> }) {
  const [stage, setStage] = useState(0)
  const onScan = useCallback(async (code: string) => {
    saw.push([code, stage])
    await new Promise<void>(r => gates.push(r))
    setStage(s => s + 1)           // the scan's result, made inside onScan like setOrder
    return { success: true }
  }, [stage, saw, gates])
  api = useScanner({ onScan })
  return <input ref={api.inputRef} />
}

test('each queued scan runs with the state the previous scan produced — even if an Enter lands between its completion and the render', async () => {
  const saw: Array<[string, number]> = []
  const gates: Array<() => void> = []
  mount(<Staged saw={saw} gates={gates} />)
  enter('A')
  enter('B')
  await sleep(30)                  // A is in flight and rendered as such
  gates.shift()!()                 // A's response arrives…
  await microtasks()               // …onScan's setStage and the hook's completion are queued (Default)
  enter('C')                       // a Sync render before that Default render
  for (let i = 0; i < 2; i++) {
    await expect.poll(() => gates.length).toBe(1)
    gates.shift()!()
  }
  await sleep(50)
  expect(saw).toEqual([['A', 0], ['B', 1], ['C', 2]])
})
