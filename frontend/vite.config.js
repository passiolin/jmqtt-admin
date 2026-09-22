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
    // 构建产物直接落到后端的静态资源目录, 于是最终只有一个 jar 需要部署。
    // 这不是为了省事: 两个产物分开意味着「前端更新了但后端没更新」这种不一致状态
    // 会真实存在, 而它是一个版本号说不清的问题。
    outDir: '../backend/src/main/resources/static',
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
