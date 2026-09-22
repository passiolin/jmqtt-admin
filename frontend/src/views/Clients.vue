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
  <div class="card">
    <div class="card-head">
      <h2>筛选</h2>
    </div>
    <div class="card-body">
      <div class="row">
        <label class="field">
          <span>节点</span>
          <select v-model="node" @change="reload">
            <option value="">全部节点</option>
            <option v-for="n in nodeIds" :key="n" :value="n">{{ n }}</option>
          </select>
        </label>
        <label class="field">
          <span>clientId 前缀</span>
          <input v-model.trim="prefix" placeholder="例如 device-01" style="width:220px"
                 @keyup.enter="reload">
        </label>
        <button class="primary" :disabled="loading" @click="reload">
          {{ loading ? '查询中…' : '查询' }}
        </button>
      </div>
      <div class="hint">
        前缀过滤在 Redis 侧完成(MATCH), 不会把全部客户端拉回来再筛 ——
        十万级客户端下这个差别就是「能用」和「卡住」。
      </div>
    </div>
  </div>

  <div v-if="aggregateNote" class="banner info">
    {{ aggregateNote }}
  </div>

  <div class="card">
    <div class="card-head">
      <h2>
        客户端
        <span v-if="total >= 0" style="font-weight:400;color:var(--text-dim);font-size:12.5px">
          ({{ num(total) }} 条{{ node ? '' : ' / 各节点' }})
        </span>
      </h2>
      <button v-if="node && cursor && !finished" class="small" :disabled="loading"
              @click="loadMore">
        加载更多
      </button>
    </div>

    <div v-if="loading && !entries.length" class="loading">加载中…</div>
    <div v-else-if="!entries.length" class="empty">
      没有匹配的在线客户端
    </div>
    <div v-else class="card-body tight">
      <div class="scroll">
        <table>
          <thead>
          <tr>
            <th>clientId</th>
            <th v-if="!node">节点</th>
            <th>来源地址</th>
            <th>协议</th>
            <th>心跳</th>
            <th>已在线</th>
            <th>订阅</th>
            <th>积压</th>
            <th></th>
          </tr>
          </thead>
          <tbody>
          <tr v-for="c in entries" :key="c.node + '/' + c.clientId">
            <td class="mono">{{ c.clientId }}</td>
            <td v-if="!node"><span class="tag">{{ c.node }}</span></td>
            <td class="mono" style="color:var(--text-dim)">{{ c.attributes.addr || '-' }}</td>
            <td>
              <span class="tag" :class="c.attributes.ver === 5 ? 'info' : ''">
                {{ c.attributes.ver === 5 ? 'v5.0' : 'v3.1.1' }}
              </span>
              <span v-if="c.attributes.persistent" class="tag ok" style="margin-left:4px">持久</span>
            </td>
            <td class="num">{{ c.attributes.keepAlive || 0 }}s</td>
            <td class="num">{{ onlineFor(c.attributes.connectedAt) }}</td>
            <td class="num">
              {{ num(c.attributes.subs) }}
              <span v-if="c.attributes.filtersTruncated"
                    class="tag warn" style="margin-left:3px">截断</span>
            </td>
            <td class="num">
              <span v-if="c.attributes.inflight || c.attributes.queued"
                    class="tag warn">
                {{ c.attributes.inflight || 0 }}在途 / {{ c.attributes.queued || 0 }}排队
              </span>
              <span v-else style="color:var(--text-faint)">-</span>
            </td>
            <td>
              <button class="small danger" @click="kick(c)">踢下线</button>
            </td>
          </tr>
          </tbody>
        </table>
      </div>
    </div>

    <div v-if="node && cursor && !finished" class="card-body"
         style="border-top:1px solid var(--border)">
      <button class="small" :disabled="loading" @click="loadMore">加载更多</button>
      <span class="hint" style="margin-left:10px">
        游标式分页: HSCAN 没有偏移量概念, 因此这里是「继续」而不是「第 N 页」
      </span>
    </div>
  </div>

  <div v-if="kickResult" class="banner" :class="kickResult.ok ? 'info' : 'warn'">
    {{ kickResult.text }}
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { api } from '../api.js'
import { duration, num } from '../format.js'

const PAGE_SIZE = 100
const AGGREGATE_LIMIT = 300

