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

const TOKEN_KEY = 'jmqtt-admin-token'
const USER_KEY = 'jmqtt-admin-user'

export function getToken() {
  return localStorage.getItem(TOKEN_KEY) || ''
}

export function getUser() {
  return localStorage.getItem(USER_KEY) || ''
}

export function setSession(token, username) {
  localStorage.setItem(TOKEN_KEY, token)
  localStorage.setItem(USER_KEY, username || '')
}

export function clearSession() {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(USER_KEY)
}

export class ApiError extends Error {
  constructor(message, status, payload) {
    super(message)
    this.status = status
    this.payload = payload
  }
}

/**
 * 统一的请求包装。
 *
 * 401 一律清理本地会话并抛出 —— 让「令牌过期」这件事只有一个处理点。
 * 若散落到各页面里, 迟早会有页面忘了处理, 表现为「页面空白但控制台没有报错」。
 */
async function request(method, url, body) {
  const headers = {}
  if (body !== undefined) {
    headers['Content-Type'] = 'application/json'
  }
  const token = getToken()
  if (token) {
    headers['Authorization'] = `Bearer ${token}`
  }
  let response
  try {
    response = await fetch(url, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body)
    })
  } catch (e) {
    throw new ApiError('无法连接后端服务: ' + e.message, 0)
  }

  let payload = null
  const text = await response.text()
  if (text) {
    try {
      payload = JSON.parse(text)
    } catch (e) {
      throw new ApiError(`响应不是合法 JSON (HTTP ${response.status})`, response.status)
    }
  }

  if (response.status === 401) {
    clearSession()
    throw new ApiError('登录已过期, 请重新登录', 401, payload)
  }
  if (!response.ok || (payload && payload.success === false)) {
    throw new ApiError((payload && payload.message) || `请求失败 (HTTP ${response.status})`,
        response.status, payload)
  }
  return payload && Object.prototype.hasOwnProperty.call(payload, 'data') ? payload.data : payload
}

function qs(params) {
  const search = new URLSearchParams()
  Object.entries(params || {}).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '') {
      search.append(key, value)
    }
  })
  const text = search.toString()
  return text ? '?' + text : ''
}

export const api = {
  // ---- 认证 ----
  login: (username, password) => request('POST', '/api/auth/login', { username, password }),
  logout: () => request('POST', '/api/auth/logout'),
  me: () => request('GET', '/api/auth/me'),

  // ---- 总览与节点 ----
  redisStatus: () => request('GET', '/api/redis/status'),
  overview: () => request('GET', '/api/overview'),
  nodes: () => request('GET', '/api/nodes'),
  node: (node) => request('GET', `/api/nodes/${encodeURIComponent(node)}`),
  snapshot: (node) => request('POST', `/api/nodes/${encodeURIComponent(node)}/snapshot`),

  // ---- 客户端 ----
  clients: (node, params) =>
      request('GET', `/api/nodes/${encodeURIComponent(node)}/clients${qs(params)}`),
  clientsAcrossNodes: (params) => request('GET', `/api/clients${qs(params)}`),
  client: (node, clientId) =>
      request('GET', `/api/nodes/${encodeURIComponent(node)}/clients/${encodeURIComponent(clientId)}`),
  kick: (node, clientId, publishWill) =>
      request('POST', `/api/nodes/${encodeURIComponent(node)}/clients/${encodeURIComponent(clientId)}/kick`,
          { publishWill }),
  command: (commandId) => request('GET', `/api/commands/${encodeURIComponent(commandId)}`),

  // ---- 主题 ----
  topics: (node, params) =>
      request('GET', `/api/nodes/${encodeURIComponent(node)}/topics${qs(params)}`),
  topicsAcrossNodes: (params) => request('GET', `/api/topics${qs(params)}`),
  overlap: (params) => request('GET', `/api/topics/overlap${qs(params)}`),

  // ---- 排水 ----
  createDrain: (body) => request('POST', '/api/drains', body),
  drains: () => request('GET', '/api/drains'),
  drain: (id) => request('GET', `/api/drains/${encodeURIComponent(id)}`),
  confirmLb: (id, override) =>
      request('POST', `/api/drains/${encodeURIComponent(id)}/confirm-lb`, { override }),
  startDrain: (id) => request('POST', `/api/drains/${encodeURIComponent(id)}/start`),
  abortDrain: (id) => request('POST', `/api/drains/${encodeURIComponent(id)}/abort`)
}
