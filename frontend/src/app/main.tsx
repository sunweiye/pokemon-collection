import { createRoot } from 'react-dom/client'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { App } from './App'
import { t } from '../i18n'
import '../styles.css'

const queryClient = new QueryClient({ defaultOptions: { queries: { staleTime: 30_000, retry: false } } })

document.title = t('document.appTitle')
createRoot(document.getElementById('root')!).render(
  <QueryClientProvider client={queryClient}>
    <App />
  </QueryClientProvider>,
)