const nodes = ref([])
const node = ref('')
const prefix = ref('')
const entries = ref([])
const cursor = ref('0')
const finished = ref(false)
const total = ref(-1)
const loading = ref(false)
const aggregate = ref(null)
const kickResult = ref(null)

const nodeIds = computed(() => nodes.value.map((n) => n.node))
const aggregateNote = computed(() => {
  const agg = aggregate.value
  if (!agg) {
    return ''
  }
  const detail = (agg.perNode || [])
      .map((p) => `${p.node} ${p.fetched}/${p.total < 0 ? '?' : p.total}${p.truncated ? '(截断)' : ''}`)
      .join(' · ')
  return `聚合视图: 每节点各取前 ${agg.perNodeLimit} 条后合并, 共展示 ${agg.entries.length} 条`
      + (agg.truncated ? '(合并后已截断)' : '') + `。各节点取数: ${detail}`
      + ' —— 这不是「全集群前 N 条」, 需要完整清单请按节点分别查看。'
})

function onlineFor(connectedAt) {
  if (!connectedAt) {
    return '-'
  }
  return duration(Date.now() - connectedAt)
}

async function loadNodes() {
  try {
    nodes.value = await api.nodes()
    if (!node.value && nodeIds.value.length === 1) {
      node.value = nodeIds.value[0]
    }
  } catch (e) {
    nodes.value = []
  }
}

async function reload() {
  loading.value = true
  entries.value = []
  cursor.value = '0'
  finished.value = false
  aggregate.value = null
  kickResult.value = null
  try {
    if (node.value) {
      await fetchPage()
    } else {
      const page = await api.clientsAcrossNodes({ limit: AGGREGATE_LIMIT, prefix: prefix.value })
      entries.value = page.entries || []
      aggregate.value = page
    }
  } catch (e) {
    kickResult.value = { ok: false, text: '查询失败: ' + e.message }
  } finally {
    loading.value = false
  }
}

async function fetchPage() {
  const page = await api.clients(node.value, {
    cursor: cursor.value,
    count: PAGE_SIZE,
    prefix: prefix.value
  })
  entries.value = cursor.value === '0' ? (page.entries || []) : entries.value.concat(page.entries || [])
  cursor.value = page.cursor
  finished.value = page.finished
  total.value = page.total
}

async function loadMore() {
  loading.value = true
  try {
    await fetchPage()
  } catch (e) {
    kickResult.value = { ok: false, text: '加载失败: ' + e.message }
  } finally {
    loading.value = false
  }
}

/**
 * 踢下线。先确认再执行 —— 它会真实断开一条设备连接, 而设备侧通常表现为一次重连,
 * 若设备逻辑写得不够健壮, 这个动作对业务是有感知的。
 */
async function kick(client) {
  const confirmed = window.confirm(
      `确认把 ${client.clientId} 从 ${client.node} 上断开?\n\n`
      + `会话与订阅都不会被删除, 客户端会立刻重连并恢复(含未确认的 QoS 1/2 消息重发)。\n`
      + `若该客户端配置了遗嘱消息, 断开时会按规范发布一次。`)
  if (!confirmed) {
    return
  }
  kickResult.value = null
  try {
    const { commandId } = await api.kick(client.node, client.clientId, true)
    kickResult.value = { ok: true, text: `已下发踢下线命令(${commandId}), 等待节点执行…` }
    pollCommand(commandId, client)
  } catch (e) {
    kickResult.value = { ok: false, text: '下发失败: ' + e.message }
  }
}

function pollCommand(commandId, client) {
  let attempts = 0
  const timer = setInterval(async () => {
    attempts++
    try {
      const result = await api.command(commandId)
      if (result.state && result.state !== 'PENDING' && result.state !== 'RECEIVED') {
        clearInterval(timer)
        const found = result.found === 'true'
        kickResult.value = {
          ok: found,
          text: found
              ? `已断开 ${client.clientId}(${result.message || ''})。它会在几秒内重连并恢复会话。`
              : `节点未能断开 ${client.clientId}: ${result.message || result.state}`
        }
        reload()
      }
    } catch (e) {
      /* 轮询期间的偶发失败忽略, 下一次继续 */
    }
    if (attempts > 30) {
      clearInterval(timer)
      kickResult.value = { ok: false, text: '等待节点响应超时, 请到总览页确认该节点是否在线' }
    }
  }, 1000)
}

onMounted(async () => {
  await loadNodes()
  await reload()
})
</script>
