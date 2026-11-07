import { useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import ReactMarkdown, { type Components } from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { ChevronRight, MessageCircle, ThumbsUp } from 'lucide-react'
import { Spinner } from '../../components/ui'
import { useHelpLibrary } from '../../help/loadHelp'
import { HELP_WHATSAPP } from '../../help/topics'
import { helpLang } from './HelpCenter'

// Onboarding O6 — one help article (design/onboarding/Traced_onboarding_Help-Article): breadcrumb, title, who it's
// for, the "Short answer" box (the article's first paragraph), the body, Related, and "Did this answer your
// question?" — Yes only thanks you, No opens WhatsApp. Nothing is stored. (The mockup's "Check my store" box is O6b.)
// The video slot stays hidden until an article has a video.

const md: Components = {
  h2: ({ children }) => <h2 className="text-h3 text-primary mt-6 mb-2">{children}</h2>,
  h3: ({ children }) => <h3 className="text-body font-semibold text-primary mt-4 mb-1.5">{children}</h3>,
  p: ({ children }) => <p className="text-body text-primary/90 leading-relaxed my-3">{children}</p>,
  ol: ({ children }) => <ol className="list-decimal ps-6 my-3 flex flex-col gap-2 text-body text-primary/90 leading-relaxed">{children}</ol>,
  ul: ({ children }) => <ul className="list-disc ps-6 my-3 flex flex-col gap-2 text-body text-primary/90 leading-relaxed">{children}</ul>,
  li: ({ children }) => <li className="ps-1">{children}</li>,
  strong: ({ children }) => <strong className="font-semibold text-primary">{children}</strong>,
  code: ({ children }) => <code className="font-mono text-[0.92em] bg-elevated rounded px-1 py-0.5" dir="ltr">{children}</code>,
  a: ({ href, children }) =>
    href && href.startsWith('/')
      ? <Link to={href} className="text-trace-blue hover:text-trace-blue-hover underline-offset-2 hover:underline">{children}</Link>
      : <a href={href} target="_blank" rel="noopener noreferrer" className="text-trace-blue hover:text-trace-blue-hover underline-offset-2 hover:underline">{children}</a>,
}

export default function HelpArticle() {
  const { id = '' } = useParams()
  const { t, i18n } = useTranslation()
  const lang = helpLang(i18n.language)
  const lib = useHelpLibrary()
  const [answer, setAnswer] = useState<'yes' | null>(null)

  if (lib === undefined) return <div className="flex justify-center py-16"><Spinner /></div>
  if (lib === null) return <p className="text-body text-critical-text" role="alert">{t('helpCenter.loadFailed')}</p>
  const found = lib[lang][id]
  // A hidden article (feature not rolled out yet) can't be opened, even by its URL.
  const a = found && !found.hidden ? found : undefined
  if (!a) {
    return (
      <div className="max-w-[760px] mx-auto flex flex-col gap-3" data-testid="help-article-missing">
        <p className="text-h3 text-primary">{t('helpCenter.notFound')}</p>
        <Link to="/help" className="text-trace-blue hover:text-trace-blue-hover">{t('helpCenter.back')}</Link>
      </div>
    )
  }
  const roles = a.roles.map(r => t(`users.roles.${r}`)).join(t('helpCenter.and'))
  const related = a.related.map(r => lib[lang][r]).filter(r => r && !r.hidden)

  return (
    <div className="max-w-[1040px] mx-auto flex flex-wrap gap-8 items-start" data-testid="help-article" data-article={a.id}>
      <article className="flex-[999_1_560px] min-w-0 flex flex-col gap-4">
        <nav aria-label={t('helpCenter.title')} className="flex flex-wrap items-center gap-1.5 text-small text-muted">
          <Link to="/help" className="hover:text-primary">{t('helpCenter.title')}</Link>
          <ChevronRight size={14} className="rtl:-scale-x-100" aria-hidden />
          <span>{t(`helpCenter.topics.${a.topic}`)}</span>
        </nav>
        <header className="flex flex-col gap-1.5">
          <h1 className="text-[30px] max-[640px]:text-[24px] leading-tight font-bold text-primary tracking-tight">{a.title}</h1>
          {roles && <p className="text-small text-muted">{t('helpCenter.forRoles', { roles })}</p>}
        </header>

        <div className="rounded-xl border border-trace-blue/25 bg-trace-blue/[0.06] px-5 py-4 text-body text-primary leading-relaxed" data-testid="help-short">
          <span className="font-semibold">{t('helpCenter.shortAnswer')}</span>{' '}
          <ReactMarkdown remarkPlugins={[remarkGfm]} components={{ ...md, p: ({ children }) => <>{children}</> }}>{a.short}</ReactMarkdown>
        </div>

        {a.video && (
          <a href={a.video} target="_blank" rel="noopener noreferrer" className="card p-4 text-body text-trace-blue" data-testid="help-video">
            {a.video}
          </a>
        )}

        <div data-testid="help-body">
          <ReactMarkdown remarkPlugins={[remarkGfm]} components={md}>{a.rest}</ReactMarkdown>
        </div>

        <section className="card p-5 flex flex-wrap items-center justify-between gap-3" data-testid="help-feedback">
          <span className="text-body font-semibold text-primary">{t('helpCenter.helpful')}</span>
          {answer === 'yes' ? (
            <span className="text-body text-success-text inline-flex items-center gap-1.5" role="status" data-testid="help-thanks">
              <ThumbsUp size={16} aria-hidden /> {t('helpCenter.thanks')}
            </span>
          ) : (
            <span className="flex flex-wrap gap-2">
              <button type="button" onClick={() => setAnswer('yes')} data-testid="help-yes"
                className="h-10 px-4 rounded-lg border border-line text-body font-semibold text-primary hover:bg-elevated">
                {t('helpCenter.yes')}
              </button>
              <a href={HELP_WHATSAPP} target="_blank" rel="noopener noreferrer" data-testid="help-no"
                className="h-10 inline-flex items-center gap-2 px-4 rounded-lg bg-trace-blue text-white text-body font-semibold hover:bg-trace-blue-hover">
                <MessageCircle size={16} aria-hidden /> {t('helpCenter.no')}
              </a>
            </span>
          )}
        </section>
      </article>

      {related.length > 0 && (
        <aside className="flex-[1_1_260px] max-w-[300px] max-[860px]:max-w-none card p-5 flex flex-col gap-2" data-testid="help-related">
          <h2 className="text-body font-semibold text-primary">{t('helpCenter.related')}</h2>
          <ul className="flex flex-col">
            {related.map(r => (
              <li key={r.id}>
                <Link to={`/help/${r.id}`} className="block py-2 border-t border-line first:border-t-0 text-body text-trace-blue hover:text-trace-blue-hover">
                  {r.title}
                </Link>
              </li>
            ))}
          </ul>
        </aside>
      )}
    </div>
  )
}
