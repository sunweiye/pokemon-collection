import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AxiosError } from 'axios'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { addToCollection, getCollection, getMe, logout, removeFromCollection, searchPokemon } from '../api/client'
import { makeApiError } from '../test/apiError'
import type { CollectionEntry, CollectionPage, Pokemon } from '../types/api'
import { App } from './App'

vi.mock('../api/client', () => ({
  addToCollection: vi.fn(),
  getCollection: vi.fn(),
  getMe: vi.fn(),
  logout: vi.fn(),
  removeFromCollection: vi.fn(),
  searchPokemon: vi.fn(),
}))

const pokemon: Pokemon = {
  pokemonId: 25,
  name: 'pikachu',
  spriteUrl: null,
  types: ['electric'],
  inCollection: false,
}

const collectionEntry: CollectionEntry = {
  entryId: 1,
  pokemonId: 25,
  name: 'pikachu',
  spriteUrl: null,
  types: ['electric'],
  caughtAt: '2026-09-24T00:00:00Z',
}

function makeCollectionPage(content: CollectionEntry[], page = 0, size = 6, totalElements = content.length, totalPages = 1): CollectionPage {
  return { content, page, size, totalElements, totalPages }
}

function renderApp() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={queryClient}>
      <App />
    </QueryClientProvider>,
  )
}

async function searchForPikachu() {
  const user = userEvent.setup()
  await screen.findByText('My collection')
  await user.click(screen.getByRole('button', { name: 'Add Pokémon' }))
  await user.type(screen.getByRole('textbox', { name: 'Search Pokémon' }), 'pikachu')
  await user.click(screen.getByRole('button', { name: 'Search' }))
  return user
}

