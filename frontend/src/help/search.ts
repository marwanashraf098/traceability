import type { HelpArticle, HelpLibrary, HelpLang } from './articles'

/**
 * Onboarding O6 — client-side help search. A query matches an article when every word of it appears in the
 * article's title or body, in EITHER language (an Arabic query finds an article while the page is in English, and
 * the other way round); results are shown in the page's language. Both sides go through `normalise`.
 */

/** Lower case; Arabic: no diacritics / tatweel, alef forms → ا, ة → ه, ى → ي; Arabic-Indic and Persian digits → 0-9. */
export function normalise(s: string): string {
  return s
    .toLowerCase()
    .replace(/[ً-ٰٟۖ-ۭ]/g, '')     // harakat, superscript alef, Quranic marks
    .replace(/ـ/g, '')                                  // tatweel
    .replace(/[آأإٱ]/g, 'ا')         // آ أ إ ٱ → ا
    .replace(/ة/g, 'ه')                            // ة → ه
    .replace(/ى/g, 'ي')                            // ى → ي
    .replace(/[٠-٩]/g, d => String(d.charCodeAt(0) - 0x0660))
    .replace(/[۰-۹]/g, d => String(d.charCodeAt(0) - 0x06F0))
    .replace(/[’‘`]/g, "'")
    .replace(/\s+/g, ' ')
    .trim()
}

function haystack(a: HelpArticle | undefined): string {
  return a ? normalise(`${a.title} ${a.short} ${a.rest}`) : ''
}

/** Ids of the visible articles (in `lang`) that match `query`; title matches first. Empty query → every visible
 *  article. Hidden articles never match. */
export function searchHelp(lib: HelpLibrary, lang: HelpLang, query: string): string[] {
  const words = normalise(query).split(' ').filter(Boolean)
  const ids = Object.keys(lib[lang]).filter(id => !lib[lang][id].hidden)
  if (words.length === 0) return ids
  const other: HelpLang = lang === 'en' ? 'ar' : 'en'
  const hits: { id: string; title: boolean }[] = []
  for (const id of ids) {
    const text = haystack(lib[lang][id]) + ' ' + haystack(lib[other][id])
    if (!words.every(w => text.includes(w))) continue
    const titles = normalise(`${lib[lang][id].title} ${lib[other][id]?.title ?? ''}`)
    hits.push({ id, title: words.every(w => titles.includes(w)) })
  }
  return hits.sort((a, b) => Number(b.title) - Number(a.title)).map(h => h.id)
}
