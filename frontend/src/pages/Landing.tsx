// Public marketing landing page for Traced.
// Copy is verbatim from docs/landing-page-build-spec.md §5.
// All CTAs read from SIGNUP_URL — do NOT scatter hardcoded paths.

import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { Logo } from '../components/Logo'

/* ─── CTA destination ─────────────────────────────────────────────────────────
   All subscribe / start / open-app CTAs use this single constant.
   TODO(payment): replace direct redirect with checkout → payment
     (Instapay / bank transfer, FR-1.5) → provision → redirect
──────────────────────────────────────────────────────────────────────────────*/
const SIGNUP_URL: string =
  (import.meta.env.VITE_APP_SIGNUP_URL as string | undefined) ?? '/signup'

/* ─── Brand gradient for the "untraced" hero word ────────────────────────────*/
const WORD_GRAD: React.CSSProperties = {
  background: 'linear-gradient(135deg, #6366FF 0%, #A78BFA 100%)',
  WebkitBackgroundClip: 'text',
  WebkitTextFillColor: 'transparent',
  backgroundClip: 'text',
}

/* ─── Hooks ───────────────────────────────────────────────────────────────────*/

function usePrefersReducedMotion(): boolean {
  const [rm, setRm] = useState<boolean>(() =>
    typeof window !== 'undefined'
      ? window.matchMedia('(prefers-reduced-motion: reduce)').matches
      : false
  )
  useEffect(() => {
    const mq = window.matchMedia('(prefers-reduced-motion: reduce)')
    const h = (e: MediaQueryListEvent) => setRm(e.matches)
    mq.addEventListener('change', h)
    return () => mq.removeEventListener('change', h)
  }, [])
  return rm
}

function useReveal(threshold = 0.12) {
  const ref = useRef<HTMLDivElement>(null)
  const [vis, setVis] = useState(false)
  useEffect(() => {
    const el = ref.current
    if (!el) return
    const io = new IntersectionObserver(
      ([e]) => { if (e.isIntersecting) { setVis(true); io.disconnect() } },
      { threshold }
    )
    io.observe(el)
    return () => io.disconnect()
  }, [threshold])
  return { ref, vis }
}

function useCounter(target: number, dp: number, run: boolean, rm: boolean): number {
  const [v, setV] = useState<number>(0)
  useEffect(() => {
    if (!run) return
    if (rm) { setV(target); return }
    const DUR = 1800
    const t0 = performance.now()
    let raf: number
    const ease = (x: number) => 1 - Math.pow(1 - x, 3)
    const tick = (now: number) => {
      const p = Math.min((now - t0) / DUR, 1)
      setV(parseFloat((ease(p) * target).toFixed(dp)))
      if (p < 1) raf = requestAnimationFrame(tick)
    }
    raf = requestAnimationFrame(tick)
    return () => cancelAnimationFrame(raf)
  }, [target, dp, run, rm])
  return v
}

/* ─── Particle canvas ─────────────────────────────────────────────────────────*/
function ParticleCanvas() {
  const rm = usePrefersReducedMotion()
  const cvRef = useRef<HTMLCanvasElement>(null)

  useEffect(() => {
    if (rm) return
    const cv = cvRef.current
    if (!cv?.parentElement) return
    const ctx = cv.getContext('2d')
    if (!ctx) return

    const DPR = Math.min(devicePixelRatio, 2)
    const resize = () => {
      const { clientWidth: w, clientHeight: h } = cv.parentElement!
      cv.width = w * DPR; cv.height = h * DPR
      cv.style.width = `${w}px`; cv.style.height = `${h}px`
      ctx.setTransform(DPR, 0, 0, DPR, 0, 0)
    }
    resize()

    const W = () => cv.width / DPR
    const H = () => cv.height / DPR
    type Pt = { x: number; y: number; vx: number; vy: number }
    const pts: Pt[] = Array.from({ length: 60 }, () => ({
      x: Math.random() * W(), y: Math.random() * H(),
      vx: (Math.random() - .5) * .22, vy: (Math.random() - .5) * .22,
    }))

    let raf: number, alive = true
    const frame = () => {
      if (!alive) return
      const w = W(), h = H()
      ctx.clearRect(0, 0, w, h)
      for (const p of pts) {
        p.x = (p.x + p.vx + w) % w
        p.y = (p.y + p.vy + h) % h
      }
      for (let i = 0; i < pts.length; i++) {
        for (let j = i + 1; j < pts.length; j++) {
          const dx = pts[i].x - pts[j].x, dy = pts[i].y - pts[j].y
          const d = Math.hypot(dx, dy)
          if (d < 110) {
            ctx.strokeStyle = `rgba(99,102,255,${.12 * (1 - d / 110)})`
            ctx.lineWidth = .5
            ctx.beginPath()
            ctx.moveTo(pts[i].x, pts[i].y)
            ctx.lineTo(pts[j].x, pts[j].y)
            ctx.stroke()
          }
        }
      }
      for (const p of pts) {
        ctx.beginPath(); ctx.arc(p.x, p.y, 1.5, 0, Math.PI * 2)
        ctx.fillStyle = 'rgba(99,102,255,.38)'; ctx.fill()
      }
      raf = requestAnimationFrame(frame)
    }

    const onVis = () => {
      document.hidden ? cancelAnimationFrame(raf) : (raf = requestAnimationFrame(frame))
    }
    document.addEventListener('visibilitychange', onVis)
    const ro = new ResizeObserver(resize)
    ro.observe(cv.parentElement!)
    raf = requestAnimationFrame(frame)

    return () => {
      alive = false
      cancelAnimationFrame(raf)
      document.removeEventListener('visibilitychange', onVis)
      ro.disconnect()
    }
  }, [rm])

  if (rm) return null
  return (
    <canvas ref={cvRef} aria-hidden
      className="absolute inset-0 pointer-events-none"
      style={{ opacity: .5 }} />
  )
}

