import { Search } from 'lucide-react'
import type { FormEvent } from 'react'
import { Button } from '../../components/ui/button'
import { Input } from '../../components/ui/input'
import { t } from '../../i18n'
import { collectionPageSizes, collectionSortOptions, type CollectionSortValue } from './collectionTypes'

type CollectionToolbarProps = {
  searchInput: string
  searchTerm: string
  sortValue: CollectionSortValue
  pageSize: number
  onSearchInputChange: (value: string) => void
  onSearch: () => void
  onClearSearch: () => void
  onSortChange: (value: CollectionSortValue) => void
  onPageSizeChange: (value: number) => void
}

export function CollectionToolbar({
  searchInput,
  searchTerm,
  sortValue,
  pageSize,
  onSearchInputChange,
  onSearch,
  onClearSearch,
  onSortChange,
  onPageSizeChange,
}: CollectionToolbarProps) {
  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    onSearch()
  }

  return (
    <div className="flex flex-col gap-3 xl:flex-row xl:items-center xl:justify-between">
      <form className="flex min-w-0 flex-1 gap-2" onSubmit={handleSubmit}>
        <Input
          value={searchInput}
          onChange={(event) => onSearchInputChange(event.target.value)}
          placeholder={t('app.collectionSearchPlaceholder')}
          aria-label={t('app.collectionSearchAria')}
        />
        <Button type="submit" variant="secondary" size="sm">
          <Search size={16} /> {t('app.collectionFilter')}
        </Button>
        {searchTerm && (
          <Button type="button" variant="ghost" size="sm" onClick={onClearSearch}>
            {t('app.clearCollectionSearch')}
          </Button>
        )}
      </form>
      <div className="flex flex-col items-stretch gap-3 sm:flex-row sm:items-center">
        <label className="flex items-center gap-2 text-sm text-slate-500">
          <span>{t('app.collectionSort')}</span>
          <select
            className="min-w-48 rounded-lg border border-slate-200 bg-white px-2 py-1.5 text-sm text-slate-700 outline-none focus:border-indigo-400 focus:ring-2 focus:ring-indigo-100"
            aria-label={t('app.collectionSort')}
            value={sortValue}
            onChange={(event) => onSortChange(event.target.value as CollectionSortValue)}
          >
            {collectionSortOptions.map((option) => <option key={option.value} value={option.value}>{t(option.label)}</option>)}
          </select>
        </label>
        <label className="flex items-center gap-2 text-sm text-slate-500">
          <span>{t('app.collectionPageSize')}</span>
          <select
            className="rounded-lg border border-slate-200 bg-white px-2 py-1.5 text-sm text-slate-700 outline-none focus:border-indigo-400 focus:ring-2 focus:ring-indigo-100"
            aria-label={t('app.collectionPageSize')}
            value={pageSize}
            onChange={(event) => onPageSizeChange(Number(event.target.value))}
          >
            {collectionPageSizes.map((size) => <option key={size} value={size}>{size}</option>)}
          </select>
        </label>
      </div>
    </div>
  )
}
