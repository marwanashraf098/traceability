import { useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { MessageCircle, Search } from 'lucide-react'
import { Spinner, cn } from '../../components/ui'
import { useHelpLibrary } from '../../help/loadHelp'
import { searchHelp } from '../../help/search'
import { ARTICLE_ORDER, HELP_WHATSAPP, START_HERE, TOPICS, type HelpTopic } from '../../help/topics'
import type { HelpLang } from '../../help/articles'
import { SETUP_CALL_URL } from '../onboarding/SetupLayout'

// Onboarding O6 — the help center (design/onboarding/Traced_onboarding_Help-Center). Inside Layout, owners and
// managers. Search (title + body, EN and AR, Arabic normalised — help/search.ts), topic chips, "Start here", the
// topic sections. Content is the markdown in src/help/{en,ar}, loaded as one lazy chunk.

export function helpLang(language: string): HelpLang {
  return language.startsWith('ar') ? 'ar' : 'en'
}

export default function HelpCenter() {
  const { t, i18n } = useTranslation()
  const lang = helpLang(i18n.language)
  const lib = useHelpLibrary()
  const [q, setQ] = useState('')
  const [topic, setTopic] = useState<HelpTopic | 'all'>('all')

  const hits = useMemo(() => (lib ? new Set(searchHelp(lib, lang, q)) : new Set<string>()), [lib, lang, q])

  if (lib === undefined) return <div className="flex justify-center py-16"><Spinner /></div>
  if (lib === null) return <p className="text-body text-critical-text" role="alert">{t('helpCenter.loadFailed')}</p>

  const articles = lib[lang]
  const total = Object.values(articles).filter(a => !a.hidden).length
  const groups = TOPICS
    .filter(tp => topic === 'all' || tp === topic)
    .map(tp => ({ tp, items: Object.values(articles).filter(a => a.topic === tp && hits.has(a.id)).sort((a, b) => order(a.id) - order(b.id)) }))
    .filter(g => g.items.length > 0)
  const searching = q.trim().length > 0

  return (
    <div className="max-w-[1040px] mx-auto flex flex-col gap-7" data-testid="help-center">
      <section className="flex flex-col items-center text-center gap-3 pt-2">
        <h1 className="text-[32px] max-[640px]:text-[26px] leading-tight font-bold text-primary tracking-tight">{t('helpCenter.heading')}</h1>
        <label className="relative w-full max-w-[620px]">
          <span className="sr-only">{t('helpCenter.searchLabel')}</span>
          <Search size={18} className="absolute start-4 top-1/2 -translate-y-1/2 text-muted pointer-events-none" aria-hidden />
          <input type="search" value={q} onChange={e => setQ(e.target.value)} placeholder={t('helpCenter.searchLabel')}
            className="input w-full h-[52px] ps-11 text-body" data-testid="help-search" />
        </label>
        <p className="text-small text-muted">{t('helpCenter.searchHint', { count: total })}</p>
      </section>

      {!searching && topic === 'all' && (
        <section className="flex flex-col gap-3" data-testid="help-start-here">
          <h2 className="text-caption font-bold uppercase tracking-wide text-trace-blue">{t('helpCenter.startHere')}</h2>
          <ol className="grid grid-cols-5 max-[900px]:grid-cols-2 max-[480px]:grid-cols-1 gap-2.5">
            {START_HERE.map((s, i) => (
              <li key={s.key}>
                <Link to={`/help/${s.articleId}`} className="card h-full p-4 flex gap-3 items-start hover:border-trace-blue transition-colors">
                  <span className="w-7 h-7 flex-shrink-0 rounded-full bg-trace-blue text-white text-small font-bold flex items-center justify-center" aria-hidden>{i + 1}</span>
                  <span className="flex flex-col gap-0.5 min-w-0">
                    <span className="text-body font-semibold text-primary">{t(`helpCenter.path.${s.key}.t`)}</span>
                    <span className="text-small text-muted">{t(`helpCenter.path.${s.key}.d`)}</span>
                  </span>
                </Link>
              </li>
            ))}
          </ol>
        </section>
      )}

      <div className="flex flex-wrap gap-2" role="group" aria-label={t('helpCenter.allTopics')} data-testid="help-topics">
        {(['all', ...TOPICS] as const).map(tp => (
          <button key={tp} type="button" aria-pressed={topic === tp} onClick={() => setTopic(tp)} data-testid={`help-topic-${tp}`}
            className={cn('h-[38px] px-3.5 rounded-full text-small font-medium border transition-colors',
              topic === tp ? 'bg-primary text-surface border-primary' : 'bg-surface text-primary border-line hover:border-trace-blue')}>
            {tp === 'all' ? t('helpCenter.allTopics') : t(`helpCenter.topics.${tp}`)}
          </button>
        ))}
      </div>

      {groups.length === 0 ? (
        <section className="card p-6 flex flex-col items-center text-center gap-3" data-testid="help-no-match">
          <p className="text-h3 text-primary">{t('helpCenter.noMatch', { q: q.trim() })}</p>
          <p className="text-body text-muted">{t('helpCenter.noMatchBody')}</p>
          <a href={HELP_WHATSAPP} target="_blank" rel="noopener noreferrer"
            className="inline-flex items-center gap-2 h-10 px-4 rounded-lg bg-trace-blue text-white text-body font-semibold hover:bg-trace-blue-hover">
            <MessageCircle size={16} aria-hidden /> {t('helpCenter.chat')}
          </a>
        </section>
      ) : (
        <div className="grid grid-cols-2 max-[860px]:grid-cols-1 gap-4">
          {groups.map(g => (
            <section key={g.tp} className="card p-5 flex flex-col gap-2" data-testid={`help-section-${g.tp}`}>
              <div className="flex items-baseline justify-between gap-3">
                <h2 className="text-h3 text-primary">{t(`helpCenter.topics.${g.tp}`)}</h2>
                <span className="text-caption text-muted">{t('helpCenter.answers', { count: g.items.length })}</span>
              </div>
              <ul className="flex flex-col">
                {g.items.map(a => (
                  <li key={a.id}>
                    <Link to={`/help/${a.id}`} data-testid={`help-link-${a.id}`}
                      className="block py-2 border-t border-line first:border-t-0 text-body text-primary hover:text-trace-blue">
                      {a.title}
                    </Link>
                  </li>
                ))}
              </ul>
            </section>
          ))}
        </div>
      )}

      <section className="card p-6 flex flex-wrap items-center justify-between gap-4">
        <div className="flex flex-col gap-1">
          <span className="text-h3 text-primary">{t('helpCenter.stillStuck')}</span>
          <span className="text-body text-muted">{t('helpCenter.stillStuckBody')}</span>
        </div>
        <div className="flex flex-wrap gap-2">
          <a href={SETUP_CALL_URL} target="_blank" rel="noopener noreferrer"
            className="h-10 inline-flex items-center px-4 rounded-lg border border-line text-body font-semibold text-primary hover:bg-elevated">
            {t('helpCenter.bookCall')}
          </a>
          <a href={HELP_WHATSAPP} target="_blank" rel="noopener noreferrer"
            className="h-10 inline-flex items-center gap-2 px-4 rounded-lg bg-trace-blue text-white text-body font-semibold hover:bg-trace-blue-hover">
            <MessageCircle size={16} aria-hidden /> {t('helpCenter.chat')}
          </a>
        </div>
      </section>
    </div>
  )
}

/** Articles inside a topic keep the master list's order (help/topics' ARTICLE_ORDER). */
function order(id: string): number {
  const i = ARTICLE_ORDER.indexOf(id)
  return i === -1 ? 999 : i
}
