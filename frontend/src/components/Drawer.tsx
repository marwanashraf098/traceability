import { useEffect, useRef, type ReactNode } from 'react'
import { X } from 'lucide-react'
import { cn } from './ui'

// Shared slide-in drawer shell — the scrim + end-anchored panel OrderDrawer and VariantDrawer
// each draw for themselves (those two are unchanged; new drawers use this). Escape and a scrim
// click close it; focus moves to the close button on open. RTL slides in from the left.

export function Drawer({
  open,
  onClose,
  title,
  subtitle,
  closeLabel,
  wide = false,
  children,
}: {
  open: boolean
  onClose: () => void
  title: ReactNode
  subtitle?: ReactNode
  closeLabel: string
  /** 760 px instead of 560 px (tables). */
  wide?: boolean
  children: ReactNode
}) {
  const closeRef = useRef<HTMLButtonElement>(null)

  useEffect(() => {
    if (!open) return
    function onKey(e: KeyboardEvent) {
      if (e.key === 'Escape') onClose()
    }
    document.addEventListener('keydown', onKey)
    closeRef.current?.focus()
    return () => document.removeEventListener('keydown', onKey)
  }, [open, onClose])

  return (
    <>
      <div
        className={cn(
          'fixed inset-0 bg-black/45 z-overlay transition-opacity duration-200',
          open ? 'opacity-100 pointer-events-auto' : 'opacity-0 pointer-events-none'
        )}
        onClick={onClose}
      />
      <aside
        role="dialog"
        aria-modal="true"
        aria-hidden={!open}
        className={cn(
          'fixed top-0 end-0 h-screen max-w-full bg-bg border-s border-line z-modal',
          wide ? 'w-[760px]' : 'w-[560px]',
          'flex flex-col shadow-e4 transition-transform duration-200',
          open ? 'translate-x-0' : 'ltr:translate-x-full rtl:-translate-x-full'
        )}
      >
        {open && (
          <>
            <div className="flex items-start gap-3 px-[22px] pt-5 pb-3">
              <div className="flex-1 min-w-0">
                <h2 className="text-h4 text-primary truncate">{title}</h2>
                {subtitle && <p className="text-small text-muted mt-0.5">{subtitle}</p>}
              </div>
              <button
                ref={closeRef}
                type="button"
                onClick={onClose}
                aria-label={closeLabel}
                className="w-8 h-8 rounded-lg flex items-center justify-center text-muted hover:text-primary hover:bg-elevated transition-colors"
              >
                <X size={18} strokeWidth={2} />
              </button>
            </div>
            <div className="flex-1 overflow-y-auto px-[22px] pb-7 flex flex-col gap-4">{children}</div>
          </>
        )}
      </aside>
    </>
  )
}
