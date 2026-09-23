/*
 * Copyright (c) 2026 ipuff.online
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 */
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  build: {
    // 产物留在 frontend/dist, 由前端自行部署(Nginx/CDN), 不打进后端 jar ——
    // 后端 jar 只提供 /api。开发走 vite dev(5173, /api 代理到后端)。
    outDir: 'dist',
    emptyOutDir: true,
    chunkSizeWarningLimit: 900
  },
  server: {
    port: 5173,
    // 开发时把 /api 代理到后端, 避免在开发环境引入 CORS 配置 ——
    // 那份配置在生产并不存在, 于是「开发能跑、生产跨域失败」这类问题会周期性出现
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:9100',
        changeOrigin: true
      }
    }
  }
})
