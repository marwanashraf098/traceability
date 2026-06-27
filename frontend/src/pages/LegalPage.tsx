import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { Logo } from '../components/Logo'

function LegalNav() {
  const [scrolled, setScrolled] = useState(false)
  useEffect(() => {
    const onScroll = () => setScrolled(window.scrollY > 8)
    window.addEventListener('scroll', onScroll, { passive: true })
    return () => window.removeEventListener('scroll', onScroll)
  }, [])
  return (
    <header
      className={`fixed top-0 left-0 right-0 z-50 transition-all duration-300 ${
        scrolled ? 'bg-base/90 backdrop-blur-md border-b border-line/50 shadow-card' : 'bg-transparent'
      }`}
    >
      <div className="max-w-6xl mx-auto px-4 sm:px-6 lg:px-8 h-16 flex items-center justify-between">
        <Link to="/" aria-label="Traced home">
          <Logo variant="wordmark" size={26} />
        </Link>
        <div className="flex items-center gap-3">
          <Link to="/login" className="btn-outline text-sm px-4 py-1.5">
            Log in
          </Link>
          <Link to="/signup" className="btn-brand text-sm px-4 py-1.5">
            Open app
          </Link>
        </div>
      </div>
    </header>
  )
}

function LegalFooter() {
  const year = new Date().getFullYear()
  return (
    <footer className="py-10 border-t border-line/50 mt-16">
      <div className="max-w-6xl mx-auto px-4 sm:px-6 lg:px-8 flex flex-col sm:flex-row items-center justify-between gap-4">
        <Link to="/" aria-label="Traced home">
          <Logo variant="wordmark" size={22} />
        </Link>
        <div className="flex flex-wrap justify-center gap-x-6 gap-y-2">
          <Link to="/privacy" className="text-sm text-muted hover:text-primary transition-colors">
            Privacy Policy
          </Link>
          <Link to="/terms" className="text-sm text-muted hover:text-primary transition-colors">
            Terms of Service
          </Link>
          <a href="mailto:hello@tracedtech.com" className="text-sm text-muted hover:text-primary transition-colors">
            Contact
          </a>
        </div>
        <p className="text-xs text-muted">© {year} Traced. All rights reserved.</p>
      </div>
    </footer>
  )
}

interface LegalPageProps {
  content: string
}

export default function LegalPage({ content }: LegalPageProps) {
  return (
    <div className="min-h-screen bg-base text-primary">
      <LegalNav />

      <main className="pt-28 pb-4 px-4 sm:px-6 lg:px-8">
        <div className="max-w-prose mx-auto">
          <ReactMarkdown
            remarkPlugins={[remarkGfm]}
            components={{
              blockquote: () => null,
              h1: ({ children }) => (
                <h1 className="text-3xl font-bold text-primary mb-6 leading-tight">{children}</h1>
              ),
              h2: ({ children }) => (
                <h2 className="text-xl font-semibold text-primary mt-10 mb-3 pb-2 border-b border-line/50">
                  {children}
                </h2>
              ),
              h3: ({ children }) => (
                <h3 className="text-base font-semibold text-primary mt-6 mb-2">{children}</h3>
              ),
              p: ({ children }) => (
                <p className="text-sm text-secondary leading-relaxed mb-4">{children}</p>
              ),
              strong: ({ children }) => (
                <strong className="font-semibold text-primary">{children}</strong>
              ),
              a: ({ href, children }) => (
                <a
                  href={href}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="text-brand hover:text-brand-hover underline underline-offset-2 transition-colors"
                >
                  {children}
                </a>
              ),
              ul: ({ children }) => (
                <ul className="list-disc list-outside pl-5 mb-4 space-y-1 text-sm text-secondary">
                  {children}
                </ul>
              ),
              li: ({ children }) => <li className="leading-relaxed">{children}</li>,
              table: ({ children }) => (
                <div className="overflow-x-auto my-6">
                  <table className="w-full text-sm border-collapse">{children}</table>
                </div>
              ),
              thead: ({ children }) => (
                <thead className="bg-elevated">{children}</thead>
              ),
              tbody: ({ children }) => (
                <tbody className="divide-y divide-line/40">{children}</tbody>
              ),
              tr: ({ children }) => <tr>{children}</tr>,
              th: ({ children }) => (
                <th className="text-left px-4 py-2.5 text-xs font-semibold text-muted uppercase tracking-wide border-b border-line/50">
                  {children}
                </th>
              ),
              td: ({ children }) => (
                <td className="px-4 py-2.5 text-secondary">{children}</td>
              ),
            }}
          >
            {content}
          </ReactMarkdown>
        </div>
      </main>

      <LegalFooter />
    </div>
  )
}
