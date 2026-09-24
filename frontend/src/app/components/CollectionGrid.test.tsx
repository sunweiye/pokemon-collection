import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { CollectionEntry } from '../../types/api'
import { CollectionGrid } from './CollectionGrid'

function renderEntry(types: string[]) {
  const entry: CollectionEntry = {
    entryId: 1,
    pokemonId: 25,
    name: 'pikachu',
    spriteUrl: null,
    types,
    caughtAt: '2026-09-24T00:00:00Z',
  }

  render(<CollectionGrid entries={[entry]} removePending={false} onRemove={vi.fn()} />)
}

describe('CollectionGrid', () => {
  it('renders the collection card type tags', () => {
    renderEntry(['electric', 'steel'])

    expect(screen.getByText('electric')).toBeInTheDocument()
    expect(screen.getByText('steel')).toBeInTheDocument()
  })

  it('renders a collection card without type tags when types is empty', () => {
    renderEntry([])

    expect(screen.getByText('pikachu')).toBeInTheDocument()
    expect(screen.queryByText('electric')).not.toBeInTheDocument()
  })
})
