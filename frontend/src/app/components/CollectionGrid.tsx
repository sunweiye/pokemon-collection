import { Trash2 } from 'lucide-react'
import type { CollectionEntry } from '../../types/api'
import { Button } from '../../components/ui/button'
import { t } from '../../i18n'
import { TypeTags } from './TypeTags'

type CollectionGridProps = {
  entries: CollectionEntry[]
  removePending: boolean
  onRemove: (entry: CollectionEntry) => void
}

export function CollectionGrid({ entries, removePending, onRemove }: CollectionGridProps) {
  return (
    <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
      {entries.map((entry) => <CollectionEntryCard key={entry.entryId} entry={entry} removePending={removePending} onRemove={onRemove} />)}
    </div>
  )
}

function CollectionEntryCard({ entry, removePending, onRemove }: { entry: CollectionEntry; removePending: boolean; onRemove: (entry: CollectionEntry) => void }) {
  return (
    <article className="group rounded-2xl border border-slate-200 bg-white p-4 transition hover:-translate-y-0.5 hover:border-indigo-200 hover:shadow-md">
      <div className="flex items-start justify-between gap-3">
        {entry.spriteUrl ? <img className="h-24 w-24 rounded-2xl bg-slate-50 object-contain" src={entry.spriteUrl} alt={t('common.pokemonSprite', { name: entry.name })} /> : <div className="h-24 w-24 rounded-2xl bg-slate-50" aria-hidden="true" />}
        <Button variant="ghost" size="icon" aria-label={t('app.removePokemon', { name: entry.name })} onClick={() => onRemove(entry)} disabled={removePending}>
          <Trash2 size={17} />
        </Button>
      </div>
      <div className="mt-4">
        <p className="font-bold capitalize text-slate-900">{entry.name}</p>
        <p className="mt-1 text-sm text-slate-500">#{entry.pokemonId}</p>
        <TypeTags types={entry.types} />
      </div>
    </article>
  )
}
