import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'
import path from 'path'
import { keycloakify } from 'keycloakify/vite-plugin'

// https://vite.dev/config/
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const BACKEND = env.VITE_API_BASE_URL || 'http://localhost:80'

  return {
    plugins: [
      react(),
      keycloakify({
        themeName: 'asm',
        themeVersion: '1.0.0',
        accountThemeImplementation: 'none',
        startKeycloakOptions: {
          port: 8090,
        },
      }),
    ],
    define: { global: 'globalThis' },
    resolve: {
      alias: {
        '@': path.resolve(__dirname, './src'),
      },
    },
    server: {
      proxy: {
        '/api': {
          target: BACKEND,
          changeOrigin: true,
        },
        '/ws': {
          target: BACKEND,
          changeOrigin: true,
          ws: true,
        }
      }
    },
    build: {
      rollupOptions: {
        output: {
          manualChunks(id) {
            if (id.includes('node_modules')) {
              if (id.includes('leaflet') || id.includes('react-leaflet')) {
                return 'leaflet-vendor';
              }
              if (id.includes('@tabler/icons-react')) {
                return 'tabler-icons';
              }
              return 'vendor';
            }
          }
        }
      }
    }
  }
})
