import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

const backend = process.env.VITE_BACKEND ?? 'http://127.0.0.1:8080'

/**
 * The backend exposes module paths at the root (/subsystems, /units, /tasks) and the newer
 * resources under /api/v1. Those root paths are also frontend routes, so navigation would
 * otherwise hit the API. A browser navigation sends `Accept: text/html`; API calls do not.
 * Returning '/index.html' from `bypass` lets Vite serve the SPA for navigations while XHR
 * keeps being proxied.
 */
const spaBypass = {
  bypass(req: { headers: Record<string, string | string[] | undefined> }) {
    const accept = req.headers.accept
    const value = Array.isArray(accept) ? accept.join(',') : (accept ?? '')
    return value.includes('text/html') ? '/index.html' : undefined
  }
}

export default defineConfig({
  plugins: [vue()],
  build: {
    // Element Plus alone is ~600 kB; one chunk is expected for an internal tool.
    chunkSizeWarningLimit: 1200
  },
  server: {
    host: '127.0.0.1',
    port: 5173,
    proxy: {
      '/api': { target: backend, changeOrigin: true },
      '/subsystems': { target: backend, changeOrigin: true, ...spaBypass },
      '/units': { target: backend, changeOrigin: true, ...spaBypass },
      '/tasks': { target: backend, changeOrigin: true, ...spaBypass }
    }
  }
})