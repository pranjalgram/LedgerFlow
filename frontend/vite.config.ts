import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    proxy: {
      '/api': 'http://localhost:8080',
      '/actuator/health': 'http://localhost:8080',
    },
  },
  test: { environment: 'jsdom', restoreMocks: true, include: ['src/**/*.test.{ts,tsx}'] },
});
