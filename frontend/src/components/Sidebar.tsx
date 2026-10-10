import { NavLink, useLocation } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { useState, useRef, useEffect, useLayoutEffect } from 'react'
import { createPortal } from 'react-dom'
import type { LucideIcon } from 'lucide-react'
import {
  LayoutDashboard, ShoppingBag, Warehouse, Inbox, ClipboardList, PackageCheck,
  Truck, Repeat, Undo2, AlertTriangle, Home, ArrowRightLeft, Settings, ChevronDown, X,
  BarChart3, PanelLeftClose, PanelLeftOpen,
  Gauge, TrendingUp, Tag, Boxes, Route as RouteIcon, Wallet, Users, Receipt,
} from 'lucide-react'
import type { Me } from '../api'
import { Logo } from './Logo'
import { cn } from './ui'
import { ANALYTICS_PAGES, analyticsEnabled, type AnalyticsPageId } from '../analytics/flag'
import { sharedSearch } from '../analytics/period'

// ── Nav config ──────────────────────────────────────────────────────────────────
// Owner/manager nav, in display order. A section with no visible items renders no header.
// The worker nav is separate and fixed (Home + the three worker screens).

type NavItem = { to: string; icon: LucideIcon; labelKey: string }
type NavSection = { id: string; headerKey?: string; items: NavItem[] }

const OWNER_SECTIONS: NavSection[] = [
  { id: 'top', items: [
    { to: '/overview',   icon: LayoutDashboard, labelKey: 'nav.overview' },
    { to: '/exceptions', icon: AlertTriangle,   labelKey: 'nav.exceptions' },
  ] },
  { id: 'outbound', headerKey: 'nav.sections.outbound', items: [
    { to: '/orders',  icon: ShoppingBag,  labelKey: 'nav.orders' },
    { to: '/fulfill', icon: PackageCheck, labelKey: 'nav.fulfill' },
    { to: '/pickups', icon: Truck,        labelKey: 'nav.pickups' },
  ] },
  { id: 'stock', headerKey: 'nav.sections.stock', items: [
    { to: '/inventory',  icon: Warehouse,     labelKey: 'nav.catalog' },
    { to: '/receiving',  icon: Inbox,         labelKey: 'nav.receiving' },
    { to: '/transfers',  icon: Repeat,        labelKey: 'nav.transfers' },
    { to: '/stock-take', icon: ClipboardList, labelKey: 'nav.stocktake' },
  ] },
  { id: 'returns', headerKey: 'nav.sections.returns', items: [
    { to: '/exchanges', icon: ArrowRightLeft, labelKey: 'nav.exchangesRefunds' },
    { to: '/returns',   icon: Undo2,          labelKey: 'nav.returns' },
  ] },
]

const WORKER_ITEMS: NavItem[] = [
  { to: '/worker-home', icon: Home,         labelKey: 'nav.home' },
  { to: '/fulfill',     icon: PackageCheck, labelKey: 'nav.fulfill' },
  { to: '/returns',     icon: Undo2,        labelKey: 'nav.returns' },
  { to: '/pickups',     icon: Truck,        labelKey: 'nav.pickups' },
]

const ANALYTICS_ICONS: Record<AnalyticsPageId, LucideIcon> = {
  summary: Gauge, revenue: TrendingUp, products: Tag, stock: Boxes,
  delivery: RouteIcon, money: Wallet, customers: Users, orders: Receipt,
}

// ── Collapsed state ───────────────────────────────────────────────────────────────
// Per device (localStorage). No stored value → expanded at ≥ 1280 px, collapsed below; when
// matchMedia is unavailable (jsdom) → expanded. Ctrl/⌘+B toggles. `enabled` false (workers)
// → always expanded, no shortcut. Below 900 px the sidebar is the phone drawer, which ignores
// this state (every rail class is min-[900px]: scoped).

export const SIDEBAR_KEY = 'traced-sidebar'

function initialCollapsed(): boolean {
  try {
    const stored = localStorage.getItem(SIDEBAR_KEY)
    if (stored === 'collapsed') return true
    if (stored === 'expanded') return false
  } catch { /* storage blocked */ }
  if (typeof window.matchMedia !== 'function') return false
  return !window.matchMedia('(min-width: 1280px)').matches
}

