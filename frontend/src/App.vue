<!--
  Copyright (c) 2026 ipuff.online

  Licensed under the Apache License, Version 2.0 (the "License");
  you may not use this file except in compliance with the License.
  You may obtain a copy of the License at

      https://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
-->
<template>
  <div v-if="isLogin" class="login-wrap">
    <router-view/>
  </div>

  <div v-else class="layout">
    <aside class="sidebar">
      <div class="brand">
        jmqtt-admin
        <small>MQTT 集群管理台</small>
      </div>
      <nav class="nav">
        <router-link to="/overview">集群总览</router-link>
        <router-link to="/clients">客户端</router-link>
        <router-link to="/topics">主题</router-link>
        <router-link to="/drain">节点排水</router-link>
      </nav>
      <div class="sidebar-foot">
        <div>{{ username }}</div>
        <div style="margin-top:6px">
          <a href="#" style="color:#8e99a6" @click.prevent="logout">退出登录</a>
        </div>
      </div>
    </aside>

    <div class="main">
      <header class="topbar">
        <h1>{{ $route.meta.title }}</h1>
        <!--
          Redis 可达状态常驻在顶部: 控制台唯一的数据来源就是它, 因此「连不上」这件事
          必须在任何页面上都看得见。放在总览页里的做法会让人在「客户端」页上
          看到一个空列表, 然后以为集群真的没有客户端。
        -->
        <span v-if="redisStatus" class="tag" :class="redisStatus.reachable ? 'ok' : 'danger'">
          Redis {{ redisStatus.reachable ? '已连接' : '不可达' }}
        </span>
        <button class="small" :disabled="loading" @click="refresh">刷新</button>
      </header>

      <main class="content">
        <div v-if="redisStatus && !redisStatus.reachable" class="banner danger">
          <h4>无法读取集群数据</h4>
          <div class="mono">{{ redisStatus.error }}</div>
          <div class="hint">
            页面上的列表与数字在这种情况下<b>不代表真实状态</b> ——
            「没有数据」与「读不到数据」是两件事, 请不要据此执行任何操作。
          </div>
        </div>
        <!-- key 随刷新序号变化, 使子页面重新拉取 -->
        <router-view :key="refreshKey"/>
      </main>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, clearSession, getUser } from './api.js'

const route = useRoute()
const router = useRouter()
const isLogin = computed(() => route.path === '/login')
const username = ref(getUser())
const redisStatus = ref(null)
const loading = ref(false)
const refreshKey = ref(0)

let timer = null

async function loadStatus() {
  if (isLogin.value) {
    return
  }
  try {
    redisStatus.value = await api.redisStatus()
  } catch (e) {
    // 状态查询失败本身就是「不可达」的一种表现, 不要把它变成页面报错
    redisStatus.value = { reachable: false, error: e.message }
  }
}

function refresh() {
  loading.value = true
  loadStatus().finally(() => {
    refreshKey.value++
    setTimeout(() => { loading.value = false }, 200)
  })
}

function logout() {
  api.logout().catch(() => {}).finally(() => {
    clearSession()
    router.push('/login')
  })
}

onMounted(() => {
  loadStatus()
  // 15 秒一次足够: 它只影响顶部那个指示灯, 各页面的数据有自己的刷新节奏
  timer = setInterval(loadStatus, 15000)
})

onUnmounted(() => {
  if (timer) {
    clearInterval(timer)
  }
})
</script>
