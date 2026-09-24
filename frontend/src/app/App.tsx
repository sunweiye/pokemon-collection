import axios from 'axios'
import { useEffect, useMemo, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { addToCollection, getCollection, getMe, logout, removeFromCollection, searchPokemon } from '../api/client'
import { t } from '../i18n'
import type { CollectionDirection, CollectionEntry, CollectionPage, CollectionSort, ErrorResponse, Pokemon } from '../types/api'
import { AppHeader } from './components/AppHeader'
import { CollectionCard, type CollectionSortValue } from './components/CollectionCard'
import { collectionPageSizes } from './components/collectionTypes'
import { HeroBanner } from './components/HeroBanner'
import { PokemonSearchDialog } from './components/PokemonSearchDialog'
import { Toast } from '../components/ui/toast'

type ApiErrorContext = 'search' | 'add' | 'collection' | 'collectionLoad' | 'logout'

type CollectionViewState = {
  page: number
  pageSize: (typeof collectionPageSizes)[number]
  searchInput: string
  searchTerm: string
  sortValue: CollectionSortValue
}

function getApiErrorCode(error: unknown): string | undefined {
  return axios.isAxiosError<ErrorResponse>(error) ? error.response?.data?.error : undefined
}

function isUnauthorized(error: unknown): boolean {
  return axios.isAxiosError(error) && error.response?.status === 401
}

function getApiErrorMessage(error: unknown, context: ApiErrorContext): string {
  if (!axios.isAxiosError<ErrorResponse>(error)) {
    if (context === 'search') return t('app.searchError')
    if (context === 'collectionLoad') return t('app.collectionLoadError')
    if (context === 'logout') return t('app.logoutError')
    return context === 'collection' ? t('app.removeError') : t('app.addError')
  }

  if (!error.response) {
    return context === 'logout' ? t('app.logoutError') : t('app.networkError')
  }

  if (context === 'collectionLoad' && error.response.status === 401) return ''

  const errorCode = error.response.data?.error
  if (errorCode === 'NOT_FOUND') {
    if (context === 'search') return t('app.searchNotFound')
    if (context === 'collectionLoad') return t('app.collectionLoadError')
    return context === 'collection' ? t('app.removeError') : t('app.addError')
  }
  if (errorCode === 'UPSTREAM_UNAVAILABLE') {
    if (context === 'search') return t('app.searchUnavailable')
    if (context === 'collectionLoad') return t('app.collectionLoadError')
    return context === 'logout' ? t('app.logoutError') : t('app.serverError')
  }
  if (errorCode === 'CSRF_FORBIDDEN' && context === 'logout') {
    return t('app.logoutError')
  }
  if (errorCode === 'DUPLICATE_COLLECTION_ENTRY') {
    return t('app.addDuplicate')
  }
  if (errorCode === 'BAD_REQUEST') {
    return t('app.badRequest')
  }
  if (errorCode === 'METHOD_NOT_ALLOWED') {
    return t('app.methodNotAllowed')
  }
  if (errorCode === 'UNSUPPORTED_MEDIA_TYPE') {
    return t('app.unsupportedMediaType')
  }
  if (errorCode === 'NOT_ACCEPTABLE') {
    return t('app.notAcceptable')
  }
  if (errorCode === 'INTERNAL_ERROR' || error.response.status >= 500) {
    if (context === 'collectionLoad') return t('app.collectionLoadError')
    return context === 'logout' ? t('app.logoutError') : t('app.serverError')
  }
  if (context === 'logout') {
    return t('app.logoutError')
  }
  return t('app.requestError')
}

export function App() {
  const queryClient = useQueryClient()
  const [submittedQuery, setSubmittedQuery] = useState('')
  const [input, setInput] = useState('')
  const [searchOpen, setSearchOpen] = useState(false)
  const [addMessage, setAddMessage] = useState('')
  const [addError, setAddError] = useState('')
  const [collectionError, setCollectionError] = useState('')
  const [collectionToast, setCollectionToast] = useState('')
  const [logoutError, setLogoutError] = useState('')
  const [collectionPage, setCollectionPage] = useState(1)
  const [collectionPageSize, setCollectionPageSize] = useState<(typeof collectionPageSizes)[number]>(6)
  const [collectionSearchInput, setCollectionSearchInput] = useState('')
  const [collectionSearchTerm, setCollectionSearchTerm] = useState('')
  const [collectionSortValue, setCollectionSortValue] = useState<CollectionSortValue>('caughtAt-desc')
  const [lastCollectionData, setLastCollectionData] = useState<CollectionPage>()
  const [lastSuccessfulCollectionState, setLastSuccessfulCollectionState] = useState<CollectionViewState>({
    page: 1,
    pageSize: 6,
    searchInput: '',
    searchTerm: '',
    sortValue: 'caughtAt-desc',
  })

  const collectionQueryKey = useMemo(
    () => ['collection', collectionPage, collectionPageSize, collectionSearchTerm, collectionSortValue] as const,
    [collectionPage, collectionPageSize, collectionSearchTerm, collectionSortValue],
  )
  const [, queryPage, queryPageSize, querySearchTerm, querySortValue] = collectionQueryKey
  const [querySort, queryDirection] = querySortValue.split('-') as [CollectionSort, CollectionDirection]

  const meQuery = useQuery({ queryKey: ['me'], queryFn: getMe, retry: false })
  const collectionQuery = useQuery({
    queryKey: collectionQueryKey,
    queryFn: () => getCollection({
      page: queryPage - 1,
      size: queryPageSize,
      query: querySearchTerm,
      sort: querySort,
      direction: queryDirection,
    }),
    enabled: meQuery.isSuccess,
    refetchOnWindowFocus: false,
    retry: false,
  })
  const searchQuery = useQuery({
    queryKey: ['pokemon', submittedQuery],
    queryFn: () => searchPokemon(submittedQuery),
    enabled: submittedQuery.length > 0,
    retry: false,
  })

  useEffect(() => {
    if (meQuery.isError) window.location.href = '/login'
  }, [meQuery.isError])

  useEffect(() => {
    if (!collectionQuery.data) return
    const [, successfulPage, successfulPageSize, successfulSearchTerm, successfulSortValue] = collectionQueryKey
    setLastCollectionData(collectionQuery.data)
    setLastSuccessfulCollectionState({
      page: Math.min(successfulPage, Math.max(1, collectionQuery.data.totalPages)),
      pageSize: successfulPageSize,
      searchInput: successfulSearchTerm,
      searchTerm: successfulSearchTerm,
      sortValue: successfulSortValue,
    })
  }, [collectionQuery.data, collectionQueryKey])

  const collectionData = collectionQuery.data ?? lastCollectionData
  const collectionEntries = collectionData?.content ?? []
  const collectionPageCount = Math.max(1, collectionData?.totalPages ?? 1)
  const collectionLoadError = collectionQuery.isError
    ? getApiErrorMessage(collectionQuery.error, 'collectionLoad')
    : ''

  useEffect(() => {
    if (!collectionQuery.data) return
    setCollectionPage((currentPage) => Math.min(currentPage, collectionPageCount))
  }, [collectionPageCount, collectionQuery.data])

  useEffect(() => {
    if (!collectionQuery.isError || isUnauthorized(collectionQuery.error)) return

    setCollectionPage(lastSuccessfulCollectionState.page)
    setCollectionPageSize(lastSuccessfulCollectionState.pageSize)
    setCollectionSearchInput(lastSuccessfulCollectionState.searchInput)
    setCollectionSearchTerm(lastSuccessfulCollectionState.searchTerm)
    setCollectionSortValue(lastSuccessfulCollectionState.sortValue)
    setCollectionToast(collectionLoadError)
  }, [collectionLoadError, collectionQuery.error, collectionQuery.isError, lastSuccessfulCollectionState])

  function clearCollectionToast() {
    setCollectionToast('')
  }

  function handleCollectionPageChange(page: number) {
    clearCollectionToast()
    setCollectionPage(page)
  }

  function handleCollectionSearch() {
    clearCollectionToast()
    setCollectionSearchTerm(collectionSearchInput.trim())
    setCollectionPage(1)
  }

  function handleClearCollectionSearch() {
    clearCollectionToast()
    setCollectionSearchInput('')
    setCollectionSearchTerm('')
    setCollectionPage(1)
  }

  function handleSortChange(value: CollectionSortValue) {
    clearCollectionToast()
    setCollectionSortValue(value)
    setCollectionPage(1)
  }

  function handlePageSizeChange(value: number) {
    clearCollectionToast()
    setCollectionPageSize(value as (typeof collectionPageSizes)[number])
    setCollectionPage(1)
  }

  function handleCollectionRetry() {
    clearCollectionToast()
    void collectionQuery.refetch()
  }

  const addMutation = useMutation({
    mutationFn: (pokemon: Pokemon) => addToCollection(pokemon.pokemonId),
    onSuccess: async (entry) => {
      setAddMessage(t('app.addSuccess'))
      setAddError('')
      clearCollectionToast()
      setPokemonCollectionStatus(entry.pokemonId, true)
      await queryClient.invalidateQueries({ queryKey: ['collection'] })
    },
    onError: (error, pokemon) => {
      setAddMessage('')
      if (getApiErrorCode(error) === 'DUPLICATE_COLLECTION_ENTRY') {
        setPokemonCollectionStatus(pokemon.pokemonId, true)
      }
      setAddError(getApiErrorMessage(error, 'add'))
    },
  })

  const removeMutation = useMutation({
    mutationFn: (entry: CollectionEntry) => removeFromCollection(entry.entryId),
    onSuccess: async (_, entry) => {
      setCollectionError('')
      clearCollectionToast()
      setPokemonCollectionStatus(entry.pokemonId, false)
      await queryClient.invalidateQueries({ queryKey: ['collection'] })
    },
    onError: (error) => {
      setCollectionError(getApiErrorMessage(error, 'collection'))
    },
  })

  function setPokemonCollectionStatus(pokemonId: number, inCollection: boolean) {
    queryClient.setQueriesData<Pokemon>({ queryKey: ['pokemon'] }, (pokemon) => {
      if (!pokemon || pokemon.pokemonId !== pokemonId) return pokemon
      return { ...pokemon, inCollection }
    })
  }

  async function handleLogout() {
    setLogoutError('')
    try {
      await logout()
      window.location.href = '/login'
    } catch (error) {
      setLogoutError(getApiErrorMessage(error, 'logout'))
    }
  }

  function handleSearch() {
    const normalizedQuery = input.trim()
    if (!normalizedQuery) return
    setAddMessage('')
    setAddError('')
    setSubmittedQuery(normalizedQuery)
  }

  function handleAddPokemon() {
    if (!searchQuery.data) return
    setAddMessage('')
    setAddError('')
    addMutation.mutate(searchQuery.data)
  }

  function handleSearchDialogOpenChange(open: boolean) {
    setSearchOpen(open)
    if (!open) {
      setInput('')
      setSubmittedQuery('')
      setAddMessage('')
      setAddError('')
    }
  }

  if (meQuery.isLoading || !meQuery.data) {
    return <main className="flex min-h-screen items-center justify-center bg-slate-50 text-sm text-slate-500">{t('app.loading')}</main>
  }

  const searchErrorCode = getApiErrorCode(searchQuery.error)
  const searchErrorMessage = searchQuery.isError ? getApiErrorMessage(searchQuery.error, 'search') : ''

  
  return (
    <main className="min-h-screen bg-slate-50 text-slate-950">
      <AppHeader username={meQuery.data.username} onLogout={handleLogout} />
      <div className="mx-auto max-w-6xl px-4 py-8 sm:px-6 lg:px-8 lg:py-12">
        {logoutError && <div className="mb-5 rounded-xl bg-rose-50 px-4 py-3 text-sm text-rose-700" role="alert">{logoutError}</div>}
        <HeroBanner />
        <CollectionCard
          entries={collectionEntries}
          totalElements={collectionData?.totalElements ?? 0}
          hasData={Boolean(collectionData)}
          isLoading={collectionQuery.isFetching}
          collectionErrorMessage={collectionLoadError}
          errorMessage={collectionError}
          searchInput={collectionSearchInput}
          searchTerm={collectionSearchTerm}
          sortValue={collectionSortValue}
          pageSize={collectionPageSize}
          currentPage={collectionPage}
          totalPages={collectionPageCount}
          removePending={removeMutation.isPending}
          onAddPokemon={() => setSearchOpen(true)}
          onSearchInputChange={setCollectionSearchInput}
          onSearch={handleCollectionSearch}
          onClearSearch={handleClearCollectionSearch}
          onSortChange={handleSortChange}
          onPageSizeChange={handlePageSizeChange}
          onPageChange={handleCollectionPageChange}
          onRetry={handleCollectionRetry}
          onRemove={(entry) => removeMutation.mutate(entry)}
        />
      </div>
      {collectionToast && <Toast message={collectionToast} onClose={() => setCollectionToast('')} />}
      <PokemonSearchDialog
        open={searchOpen}
        input={input}
        isFetching={searchQuery.isFetching}
        isAdding={addMutation.isPending}
        pokemon={searchQuery.data}
        errorCode={searchErrorCode}
        errorMessage={searchErrorMessage}
        addMessage={addMessage}
        addError={addError}
        onOpenChange={handleSearchDialogOpenChange}
        onInputChange={setInput}
        onSearch={handleSearch}
        onRetry={() => searchQuery.refetch()}
        onAdd={handleAddPokemon}
      />
    </main>
  )
}
