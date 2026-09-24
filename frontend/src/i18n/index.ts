import { en } from './en'

const resources = { en }
export type Locale = keyof typeof resources

let currentLocale: Locale = 'en'

function resolveMessage(key: string): string {
  let value: unknown = resources[currentLocale]

  for (const segment of key.split('.')) {
    if (!value || typeof value !== 'object') break
    value = (value as Record<string, unknown>)[segment]
  }

  if (typeof value !== 'string') {
    throw new Error(`Missing translation: ${currentLocale}.${key}`)
  }

  return value
}

export function t(key: string, values: Record<string, string | number> = {}): string {
  return resolveMessage(key).replace(/\{\{(\w+)\}\}/g, (match, name: string) => {
    return name in values ? String(values[name]) : match
  })
}

export function setLocale(locale: Locale): void {
  currentLocale = locale
}