/* ─── Scroll-reveal wrapper ───────────────────────────────────────────────────*/
function Reveal({ children, className = '', delay = 0 }: {
  children: React.ReactNode; className?: string; delay?: number
}) {
  const rm = usePrefersReducedMotion()
  const { ref, vis } = useReveal()
  return (
    <div ref={ref} className={className}
      style={rm ? {} : {
        opacity: vis ? 1 : 0,
        transform: vis ? 'none' : 'translateY(28px)',
        transition: `opacity .65s ease-out ${delay}ms, transform .65s ease-out ${delay}ms`,
      }}>
      {children}
    </div>
  )
}

/* ─── Nav ─────────────────────────────────────────────────────────────────────*/
function Nav() {
  const rm = usePrefersReducedMotion()
  const [scrolled, setScrolled] = useState(false)
  const [open, setOpen] = useState(false)

  useEffect(() => {
    const h = () => setScrolled(window.scrollY > 10)
    window.addEventListener('scroll', h, { passive: true })
    return () => window.removeEventListener('scroll', h)
  }, [])

  const navLinks = [
    { href: '#how-it-works', label: 'How it works' },
    { href: '#why-traced',   label: 'Why Traced'   },
    { href: '#pricing',      label: 'Pricing'       },
  ]

  return (
    <nav role="navigation" aria-label="Site navigation"
      className={`fixed top-0 inset-x-0 z-50 transition-all duration-300 ${
        scrolled ? 'bg-base/90 backdrop-blur-md border-b border-line/50' : ''
      }`}>
      <div className="max-w-6xl mx-auto px-4 sm:px-6 lg:px-8 flex items-center justify-between h-16">
        <a href="/" aria-label="Traced — home">
          <Logo variant="wordmark" size={26} className={rm ? '' : 'lp-logo'} />
        </a>

        <div className="hidden md:flex items-center gap-8">
          {navLinks.map(l => (
            <a key={l.href} href={l.href}
              className="text-sm text-muted hover:text-primary transition-colors">
              {l.label}
            </a>
          ))}
        </div>

        <div className="hidden md:flex items-center gap-3">
          <Link to="/login"
            className="text-sm text-muted hover:text-primary transition-colors px-3 py-1.5">
            Log in
          </Link>
          {/* TODO(payment): replace direct redirect with checkout → payment (FR-1.5) */}
          <a href={SIGNUP_URL}
            className="btn-brand text-sm px-4 py-2 rounded-lg hover:shadow-brand transition-shadow">
            Open app
          </a>
        </div>

        <button
          className="md:hidden p-2 text-muted hover:text-primary transition-colors"
          aria-label={open ? 'Close menu' : 'Open menu'}
          aria-expanded={open}
          onClick={() => setOpen(o => !o)}>
          <svg width="20" height="20" viewBox="0 0 20 20" fill="none"
            stroke="currentColor" strokeWidth="2" strokeLinecap="round">
            {open
              ? <><line x1="4" y1="4" x2="16" y2="16"/><line x1="16" y1="4" x2="4" y2="16"/></>
              : <><line x1="3" y1="6" x2="17" y2="6"/><line x1="3" y1="10" x2="17" y2="10"/><line x1="3" y1="14" x2="17" y2="14"/></>
            }
          </svg>
        </button>
      </div>

      {open && (
        <div className="md:hidden bg-base/95 backdrop-blur-md border-b border-line px-4 py-5 flex flex-col gap-4">
          {navLinks.map(l => (
            <a key={l.href} href={l.href} onClick={() => setOpen(false)}
              className="text-sm text-muted hover:text-primary transition-colors py-1">
              {l.label}
            </a>
          ))}
          <div className="flex gap-3 pt-3 border-t border-line">
            <Link to="/login" className="text-sm text-muted hover:text-primary py-2 flex-shrink-0">
              Log in
            </Link>
            {/* TODO(payment): replace direct redirect with checkout → payment (FR-1.5) */}
            <a href={SIGNUP_URL}
              className="btn-brand text-sm px-4 py-2 rounded-lg flex-1 text-center inline-flex items-center justify-center">
              Open app
            </a>
          </div>
        </div>
      )}
    </nav>
  )
}