describe('App', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(getMe).mockResolvedValue({ id: 1, username: 'ash' })
    vi.mocked(getCollection).mockResolvedValue(makeCollectionPage([]))
    vi.mocked(logout).mockResolvedValue()
    vi.mocked(removeFromCollection).mockResolvedValue()
  })

  afterEach(() => vi.unstubAllGlobals())

  it('disables the add action when search says the Pokemon is already collected', async () => {
    vi.mocked(searchPokemon).mockResolvedValue({ ...pokemon, inCollection: true })
    renderApp()

    await searchForPikachu()

    const addButton = await screen.findByRole('button', { name: 'Already added' })
    expect(addButton).toBeDisabled()
    expect(addToCollection).not.toHaveBeenCalled()
  })

  it('renders the name, number, and types for a successful search', async () => {
    vi.mocked(searchPokemon).mockResolvedValue(pokemon)
    renderApp()

    await searchForPikachu()

    expect(screen.getByText('pikachu')).toBeInTheDocument()
    expect(screen.getByText('#25')).toBeInTheDocument()
    expect(screen.getByText('electric')).toBeInTheDocument()
  })

  it('clears the add dialog after it is closed and reopened', async () => {
    vi.mocked(searchPokemon).mockResolvedValue(pokemon)
    renderApp()

    const user = await searchForPikachu()
    expect(await screen.findByText('pikachu')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Close' }))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Add Pokémon' }))
    expect(screen.getByRole('textbox', { name: 'Search Pokémon' })).toHaveValue('')
    expect(screen.queryByText('pikachu')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Already added' })).not.toBeInTheDocument()
  })

  it('shows a loading action and marks the result as added after success', async () => {
    vi.mocked(searchPokemon).mockResolvedValue(pokemon)
    let resolveAdd!: (entry: CollectionEntry) => void
    vi.mocked(addToCollection).mockReturnValue(new Promise((resolve) => { resolveAdd = resolve }))
    renderApp()

    const user = await searchForPikachu()
    expect(screen.getByText('pikachu')).toBeInTheDocument()
    expect(screen.getByText('#25')).toBeInTheDocument()
    expect(screen.getByText('electric')).toBeInTheDocument()
    await user.click(await screen.findByRole('button', { name: 'Add' }))

    expect(screen.getByRole('button', { name: 'Adding…' })).toBeDisabled()
    resolveAdd(collectionEntry)

    const addedButton = await screen.findByRole('button', { name: 'Already added' })
    expect(addedButton).toBeDisabled()
    expect(await screen.findByRole('status')).toHaveTextContent('Added to your collection.')
  })

  it('refreshes the collection after a successful add', async () => {
    vi.mocked(searchPokemon).mockResolvedValue(pokemon)
    let collectionCalls = 0
    vi.mocked(getCollection).mockImplementation(async () => {
      collectionCalls += 1
      return collectionCalls === 1 ? makeCollectionPage([]) : makeCollectionPage([collectionEntry])
    })
    vi.mocked(addToCollection).mockResolvedValue(collectionEntry)
    renderApp()

    const user = await searchForPikachu()
    await user.click(await screen.findByRole('button', { name: 'Add' }))

    await waitFor(() => expect(getCollection).toHaveBeenCalledTimes(2))
    expect(screen.getAllByText('pikachu')).toHaveLength(2)
  })

  it('covers the collection section while a page request is in progress', async () => {
    let collectionCalls = 0
    let resolveNextPage!: (page: CollectionPage) => void
    vi.mocked(getCollection).mockImplementation(async () => {
      collectionCalls += 1
      if (collectionCalls === 1) return makeCollectionPage([collectionEntry], 0, 6, 7, 2)
      return new Promise((resolve) => { resolveNextPage = resolve })
    })
    renderApp()

    const user = userEvent.setup()
    await screen.findByText('pikachu')
    const pageButton = screen.getByRole('button', { name: 'Go to page 2' })
    pageButton.focus()
    await user.click(pageButton)

    expect(await screen.findByText('Loading collection…')).toBeInTheDocument()
    expect(screen.getByText('My collection').closest('section')).toHaveAttribute('aria-busy', 'true')
    expect(screen.getByText('pikachu')).toBeInTheDocument()
    expect(screen.getByText('7 Pokémon collected')).toBeInTheDocument()
    expect(screen.getByText('Page 2 of 2')).toBeInTheDocument()
    expect(screen.getByText('My collection').closest('section')?.firstElementChild).toHaveProperty('inert', true)

    resolveNextPage(makeCollectionPage([collectionEntry], 1, 6, 7, 2))
    await waitFor(() => expect(screen.queryByText('Loading collection…')).not.toBeInTheDocument())
    expect(document.activeElement).toBe(pageButton)
  })

  it('rolls collection controls back after a failed page request', async () => {
    let collectionCalls = 0
    vi.mocked(getCollection).mockImplementation(async ({ page }) => {
      collectionCalls += 1
      if (collectionCalls === 1) return makeCollectionPage([collectionEntry], 0, 6, 7, 2)
      if (collectionCalls === 2) throw makeApiError(502, 'UPSTREAM_UNAVAILABLE')
      return makeCollectionPage([collectionEntry], page, 6, 7, 2)
    })
    renderApp()

    const user = userEvent.setup()
    await screen.findByText('pikachu')
    await user.click(screen.getByRole('button', { name: 'Go to page 2' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Unable to load your collection right now')
    expect(screen.getByText('pikachu')).toBeInTheDocument()
    expect(screen.getByText('7 Pokémon collected')).toBeInTheDocument()
    expect(screen.getByText('Page 1 of 2')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Go to page 1' })).toHaveAttribute('aria-current', 'page')
    expect(screen.queryByRole('button', { name: 'Retry' })).not.toBeInTheDocument()
    const toast = screen.getAllByRole('alert').find((element) => element.className.includes('fixed'))
    expect(toast).toBeDefined()
    expect(toast).toHaveClass('z-[60]')
    await user.click(within(toast!).getByRole('button', { name: 'Close' }))
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Go to page 2' }))
    await waitFor(() => expect(getCollection).toHaveBeenLastCalledWith(expect.objectContaining({ page: 1 })))
    await waitFor(() => expect(screen.getByText('Page 2 of 2')).toBeInTheDocument())
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('does not toast or roll back collection controls for a 401 response', async () => {
    vi.mocked(getCollection).mockImplementation(async ({ page }) => {
      if (page === 1) throw makeApiError(401, 'UNAUTHORIZED', '/api/collection')
      return makeCollectionPage([collectionEntry], page, 6, 7, 2)
    })
    renderApp()

    const user = userEvent.setup()
    await screen.findByText('pikachu')
    await user.click(screen.getByRole('button', { name: 'Go to page 2' }))

    await waitFor(() => expect(getCollection).toHaveBeenCalledWith(expect.objectContaining({ page: 1 })))
    expect(screen.getByText('Page 2 of 2')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Go to page 2' })).toHaveAttribute('aria-current', 'page')
  })

  it.each(['page', 'sort', 'filter', 'page size'])('keeps the old collection covered while changing %s', async (change) => {
    let collectionCalls = 0
    let resolveNextRequest!: (page: CollectionPage) => void
    vi.mocked(getCollection).mockImplementation(async () => {
      collectionCalls += 1
      if (collectionCalls === 1) return makeCollectionPage([collectionEntry], 0, 6, 7, 2)
      return new Promise((resolve) => { resolveNextRequest = resolve })
    })
    renderApp()

    const user = userEvent.setup()
    await screen.findByText('pikachu')

    const triggerChange = {
      page: () => user.click(screen.getByRole('button', { name: 'Go to page 2' })),
      sort: () => user.selectOptions(screen.getByRole('combobox', { name: 'Sort collection' }), 'name-desc'),
      filter: async () => {
        await user.type(screen.getByRole('textbox', { name: 'Search collection by name' }), 'chu')
        await user.click(screen.getByRole('button', { name: 'Filter' }))
      },
      'page size': () => user.selectOptions(screen.getByRole('combobox', { name: 'Items per page' }), '18'),
    }[change]!

    await triggerChange()
    expect(await screen.findByText('Loading collection…')).toBeInTheDocument()
    expect(screen.getByText('pikachu')).toBeInTheDocument()
    expect(screen.getByText('7 Pokémon collected')).toBeInTheDocument()
    expect(screen.getByText('My collection').closest('section')).toHaveAttribute('aria-busy', 'true')

    resolveNextRequest(makeCollectionPage([collectionEntry], 0, 6, 7, 2))
    await waitFor(() => expect(screen.queryByText('Loading collection…')).not.toBeInTheDocument())
  })

  it.each(['page', 'sort', 'filter', 'page size'])('restores the last successful %s control after a failed request', async (change) => {
    let collectionCalls = 0
    vi.mocked(getCollection).mockImplementation(async () => {
      collectionCalls += 1
      if (collectionCalls === 1) return makeCollectionPage([collectionEntry], 0, 6, 7, 2)
      throw makeApiError(502, 'UPSTREAM_UNAVAILABLE')
    })
    renderApp()

    const user = userEvent.setup()
    await screen.findByText('pikachu')

    const triggerChange = {
      page: () => user.click(screen.getByRole('button', { name: 'Go to page 2' })),
      sort: () => user.selectOptions(screen.getByRole('combobox', { name: 'Sort collection' }), 'name-desc'),
      filter: async () => {
        await user.type(screen.getByRole('textbox', { name: 'Search collection by name' }), 'chu')
        await user.click(screen.getByRole('button', { name: 'Filter' }))
      },
      'page size': () => user.selectOptions(screen.getByRole('combobox', { name: 'Items per page' }), '18'),
    }[change]!

    await triggerChange()
    expect(await screen.findByRole('alert')).toHaveTextContent('Unable to load your collection right now')
    expect(screen.getByText('pikachu')).toBeInTheDocument()
    expect(screen.getByText('Page 1 of 2')).toBeInTheDocument()
    expect(screen.getByRole('combobox', { name: 'Sort collection' })).toHaveValue('caughtAt-desc')
    expect(screen.getByRole('combobox', { name: 'Items per page' })).toHaveValue('6')
    expect(screen.getByRole('textbox', { name: 'Search collection by name' })).toHaveValue('')
    expect(screen.queryByRole('button', { name: 'Clear' })).not.toBeInTheDocument()
  })

  it('handles a duplicate add response and prevents another attempt', async () => {
    vi.mocked(searchPokemon).mockResolvedValue(pokemon)
    const error = makeApiError(409, 'DUPLICATE_COLLECTION_ENTRY', '/api/collection')
    vi.mocked(addToCollection).mockRejectedValue(error)
    renderApp()

    const user = await searchForPikachu()
    await user.click(await screen.findByRole('button', { name: 'Add' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('already in your collection')
    expect(screen.getByRole('button', { name: 'Already added' })).toBeDisabled()
  })

  it('shows a server error when search fails unexpectedly', async () => {
    const error = makeApiError(500, 'INTERNAL_ERROR', '/api/pokemon/search')
    vi.mocked(searchPokemon).mockRejectedValue(error)
    renderApp()

    await searchForPikachu()

    expect(await screen.findByRole('alert')).toHaveTextContent('server could not complete the request')
  })

  it('shows a not-found status without a retry action for a 404 search', async () => {
    vi.mocked(searchPokemon).mockRejectedValue(makeApiError(404, 'NOT_FOUND'))
    renderApp()

    await searchForPikachu()

    expect(await screen.findByRole('status')).toHaveTextContent('No matching Pokémon found')
    expect(screen.queryByRole('button', { name: 'Retry' })).not.toBeInTheDocument()
  })

  it('shows an unavailable message and retries a 502 search', async () => {
    vi.mocked(searchPokemon)
      .mockRejectedValueOnce(makeApiError(502, 'UPSTREAM_UNAVAILABLE'))
      .mockResolvedValueOnce(pokemon)
    renderApp()

    const user = await searchForPikachu()
    expect(await screen.findByRole('alert')).toHaveTextContent('temporarily unavailable')

    await user.click(screen.getByRole('button', { name: 'Retry' }))

    expect(await screen.findByText('pikachu')).toBeInTheDocument()
    expect(searchPokemon).toHaveBeenCalledTimes(2)
  })

  it('shows a network error when search receives no response', async () => {
    vi.mocked(searchPokemon).mockRejectedValue(new AxiosError('Network Error'))
    renderApp()

    await searchForPikachu()

    expect(await screen.findByRole('alert')).toHaveTextContent('Unable to reach the server')
  })

  it('shows the bad-request message for a 400 search response', async () => {
    vi.mocked(searchPokemon).mockRejectedValue(makeApiError(400, 'BAD_REQUEST'))
    renderApp()

    await searchForPikachu()

    expect(await screen.findByRole('alert')).toHaveTextContent('request was invalid')
  })

  it('shows the generic request message for an unknown 4xx response', async () => {
    vi.mocked(searchPokemon).mockRejectedValue(makeApiError(418, 'TEAPOT'))
    renderApp()

    await searchForPikachu()

    expect(await screen.findByRole('alert')).toHaveTextContent('request could not be completed')
  })

  it.each([
    ['METHOD_NOT_ALLOWED', 'This action is not supported'],
    ['UNSUPPORTED_MEDIA_TYPE', 'The request format is not supported'],
    ['NOT_ACCEPTABLE', 'The requested response format is not available'],
  ])('shows the explicit message for %s', async (errorCode, message) => {
    vi.mocked(searchPokemon).mockRejectedValue(makeApiError(400, errorCode))
    renderApp()

    await searchForPikachu()

    expect(await screen.findByRole('alert')).toHaveTextContent(message)
  })

  it('shows a user-facing message when adding throws without an API response', async () => {
    vi.mocked(searchPokemon).mockResolvedValue(pokemon)
    vi.mocked(addToCollection).mockRejectedValue(new Error('Unexpected client error'))
    renderApp()

    const user = await searchForPikachu()
    await user.click(await screen.findByRole('button', { name: 'Add' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Unable to add Pokémon right now')
  })

  it('removes an entry and refreshes the collection', async () => {
    let collectionCalls = 0
    vi.mocked(getCollection).mockImplementation(async () => {
      collectionCalls += 1
      return collectionCalls === 1 ? makeCollectionPage([collectionEntry]) : makeCollectionPage([])
    })
    renderApp()

    const user = userEvent.setup()
    await screen.findByText('pikachu')
    await user.click(screen.getByRole('button', { name: 'Remove pikachu' }))

    await waitFor(() => expect(removeFromCollection).toHaveBeenCalledWith(1))
    expect(await screen.findByText('Your collection is empty')).toBeInTheDocument()
  })

  it('returns to the last valid page after deleting the final item on the last page', async () => {
    const lastPageEntry = { ...collectionEntry, entryId: 2, name: 'eevee', pokemonId: 133 }
    let removed = false
    vi.mocked(getCollection).mockImplementation(async ({ page }) => {
      if (removed) return makeCollectionPage([collectionEntry], 0, 6, 1, 1)
      return page === 0
        ? makeCollectionPage([collectionEntry], 0, 6, 7, 2)
        : makeCollectionPage([lastPageEntry], 1, 6, 7, 2)
    })
    vi.mocked(removeFromCollection).mockImplementation(async () => {
      removed = true
    })
    renderApp()

    const user = userEvent.setup()
    await screen.findByText('pikachu')
    await user.click(screen.getByRole('button', { name: 'Go to page 2' }))
    await screen.findByText('eevee')
    await user.click(screen.getByRole('button', { name: 'Remove eevee' }))

    await waitFor(() => expect(screen.getByText('Page 1 of 1')).toBeInTheDocument())
    expect(screen.getByText('pikachu')).toBeInTheDocument()
    expect(removeFromCollection).toHaveBeenCalledWith(2)
  })

  it('shows a message when removing an entry fails', async () => {
    vi.mocked(getCollection).mockResolvedValue(makeCollectionPage([collectionEntry]))
    vi.mocked(removeFromCollection).mockRejectedValue(makeApiError(500, 'INTERNAL_ERROR'))
    renderApp()

    const user = userEvent.setup()
    await screen.findByText('pikachu')
    await user.click(screen.getByRole('button', { name: 'Remove pikachu' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('server could not complete the request')
  })

  it('shows a collection loading error instead of an empty collection message', async () => {
    vi.mocked(getCollection).mockRejectedValue(makeApiError(502, 'UPSTREAM_UNAVAILABLE'))
    renderApp()

    expect((await screen.findAllByText(/Unable to load your collection right now/)).length).toBeGreaterThan(0)
    expect(screen.queryByText('Your collection is empty')).not.toBeInTheDocument()
    expect(screen.queryByText('0 Pokémon collected')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument()
  })

  it('uses the collection loading message for a collection 404', async () => {
    vi.mocked(getCollection).mockRejectedValue(makeApiError(404, 'NOT_FOUND'))
    renderApp()

    expect((await screen.findAllByText(/Unable to load your collection right now/)).length).toBeGreaterThan(0)
    expect(screen.queryByText('Unable to remove Pokémon right now')).not.toBeInTheDocument()
    expect(screen.queryByText('0 Pokémon collected')).not.toBeInTheDocument()
  })

  it('uses the collection loading message for a non-Axios failure', async () => {
    vi.mocked(getCollection).mockRejectedValue(new Error('Unexpected client error'))
    renderApp()

    expect((await screen.findAllByText(/Unable to load your collection right now/)).length).toBeGreaterThan(0)
    expect(screen.queryByText('Unable to remove Pokémon right now')).not.toBeInTheDocument()
  })

  it('redirects to the login page when the initial session check returns 401', async () => {
    const testWindow = Object.create(window) as Window & typeof globalThis
    Object.defineProperty(testWindow, 'location', { value: { href: '/' }, configurable: true })
    vi.stubGlobal('window', testWindow)
    vi.mocked(getMe).mockRejectedValue(makeApiError(401, 'UNAUTHORIZED', '/api/auth/me'))

    renderApp()

    await waitFor(() => expect(testWindow.location.href).toBe('/login'))
  })

  it('logs out and redirects to the login page', async () => {
    const testWindow = Object.create(window) as Window & typeof globalThis
    Object.defineProperty(testWindow, 'location', { value: { href: '/' }, configurable: true })
    vi.stubGlobal('window', testWindow)
    renderApp()

    const user = userEvent.setup()
    await screen.findByText('My collection')
    await user.click(screen.getByRole('button', { name: 'Log out' }))

    await waitFor(() => expect(logout).toHaveBeenCalledOnce())
    expect(testWindow.location.href).toBe('/login')
  })

  it('shows an error when logout fails and stays on the current page', async () => {
    const testWindow = Object.create(window) as Window & typeof globalThis
    Object.defineProperty(testWindow, 'location', { value: { href: '/' }, configurable: true })
    vi.stubGlobal('window', testWindow)
    vi.mocked(logout).mockRejectedValue(makeApiError(403, 'CSRF_FORBIDDEN', '/api/auth/logout'))
    renderApp()

    const user = userEvent.setup()
    await screen.findByText('My collection')
    await user.click(screen.getByRole('button', { name: 'Log out' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Unable to log out right now')
    expect(testWindow.location.href).toBe('/')
  })

  it('distinguishes an empty collection from an empty filtered result', async () => {
    renderApp()

    const user = userEvent.setup()
    expect(await screen.findByText('Your collection is empty')).toBeInTheDocument()
    await user.type(screen.getByRole('textbox', { name: 'Search collection by name' }), 'chu')
    await user.click(screen.getByRole('button', { name: 'Filter' }))

    expect(await screen.findByText('No matching Pokémon found in your collection.')).toBeInTheDocument()
    expect(screen.queryByText('Your collection is empty')).not.toBeInTheDocument()
  })

  it('clears the collection filter and returns to the first page', async () => {
    vi.mocked(getCollection).mockImplementation(async ({ page, query }) => query
      ? makeCollectionPage([], 0, 6, 0, 1)
      : makeCollectionPage([collectionEntry], page, 6, 7, 2))
    renderApp()

    const user = userEvent.setup()
    await screen.findByText('pikachu')
    await user.click(screen.getByRole('button', { name: 'Go to page 2' }))
    await waitFor(() => expect(screen.getByText('Page 2 of 2')).toBeInTheDocument())
    await user.type(screen.getByRole('textbox', { name: 'Search collection by name' }), 'chu')
    await user.click(screen.getByRole('button', { name: 'Filter' }))
    await screen.findByText('No matching Pokémon found in your collection.')
    await user.click(screen.getByRole('button', { name: 'Clear' }))

    await waitFor(() => expect(screen.getByText('Page 1 of 2')).toBeInTheDocument())
    expect(screen.getByRole('textbox', { name: 'Search collection by name' })).toHaveValue('')
    expect(getCollection).toHaveBeenLastCalledWith(expect.objectContaining({ page: 0, query: '' }))
  })

  it('does not submit a whitespace-only Pokemon search', async () => {
    renderApp()

    const user = userEvent.setup()
    await screen.findByText('My collection')
    await user.click(screen.getByRole('button', { name: 'Add Pokémon' }))
    await user.type(screen.getByRole('textbox', { name: 'Search Pokémon' }), '   ')

    expect(screen.getByRole('button', { name: 'Search' })).toBeDisabled()
    expect(searchPokemon).not.toHaveBeenCalled()
  })

  it('paginates the collection and allows changing the page size', async () => {
    const entries = Array.from({ length: 7 }, (_, index) => ({
      ...collectionEntry,
      entryId: index + 1,
      pokemonId: index + 1,
      name: `pokemon-${index + 1}`,
    }))
    vi.mocked(getCollection).mockImplementation(async ({ size }) => size === 6
      ? makeCollectionPage(entries.slice(0, 6), 0, 6, 7, 2)
      : makeCollectionPage(entries, 0, size, 7, 1))
    renderApp()

    const user = userEvent.setup()
    expect(await screen.findByText('pokemon-1')).toBeInTheDocument()
    expect(screen.getByText('pokemon-6')).toBeInTheDocument()
    expect(screen.queryByText('pokemon-7')).not.toBeInTheDocument()
    expect(screen.getByText('Page 1 of 2')).toBeInTheDocument()

    await user.selectOptions(screen.getByRole('combobox', { name: 'Items per page' }), '18')

    expect(screen.getByText('pokemon-7')).toBeInTheDocument()
    expect(screen.getByText('Page 1 of 1')).toBeInTheDocument()
  })

  it('filters the local collection by name and sends the selected sort', async () => {
    vi.mocked(getCollection).mockResolvedValue(makeCollectionPage([collectionEntry]))
    renderApp()

    const user = userEvent.setup()
    await screen.findByText('pikachu')
    await user.type(screen.getByRole('textbox', { name: 'Search collection by name' }), 'chu')
    await user.click(screen.getByRole('button', { name: 'Filter' }))

    await waitFor(() => expect(getCollection).toHaveBeenLastCalledWith(expect.objectContaining({ query: 'chu' })))
    await user.selectOptions(screen.getByRole('combobox', { name: 'Sort collection' }), 'name-desc')
    await waitFor(() => expect(getCollection).toHaveBeenLastCalledWith(expect.objectContaining({ sort: 'name', direction: 'desc' })))
  })

  it('shows first, last, and nearby page controls with a two-page margin', async () => {
    const entries = Array.from({ length: 6 }, (_, index) => ({
      ...collectionEntry,
      entryId: index + 1,
      pokemonId: index + 1,
      name: `pokemon-${index + 1}`,
    }))
    vi.mocked(getCollection).mockImplementation(async ({ page }) => makeCollectionPage(entries, page, 6, 60, 10))
    renderApp()

    const user = userEvent.setup()
    await screen.findByText('pokemon-1')
    expect(screen.getByRole('button', { name: 'First page' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Last page' })).not.toBeDisabled()
    expect(screen.getByRole('button', { name: 'Go to page 1' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Go to page 2' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Go to page 3' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Go to page 4' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Go to page 10' })).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Go to page 3' }))

    await waitFor(() => expect(screen.getByText('Page 3 of 10')).toBeInTheDocument())
    await waitFor(() => expect(getCollection).toHaveBeenLastCalledWith(expect.objectContaining({ page: 2 })))
    expect(screen.getByRole('button', { name: 'Go to page 5' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Go to page 6' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'First page' })).not.toBeDisabled()

    await user.click(screen.getByRole('button', { name: 'Next' }))
    await waitFor(() => expect(screen.getByText('Page 4 of 10')).toBeInTheDocument())
    expect(getCollection).toHaveBeenLastCalledWith(expect.objectContaining({ page: 3 }))
    await user.click(screen.getByRole('button', { name: 'Previous' }))
    await waitFor(() => expect(screen.getByText('Page 3 of 10')).toBeInTheDocument())
    await user.click(screen.getByRole('button', { name: 'Last page' }))
    await waitFor(() => expect(screen.getByText('Page 10 of 10')).toBeInTheDocument())
    expect(screen.getByRole('button', { name: 'Next' })).toBeDisabled()
  })
})
