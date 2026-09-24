import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import { resolve } from 'node:path'

export default defineConfig({
  plugins: [react(), tailwindcss()],
  build: {
    manifest: 'manifest.json',
    rollupOptions: {
      input: {
        app: resolve(__dirname, 'src/app/main.tsx'),
        login: resolve(__dirname, 'src/login/main.tsx'),
      },
    },
  },
  // Development only. The app is always opened through Spring Boot on port 8080;
  // the page shell loads its modules from this server. It is not an entry point:
  // there is no proxy and no HTML page here.
  server: {
    host: '0.0.0.0',
    port: 5173,
    strictPort: true, // the backend's dev profile points at exactly this port
    origin: 'http://localhost:5173', // absolute URLs for assets imported by modules
    cors: { origin: 'http://localhost:8080' }, // only the Spring Boot page may load modules
  },
  test: {
    environment: 'jsdom',
    setupFiles: './src/test/setup.ts',
    globals: true,
    coverage: {
      provider: 'v8',
      reporter: ['text', 'html', 'lcov'],
      reportsDirectory: './coverage',
      include: ['src/**/*.{ts,tsx}'],
      exclude: ['src/**/*.test.{ts,tsx}', 'src/test/**', 'src/types/**'],
    },
  },
})
