import { useEffect, type ReactNode } from 'react'
import { X } from 'lucide-react'
import { Button } from './button'
import { t } from '../../i18n'

export function Dialog({
  open,
  onOpenChange,
  title,
  description,
  children,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  title: string
  description?: string
  children: ReactNode
}) {
  useEffect(() => {
    if (!open) return
    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === 'Escape') onOpenChange(false)
    }
    window.addEventListener('keydown', handleKeyDown)
    return () => window.removeEventListener('keydown', handleKeyDown)
  }, [open, onOpenChange])

  if (!open) return null

  return (
    <div
      className="fixed inset-0 z-50 grid place-items-center bg-slate-950/40 p-4 backdrop-blur-sm"
      role="presentation"
      onMouseDown={(event) => {
        if (event.target === event.currentTarget) onOpenChange(false)
      }}
    >
      <section
        className="w-full max-w-xl rounded-3xl border border-white/70 bg-white p-6 shadow-2xl"
        role="dialog"
        aria-modal="true"
        aria-labelledby="search-dialog-title"
      >
        <div className="mb-5 flex items-start justify-between gap-4">
          <div>
            <h2 id="search-dialog-title" className="text-xl font-bold text-slate-950">{title}</h2>
            {description && <p className="mt-1 text-sm text-slate-500">{description}</p>}
          </div>
          <Button variant="ghost" size="icon" aria-label={t('common.close')} onClick={() => onOpenChange(false)}>
            <X size={18} />
          </Button>
        </div>
        {children}
      </section>
    </div>
  )
}
