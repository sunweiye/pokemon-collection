import { LoaderCircle } from 'lucide-react'
import type { Pokemon } from '../../types/api'
import { Button } from '../../components/ui/button'
import { t } from '../../i18n'
import { TypeTags } from './TypeTags'

type PokemonResultProps = {
  pokemon: Pokemon
  onAdd: () => void
  isAdding: boolean
}

export function PokemonResult({ pokemon, onAdd, isAdding }: PokemonResultProps) {
  const isAdded = pokemon.inCollection

  return (
    <article className="mt-5 flex items-center gap-4 rounded-2xl border border-indigo-100 bg-indigo-50/60 p-4">
      {pokemon.spriteUrl ? <img className="h-20 w-20 rounded-xl bg-white object-contain" src={pokemon.spriteUrl} alt={t('common.pokemonSprite', { name: pokemon.name })} /> : <div className="h-20 w-20 rounded-xl bg-white" aria-hidden="true" />}
      <div className="min-w-0 flex-1">
        <p className="font-bold capitalize text-slate-900">{pokemon.name}</p>
        <p className="mt-1 text-sm text-slate-500">#{pokemon.pokemonId}</p>
        <TypeTags types={pokemon.types} />
      </div>
      <Button size="sm" onClick={onAdd} disabled={isAdding || isAdded}>
        {isAdding && <LoaderCircle size={15} className="animate-spin" />}
        {isAdding ? t('app.adding') : isAdded ? t('app.alreadyAdded') : t('app.add')}
      </Button>
    </article>
  )
}
