import { LoaderCircle, Plus, Sparkles } from 'lucide-react'
import { useEffect, useRef } from 'react'
import { Button } from '../../components/ui/button'
import { Card, CardContent, CardHeader } from '../../components/ui/card'
import { t } from '../../i18n'
import type { CollectionEntry } from '../../types/api'
import { CollectionGrid } from './CollectionGrid'
import { CollectionPagination } from './CollectionPagination'
import { CollectionToolbar } from './CollectionToolbar'
import type { CollectionSortValue } from './collectionTypes'

export type { CollectionSortValue } from './collectionTypes'

type CollectionCardProps = {
  entries: CollectionEntry[]
  totalElements: number
  hasData: boolean
  isLoading: boolean
  collectionErrorMessage: string
  errorMessage: string
  searchInput: string
  searchTerm: string
  sortValue: CollectionSortValue
  pageSize: number
  currentPage: number
  totalPages: number
  removePending: boolean
  onAddPokemon: () => void
  onSearchInputChange: (value: string) => void
  onSearch: () => void
  onClearSearch: () => void
  onSortChange: (value: CollectionSortValue) => void
  onPageSizeChange: (value: number) => void
  onPageChange: (page: number) => void
  onRetry: () => void
  onRemove: (entry: CollectionEntry) => void
}

export function CollectionCard({
  entries,
  totalElements,
  hasData,
  isLoading,
  collectionErrorMessage,
  errorMessage,
  searchInput,
  searchTerm,
  sortValue,
  pageSize,
  currentPage,
  totalPages,
  removePending,
  onAddPokemon,
  onSearchInputChange,
  onSearch,
  onClearSearch,
  onSortChange,
  onPageSizeChange,
  onPageChange,
  onRetry,
  onRemove,
}: CollectionCardProps) {
  const contentRef = useRef<HTMLDivElement>(null)
  const focusRestoreRef = useRef<HTMLElement | null>(null)

  useEffect(() => {
    const content = contentRef.current
    if (!content) return

    if (isLoading) {
      const activeElement = document.activeElement
      if (activeElement instanceof HTMLElement && content.contains(activeElement)) {
        focusRestoreRef.current = activeElement
      }
      content.inert = true
      return
    }

    content.inert = false
    const focusTarget = focusRestoreRef.current
    if (focusTarget && document.contains(focusTarget)) focusTarget.focus()
    focusRestoreRef.current = null
  }, [isLoading])

  return (
    <Card className="relative overflow-hidden" aria-busy={isLoading}>
      <div ref={contentRef} className={isLoading ? 'pointer-events-none' : undefined}>
        <CardHeader className="flex flex-col gap-4 border-b border-slate-100">
          <div className="flex items-center justify-between gap-3">
            <div>
              <h2 className="text-xl font-bold text-slate-950">{t('app.myCollection')}</h2>
              {hasData && <p className="mt-1 text-sm text-slate-500">{t('app.collectionCount', { count: totalElements })}</p>}
            </div>
            <Button variant="secondary" size="sm" onClick={onAddPokemon}>
              <Plus size={16} /> {t('app.addPokemon')}
            </Button>
          </div>
          <CollectionToolbar
            searchInput={searchInput}
            searchTerm={searchTerm}
            sortValue={sortValue}
            pageSize={pageSize}
            onSearchInputChange={onSearchInputChange}
            onSearch={onSearch}
            onClearSearch={onClearSearch}
            onSortChange={onSortChange}
            onPageSizeChange={onPageSizeChange}
          />
        </CardHeader>
        <CardContent className="pt-6">
          {!hasData && collectionErrorMessage && (
            <div className="mb-4 flex items-center justify-between gap-3 rounded-xl bg-rose-50 px-4 py-3 text-sm text-rose-700" role="alert">
              <span>{collectionErrorMessage}</span>
              <Button type="button" variant="ghost" size="sm" onClick={onRetry} disabled={isLoading}>{t('app.retry')}</Button>
            </div>
          )}
          {errorMessage && <div className="mb-4 rounded-xl bg-rose-50 px-4 py-3 text-sm text-rose-700" role="alert">{errorMessage}</div>}
          {hasData && entries.length === 0 && <EmptyCollection searchTerm={searchTerm} />}
          {hasData && entries.length > 0 && <CollectionGrid entries={entries} removePending={removePending} onRemove={onRemove} />}
          {hasData && (entries.length > 0 || totalElements > 0) && <CollectionPagination currentPage={currentPage} totalPages={totalPages} onPageChange={onPageChange} />}
        </CardContent>
      </div>
      {isLoading && (
        <div className="absolute inset-0 z-20 flex items-center justify-center bg-slate-900/20 px-6 backdrop-blur-[1px]" role="status" aria-live="polite">
          <div className="flex items-center gap-3 rounded-2xl bg-white/95 px-5 py-4 text-sm font-semibold text-slate-700 shadow-lg">
            <LoaderCircle size={19} className="animate-spin text-indigo-600" aria-hidden="true" />
            <span>{t('app.loadingCollection')}</span>
          </div>
        </div>
      )}
    </Card>
  )
}

function EmptyCollection({ searchTerm }: { searchTerm: string }) {
  return (
    <div className="rounded-2xl border border-dashed border-slate-200 bg-slate-50 px-6 py-12 text-center">
      <Sparkles className="mx-auto mb-3 text-indigo-400" size={28} />
      <p className="font-semibold text-slate-700">{searchTerm ? t('app.noCollectionResults') : t('app.emptyTitle')}</p>
      {!searchTerm && <p className="mt-1 text-sm text-slate-500">{t('app.emptyDescription')}</p>}
    </div>
  )
}
