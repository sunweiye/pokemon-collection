import { X } from 'lucide-react'
import { t } from '../../i18n'

type ToastProps = {
  message: string
  onClose: () => void
}

export function Toast({ message, onClose }: ToastProps) {
  return (
    <div className="fixed inset-x-4 top-5 z-[60] mx-auto flex max-w-md items-start justify-between gap-4 rounded-2xl border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-800 shadow-xl" role="alert" aria-live="assertive">
      <span>{message}</span>
      <button type="button" className="shrink-0 rounded-lg p-1 text-rose-600 hover:bg-rose-100" onClick={onClose} aria-label={t('common.close')}>
        <X size={17} />
      </button>
    </div>
  )
}
