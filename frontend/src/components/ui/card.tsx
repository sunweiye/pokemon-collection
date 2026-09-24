import type { HTMLAttributes } from 'react'

export function Card({ className = '', ...props }: HTMLAttributes<HTMLElement>) {
  return <section className={`rounded-3xl border border-slate-200/80 bg-white shadow-sm ${className}`} {...props} />
}

export function CardHeader({ className = '', ...props }: HTMLAttributes<HTMLDivElement>) {
  return <div className={`p-6 ${className}`} {...props} />
}

export function CardContent({ className = '', ...props }: HTMLAttributes<HTMLDivElement>) {
  return <div className={`px-6 pb-6 ${className}`} {...props} />
}
