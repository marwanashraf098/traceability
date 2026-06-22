import '@testing-library/jest-dom/vitest'
import { afterEach } from 'vitest'
import { cleanup } from '@testing-library/react'

// @testing-library/react auto-cleanup requires globals:true to detect afterEach.
// Since we use explicit vitest imports (no globals), we wire it manually here.
afterEach(cleanup)
