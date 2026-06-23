import { test, expect, describe, afterEach } from 'vitest'
import i18n from '../i18n'

// Verifies the languageChanged hook wired in i18n.ts:
// Every changeLanguage call (including the startup load from localStorage)
// updates document.documentElement.dir and .lang without a page reload.

describe('RTL dir flip', () => {
  afterEach(async () => {
    // Restore English baseline between tests.
    await i18n.changeLanguage('en')
  })

  test('rtl1 — switching to ar sets dir=rtl and lang=ar without remount', async () => {
    await i18n.changeLanguage('ar')
    expect(document.documentElement.dir).toBe('rtl')
    expect(document.documentElement.lang).toBe('ar')
  })

  test('rtl2 — switching back to en from ar sets dir=ltr and lang=en', async () => {
    await i18n.changeLanguage('ar')
    await i18n.changeLanguage('en')
    expect(document.documentElement.dir).toBe('ltr')
    expect(document.documentElement.lang).toBe('en')
  })
})
