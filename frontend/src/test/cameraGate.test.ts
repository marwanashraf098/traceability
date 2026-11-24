import { describe, test, expect } from 'vitest'
import { CameraGate } from '../bazar/cameraGate'

// Bazar C1 — the camera gate on its own, with an explicit clock (settle 250 ms, empty view 600 ms over ≥ 3 attempts).

const timing = { settleMs: 250, quietMs: 600, quietFrames: 3 }

function gate(mode: 'pause' | 'pile', reply: 'await' | void = undefined) {
  const sent: string[] = []
  const g = new CameraGate(mode, c => { sent.push(c); return reply }, timing)
  return { g, sent }
}

/** Frames every 60 ms from {@code from}; returns the time after the last one. */
function feed(g: CameraGate, frames: string[][], from: number, step = 60): number {
  let t = from
  for (const f of frames) { g.frame(f, t); t += step }
  return t
}

describe('settle: one aim = one code', () => {
  test('two codes in one frame → nothing sent, "two labels"', () => {
    const { g, sent } = gate('pause')
    g.frame(['A', 'B'], 0)
    g.endSettle()
    expect(sent).toEqual([])
    expect(g.notice).toBe('twoLabels')
  })

  test('A then B inside the settle window → nothing sent', () => {
    const { g, sent } = gate('pause')
    g.frame(['A'], 0)
    g.frame(['B'], 120)
    g.endSettle()
    expect(sent).toEqual([])
    expect(g.notice).toBe('twoLabels')
  })

  test('the same code over 20 frames → sent once', () => {
    const { g, sent } = gate('pause')
    feed(g, Array.from({ length: 20 }, () => ['A']), 0)
    g.endSettle()
    expect(sent).toEqual(['A'])
  })

  test('a frame after the window ends the settle first (no timer needed)', () => {
    const { g, sent } = gate('pause')
    g.frame(['A'], 0)
    g.frame(['A'], 300)
    expect(sent).toEqual(['A'])
  })

  test('busy: codes are ignored, never collected', () => {
    const { g, sent } = gate('pause')
    g.frame(['A'], 0, true)
    g.endSettle()
    expect(sent).toEqual([])
    expect(g.phase).toBe('open')
  })
})

describe('pause (quick sale, cart, Return / Exchange)', () => {
  test('after a send every code is ignored until the view is clear', () => {
    const { g, sent } = gate('pause')
    g.frame(['A'], 0); g.endSettle()
    let t = feed(g, [['B'], ['B'], ['A', 'B'], ['B']], 300)
    g.endSettle()
    expect(sent).toEqual(['A'])
    t = feed(g, [[], [], [], [], [], [], [], [], [], [], [], []], t)    // 12 × 60 ms empty
    expect(g.phase).toBe('open')
    g.frame(['B'], t); g.endSettle()
    expect(sent).toEqual(['A', 'B'])
  })

  test('fewer than 3 empty attempts → still paused, however long', () => {
    const { g } = gate('pause')
    g.frame(['A'], 0); g.endSettle()
    g.frame([], 1_000)
    g.frame([], 5_000)
    expect(g.phase).toBe('paused')
  })

  test('3 empty attempts under 600 ms → still paused; a code restarts the count', () => {
    const { g } = gate('pause')
    g.frame(['A'], 0); g.endSettle()
    feed(g, [[], [], []], 300, 100)                 // 300, 400, 500 — 200 ms
    expect(g.phase).toBe('paused')
    g.frame(['A'], 600)
    feed(g, [[], []], 700, 400)                    // 700, 1100 — only 2 since the code
    expect(g.phase).toBe('paused')
    g.frame([], 1500)
    expect(g.phase).toBe('open')
  })

  test("waiting for the result: empty frames before it don't count", () => {
    const { g, sent } = gate('pause', 'await')
    g.frame(['A'], 0); g.endSettle()
    let t = feed(g, Array.from({ length: 15 }, () => []), 300)
    expect(g.phase).toBe('paused')
    expect(g.awaitingResult).toBe(true)
    g.resultShown()
    g.frame(['B'], t); t += 60                      // the next label right after the card: still paused
    g.endSettle()
    expect(sent).toEqual(['A'])
    t = feed(g, Array.from({ length: 12 }, () => []), t)
    expect(g.phase).toBe('open')
  })

  test('"Scan next" ends the pause; the code just sent stays ignored, a new one goes', () => {
    const { g, sent } = gate('pause', 'await')
    g.frame(['A'], 0); g.endSettle()
    g.scanNext()
    g.frame(['A'], 100); g.endSettle()
    expect(sent).toEqual(['A'])
    g.frame(['B'], 200); g.endSettle()
    expect(sent).toEqual(['A', 'B'])
  })

  test('after Scan next, the sent code still in view beside a new one is two labels', () => {
    const { g, sent } = gate('pause')
    g.frame(['A'], 0); g.endSettle()
    g.scanNext()
    g.frame(['A', 'B'], 100); g.endSettle()
    expect(sent).toEqual(['A'])
    expect(g.notice).toBe('twoLabels')
  })
})

describe('pile (Leftovers scan-back)', () => {
  test('A, B, C one after another with no empty view → three sends', () => {
    const { g, sent } = gate('pile')
    let t = feed(g, [['A'], ['A'], ['A'], ['A'], ['A']], 0)
    t = feed(g, [['B'], ['B'], ['B'], ['B'], ['B']], t)
    feed(g, [['C'], ['C'], ['C']], t)
    expect(sent).toEqual(['A', 'B'])          // C still settling
    g.endSettle()
    expect(sent).toEqual(['A', 'B', 'C'])
  })

  test('two codes in one frame → nothing sent', () => {
    const { g, sent } = gate('pile')
    g.frame(['A', 'B'], 0)
    g.endSettle()
    expect(sent).toEqual([])
    expect(g.notice).toBe('twoLabels')
  })

  test('the same code again is ignored until the view has been clear', () => {
    const { g, sent } = gate('pile')
    g.frame(['A'], 0); g.endSettle()
    let t = feed(g, [['A'], ['A']], 300)
    g.endSettle()
    expect(sent).toEqual(['A'])
    t = feed(g, Array.from({ length: 12 }, () => []), t)
    g.frame(['A'], t); g.endSettle()
    expect(sent).toEqual(['A', 'A'])
  })
})
