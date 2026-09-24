import { http } from './http'
import type { CollectionDirection, CollectionEntry, CollectionPage, CollectionSort, Pokemon, Trainer } from '../types/api'

export async function login(username: string, password: string): Promise<Trainer> {
  const response = await http.post<Trainer>('/api/auth/login', { username, password })
  return response.data
}

export async function getMe(): Promise<Trainer> {
  const response = await http.get<Trainer>('/api/auth/me')
  return response.data
}

export async function logout(): Promise<void> {
  await http.post('/api/auth/logout')
}

export async function searchPokemon(query: string): Promise<Pokemon> {
  const response = await http.get<Pokemon>('/api/pokemon/search', { params: { query } })
  return response.data
}

export async function getCollection(params: {
  page: number
  size: number
  query: string
  sort: CollectionSort
  direction: CollectionDirection
}): Promise<CollectionPage> {
  const response = await http.get<CollectionPage>('/api/collection', { params })
  return response.data
}

export async function addToCollection(pokemonId: number): Promise<CollectionEntry> {
  const response = await http.post<CollectionEntry>('/api/collection', { pokemonId })
  return response.data
}

export async function removeFromCollection(entryId: number): Promise<void> {
  await http.delete(`/api/collection/${entryId}`)
}