export function useSidebarCollapsed(enabled: boolean): [boolean, () => void] {
  const [collapsed, setCollapsed] = useState<boolean>(() => enabled && initialCollapsed())
  const toggle = useRef(() => {})
  toggle.current = () => {
    if (!enabled) return
    setCollapsed(c => {
      const next = !c
      try { localStorage.setItem(SIDEBAR_KEY, next ? 'collapsed' : 'expanded') } catch { /* storage blocked */ }
      return next
    })
  }
  useEffect(() => {
    if (!enabled) return
    function onKeyDown(e: KeyboardEvent) {
      if ((e.metaKey || e.ctrlKey) && !e.shiftKey && !e.altKey && e.key.toLowerCase() === 'b') {
        e.preventDefault()
        toggle.current()
      }
    }
    document.addEventListener('keydown', onKeyDown)
    return () => document.removeEventListener('keydown', onKeyDown)
  }, [enabled])
  return [enabled && collapsed, () => toggle.current()]
}

/** The rail only exists at ≥ 900 px; below that the drawer shows full labels. */
function isWide(): boolean {
  return typeof window.matchMedia !== 'function' || window.matchMedia('(min-width: 900px)').matches
}

function isRtl(): boolean {
  return document.documentElement.dir === 'rtl'
}

/** Fixed position beside an element, on the inline-end side (right in LTR, left in RTL).
 *  `top` is the element's vertical centre (tooltips) or, with alignTop, its top edge (flyouts). */
function besideStyle(el: HTMLElement, alignTop = false, gap = 8): React.CSSProperties {
  const r = el.getBoundingClientRect()
  const top = alignTop ? Math.max(8, r.top - 8) : r.top + r.height / 2
  return isRtl() ? { top, right: window.innerWidth - r.left + gap } : { top, left: r.right + gap }
}

// ── Rail tooltip ────────────────────────────────────────────────────────────────
// Fixed-position so the scrolling nav can't clip it; shown on hover AND keyboard focus.

type TipState = { label: string; style: React.CSSProperties } | null

function useRailTip(collapsed: boolean) {
  const [tip, setTip] = useState<TipState>(null)
  useEffect(() => { if (!collapsed) setTip(null) }, [collapsed])
  function bind(label: string) {
    if (!collapsed) return {}
    const show = (e: React.SyntheticEvent<HTMLElement>) => {
      if (isWide()) setTip({ label, style: besideStyle(e.currentTarget) })
    }
    const hide = () => setTip(null)
    return { onMouseEnter: show, onFocus: show, onMouseLeave: hide, onBlur: hide }
  }
  // Portalled to <body>: outside the rail's .sidebar-rail styles and the nav's scroll clipping.
  const node = tip && createPortal(
    <div role="tooltip" data-testid="rail-tooltip" style={tip.style}
      className="fixed z-dropdown -translate-y-1/2 pointer-events-none whitespace-nowrap rounded-md bg-elevated border border-line shadow-e2 px-2.5 py-1.5 text-small text-primary">
      {tip.label}
    </div>,
    document.body,
  )
  return { bind, node }
}

// ── Nav link ────────────────────────────────────────────────────────────────────

function SideNavLink({ to, icon: Icon, label, collapsed, tipProps }: {
  to: string; icon: LucideIcon; label: string; collapsed: boolean
  tipProps?: Record<string, unknown>
}) {
  return (
    <NavLink to={to} {...tipProps} className={({ isActive }) => cn('nav-item', isActive && 'nav-item-active')}>
      {({ isActive }) => (
        <>
          <Icon size={18} strokeWidth={1.75} className={cn('flex-shrink-0', isActive && 'text-trace-blue')} />
          <span className={cn('truncate', collapsed && 'min-[900px]:sr-only')}>{label}</span>
        </>
      )}
    </NavLink>
  )
}

function SectionHeader({ label, collapsed, children }: { label: string; collapsed: boolean; children?: React.ReactNode }) {
  return (
    <>
      {collapsed && <div aria-hidden="true" className="hidden min-[900px]:block h-px bg-sidebar-line mx-4 my-2.5" />}
      <div className={cn('flex items-center px-[18px] pt-3.5 pb-1 text-[11px] font-semibold tracking-wider text-sidebar-text uppercase',
        collapsed && 'min-[900px]:hidden')}>
        {label}{children}
      </div>
    </>
  )
}

