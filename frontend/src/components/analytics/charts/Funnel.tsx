import type { ReactNode } from 'react'

export interface FunnelStep {
  key: string
  label: string
  value: number
  display: ReactNode
  color: string
}

/**
 * Order funnel (the mockup's funnel): one bar per step, scaled to the first, with the share of
 * the previous step that continued between them. Bars grow from the start edge (right in RTL).
 */
export function Funnel({ steps, conversion }: { steps: FunnelStep[]; conversion: (share: number) => ReactNode }) {
  const first = steps[0]?.value ?? 0
  return (
    <div className="flex flex-col gap-1.5">
      {steps.map((s, i) => {
        const prev = i > 0 ? steps[i - 1].value : 0
        return (
          <div key={s.key} className="flex flex-col gap-1.5">
            {i > 0 && (
              <div className="ms-[122px] max-sm:ms-[96px] text-[12px] text-muted flex items-center gap-1.5" data-testid={`funnel-conv-${s.key}`}>
                ↓ {prev > 0 ? conversion(s.value / prev) : '—'}
              </div>
            )}
            <div className="grid grid-cols-[110px_minmax(0,1fr)] max-sm:grid-cols-[84px_minmax(0,1fr)] gap-3 items-center">
              <span className="text-[13px] text-neutral-text">{s.label}</span>
              <div className="flex items-center gap-2.5 min-w-0">
                <div className="h-6 rounded-e flex-none" style={{ width: `${first > 0 ? Math.max(1, (s.value / first) * 68) : 0}%`, background: s.color }} />
                <span className="font-semibold text-[13px] whitespace-nowrap text-neutral-text tabular-nums">{s.display}</span>
              </div>
            </div>
          </div>
        )
      })}
    </div>
  )
}
