import { forwardRef, type ButtonHTMLAttributes, type HTMLAttributes, type ReactNode } from 'react'
import { AlertCircle, Inbox, LoaderCircle } from 'lucide-react'

type ButtonProps = Omit<ButtonHTMLAttributes<HTMLButtonElement>, 'type'> & {
  variant?: 'default' | 'primary' | 'quiet'
  size?: 'small' | 'normal'
  loading?: boolean
  selected?: boolean
  danger?: boolean
  icon?: ReactNode
  type?: 'button' | 'submit' | 'reset'
}

/** Native button behavior; loading is disabled too, so repeat clicks cannot submit. */
export const UiButton = forwardRef<HTMLButtonElement, ButtonProps>(function UiButton(
  { variant = 'default', size = 'normal', loading = false, selected, danger, icon, children, className = '', disabled, type = 'button', ...props }, ref
) {
  return <button {...props} ref={ref} type={type} className={`ofs-button ${className}`}
    data-variant={variant} data-size={size} data-selected={selected} data-danger={danger}
    aria-pressed={selected} aria-busy={loading || undefined} disabled={disabled || loading}>
    {loading ? <LoaderCircle size={14} className="ofs-loading-mark" aria-hidden="true" /> : icon}
    {children}
  </button>
})

/** Tooltips may wrap this component: refs and injected event handlers reach the button. */
export const IconButton = forwardRef<HTMLButtonElement, ButtonProps & { label: string }>(function IconButton(
  { label, className = '', ...props }, ref
) {
  return <UiButton variant="quiet" {...props} ref={ref} aria-label={label} className={`ofs-icon-button ${className}`} />
})

export function PanelHeading({ title, description, actions, className = '' }: {
  title: ReactNode; description?: ReactNode; actions?: ReactNode; className?: string
}): React.JSX.Element {
  return <header className={`ofs-panel-heading ${className}`}>
    <div className="ofs-panel-heading-copy"><h2>{title}</h2>{description && <p>{description}</p>}</div>
    {actions && <div className="ofs-panel-actions">{actions}</div>}
  </header>
}

export function SurfaceCard({ className = '', ...props }: HTMLAttributes<HTMLElement>): React.JSX.Element {
  return <section {...props} className={`ofs-card ${className}`} />
}

export function ActionCard({ className = '', ...props }: ButtonHTMLAttributes<HTMLButtonElement>): React.JSX.Element {
  return <button type="button" {...props} className={`ofs-card ${className}`} />
}

export function EmptyState({ title, tone = 'neutral', action, icon }: {
  title: ReactNode; tone?: 'neutral' | 'error'; action?: ReactNode; icon?: ReactNode
}): React.JSX.Element {
  return <div className="ofs-empty" data-tone={tone} role={tone === 'error' ? 'alert' : undefined}>
    {icon ?? (tone === 'error' ? <AlertCircle size={24} aria-hidden="true" /> : <Inbox size={24} aria-hidden="true" />)}
    <div>{title}</div>{action}
  </div>
}

export function LoadingState({ label, rows = 4 }: { label: string; rows?: number }): React.JSX.Element {
  return <div className="ofs-loading" role="status" aria-label={label} aria-busy="true">
    {Array.from({ length: rows }, (_, i) => <span key={i} className="ofs-skeleton-line" aria-hidden="true" />)}
  </div>
}

export function StatusBadge({ children, tone = 'neutral' }: {
  children: ReactNode; tone?: 'neutral' | 'success' | 'warning' | 'error'
}): React.JSX.Element {
  return <span className="ofs-status" data-tone={tone}>{children}</span>
}