function BetaTag() {
  const { t } = useTranslation()
  return (
    <span className="ms-1.5 text-[10px] font-bold tracking-[0.04em] text-[#93b4ff] bg-trace-blue/20 px-1.5 py-0.5 rounded normal-case">
      {t('nav.beta')}
    </span>
  )
}

// ── Analytics section (owner only, behind VITE_ANALYTICS_ENABLED) ───────────────────
// Expanded: a foldable section (state per device; unfolds itself on an analytics page).
// Rail: one icon that opens a flyout with the pages. Links carry the shared period state.

const ANALYTICS_NAV_KEY = 'traced-analytics-nav'

function AnalyticsSection({ collapsed }: { collapsed: boolean }) {
  const { t } = useTranslation()
  const { pathname, search } = useLocation()
  const inAnalytics = pathname.startsWith('/analytics')
  const [open, setOpen] = useState<boolean>(() => {
    try { return localStorage.getItem(ANALYTICS_NAV_KEY) !== 'closed' } catch { return true }
  })
  const expanded = open || inAnalytics
  function toggle() {
    const next = !expanded
    setOpen(next)
    try { localStorage.setItem(ANALYTICS_NAV_KEY, next ? 'open' : 'closed') } catch { /* storage blocked */ }
  }
  const carry = inAnalytics ? sharedSearch(new URLSearchParams(search)) : ''
  const pages = ANALYTICS_PAGES.filter(p => p.ready)

  // Rail flyout — closes on Escape, an outside click and any navigation.
  const [flyout, setFlyout] = useState<React.CSSProperties | null>(null)
  const railBtn = useRef<HTMLButtonElement>(null)
  const flyoutRef = useRef<HTMLDivElement>(null)
  useEffect(() => { setFlyout(null) }, [pathname, collapsed])
  useEffect(() => {
    if (!flyout) return
    function onKey(e: KeyboardEvent) {
      if (e.key === 'Escape') { setFlyout(null); railBtn.current?.focus() }
    }
    function onDown(e: MouseEvent) {
      const n = e.target as Node
      if (!flyoutRef.current?.contains(n) && !railBtn.current?.contains(n)) setFlyout(null)
    }
    document.addEventListener('keydown', onKey)
    document.addEventListener('mousedown', onDown)
    return () => { document.removeEventListener('keydown', onKey); document.removeEventListener('mousedown', onDown) }
  }, [flyout])
  useLayoutEffect(() => {
    const el = flyoutRef.current
    if (!flyout || !el) return
    // Keep it on screen when the rail button sits low in a short window.
    const overflow = el.getBoundingClientRect().bottom - (window.innerHeight - 8)
    if (overflow > 0) el.style.top = `${Math.max(8, (flyout.top as number) - overflow)}px`
    el.querySelector<HTMLElement>('a')?.focus()
  }, [flyout])

  return (
    <>
      {collapsed && (
        <div className="hidden min-[900px]:block">
          <div aria-hidden="true" className="h-px bg-sidebar-line mx-4 my-2.5" />
          <button ref={railBtn} type="button" data-testid="nav-analytics-rail"
            aria-label={t('nav.analytics')} aria-haspopup="true" aria-expanded={!!flyout}
            aria-controls="nav-analytics-flyout"
            onClick={() => setFlyout(f => (f ? null : besideStyle(railBtn.current!, true)))}
            className={cn('nav-item w-full', inAnalytics && 'nav-item-active')}>
            <BarChart3 size={18} strokeWidth={1.75} className={cn('flex-shrink-0', inAnalytics && 'text-trace-blue')} />
          </button>
        </div>
      )}
      {flyout && createPortal(
        <div ref={flyoutRef} id="nav-analytics-flyout" data-testid="nav-analytics-flyout" role="menu"
          style={flyout}
          className="fixed z-dropdown w-56 max-h-[calc(100vh-16px)] overflow-y-autorounded-lg bg-sidebar border border-sidebar-line shadow-e4 py-1.5">
          <div className="flex items-center px-[14px] pt-1 pb-1.5 text-[11px] font-semibold tracking-wider text-sidebar-text uppercase">
            {t('nav.analytics')}<BetaTag />
          </div>
          {pages.map(p => {
            const Icon = ANALYTICS_ICONS[p.id]
            return (
              <NavLink key={p.id} role="menuitem" to={`/analytics/${p.id}${carry}`}
                className={({ isActive }) => cn('nav-item', isActive && 'nav-item-active')}>
                {({ isActive }) => (
                  <>
                    <Icon size={16} strokeWidth={1.75} className={cn('flex-shrink-0', isActive && 'text-trace-blue')} />
                    <span className="truncate">{t(`analytics.pages.${p.id}.nav`)}</span>
                  </>
                )}
              </NavLink>
            )
          })}
        </div>,
        document.body,
      )}
      <div data-testid="nav-analytics" className={cn(collapsed && 'min-[900px]:hidden')}>
        <button type="button" onClick={toggle} aria-expanded={expanded} aria-controls="nav-analytics-sub"
          className="w-full flex items-center px-[18px] pt-3.5 pb-1 text-[11px] font-semibold tracking-wider text-sidebar-text uppercase hover:text-sidebar-active transition-colors">
          <span>{t('nav.analytics')}</span>
          <BetaTag />
          <ChevronDown size={13} strokeWidth={2} className={cn('ms-auto transition-transform', !expanded && 'ltr:-rotate-90 rtl:rotate-90')} />
        </button>
        {expanded && (
          <div id="nav-analytics-sub" className="flex flex-col gap-0.5">
            {pages.map(p => {
              const Icon = ANALYTICS_ICONS[p.id]
              return (
                <NavLink key={p.id} to={`/analytics/${p.id}${carry}`}
                  className={({ isActive }) => cn('nav-item', isActive && 'nav-item-active')}>
                  {({ isActive }) => (
                    <>
                      <Icon size={18} strokeWidth={1.75} className={cn('flex-shrink-0', isActive && 'text-trace-blue')} />
                      <span className="truncate">{t(`analytics.pages.${p.id}.nav`)}</span>
                    </>
                  )}
                </NavLink>
              )
            })}
          </div>
        )}
      </div>
    </>
  )
}