/* ─── Dashboard mock ──────────────────────────────────────────────────────────*/
const CHART_PTS = '0,48 28,35 57,38 85,20 114,24 142,12 171,16 200,14'

function StatCard({ label, value, delta, pos }: {
  label: string; value: string; delta: string; pos: boolean
}) {
  return (
    <div className="bg-elevated rounded-lg p-3 hover:bg-elevated/80 transition-colors">
      <div className="text-xs text-muted mb-1 truncate">{label}</div>
      <div className="text-base font-semibold text-primary font-mono tabular-nums leading-none mb-1">
        {value}
      </div>
      <div className={`text-xs font-medium ${pos ? 'text-success' : 'text-danger'}`}>{delta}</div>
    </div>
  )
}

function DashboardMock() {
  const rm = usePrefersReducedMotion()
  const { ref, vis } = useReveal(0.05)
  const chartLineRef = useRef<SVGPolylineElement>(null)
  const [dashLen, setDashLen] = useState(600)

  useEffect(() => {
    if (chartLineRef.current) setDashLen(chartLineRef.current.getTotalLength())
  }, [])

  const orders     = useCounter(24591, 0, vis, rm)
  const ontime     = useCounter(96.8,  1, vis, rm)
  const returnRate = useCounter(2.1,   1, vis, rm)
  const invVal     = useCounter(7.35,  2, vis, rm)

  return (
    <div ref={ref} className="bg-panel border border-line/50 rounded-2xl p-4 shadow-elevated flex-1">
      <div className="flex items-center justify-between mb-3">
        <span className="text-sm font-medium text-primary">Overview</span>
        <span className="flex items-center gap-1.5 text-xs text-success">
          <span className="w-1.5 h-1.5 rounded-full bg-success inline-block"
            style={{ animation: rm ? 'none' : 'lpLivePulse 2s ease-in-out infinite' }} />
          Live
        </span>
      </div>

      <div className="grid grid-cols-2 gap-2 mb-3">
        <StatCard label="Total Orders"     value={orders.toLocaleString('en')} delta="+12.6%" pos />
        <StatCard label="On-time Delivery" value={`${ontime}%`}                delta="+2.4%"  pos />
        <StatCard label="Return Rate"      value={`${returnRate}%`}            delta="-1.3%"  pos={false} />
        <StatCard label="Inventory Value"  value={`EGP ${invVal}M`}            delta="+9.6%"  pos />
      </div>

      <div className="bg-elevated/40 rounded-lg p-3">
        <div className="text-xs text-muted mb-2">Orders Over Time</div>
        <svg viewBox="0 0 200 56" className="w-full h-14" preserveAspectRatio="none">
          <defs>
            <linearGradient id="lpChartGrad" x1="0" y1="0" x2="0" y2="1">
              <stop offset="0%"   stopColor="#6366FF" stopOpacity=".25" />
              <stop offset="100%" stopColor="#6366FF" stopOpacity="0"   />
            </linearGradient>
          </defs>
          <polygon
            points={`${CHART_PTS} 200,56 0,56`}
            fill="url(#lpChartGrad)"
            style={{ opacity: vis ? 1 : 0, transition: rm ? 'none' : 'opacity .4s ease-out .4s' }}
          />
          <polyline
            ref={chartLineRef}
            points={CHART_PTS}
            fill="none" stroke="#6366FF"
            strokeWidth="1.75" strokeLinecap="round" strokeLinejoin="round"
            style={rm ? {} : {
              strokeDasharray: dashLen,
              strokeDashoffset: vis ? 0 : dashLen,
              transition: vis ? 'stroke-dashoffset 1.5s ease-out .1s' : 'none',
            }}
          />
        </svg>
      </div>
    </div>
  )
}

/* ─── Phone timeline mock ─────────────────────────────────────────────────────*/
const TIMELINE_STEPS = [
  { label: 'Picked Up',           done: true,  active: false },
  { label: 'Arrived at Facility', done: true,  active: false },
  { label: 'In Transit',          done: false, active: true  },
  { label: 'Out for Delivery',    done: false, active: false },
  { label: 'Delivered',           done: false, active: false },
]

