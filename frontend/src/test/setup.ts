import '@testing-library/jest-dom/vitest'
import { afterEach, beforeAll } from 'vitest'
import { cleanup } from '@testing-library/react'

// @testing-library/react auto-cleanup requires globals:true to detect afterEach.
// Since we use explicit vitest imports (no globals), we wire it manually here.
afterEach(cleanup)

// Node 22 experimental localStorage is undefined without --localstorage-file.
// Ensure a functional in-memory localStorage is always available in tests.
beforeAll(() => {
  if (typeof globalThis.localStorage === 'undefined') {
    const store: Record<string, string> = {}
    Object.defineProperty(globalThis, 'localStorage', {
      value: {
        getItem: (k: string) => store[k] ?? null,
        setItem: (k: string, v: string) => { store[k] = v },
        removeItem: (k: string) => { delete store[k] },
        clear: () => { Object.keys(store).forEach(k => delete store[k]) },
      },
      writable: true,
    })
  }
})