// ── Identity helpers (also used by the top bar) ─────────────────────────────────────

function roleInitials(role: string | null): string {
  return role ? role.slice(0, 2).toUpperCase() : '?'
}

function nameInitials(name: string): string {
  return name.trim().split(/\s+/).slice(0, 2).map(w => w[0]).join('').toUpperCase()
}

export function avatarInitials(me: Me | null, role: string | null): string {
  return me?.name ? nameInitials(me.name) : roleInitials(role)
}

// ── Sidebar ─────────────────────────────────────────────────────────────────────
// Fixed dark rail — pinned to the sidebar-* tokens, never flips with the content-area theme.
// Logo + collapse toggle pinned top; Settings + identity pinned bottom; the nav between scrolls.
// Below 900 px it is the slide-in phone menu (Layout's top-bar button opens it).

export default function Sidebar({ role, me, navOpen, onCloseNav }: {
  role: string | null
  me: Me | null
  navOpen: boolean
  onCloseNav: () => void
}) {
  const { t } = useTranslation()
  const isWorker = role === 'worker'
  const [collapsed, toggleCollapsed] = useSidebarCollapsed(!isWorker)
  const { bind, node: tipNode } = useRailTip(collapsed)

  // Width animates only on a user toggle — never on mount (Layout remounts on every route).
  const [animate, setAnimate] = useState(false)
  useEffect(() => {
    const id = requestAnimationFrame(() => setAnimate(true))
    return () => cancelAnimationFrame(id)
  }, [])

  const toggleLabel = collapsed ? t('nav.expand') : t('nav.collapse')
  const ToggleIcon = collapsed ? PanelLeftOpen : PanelLeftClose

  function link(item: NavItem) {
    const label = t(item.labelKey)
    return <SideNavLink key={item.to} to={item.to} icon={item.icon} label={label} collapsed={collapsed} tipProps={bind(label)} />
  }

  return (
    <aside
      id="app-nav"
      data-testid="app-nav"
      data-open={navOpen}
      data-collapsed={collapsed}
      className={cn(
        'w-56 flex-shrink-0 bg-sidebar border-e border-sidebar-line flex flex-col',
        collapsed && 'min-[900px]:w-16 sidebar-rail',
        animate && 'min-[900px]:transition-[width] min-[900px]:duration-200 min-[900px]:ease-out motion-reduce:transition-none',
        'max-[899px]:fixed max-[899px]:inset-y-0 max-[899px]:start-0 max-[899px]:z-modal max-[899px]:shadow-e4',
        'max-[899px]:transition-transform max-[899px]:duration-200',
        navOpen ? 'max-[899px]:translate-x-0' : 'max-[899px]:ltr:-translate-x-full max-[899px]:rtl:translate-x-full',
      )}>

      {/* Wordmark + collapse toggle */}
      <div className={cn('flex-shrink-0 flex items-center justify-between gap-2 px-[18px] h-[61px] border-b border-sidebar-line',
        collapsed && 'min-[900px]:justify-center min-[900px]:px-0')}>
        <span className={cn('min-w-0', collapsed && 'min-[900px]:hidden')}>
          <Logo variant="mark" size={18} className="text-sidebar-active" />
        </span>
        {!isWorker && (
          <button type="button" onClick={toggleCollapsed} data-testid="sidebar-toggle"
            aria-label={toggleLabel} aria-expanded={!collapsed} aria-controls="app-nav-list"
            title={`${toggleLabel} (${navigator.platform?.startsWith('Mac') ? '⌘' : 'Ctrl+'}B)`}
            className="max-[899px]:hidden w-8 h-8 -me-1.5 rounded-md flex items-center justify-center text-sidebar-text hover:text-sidebar-active hover:bg-sidebar-line/60 transition-colors">
            <ToggleIcon size={18} strokeWidth={1.75} className="rtl:-scale-x-100" />
          </button>
        )}
        <button type="button" onClick={onCloseNav} aria-label={t('nav.closeMenu')}
          className="min-[900px]:hidden text-sidebar-text hover:text-sidebar-active">
          <X size={18} strokeWidth={2} />
        </button>
      </div>

      {/* Nav — worker gets the reduced task-scoped set; owner/manager the grouped nav. */}
      <nav id="app-nav-list" className="sidebar-scroll flex-1 min-h-0 overflow-y-auto overflow-x-hidden py-2 flex flex-col gap-0.5">
        {isWorker ? WORKER_ITEMS.map(link) : (
          <>
            {OWNER_SECTIONS.filter(s => s.items.length > 0).map(s => (
              <div key={s.id} className="flex flex-col gap-0.5">
                {s.headerKey && <SectionHeader label={t(s.headerKey)} collapsed={collapsed} />}
                {s.items.map(link)}
              </div>
            ))}
            {role === 'owner' && analyticsEnabled() && <AnalyticsSection collapsed={collapsed} />}
          </>
        )}
      </nav>

      {/* Bottom: Settings (owner/manager) + identity — real name+role once /me resolves;
          role-only placeholder until then/on failure. */}
      <div className="flex-shrink-0 border-t border-sidebar-line">
        {!isWorker && (
          <div className="py-1.5">
            {link({ to: '/settings', icon: Settings, labelKey: 'nav.settings' })}
          </div>
        )}
        <div className={cn('px-[18px] py-[14px] flex items-center gap-2.5', !isWorker && 'border-t border-sidebar-line',
          collapsed && 'min-[900px]:justify-center min-[900px]:px-0')}
          {...bind(me?.name ?? (role ? t(`users.roles.${role}`) : ''))} tabIndex={collapsed ? 0 : undefined}>
          <span className="w-[30px] h-[30px] rounded-full bg-trace-blue text-white text-[12px] font-bold flex items-center justify-center flex-shrink-0">
            {avatarInitials(me, role)}
          </span>
          <span className={cn('min-w-0', collapsed && 'min-[900px]:sr-only')}>
            {me ? (
              <span className="min-w-0 flex flex-col">
                <span className="text-small text-sidebar-active leading-tight truncate">{me.name}</span>
                <span className="text-caption text-sidebar-text leading-tight">{t(`users.roles.${me.role}`)}</span>
              </span>
            ) : role && (
              <span className="text-small text-sidebar-text capitalize">{t(`users.roles.${role}`)}</span>
            )}
          </span>
        </div>
      </div>
      {tipNode}
    </aside>
  )
}
