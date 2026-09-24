export type ErrorResponse = {
  error: string
  message: string
}

export type Trainer = {
  id: number
  username: string
}

export type Pokemon = {
  pokemonId: number
  name: string
  spriteUrl: string | null
  types: string[]
  inCollection: boolean
}

export type CollectionEntry = {
  entryId: number
  pokemonId: number
  name: string
  spriteUrl: string | null
  types: string[]
  caughtAt: string
}

export type CollectionSort = 'caughtAt' | 'pokemonId' | 'name'
export type CollectionDirection = 'asc' | 'desc'

export type CollectionPage = {
  content: CollectionEntry[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}
