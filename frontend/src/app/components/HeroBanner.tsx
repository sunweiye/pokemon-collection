import { ShieldCheck } from 'lucide-react'
import { t } from '../../i18n'

export function HeroBanner() {
  return (
    <section className="mb-8 flex flex-col justify-between gap-5 rounded-3xl bg-gradient-to-br from-indigo-600 to-sky-500 px-6 py-7 text-white shadow-lg shadow-indigo-100 sm:flex-row sm:items-end sm:px-8">
      <div>
        <p className="mb-2 flex items-center gap-2 text-sm font-semibold text-indigo-100"><ShieldCheck size={16} /> {t('app.heroEyebrow')}</p>
        <h1 className="text-3xl font-bold tracking-tight sm:text-4xl">{t('app.heroTitle')}</h1>
        <p className="mt-2 max-w-lg text-sm leading-6 text-indigo-100">{t('app.heroDescription')}</p>
      </div>
    </section>
  )
}
