import axios from 'axios'

// The page shell rendered by Spring Boot (Thymeleaf) is the only source of the
// CSRF token. The app is always opened through the backend (port 8080), so the
// meta tags are always present; there is no fallback that fetches a token.
const csrfHeader = document.querySelector('meta[name="_csrf_header"]')?.getAttribute('content')?.trim() ?? ''
const csrfToken = document.querySelector('meta[name="_csrf"]')?.getAttribute('content')?.trim() ?? ''

const writeMethods = ['post', 'put', 'patch', 'delete']

export const http = axios.create({
  withCredentials: true,
})

http.interceptors.request.use((config) => {
  if (writeMethods.includes(config.method?.toLowerCase() ?? '')) {
    if (!csrfHeader || !csrfToken) {
      throw new Error('The page did not provide a CSRF token. Open the app through the Spring Boot server and reload the page.')
    }
    config.headers.set(csrfHeader, csrfToken)
  }
  return config
})

http.interceptors.response.use(
  (response) => response,
  (error) => {
    const status = error.response?.status
    const requestUrl = error.config?.url ?? ''
    if (status === 401 && !requestUrl.endsWith('/api/auth/login')) {
      window.location.href = '/login'
    }
    return Promise.reject(error)
  },
)
