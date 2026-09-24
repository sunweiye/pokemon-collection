import { ChevronLeft, ChevronRight, ChevronsLeft, ChevronsRight } from 'lucide-react'
import { Button } from '../../components/ui/button'
import { t } from '../../i18n'

type PaginationItem = number | 'ellipsis-start' | 'ellipsis-end'

function getPaginationItems(currentPage: number, totalPages: number): PaginationItem[] {
  const startPage = Math.max(2, currentPage - 2)
  const endPage = Math.min(totalPages - 1, currentPage + 2)
  const items: PaginationItem[] = [1]

  if (startPage > 2) items.push('ellipsis-start')
  for (let page = startPage; page <= endPage; page += 1) items.push(page)
  if (endPage < totalPages - 1) items.push('ellipsis-end')
  if (totalPages > 1) items.push(totalPages)

  return items
}

type CollectionPaginationProps = {
  currentPage: number
  totalPages: number
  onPageChange: (page: number) => void
}

export function CollectionPagination({ currentPage, totalPages, onPageChange }: CollectionPaginationProps) {
  return (
    <div className="mt-6 flex flex-col gap-3 border-t border-slate-100 pt-5 sm:flex-row sm:items-center sm:justify-between">
      <p className="text-sm text-slate-500">{t('app.pageIndicator', { page: currentPage, total: totalPages })}</p>
      <div className="flex flex-wrap items-center justify-center gap-2 sm:justify-end">
        <Button variant="secondary" size="sm" aria-label={t('app.firstPage')} onClick={() => onPageChange(1)} disabled={currentPage === 1}>
          <ChevronsLeft size={16} />
        </Button>
        <Button variant="secondary" size="sm" aria-label={t('app.previousPage')} onClick={() => onPageChange(currentPage - 1)} disabled={currentPage === 1}>
          <ChevronLeft size={16} />
        </Button>
        <div className="flex items-center gap-1">
          {getPaginationItems(currentPage, totalPages).map((item) => item === 'ellipsis-start' || item === 'ellipsis-end'
            ? <span className="px-1 text-sm text-slate-400" aria-hidden="true" key={item}>…</span>
            : <Button
                key={item}
                variant={item === currentPage ? 'primary' : 'secondary'}
                size="sm"
                aria-label={t('app.pageNumber', { page: item })}
                aria-current={item === currentPage ? 'page' : undefined}
                onClick={() => onPageChange(item)}
              >
                {item}
              </Button>)}
        </div>
        <Button variant="secondary" size="sm" aria-label={t('app.nextPage')} onClick={() => onPageChange(currentPage + 1)} disabled={currentPage === totalPages}>
          <ChevronRight size={16} />
        </Button>
        <Button variant="secondary" size="sm" aria-label={t('app.lastPage')} onClick={() => onPageChange(totalPages)} disabled={currentPage === totalPages}>
          <ChevronsRight size={16} />
        </Button>
      </div>
    </div>
  )
}
