/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{js,ts,jsx,tsx}'],
  theme: {
    extend: {
      colors: {
        // ── Design System v1.0 tokens ─────────────────────────────────────────
        charcoal: '#F2F4F7',   // light theme repoint (was #0D1117)
        surface:  '#FFFFFF',   // light theme repoint (was #0F141B)

        'trace-blue': {
          DEFAULT: '#2563EB',
          hover:   '#1D4ED8',
          active:  '#1E40AF',
        },

        critical: { DEFAULT: '#DC2626', text: '#B91C1C' },
        info:     { DEFAULT: '#0EA5E9', text: '#0369A1' },
        neutral:  { text: '#4B5563' },

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

        bg: '#F7F8FA',

        // ── Day 15 aliases — old names → new values so existing pages render ──
        base:    '#F7F8FA',   // light theme repoint (was #0D1117)
        panel:   '#FFFFFF',   // light theme repoint (was #0F141B)
        elevated:'#F2F4F7',   // light theme repoint (was #161B22)
        line:    '#E5E7EB',   // light theme repoint (was #262C36)
        primary:   '#111827',   // light theme repoint (was #F2F4F7)
        muted:     '#5B6675',   // light theme repoint (was #828B99)
        secondary: '#5B6675',   // orphan alias in Locations/ShopifyInventory/LegalPage → muted

        // ── Fixed sidebar palette — NEVER flips with theme (Layout.tsx rail only) ──
        sidebar: {
          DEFAULT: '#0D1117',
          text:    '#9CA6B2',
          active:  '#F2F4F7',
          line:    '#262C36',
        },

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
          text:    '#15803D', // light theme repoint (was #4ADE80)
        },
        warning: {
          DEFAULT: '#F59E0B', // unchanged
          muted:   '#78350F',
          text:    '#B45309', // light theme repoint (was #FBBF24)
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

      // Light-UI elevations — low-alpha neutral-gray recipe (was higher-alpha black)
      boxShadow: {
        none: 'none',
        e1:   '0 1px 2px 0 rgba(16,24,40,0.06), 0 1px 3px 0 rgba(16,24,40,0.10)',
        e2:   '0 2px 4px -2px rgba(16,24,40,0.06), 0 4px 8px -2px rgba(16,24,40,0.10)',
        e3:   '0 4px 6px -2px rgba(16,24,40,0.03), 0 12px 16px -4px rgba(16,24,40,0.08)',
        e4:   '0 8px 8px -4px rgba(16,24,40,0.03), 0 20px 24px -4px rgba(16,24,40,0.08)',
        // Day 15 compat aliases
        card:     '0 1px 2px 0 rgba(16,24,40,0.06), 0 1px 3px 0 rgba(16,24,40,0.10)',
        elevated: '0 2px 4px -2px rgba(16,24,40,0.06), 0 4px 8px -2px rgba(16,24,40,0.10)',
        brand:      '0 0 20px 0 rgba(37,99,235,0.25)',
        glow:       '0 0 0 4px rgba(37,99,235,0.35)',
        'ring-accent': '0 0 0 3px rgba(37,99,235,0.14)',
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
        shimmer: {
          '0%':   { backgroundPosition: '-200px 0' },
          '100%': { backgroundPosition: '200px 0' },
        },
        indet: {
          '0%':   { left: '-30%' },
          '100%': { left: '100%' },
        },
        shake: {
          '0%, 100%': { transform: 'translateX(0)' },
          '20%':      { transform: 'translateX(-8px)' },
          '40%':      { transform: 'translateX(8px)' },
          '60%':      { transform: 'translateX(-6px)' },
          '80%':      { transform: 'translateX(6px)' },
        },
      },
      animation: {
        flash:   'flash 0.6s ease-out forwards',
        fadeIn:  'fadeIn 0.2s ease-out',
        dotPing: 'dotPing 1.5s cubic-bezier(0,0,0.2,1) infinite',
        shimmer: 'shimmer 1.4s linear infinite',
        indet:   'indet 1.4s ease-in-out infinite',
        shake:   'shake 0.4s ease-in-out',
      },
    },
  },
  plugins: [],
}
