import { createRoot } from 'react-dom/client'
import { LoginApp } from './LoginApp'
import { t } from '../i18n'
import '../styles.css'

document.title = t('document.loginTitle')
createRoot(document.getElementById('root')!).render(<LoginApp />)
