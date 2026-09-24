type TypeTagsProps = {
  types: string[]
}

export function TypeTags({ types }: TypeTagsProps) {
  return (
    <div className="mt-2 flex flex-wrap gap-1.5">
      {types.map((type) => <span className="rounded-full bg-slate-100 px-2 py-1 text-xs font-semibold capitalize text-slate-600" key={type}>{type}</span>)}
    </div>
  )
}
