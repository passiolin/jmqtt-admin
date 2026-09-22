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
import { createApp } from 'vue'
import { createRouter, createWebHashHistory } from 'vue-router'

import App from './App.vue'
import Login from './views/Login.vue'
import Overview from './views/Overview.vue'
import Clients from './views/Clients.vue'
import Topics from './views/Topics.vue'
import Drain from './views/Drain.vue'
import { getToken } from './api.js'
import './styles.css'

const routes = [
  { path: '/', redirect: '/overview' },
  { path: '/overview', component: Overview, meta: { title: '集群总览' } },
  { path: '/clients', component: Clients, meta: { title: '客户端' } },
  { path: '/topics', component: Topics, meta: { title: '主题' } },
  { path: '/drain', component: Drain, meta: { title: '节点排水' } },
  { path: '/login', component: Login, meta: { title: '登录', public: true } }
]

// hash 路由: 不需要服务端配置 history fallback。
// 用 history 模式就要在后端加一条「非 /api 路径一律返回 index.html」的转发,
// 而那条规则一旦漏配, 表现是「刷新页面 404」—— 一个只在生产出现、开发环境看不出的小坑
const router = createRouter({
  history: createWebHashHistory(),
  routes
})

router.beforeEach((to) => {
  const logged = !!getToken()
  if (!to.meta.public && !logged) {
    return { path: '/login' }
  }
  if (to.meta.public && logged) {
    return { path: '/overview' }
  }
  return true
})

router.afterEach((to) => {
  document.title = (to.meta.title ? to.meta.title + ' · ' : '') + 'jmqtt-admin'
})

createApp(App).use(router).mount('#app')
