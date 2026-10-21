import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

// The SPA and the API share one origin in every environment (this proxy in dev, nginx or the platform router in
// production). That keeps the auth cookies first-party and means the API needs no CORS configuration at all.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': { target: process.env.API_URL ?? 'http://localhost:8080' },
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
    css: false,
  },
});