function PhoneMock() {
  const rm = usePrefersReducedMotion()
  return (
    <div className="bg-panel border border-line/50 rounded-2xl p-4 shadow-elevated w-44 flex-shrink-0">
      <div className="text-xs text-muted font-mono mb-3 tracking-wider">#TRC-98271</div>
      <div className="flex flex-col">
        {TIMELINE_STEPS.map((step, i) => (
          <div key={i} className="flex items-start gap-2">
            <div className="flex flex-col items-center flex-shrink-0 pt-0.5">
              <div
                className={`w-2.5 h-2.5 rounded-full ${
                  step.done   ? 'bg-success' :
                  step.active ? 'bg-brand'   : 'bg-line'
                }`}
                style={step.active && !rm ? {
                  animation: 'lpTimelinePulse 2s ease-in-out infinite',
                } : undefined}
              />
              {i < TIMELINE_STEPS.length - 1 && (
                <div className="w-px min-h-[20px] bg-line/50 mt-0.5 mb-0.5" />
              )}
            </div>
            <div className="pb-3 flex items-center gap-1 flex-1 min-w-0">
              <span className={`text-xs leading-tight truncate ${
                step.done   ? 'text-muted/60 line-through' :
                step.active ? 'text-primary font-medium'  : 'text-muted'
              }`}>{step.label}</span>
              {step.done && (
                <span className="text-success text-xs ms-auto flex-shrink-0">✓</span>
              )}
            </div>
          </div>
        ))}
      </div>
    </div>
  )
}

/* ─── Hero ────────────────────────────────────────────────────────────────────*/
function HeroSection() {
  return (
    <section
      className="relative min-h-screen flex items-center pt-24 pb-16 overflow-hidden"
      aria-labelledby="hero-heading">
      <ParticleCanvas />

      <div aria-hidden className="pointer-events-none absolute inset-0 overflow-hidden">
        <div className="absolute top-1/4 left-1/4 w-[500px] h-[500px] rounded-full bg-brand/[.07] blur-[140px]" />
        <div className="absolute top-1/2 right-1/4 w-96 h-96 rounded-full blur-[100px]"
          style={{ background: 'rgba(139,92,246,.05)' }} />
      </div>

      <div className="relative z-10 max-w-6xl mx-auto px-4 sm:px-6 lg:px-8 w-full">
        <div className="flex flex-col lg:flex-row items-center gap-10 lg:gap-16">
          {/* Copy + CTAs */}
          <div className="flex-1 text-center lg:text-start">
            <div className="inline-flex items-center gap-2 text-xs font-medium text-muted border border-line/50 rounded-full px-3 py-1.5 mb-6 bg-panel/40 backdrop-blur-sm">
              <span className="w-1.5 h-1.5 rounded-full bg-brand flex-shrink-0" />
              Intelligence layer for physical products
            </div>

            <h1 id="hero-heading"
              className="text-4xl sm:text-5xl lg:text-[3.5rem] font-light leading-[1.06] tracking-tight text-primary mb-5">
              nothing moves<br />
              <span style={WORD_GRAD}>untraced.</span>
            </h1>

            <p className="text-base sm:text-lg text-muted leading-relaxed mb-8 max-w-xl mx-auto lg:mx-0">
              Complete visibility over every physical thing you own — wherever it is, however it moves.
            </p>

            {/* TODO(payment): replace direct redirect with checkout → payment (Instapay/bank transfer per FR-1.5) → provision → redirect */}
            <div className="flex flex-col sm:flex-row items-center lg:items-start gap-3">
              <a href={SIGNUP_URL}
                className="btn-brand text-base px-6 py-3 rounded-xl w-full sm:w-auto hover:shadow-brand transition-shadow focus-visible:ring-2 focus-visible:ring-brand focus-visible:outline-none">
                Start now
              </a>
              <a href="#pricing"
                className="btn-outline text-base px-6 py-3 rounded-xl w-full sm:w-auto focus-visible:ring-2 focus-visible:ring-brand focus-visible:outline-none">
                See pricing
              </a>
            </div>
          </div>

          {/* Dashboard + phone mocks */}
          <div className="flex-1 w-full max-w-lg lg:max-w-none">
            <div className="flex items-start gap-4">
              <DashboardMock />
              <div className="hidden lg:block mt-8">
                <PhoneMock />
              </div>
            </div>
          </div>
        </div>
      </div>
    </section>
  )
}

