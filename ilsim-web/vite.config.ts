import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    allowedHosts: ['.trycloudflare.com'],
    // xfwd: 서버가 접속자 IP 를 구분하도록 X-Forwarded-For 를 붙인다(ILSIM_FORWARD_HEADERS=native 와 함께 쓴다).
    proxy: { '/api': { target: 'http://localhost:8080', xfwd: true } },
  },
})
