import { LogOut, Sparkles } from 'lucide-react'
import { Button } from '../../components/ui/button'
import { t } from '../../i18n'

type AppHeaderProps = {
  username: string
  onLogout: () => void
}

export function AppHeader({ username, onLogout }: AppHeaderProps) {
  return (
    <header className="sticky top-0 z-30 border-b border-slate-200/80 bg-white/90 backdrop-blur">
      <div className="mx-auto flex max-w-6xl items-center justify-between gap-4 px-4 py-4 sm:px-6 lg:px-8">
        <div className="flex items-center gap-3">
          <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-indigo-600 text-white shadow-md shadow-indigo-200">
            <Sparkles size={19} />
          </div>
          <div>
            <p className="text-sm font-bold text-slate-950">{t('document.appTitle')}</p>
            <p className="hidden text-xs text-slate-500 sm:block">{t('app.brandTagline')}</p>
          </div>
        </div>
        <div className="flex items-center gap-2 sm:gap-4">
          <span className="hidden text-sm text-slate-500 sm:inline">{t('app.trainer', { username })}</span>
          <Button variant="ghost" size="sm" onClick={onLogout} aria-label={t('app.logout')}>
            <LogOut size={16} />
            <span className="hidden sm:inline">{t('app.logout')}</span>
          </Button>
        </div>
      </div>
    </header>
  )
}