/* ─── How it works ────────────────────────────────────────────────────────────*/
const HOW_STEPS = [
  {
    title: 'Track',
    body:  'Know exactly where every item is and where it has been.',
    icon: (
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5"
        strokeLinecap="round" strokeLinejoin="round" width="22" height="22">
        <circle cx="12" cy="12" r="9" /><circle cx="12" cy="12" r="3" />
        <line x1="12" y1="3" x2="12" y2="6" /><line x1="12" y1="18" x2="12" y2="21" />
        <line x1="3" y1="12" x2="6" y2="12" /><line x1="18" y1="12" x2="21" y2="12" />
      </svg>
    ),
  },
  {
    title: 'Monitor',
    body:  'See movements, transfers, deliveries, returns, and exceptions as they happen.',
    icon: (
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5"
        strokeLinecap="round" strokeLinejoin="round" width="22" height="22">
        <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z" />
        <circle cx="12" cy="12" r="3" />
      </svg>
    ),
  },
  {
    title: 'Investigate',
    body:  'Access the complete history of any product, including location changes, status updates, and ownership trail.',
    icon: (
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5"
        strokeLinecap="round" strokeLinejoin="round" width="22" height="22">
        <circle cx="11" cy="11" r="8" />
        <line x1="21" y1="21" x2="16.65" y2="16.65" />
      </svg>
    ),
  },
  {
    title: 'Act',
    body:  'Make faster operational decisions using live information instead of outdated reports.',
    icon: (
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5"
        strokeLinecap="round" strokeLinejoin="round" width="22" height="22">
        <polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2" />
      </svg>
    ),
  },
]

function HowItWorksSection() {
  return (
    <section id="how-it-works" className="py-24" aria-labelledby="hiw-heading">
      <div className="max-w-6xl mx-auto px-4 sm:px-6 lg:px-8">
        <Reveal className="text-center mb-16">
          <p className="text-xs font-medium text-brand uppercase tracking-widest mb-3">
            How it works
          </p>
          <h2 id="hiw-heading"
            className="text-3xl sm:text-4xl font-light text-primary tracking-tight">
            From scan to insight — in one system
          </h2>
        </Reveal>

        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-6">
          {HOW_STEPS.map((step, i) => (
            <Reveal key={step.title} delay={i * 80}>
              <div className="card p-6 hover:border-brand/30 hover:-translate-y-1 transition-all duration-200 group h-full flex flex-col">
                <div className="w-10 h-10 rounded-lg bg-brand/10 flex items-center justify-center text-brand mb-4 flex-shrink-0 group-hover:bg-brand/20 transition-colors">
                  {step.icon}
                </div>
                <h3 className="text-base font-semibold text-primary mb-2">{step.title}</h3>
                <p className="text-sm text-muted leading-relaxed">{step.body}</p>
              </div>
            </Reveal>
          ))}
        </div>
      </div>
    </section>
  )
}

/* ─── Why Traced ──────────────────────────────────────────────────────────────*/
const WHY_CARDS = [
  {
    title: 'Real-time visibility',
    body:  "Know what's happening now, not what happened yesterday.",
    icon: (
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5"
        strokeLinecap="round" strokeLinejoin="round" width="22" height="22">
        <circle cx="12" cy="12" r="10" />
        <path d="M12 8v4l2 2" />
        <path d="M2 12h2m16 0h2M12 2v2m0 16v2" />
      </svg>
    ),
  },
  {
    title: 'Complete traceability',
    body:  'Every product carries a clear and auditable history.',
    icon: (
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5"
        strokeLinecap="round" strokeLinejoin="round" width="22" height="22">
        <path d="M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71" />
        <path d="M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71" />
      </svg>
    ),
  },
  {
    title: 'One source of truth',
    body:  'Replace fragmented spreadsheets and disconnected tools.',
    icon: (
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5"
        strokeLinecap="round" strokeLinejoin="round" width="22" height="22">
        <ellipse cx="12" cy="5" rx="9" ry="3" />
        <path d="M3 5v14c0 1.66 4.03 3 9 3s9-1.34 9-3V5" />
        <path d="M3 12c0 1.66 4.03 3 9 3s9-1.34 9-3" />
      </svg>
    ),
  },
  {
    title: 'Lightweight deployment',
    body:  'Get visibility without the cost and complexity of a traditional ERP implementation.',
    icon: (
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5"
        strokeLinecap="round" strokeLinejoin="round" width="22" height="22">
        <path d="M12 2L8.09 6.91 3 8l3 8.09L4 21l8-3 8 3-2-4.91L21 8l-5.09-1.09L12 2z" />
      </svg>
    ),
  },
]

