// Extends vitest's expect with jest-dom matchers (toBeInTheDocument, etc.)
// without requiring globals:true — safe for the no-globals config we use.
import '@testing-library/jest-dom/vitest'
