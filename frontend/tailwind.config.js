/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{js,ts,jsx,tsx}'],
  theme: {
    extend: {
      keyframes: {
        flash: {
          '0%':   { opacity: '0.35' },
          '50%':  { opacity: '0.35' },
          '100%': { opacity: '0' },
        },
      },
      animation: {
        flash: 'flash 0.6s ease-out forwards',
      },
    },
  },
  plugins: [],
}