function WhyTracedSection() {
  return (
    <section id="why-traced" className="py-24 bg-panel/30" aria-labelledby="why-heading">
      <div className="max-w-6xl mx-auto px-4 sm:px-6 lg:px-8">
        <Reveal className="text-center mb-16">
          <p className="text-xs font-medium text-brand uppercase tracking-widest mb-3">
            Why Traced
          </p>
          <h2 id="why-heading"
            className="text-3xl sm:text-4xl font-light text-primary tracking-tight">
            Built for operations that move fast
          </h2>
        </Reveal>

        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-6">
          {WHY_CARDS.map((card, i) => (
            <Reveal key={card.title} delay={i * 80}>
              <div className="card p-6 hover:border-brand/30 hover:-translate-y-1 transition-all duration-200 group h-full flex flex-col">
                <div className="w-10 h-10 rounded-lg bg-brand/10 flex items-center justify-center text-brand mb-4 flex-shrink-0 group-hover:bg-brand/20 transition-colors">
                  {card.icon}
                </div>
                <h3 className="text-base font-semibold text-primary mb-2">{card.title}</h3>
                <p className="text-sm text-muted leading-relaxed">{card.body}</p>
              </div>
            </Reveal>
          ))}
        </div>
      </div>
    </section>
  )
}

/* ─── Positioning + elevator pitch ───────────────────────────────────────────*/
function PositioningSection() {
  return (
    <section className="py-24" aria-labelledby="pos-heading">
      <div className="max-w-6xl mx-auto px-4 sm:px-6 lg:px-8">
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-12 lg:gap-16">
          <Reveal>
            <p className="text-xs font-medium text-brand uppercase tracking-widest mb-4">
              Positioning
            </p>
            <h2 id="pos-heading"
              className="text-2xl sm:text-3xl font-light text-primary tracking-tight mb-5 leading-snug">
              Designed for businesses that have outgrown spreadsheets
            </h2>
            <p className="text-base text-muted leading-relaxed">
              For any business that moves physical products and has outgrown spreadsheets and guesswork, Traced is the intelligence layer that tracks every item, every movement, and every action in real time — turning scattered tools into one source of truth, without the weight of an ERP.
            </p>
          </Reveal>

          <Reveal delay={120}>
            <p className="text-xs font-medium text-brand uppercase tracking-widest mb-4">
              What it is
            </p>
            <h3 className="text-2xl sm:text-3xl font-light text-primary tracking-tight mb-5 leading-snug">
              One live source of truth — from any phone
            </h3>
            <p className="text-base text-muted leading-relaxed">
              Traced is the intelligence layer for physical products. Whether you're running a warehouse, a store, a distribution operation, or all three, Traced connects your systems and your people into one live source of truth — tracking every item's location, state, and history automatically, from any phone. The moment something moves, it's traced. No ERP implementation, no guesswork, no lost inventory.
            </p>
          </Reveal>
        </div>
      </div>
    </section>
  )
}

/* ─── Pricing ─────────────────────────────────────────────────────────────────*/
const PRICING_FEATURES = [
  'Shopify + Bosta connect',
  'Piece-level barcode tracking',
  'AWB printing',
  'Returns receiving',
  'Real-time custody timeline',
  'Exceptions & alerts',
  'Analytics dashboard',
  'Unlimited users',
  'Arabic + English',
  'Priority support',
]

function PricingSection() {
  return (
    <section id="pricing" className="py-24 bg-panel/30" aria-labelledby="pricing-heading">
      <div className="max-w-6xl mx-auto px-4 sm:px-6 lg:px-8">
        <Reveal className="text-center mb-12">
          <p className="text-xs font-medium text-brand uppercase tracking-widest mb-3">
            Pricing
          </p>
          <h2 id="pricing-heading"
            className="text-3xl sm:text-4xl font-light text-primary tracking-tight">
            One plan. Everything included.
          </h2>
        </Reveal>

        <Reveal className="max-w-sm mx-auto" delay={80}>
          <div className="card p-8 text-center hover:border-brand/40 hover:-translate-y-1 hover:shadow-brand transition-all duration-300 flex flex-col">
            <p className="text-xs font-medium text-brand uppercase tracking-widest mb-4">
              Traced
            </p>

            <div className="mb-1">
              <span className="text-5xl font-light text-primary tabular-nums">999</span>
              <span className="text-lg text-muted ms-1">EGP / month</span>
            </div>
            <p className="text-sm text-muted mb-8">
              Everything you need to trace every piece — one flat price.
            </p>

            <ul className="text-start space-y-3 mb-8 flex-1">
              {PRICING_FEATURES.map(f => (
                <li key={f} className="flex items-start gap-2.5">
                  <svg className="text-success flex-shrink-0 mt-0.5" width="16" height="16"
                    viewBox="0 0 16 16" fill="none" stroke="currentColor"
                    strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                    <polyline points="2,8 6,12 14,4" />
                  </svg>
                  <span className="text-sm text-muted">{f}</span>
                </li>
              ))}
            </ul>

            {/* TODO(payment): replace direct redirect with checkout → payment (Instapay/bank transfer per FR-1.5) → provision → redirect */}
            <a href={SIGNUP_URL}
              className="btn-brand text-base px-6 py-3 rounded-xl w-full hover:shadow-brand transition-shadow focus-visible:ring-2 focus-visible:ring-brand focus-visible:outline-none block text-center">
              Start now
            </a>
          </div>
        </Reveal>
      </div>
    </section>
  )
}

