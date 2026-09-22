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
  <div class="login-card">
    <h1>jmqtt-admin</h1>
    <p class="sub">MQTT 集群管理台</p>

    <label class="field">
      <span>用户名</span>
      <input v-model.trim="form.username" autocomplete="username" @keyup.enter="submit">
    </label>
    <label class="field">
      <span>密码</span>
      <input v-model="form.password" type="password" autocomplete="current-password"
             @keyup.enter="submit">
    </label>

    <div v-if="error" class="banner danger" style="margin:12px 0 0">{{ error }}</div>

    <button class="primary" :disabled="busy" @click="submit">
      {{ busy ? '登录中…' : '登录' }}
    </button>

    <div class="hint" style="margin-top:12px">
      账号来自管理台配置文件(<span class="mono">jmqtt.admin.username / password</span>),
      默认 <span class="mono">jmqtt / jmqtt</span>。仅用于内网管理台。
    </div>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api, setSession } from '../api.js'

const router = useRouter()
const form = reactive({ username: 'jmqtt', password: '' })
const busy = ref(false)
const error = ref('')

async function submit() {
  if (!form.username || !form.password) {
    error.value = '请输入用户名与密码'
    return
  }
  busy.value = true
  error.value = ''
  try {
    const data = await api.login(form.username, form.password)
    setSession(data.token, data.username)
    router.push('/overview')
  } catch (e) {
    error.value = e.message
  } finally {
    busy.value = false
  }
}
</script>
