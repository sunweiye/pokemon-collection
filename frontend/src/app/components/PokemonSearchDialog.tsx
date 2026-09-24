import { Search } from 'lucide-react'
import { Button } from '../../components/ui/button'
import { Dialog } from '../../components/ui/dialog'
import { Input } from '../../components/ui/input'
import { t } from '../../i18n'
import type { Pokemon } from '../../types/api'
import { PokemonResult } from './PokemonResult'

type PokemonSearchDialogProps = {
  open: boolean
  input: string
  isFetching: boolean
  isAdding: boolean
  pokemon?: Pokemon
  errorCode?: string
  errorMessage: string
  addMessage: string
  addError: string
  onOpenChange: (open: boolean) => void
  onInputChange: (value: string) => void
  onSearch: () => void
  onRetry: () => void
  onAdd: () => void
}

export function PokemonSearchDialog({
  open,
  input,
  isFetching,
  isAdding,
  pokemon,
  errorCode,
  errorMessage,
  addMessage,
  addError,
  onOpenChange,
  onInputChange,
  onSearch,
  onRetry,
  onAdd,
}: PokemonSearchDialogProps) {
  function handleSubmit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault()
    onSearch()
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange} title={t('app.searchDialogTitle')} description={t('app.searchDialogDescription')}>
      <form className="flex flex-col gap-3 sm:flex-row" onSubmit={handleSubmit}>
        <Input autoFocus value={input} onChange={(event) => onInputChange(event.target.value)} placeholder={t('app.searchPlaceholder')} aria-label={t('app.searchAria')} />
        <Button type="submit" className="shrink-0" disabled={!input.trim() || isFetching}>
          <Search size={16} /> {isFetching ? t('app.searching') : t('app.search')}
        </Button>
      </form>
      {errorMessage && (
        <div className="mt-4 flex items-center justify-between gap-3 rounded-xl bg-rose-50 px-4 py-3 text-sm text-rose-700" role={errorCode === 'NOT_FOUND' ? 'status' : 'alert'}>
          <span>{errorMessage}</span>
          {errorCode === 'UPSTREAM_UNAVAILABLE' && <Button type="button" variant="ghost" size="sm" onClick={onRetry} disabled={isFetching}>{t('app.retry')}</Button>}
        </div>
      )}
      {pokemon && <PokemonResult pokemon={pokemon} onAdd={onAdd} isAdding={isAdding} />}
      {addMessage && <div className="mt-4 rounded-xl bg-emerald-50 px-4 py-3 text-sm text-emerald-700" role="status">{addMessage}</div>}
      {addError && <div className="mt-4 rounded-xl bg-rose-50 px-4 py-3 text-sm text-rose-700" role="alert">{addError}</div>}
    </Dialog>
  )
}
