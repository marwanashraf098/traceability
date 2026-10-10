import { NavLink, useLocation, useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { useState, useRef, useEffect } from 'react'
import { createPortal } from 'react-dom'
import type { LucideIcon } from 'lucide-react'
import {
  LayoutDashboard, ShoppingBag, Warehouse, Inbox, ClipboardList, PackageCheck,
  Truck, Repeat, Undo2, AlertTriangle, Home, ArrowRightLeft, Settings, X,
  BarChart3, Workflow, PanelLeftClose, PanelLeftOpen,
  Gauge, TrendingUp, Tag, Boxes, Route as RouteIcon, Wallet, Users, Receipt,
} from 'lucide-react'
import type { Me } from '../api'
import { Logo } from './Logo'
import { cn } from './ui'
import { analyticsEnabled, isReadyPage, type AnalyticsPageId } from '../analytics/flag'
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

/** Analytics-mode nav (owner only, behind VITE_ANALYTICS_ENABLED). Order matches ANALYTICS_PAGES. */
const ANALYTICS_SECTIONS: { id: string; headerKey?: string; pages: AnalyticsPageId[] }[] = [
  { id: 'a-top',     pages: ['summary'] },
  { id: 'money',     headerKey: 'nav.analyticsSections.money',     pages: ['revenue', 'orders', 'money'] },
  { id: 'products',  headerKey: 'nav.analyticsSections.products',  pages: ['products', 'stock'] },
  { id: 'customers', headerKey: 'nav.analyticsSections.customers', pages: ['delivery', 'customers'] },
]

// ── Mode (Operations | Analytics) ─────────────────────────────────────────────────
// Derived from the route during render — never stored — so a freshly mounted Layout shows the
// right list on its first paint. Only an owner with analytics enabled has the switch; everyone
// else is always in Operations. Each mode remembers its last-visited page per device, validated
// on read (Operations: a nav item path; Analytics: a ready page + the shared period).

type Mode = 'operations' | 'analytics'
const MODES: Mode[] = ['operations', 'analytics']

export const LAST_PAGE_KEY: Record<Mode, string> = {
  operations: 'traced-nav-last-operations',
  analytics:  'traced-nav-last-analytics',
}
const LEGACY_ANALYTICS_NAV_KEY = 'traced-analytics-nav'

const OPERATIONS_PATHS = [...OWNER_SECTIONS.flatMap(s => s.items.map(i => i.to)), '/alerts', '/settings']
const MODE_HOME: Record<Mode, string> = { operations: '/overview', analytics: '/analytics/summary' }

function routeMode(pathname: string): Mode {
  return pathname.startsWith('/analytics/') ? 'analytics' : 'operations'
}

/** The Operations nav item a route belongs to (longest prefix), e.g. /transfers/abc → /transfers. */
function operationsItemPath(pathname: string): string | null {
  let best: string | null = null
  for (const p of OPERATIONS_PATHS) {
    if ((pathname === p || pathname.startsWith(p + '/')) && (!best || p.length > best.length)) best = p
  }
  return best
}

/** /analytics/<ready page> + the shared period state, or null. */
function analyticsEntry(pathname: string, search: string): string | null {
  const m = /^\/analytics\/([a-z]+)$/.exec(pathname)
  return m && isReadyPage(m[1]) ? `/analytics/${m[1]}${sharedSearch(new URLSearchParams(search))}` : null
}

function readLastPage(mode: Mode): string {
  let stored: string | null = null
  try { stored = localStorage.getItem(LAST_PAGE_KEY[mode]) } catch { /* storage blocked */ }
  if (stored) {
    if (mode === 'operations' && OPERATIONS_PATHS.includes(stored)) return stored
    if (mode === 'analytics') {
      const q = stored.indexOf('?')
      const entry = analyticsEntry(q < 0 ? stored : stored.slice(0, q), q < 0 ? '' : stored.slice(q))
      if (entry) return entry
    }
  }
  return MODE_HOME[mode]
}

function rememberPage(pathname: string, search: string) {
  const mode = routeMode(pathname)
  const entry = mode === 'analytics' ? analyticsEntry(pathname, search) : operationsItemPath(pathname)
  if (!entry) return
  try { localStorage.setItem(LAST_PAGE_KEY[mode], entry) } catch { /* storage blocked */ }
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

/** Fixed position beside an element's vertical centre, on the inline-end side (right in LTR, left in RTL). */
function besideStyle(el: HTMLElement, gap = 8): React.CSSProperties {
  const r = el.getBoundingClientRect()
  const top = r.top + r.height / 2
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
    <span className="text-[9px] font-bold tracking-[0.04em] text-[#93b4ff] bg-trace-blue/20 rounded normal-case px-1 py-px">
      {t('nav.beta')}
    </span>
  )
}

// ── Mode switch ─────────────────────────────────────────────────────────────────
// A tablist (roving tabindex, MANUAL activation: arrows / Home / End move focus, Enter or Space
// navigates). Horizontal — arrows mirrored in RTL — when expanded and in the phone drawer;
// vertical (Up / Down) as two stacked icons in the rail. The nav list is its tabpanel.

function ModeSwitch({ mode, collapsed, onSelect, tipProps }: {
  mode: Mode
  collapsed: boolean
  onSelect: (m: Mode) => void
  tipProps: (label: string) => Record<string, unknown>
}) {
  const { t } = useTranslation()
  const refs = useRef<Record<Mode, HTMLButtonElement | null>>({ operations: null, analytics: null })
  const vertical = collapsed && isWide()

  function onKeyDown(e: React.KeyboardEvent, current: Mode) {
    const i = MODES.indexOf(current)
    const fwd = vertical ? 'ArrowDown' : isRtl() ? 'ArrowLeft' : 'ArrowRight'
    const back = vertical ? 'ArrowUp' : isRtl() ? 'ArrowRight' : 'ArrowLeft'
    let next: number | null = null
    // No wrap-around: an arrow at the visual edge stays put, so RTL mirroring is meaningful.
    if (e.key === fwd) next = Math.min(i + 1, MODES.length - 1)
    else if (e.key === back) next = Math.max(i - 1, 0)
    else if (e.key === 'Home') next = 0
    else if (e.key === 'End') next = MODES.length - 1
    if (next === null) return
    e.preventDefault()
    refs.current[MODES[next]]?.focus()
  }

  return (
    <div role="tablist" aria-label={t('nav.modes.label')} aria-orientation={vertical ? 'vertical' : 'horizontal'}
      data-testid="mode-switch"
      className={cn('flex-shrink-0 flex gap-0.5 mx-3 mt-3 mb-1 p-0.5 rounded-lg bg-sidebar-line/60',
        collapsed && 'min-[900px]:flex-col min-[900px]:mx-2.5')}>
      {MODES.map(m => {
        const selected = m === mode
        const Icon = m === 'operations' ? Workflow : BarChart3
        const label = m === 'operations' ? t('nav.modes.operations') : t('nav.analytics')
        return (
          <button key={m} ref={el => { refs.current[m] = el }} type="button" role="tab"
            id={`mode-tab-${m}`} data-testid={`mode-tab-${m}`}
            aria-selected={selected} aria-controls="app-nav-list" tabIndex={selected ? 0 : -1}
            onClick={() => onSelect(m)} onKeyDown={e => onKeyDown(e, m)} {...tipProps(label)}
            className={cn('flex-auto flex items-center justify-center gap-1.5 min-w-0 rounded-md px-1.5 py-1.5 text-[13px] font-medium whitespace-nowrap transition-colors',
              'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-trace-blue',
              selected ? 'bg-brand/[0.22] text-sidebar-active' : 'text-sidebar-text hover:text-sidebar-active',
              collapsed && 'min-[900px]:py-2')}>
            <Icon size={15} strokeWidth={1.75} className={cn('flex-shrink-0', selected && 'text-trace-blue', !collapsed && 'hidden', collapsed && 'max-[899px]:hidden')} />
            <span className={cn('truncate', collapsed && 'min-[900px]:sr-only')}>{label}</span>
            {m === 'analytics' && <span className={cn(collapsed && 'min-[900px]:hidden')}><BetaTag /></span>}
          </button>
        )
      })}
    </div>
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
  const navigate = useNavigate()
  const { pathname, search } = useLocation()
  const isWorker = role === 'worker'
  const [collapsed, toggleCollapsed] = useSidebarCollapsed(!isWorker)
  const { bind, node: tipNode } = useRailTip(collapsed)

  const hasSwitch = role === 'owner' && analyticsEnabled()
  const mode: Mode = hasSwitch ? routeMode(pathname) : 'operations'
  const carry = mode === 'analytics' ? sharedSearch(new URLSearchParams(search)) : ''

  useEffect(() => {
    if (hasSwitch) rememberPage(pathname, search)
  }, [hasSwitch, pathname, search])

  // The Analytics fold of the earlier sidebar is gone — drop its stored state.
  useEffect(() => {
    try { localStorage.removeItem(LEGACY_ANALYTICS_NAV_KEY) } catch { /* storage blocked */ }
  }, [])

  function selectMode(next: Mode) {
    if (next === mode) return
    navigate(readLastPage(next))
  }

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

      {hasSwitch && <ModeSwitch mode={mode} collapsed={collapsed} onSelect={selectMode} tipProps={bind} />}

      {/* Nav — worker gets the reduced task-scoped set; owner/manager the grouped Operations nav;
          an owner on an analytics page the Analytics nav. With the switch, the list is its tabpanel. */}
      <nav id="app-nav-list"
        {...(hasSwitch ? { role: 'tabpanel', 'aria-labelledby': `mode-tab-${mode}` } : {})}
        className="sidebar-scroll flex-1 min-h-0 overflow-y-auto overflow-x-hidden py-2 flex flex-col gap-0.5">
        {isWorker ? WORKER_ITEMS.map(link) : mode === 'analytics' ? (
          <div data-testid="nav-analytics" className="flex flex-col gap-0.5">
            {ANALYTICS_SECTIONS.map(s => ({ ...s, pages: s.pages.filter(isReadyPage) }))
              .filter(s => s.pages.length > 0)
              .map(s => (
                <div key={s.id} className="flex flex-col gap-0.5">
                  {s.headerKey && <SectionHeader label={t(s.headerKey)} collapsed={collapsed} />}
                  {s.pages.map(id => link({
                    to: `/analytics/${id}${carry}`, icon: ANALYTICS_ICONS[id], labelKey: `analytics.pages.${id}.nav`,
                  }))}
                </div>
              ))}
          </div>
        ) : (
          OWNER_SECTIONS.filter(s => s.items.length > 0).map(s => (
            <div key={s.id} className="flex flex-col gap-0.5">
              {s.headerKey && <SectionHeader label={t(s.headerKey)} collapsed={collapsed} />}
              {s.items.map(link)}
            </div>
          ))
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
