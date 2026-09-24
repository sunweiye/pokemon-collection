import type { ButtonHTMLAttributes } from 'react'

type ButtonVariant = 'primary' | 'secondary' | 'ghost' | 'danger'
type ButtonSize = 'sm' | 'md' | 'lg' | 'icon'

const variants: Record<ButtonVariant, string> = {
  primary: 'bg-slate-900 text-white shadow-sm hover:bg-slate-700 focus-visible:ring-slate-400',
  secondary: 'bg-indigo-50 text-indigo-700 hover:bg-indigo-100 focus-visible:ring-indigo-300',
  ghost: 'text-slate-600 hover:bg-slate-100 hover:text-slate-900 focus-visible:ring-slate-300',
  danger: 'bg-rose-50 text-rose-700 hover:bg-rose-100 focus-visible:ring-rose-300',
}

const sizes: Record<ButtonSize, string> = {
  sm: 'h-9 px-3 text-sm',
  md: 'h-10 px-4 text-sm',
  lg: 'h-11 px-5 text-sm',
  icon: 'h-10 w-10',
}

export function Button({
  className = '',
  variant = 'primary',
  size = 'md',
  type = 'button',
  ...props
}: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: ButtonVariant; size?: ButtonSize }) {
  return (
    <button
      type={type}
      className={`inline-flex items-center justify-center gap-2 rounded-xl font-semibold transition focus-visible:outline-none focus-visible:ring-4 disabled:cursor-not-allowed disabled:opacity-50 ${variants[variant]} ${sizes[size]} ${className}`}
      {...props}
    />
  )
}
