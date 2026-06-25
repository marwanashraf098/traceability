// Node-burst mark — center node + 8 radiating spoke nodes
// Uses currentColor so parent sets the color via `text-brand` etc.

export function Logo({
  variant = 'icon',
  size    = 32,
  className = '',
}: {
  variant?:  'icon' | 'wordmark'
  size?:     number
  className?: string
}) {
  const burst = (
    <svg
      data-testid="logo-svg"
      width={size}
      height={size}
      viewBox="0 0 32 32"
      fill="currentColor"
      aria-hidden="true"
      xmlns="http://www.w3.org/2000/svg"
    >
      {/* Spokes — thin, slightly transparent */}
      <g fill="none" stroke="currentColor" strokeWidth="0.75" opacity="0.45">
        <line x1="16" y1="16" x2="27"   y2="16"   />
        <line x1="16" y1="16" x2="23.8" y2="23.8" />
        <line x1="16" y1="16" x2="16"   y2="27"   />
        <line x1="16" y1="16" x2="8.2"  y2="23.8" />
        <line x1="16" y1="16" x2="5"    y2="16"   />
        <line x1="16" y1="16" x2="8.2"  y2="8.2"  />
        <line x1="16" y1="16" x2="16"   y2="5"    />
        <line x1="16" y1="16" x2="23.8" y2="8.2"  />
      </g>
      {/* Center node */}
      <circle cx="16"   cy="16"   r="3.5"  />
      {/* Outer nodes (8 cardinal + diagonal positions at r=11) */}
      <circle cx="27"   cy="16"   r="1.75" />
      <circle cx="23.8" cy="23.8" r="1.75" />
      <circle cx="16"   cy="27"   r="1.75" />
      <circle cx="8.2"  cy="23.8" r="1.75" />
      <circle cx="5"    cy="16"   r="1.75" />
      <circle cx="8.2"  cy="8.2"  r="1.75" />
      <circle cx="16"   cy="5"    r="1.75" />
      <circle cx="23.8" cy="8.2"  r="1.75" />
    </svg>
  )

  if (variant === 'icon') {
    return (
      <span className={`inline-block text-brand ${className}`}>
        {burst}
      </span>
    )
  }

  // wordmark: icon + "traced" text (tr in brand color)
  return (
    <span className={`inline-flex items-center gap-2.5 ${className}`}>
      <span className="text-brand flex-shrink-0">{burst}</span>
      <span className="font-light text-xl tracking-tight select-none text-primary">
        <span className="text-brand">tr</span>aced
      </span>
    </span>
  )
}