/* ─── Mission / Vision / Brand story ─────────────────────────────────────────*/
function MissionSection() {
  return (
    <section className="py-24" aria-labelledby="mission-heading">
      <div className="max-w-6xl mx-auto px-4 sm:px-6 lg:px-8">
        <Reveal className="text-center mb-16">
          <h2 id="mission-heading"
            className="text-3xl sm:text-4xl font-light text-primary tracking-tight">
            Built with purpose
          </h2>
        </Reveal>

        <div className="grid grid-cols-1 md:grid-cols-3 gap-8">
          <Reveal delay={0}>
            <div className="card p-6 h-full flex flex-col">
              <p className="text-xs font-medium text-brand uppercase tracking-widest mb-3">
                Mission
              </p>
              <p className="text-sm text-muted leading-relaxed">
                To give anyone who moves physical products complete, real-time visibility over where everything is, what's happening to it, and who touched it last.
              </p>
            </div>
          </Reveal>
          <Reveal delay={100}>
            <div className="card p-6 h-full flex flex-col">
              <p className="text-xs font-medium text-brand uppercase tracking-widest mb-3">
                Vision
              </p>
              <p className="text-sm text-muted leading-relaxed">
                A world where nothing physical is ever lost, guessed at, or untracked — where every product carries a clear, intelligent trail from origin to destination.
              </p>
            </div>
          </Reveal>
          <Reveal delay={200}>
            <div className="card p-6 h-full flex flex-col">
              <p className="text-xs font-medium text-brand uppercase tracking-widest mb-3">
                Our story
              </p>
              <p className="text-sm text-muted leading-relaxed">
                Most businesses don't lose money on the things they can see. They lose it in the gaps — the inventory that drifts, the return that vanishes, the item nobody can account for, the question "where is it right now?" that no one can answer. Traced was built to close them. We give every physical product an intelligent trail — its location, its condition, its full history, the person who last touched it — updated in real time, visible to everyone who needs it.
              </p>
            </div>
          </Reveal>
        </div>
      </div>
    </section>
  )
}

/* ─── Flow strip ──────────────────────────────────────────────────────────────*/
const FLOW_NODES = [
  {
    label: 'Warehouse',
    icon: (
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5"
        strokeLinecap="round" strokeLinejoin="round" width="20" height="20">
        <path d="M3 9l9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z" />
        <polyline points="9 22 9 12 15 12 15 22" />
      </svg>
    ),
  },
  {
    label: 'Store',
    icon: (
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5"
        strokeLinecap="round" strokeLinejoin="round" width="20" height="20">
        <path d="M6 2L3 6v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V6l-3-4z" />
        <line x1="3" y1="6" x2="21" y2="6" />
        <path d="M16 10a4 4 0 0 1-8 0" />
      </svg>
    ),
  },
  {
    label: 'In Transit',
    icon: (
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5"
        strokeLinecap="round" strokeLinejoin="round" width="20" height="20">
        <rect x="1" y="3" width="15" height="13" />
        <polygon points="16 8 20 8 23 11 23 16 16 16 16 8" />
        <circle cx="5.5" cy="18.5" r="2.5" />
        <circle cx="18.5" cy="18.5" r="2.5" />
      </svg>
    ),
  },
  {
    label: 'Customer',
    icon: (
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5"
        strokeLinecap="round" strokeLinejoin="round" width="20" height="20">
        <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2" />
        <circle cx="12" cy="7" r="4" />
      </svg>
    ),
  },
  {
    label: 'Returns',
    icon: (
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5"
        strokeLinecap="round" strokeLinejoin="round" width="20" height="20">
        <polyline points="1 4 1 10 7 10" />
        <path d="M3.51 15a9 9 0 1 0 .49-4.95" />
      </svg>
    ),
  },
]

