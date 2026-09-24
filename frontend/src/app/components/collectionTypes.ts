export const collectionPageSizes = [6, 18, 30] as const

export const collectionSortOptions = [
  { value: 'caughtAt-desc', label: 'app.sortAddedNewest' },
  { value: 'caughtAt-asc', label: 'app.sortAddedOldest' },
  { value: 'pokemonId-asc', label: 'app.sortPokemonIdAsc' },
  { value: 'pokemonId-desc', label: 'app.sortPokemonIdDesc' },
  { value: 'name-asc', label: 'app.sortNameAsc' },
  { value: 'name-desc', label: 'app.sortNameDesc' },
] as const

export type CollectionSortValue = (typeof collectionSortOptions)[number]['value']
