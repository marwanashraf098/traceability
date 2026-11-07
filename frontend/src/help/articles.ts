/**
 * Onboarding O6 — a help article is a markdown file per language: src/help/{en,ar}/<id>.md, the same id in both.
 *
 *   ---
 *   id: order-not-in-pick-pack
 *   topic: pack
 *   title: Why isn't my order in Pick & Pack?
 *   roles: [owner, manager]
 *   related: [connect-bosta, other-couriers]
 *   alerts: [pack_set_aside]
 *   screens: [/fulfill]
 *   video:
 *   hidden: true        # optional — a feature not rolled out yet: out of search, topics and every link
 *   ---
 *   <the short answer: the FIRST paragraph, 1–2 sentences>
 *
 *   <the rest of the article: steps / causes, markdown>
 *
 * The first paragraph of the body is shown in the "Short answer" box; the rest below it. `video` stays empty until a
 * video exists (its slot is hidden while empty).
 */
export interface HelpArticle {
  id: string
  topic: string
  title: string
  roles: string[]
  related: string[]
  alerts: string[]
  screens: string[]
  video: string | null
  /** Not shown anywhere (search, topics, Related) and not openable, until its feature is rolled out. */
  hidden: boolean
  /** The first paragraph of the body. */
  short: string
  /** Everything after it. */
  rest: string
}

function list(v: string): string[] {
  const inner = v.trim().replace(/^\[/, '').replace(/\]$/, '').trim()
  return inner ? inner.split(',').map(s => s.trim()).filter(Boolean) : []
}

/** Parses one file. Throws on a missing frontmatter block or id / topic / title (the guard tests rely on it). */
export function parseArticle(raw: string): HelpArticle {
  const text = raw.replace(/\r\n/g, '\n')
  const m = /^---\n([\s\S]*?)\n---\n?([\s\S]*)$/.exec(text)
  if (!m) throw new Error('help article without frontmatter')
  const meta: Record<string, string> = {}
  for (const line of m[1].split('\n')) {
    const i = line.indexOf(':')
    if (i <= 0) continue
    meta[line.slice(0, i).trim()] = line.slice(i + 1).trim()
  }
  if (!meta.id || !meta.topic || !meta.title) throw new Error('help article needs id, topic and title')
  const body = m[2].trim()
  const split = body.search(/\n\s*\n/)
  const short = (split === -1 ? body : body.slice(0, split)).trim()
  const rest = split === -1 ? '' : body.slice(split).trim()
  return {
    id: meta.id,
    topic: meta.topic,
    title: meta.title.replace(/^["']|["']$/g, ''),
    roles: list(meta.roles ?? ''),
    related: list(meta.related ?? ''),
    alerts: list(meta.alerts ?? ''),
    screens: list(meta.screens ?? ''),
    video: meta.video ? meta.video : null,
    hidden: meta.hidden === 'true',
    short,
    rest,
  }
}

export type HelpLang = 'en' | 'ar'
export type HelpLibrary = Record<HelpLang, Record<string, HelpArticle>>