function FlowStripSection() {
  const rm = usePrefersReducedMotion()
  return (
    <section className="py-24 bg-panel/20" aria-label="Order journey">
      <div className="max-w-6xl mx-auto px-4 sm:px-6 lg:px-8">
        <Reveal className="text-center mb-16">
          <p className="text-xs font-medium text-brand uppercase tracking-widest mb-3">
            The journey
          </p>
          <h2 className="text-3xl sm:text-4xl font-light text-primary tracking-tight">
            Every move, traced
          </h2>
        </Reveal>

        <Reveal>
          <div className="flex flex-col sm:flex-row items-center justify-center">
            {FLOW_NODES.map((node, i) => (
              <div key={node.label} className="flex flex-col sm:flex-row items-center">
                <div className="flex flex-col items-center gap-3 group">
                  <div className="w-14 h-14 rounded-2xl bg-panel border border-line/60 flex items-center justify-center text-brand group-hover:border-brand/40 group-hover:bg-brand/10 group-hover:-translate-y-1 transition-all duration-200">
                    {node.icon}
                  </div>
                  <span className="text-xs text-muted font-medium whitespace-nowrap">
                    {node.label}
                  </span>
                </div>
                {i < FLOW_NODES.length - 1 && (
                  <div className="mx-3 my-2 sm:my-0 flex-shrink-0">
                    <div
                      className="w-px sm:w-12 h-8 sm:h-px rounded-full"
                      style={{
                        background: 'linear-gradient(90deg, rgba(99,102,255,.1) 0%, rgba(99,102,255,.55) 50%, rgba(99,102,255,.1) 100%)',
                        backgroundSize: '200% 100%',
                        animation: rm ? 'none' : `lpFlowShimmer 2.5s linear ${i * 0.35}s infinite`,
                      }}
                    />
                  </div>
                )}
              </div>
            ))}
          </div>
        </Reveal>
      </div>
    </section>
  )
}

/* ─── Footer ──────────────────────────────────────────────────────────────────*/
function Footer() {
  const year = new Date().getFullYear()
  return (
    <footer className="py-16 border-t border-line/50">
      <div className="max-w-6xl mx-auto px-4 sm:px-6 lg:px-8">
        <div className="flex flex-col md:flex-row items-center md:items-start justify-between gap-8">
          <div className="text-center md:text-start">
            <Logo variant="wordmark" size={26} className="mb-3" />
            <p className="text-sm text-muted max-w-xs">
              Complete visibility. Real-time intelligence. Every move, traced.
            </p>
          </div>

          <nav aria-label="Footer navigation"
            className="flex flex-wrap justify-center md:justify-end gap-x-8 gap-y-3">
            <a href="#how-it-works" className="text-sm text-muted hover:text-primary transition-colors">
              How it works
            </a>
            <a href="#why-traced" className="text-sm text-muted hover:text-primary transition-colors">
              Why Traced
            </a>
            <a href="#pricing" className="text-sm text-muted hover:text-primary transition-colors">
              Pricing
            </a>
            <Link to="/login" className="text-sm text-muted hover:text-primary transition-colors">
              Log in
            </Link>
            {/* TODO(payment): replace direct redirect with checkout → payment (FR-1.5) */}
            <a href={SIGNUP_URL} className="text-sm text-brand hover:text-brand-hover transition-colors">
              Get started
            </a>
          </nav>
        </div>

        <div className="mt-10 pt-6 border-t border-line/30 text-center">
          <p className="text-xs text-muted">© {year} Traced. All rights reserved.</p>
        </div>
      </div>
    </footer>
  )
}

/* ─── Keyframes (landing-page-only, scoped by lp- prefix) ────────────────────*/
const LP_STYLES = `
  .lp-logo { animation: lpLogoPulse 4s ease-in-out infinite; }
  @keyframes lpLogoPulse {
    0%, 100% { opacity: .82; }
    50%       { opacity: 1; }
  }
  @keyframes lpTimelinePulse {
    0%, 100% { box-shadow: 0 0 0 0 rgba(99,102,255,.55); }
    50%       { box-shadow: 0 0 0 6px rgba(99,102,255,0); }
  }
  @keyframes lpLivePulse {
    0%, 100% { opacity: 1; }
    50%       { opacity: .25; }
  }
  @keyframes lpFlowShimmer {
    0%   { background-position: 200% 0; }
    100% { background-position: -200% 0; }
  }
`

/* ─── Landing page ────────────────────────────────────────────────────────────*/
export default function Landing() {
  return (
    <>
      <style>{LP_STYLES}</style>
      <div className="min-h-screen bg-base text-primary">
        <Nav />
        <main>
          <HeroSection />
          <HowItWorksSection />
          <WhyTracedSection />
          <PositioningSection />
          <PricingSection />
          <MissionSection />
          <FlowStripSection />
        </main>
        <Footer />
      </div>
    </>
  )
}
