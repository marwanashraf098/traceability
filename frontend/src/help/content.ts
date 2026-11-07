import { parseArticle, type HelpArticle, type HelpLibrary } from './articles'

/**
 * Onboarding O6 — every help article, both languages, parsed. This module is only ever imported DYNAMICALLY
 * (loadHelp.ts), so all the markdown lands in one lazy chunk loaded when the help center (or an article) opens.
 */
const raw = import.meta.glob('./{en,ar}/*.md', { query: '?raw', import: 'default', eager: true }) as Record<string, string>

function build(): HelpLibrary {
  const lib: HelpLibrary = { en: {}, ar: {} }
  for (const [path, text] of Object.entries(raw)) {
    const m = /\.\/(en|ar)\/([^/]+)\.md$/.exec(path)
    if (!m) continue
    const a: HelpArticle = parseArticle(text)
    if (a.id !== m[2]) throw new Error(`help article ${path}: id "${a.id}" doesn't match its file name`)
    lib[m[1] as 'en' | 'ar'][a.id] = a
  }
  return lib
}

export const LIBRARY: HelpLibrary = build()
