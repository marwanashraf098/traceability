/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{js,ts,jsx,tsx}'],
  theme: {
    extend: {
      colors: {
        // ── Design System v1.0 tokens ─────────────────────────────────────────
        charcoal: '#0D1117',
        surface:  '#0F141B',

        'trace-blue': {
          DEFAULT: '#2563EB',
          hover:   '#1D4ED8',
          active:  '#1E40AF',
        },

        critical: '#DC2626',
        info:     '#0EA5E9',

        grey: {
          50:  '#F2F4F7',
          100: '#D1D5DB',
          200: '#A9B0BC',
          300: '#828B99',
          400: '#5B6675',
          500: '#3A4553',
          600: '#2A333F',
          700: '#262C36',
          800: '#1A1F27',
          900: '#0D1117',
        },

        bg: '#0D1117',

        // ── Day 15 aliases — old names → new values so existing pages render ──
        base:    '#0D1117',   // was #0B1220 → now bg/charcoal
        panel:   '#0F141B',   // was #1E293B → now surface
        elevated:'#161B22',   // was #253449
        line:    '#262C36',   // was #2D3F55 → now grey.700
        primary:   '#F2F4F7',   // was #F8FAFC — text token alias
        muted:     '#828B99',   // was #647488 — text token alias
        secondary: '#828B99',   // orphan alias in Locations/ShopifyInventory/LegalPage → muted

        brand: {
          DEFAULT: '#2563EB', // was #6366FF → trace-blue
          hover:   '#1D4ED8', // was #5153E8 → trace-blue-hover
        },
        accent: {
          DEFAULT: '#2563EB', // was #3882F6, CONFIRM → trace-blue
        },
        cyan: {
          DEFAULT: '#0EA5E9', // was #22D3EE, CONFIRM → info
        },
        success: {
          DEFAULT: '#16A34A', // was #22C55E
          muted:   '#14532D',
        },
        warning: {
          DEFAULT: '#F59E0B', // unchanged
          muted:   '#78350F',
        },
        danger: {
          DEFAULT: '#DC2626', // was #EF4444 → critical
          muted:   '#7F1D1D',
        },
      },

      fontFamily: {
        sans:   ['"Geist Variable"', 'Inter', 'system-ui', 'sans-serif'],
        mono:   ['"Geist Mono"', 'ui-monospace', 'monospace'],
        arabic: ['Cairo', 'sans-serif'],
      },

      fontSize: {
        // Design System v1.0
        h1:        ['40px', { lineHeight: '48px', fontWeight: '700' }],
        h2:        ['32px', { lineHeight: '40px', fontWeight: '600' }],
        h3:        ['24px', { lineHeight: '32px', fontWeight: '600' }],
        h4:        ['20px', { lineHeight: '28px', fontWeight: '600' }],
        'body-lg': ['16px', { lineHeight: '24px' }],
        body:      ['14px', { lineHeight: '20px' }],
        small:     ['12px', { lineHeight: '16px' }],
        caption:   ['12px', { lineHeight: '16px' }],
        // Day 15 compat — keep display for StatCard
        display:   ['2.25rem', { lineHeight: '1.1', fontWeight: '300' }],
      },

      borderRadius: {
        none:    '0',
        sm:      '4px',
        DEFAULT: '6px',
        md:      '8px',
        lg:      '10px',
        xl:      '12px',
        '2xl':   '16px',
        '3xl':   '20px',
        full:    '9999px',
      },

      // Dark-UI elevations (adapted to higher-alpha black — CONFIRM)
      boxShadow: {
        none: 'none',
        e1:   '0 1px 2px 0 rgba(0,0,0,0.30)',
        e2:   '0 4px 12px -2px rgba(0,0,0,0.38)',
        e3:   '0 8px 24px -4px rgba(0,0,0,0.46)',
        e4:   '0 16px 48px -8px rgba(0,0,0,0.55)',
        // Day 15 compat aliases
        card:     '0 1px 2px 0 rgba(0,0,0,0.30)',
        elevated: '0 4px 12px -2px rgba(0,0,0,0.38)',
        brand:    '0 0 20px 0 rgba(37,99,235,0.25)',
        glow:     '0 0 0 3px rgba(37,99,235,0.35)',
      },

      zIndex: {
        base:     '0',
        overlay:  '700',
        modal:    '800',
        sticky:   '900',
        dropdown: '1000',
      },

      // Keep ALL existing animations — scan flash + timeline pulse are safety-critical
      keyframes: {
        flash: {
          '0%':   { opacity: '0.35' },
          '50%':  { opacity: '0.35' },
          '100%': { opacity: '0' },
        },
        fadeIn: {
          '0%':   { opacity: '0', transform: 'translateY(4px)' },
          '100%': { opacity: '1', transform: 'translateY(0)' },
        },
        dotPing: {
          '0%':        { transform: 'scale(1)', opacity: '1' },
          '75%, 100%': { transform: 'scale(2)', opacity: '0' },
        },
      },
      animation: {
        flash:   'flash 0.6s ease-out forwards',
        fadeIn:  'fadeIn 0.2s ease-out',
        dotPing: 'dotPing 1.5s cubic-bezier(0,0,0.2,1) infinite',
      },
    },
  },
  plugins: [],
}
