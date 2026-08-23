/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{js,ts,jsx,tsx}'],
  theme: {
    extend: {
      fontFamily: {
        sans: ['Inter', 'system-ui', 'sans-serif'],
      },
      colors: {
        ink: {
          950: '#0a0a0b',
          900: '#111113',
          850: '#17171a',
          800: '#1d1d21',
          700: '#292930',
          600: '#3a3a43',
          500: '#565661',
          400: '#8b8b96',
          300: '#b4b4bd',
        },
        amber: {
          400: '#fbbf24',
          500: '#f59e0b',
          600: '#d97706',
        },
      },
      keyframes: {
        'fade-in': {
          '0%': { opacity: '0', transform: 'translateY(6px)' },
          '100%': { opacity: '1', transform: 'translateY(0)' },
        },
        'equalize': {
          '0%, 100%': { transform: 'scaleY(0.35)' },
          '50%': { transform: 'scaleY(1)' },
        },
        'eq-bar': {
          '0%': { transform: 'scaleY(0.3)' },
          '100%': { transform: 'scaleY(1)' },
        },
      },
      animation: {
        'fade-in': 'fade-in 0.3s ease-out both',
        'bar-1': 'equalize 0.9s ease-in-out infinite',
        'bar-2': 'equalize 0.9s ease-in-out infinite 0.2s',
        'bar-3': 'equalize 0.9s ease-in-out infinite 0.4s',
      },
    },
  },
  plugins: [],
};
