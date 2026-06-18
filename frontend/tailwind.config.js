/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{js,ts,jsx,tsx}'],
  theme: {
    extend: {
      colors: {
        // ── Surface tokens (dark-first) ──────────────────────────────────────
        base:     '#0B1220',   // deepest bg — body, sidebar
        panel:    '#1E293B',   // card / panel bg
        elevated: '#253449',   // hover state, nested cards
        line:     '#2D3F55',   // border / divider

        // ── Text tokens ───────────────────────────────────────────────────────
        primary:  '#F8FAFC',   // main text (near-white)
        muted:    '#647488',   // secondary / meta text

        // ── Brand palette ─────────────────────────────────────────────────────
        brand: {
          DEFAULT: '#6366FF',
          hover:   '#5153E8',
        },
        accent: {
          DEFAULT: '#3882F6',
        },
        cyan: {
          DEFAULT: '#22D3EE',
        },

        // ── Semantic state tokens ─────────────────────────────────────────────
        success: {
          DEFAULT: '#22C55E',
          muted:   '#166534',
        },
        warning: {
          DEFAULT: '#F59E0B',
          muted:   '#78350F',
        },
        danger: {
          DEFAULT: '#EF4444',
          muted:   '#7F1D1D',
        },
      },

      fontSize: {
        display: ['2.25rem',  { lineHeight: '1.1', fontWeight: '300' }],
        h1:      ['1.5rem',   { lineHeight: '1.2', fontWeight: '600' }],
        h2:      ['1.25rem',  { lineHeight: '1.3', fontWeight: '600' }],
        h3:      ['1.125rem', { lineHeight: '1.4', fontWeight: '600' }],
        body:    ['0.875rem', { lineHeight: '1.5' }],
        small:   ['0.8125rem',{ lineHeight: '1.4' }],
        caption: ['0.75rem',  { lineHeight: '1.4' }],
      },

      fontFamily: {
        sans:    ['Inter', 'system-ui', 'sans-serif'],
        arabic:  ['Cairo', 'system-ui', 'sans-serif'],
      },

      borderRadius: {
        sm:    '6px',
        DEFAULT: '8px',
        md:    '10px',
        lg:    '14px',
        xl:    '18px',
        '2xl': '24px',
      },

      boxShadow: {
        card:    '0 1px 3px 0 rgba(0,0,0,0.4), 0 1px 2px -1px rgba(0,0,0,0.4)',
        elevated:'0 4px 16px 0 rgba(0,0,0,0.5)',
        brand:   '0 0 20px 0 rgba(99,102,255,0.25)',
        glow:    '0 0 0 3px rgba(99,102,255,0.35)',
      },

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
        flash:    'flash 0.6s ease-out forwards',
        fadeIn:   'fadeIn 0.2s ease-out',
        dotPing:  'dotPing 1.5s cubic-bezier(0,0,0.2,1) infinite',
      },
    },
  },
  plugins: [],
}
