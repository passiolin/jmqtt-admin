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
          <tr v-for="c in entries" :key="c.node + '/' + c.clientId"
              class="clickable" @click="openDetail(c)" title="点击查看客户端详情">
            <td class="mono entity">{{ c.clientId }}</td>
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

  <CaptureDialog :open="captureOpen" :node="drawer.client?.node || ''"
                 :filter="captureFilter || ''" :client-id="captureClientId"
                 @close="captureOpen = false"
                 @started="closeDrawer"/>

  <!-- 客户端详情浮窗: 两级视图 —— 客户端详情 / 订阅者清单 -->
  <div v-if="drawer.open" class="modal-mask" @click.self="closeDrawer">
    <div class="modal">
      <div v-if="drawer.view === 'client'" class="modal-head">
        <h2 class="mono" style="font-size:14px">{{ drawer.client.clientId }}</h2>
        <div>
          <span class="tag">{{ drawer.client.node }}</span>
          <span v-if="detail?.attributes.persistent" class="tag ok" style="margin-left:4px">持久会话</span>
          <button class="small" style="margin-left:8px" title="监听该客户端发布与收到的全部消息"
                  @click="captureClient">监听</button>
          <button class="small" style="margin-left:4px" @click="closeDrawer">关闭</button>
        </div>
      </div>
      <div v-else class="modal-head">
        <h2 class="mono" style="font-size:13.5px;word-break:break-all">{{ drawer.filter }}</h2>
        <button class="small" @click="drawer.view = 'client'">← 返回客户端</button>
      </div>

      <div class="modal-body">
      <template v-if="drawer.view === 'client'">
        <div v-if="detailLoading" class="loading">加载中…</div>
        <template v-else-if="detail">
          <div class="drawer-section">连接</div>
          <dl class="kv two-col">
            <div><dt>来源地址</dt><dd class="mono">{{ detail.attributes.addr || '-' }}</dd></div>
            <div><dt>协议</dt>
              <dd>{{ detail.attributes.ver === 5 ? 'MQTT 5.0' : 'MQTT 3.1.1' }}
                <span v-if="detail.attributes.receiveMax"
                      style="color:var(--text-faint)">(Receive Maximum {{ detail.attributes.receiveMax }})</span>
              </dd></div>
            <div><dt>心跳</dt><dd>{{ detail.attributes.keepAlive || 0 }}s</dd></div>
            <div><dt>接入时间</dt><dd>{{ timeStr(detail.attributes.connectedAt) }}</dd></div>
          </dl>

          <div class="drawer-section">会话</div>
          <dl class="kv two-col">
            <div><dt>会话类型</dt>
              <dd>{{ detail.attributes.persistent ? '持久(断开保留)' : '临时(断开即清)' }}</dd></div>
            <div><dt>保留时长</dt><dd>{{ detail.attributes.expiry ?? '-' }}s</dd></div>
            <div><dt>最近活跃</dt><dd>{{ timeStr(detail.attributes.lastActiveAt) }}</dd></div>
            <div><dt>遗嘱消息</dt><dd>{{ detail.attributes.hasWill ? '有' : '无' }}</dd></div>
          </dl>

          <div class="drawer-section">发送缓冲</div>
          <dl class="kv two-col">
            <div><dt>在途(未确认 QoS 1/2)</dt><dd>{{ num(detail.attributes.inflight || 0) }}</dd></div>
            <div><dt>排队</dt><dd>{{ num(detail.attributes.queued || 0) }}</dd></div>
          </dl>

          <div class="drawer-section">
            订阅({{ detail.attributes.subs || 0 }})
            <span v-if="detail.attributes.filtersTruncated" class="tag warn" style="margin-left:6px">
              列表被截断(实际 {{ detail.attributes.subs }} 条)
            </span>
          </div>
          <div class="filter-list">
            <div v-for="f in (detail.attributes.filters || [])" :key="f" class="filter-row">
              <button class="filter-name mono" @click="openFilter(f)"
                      :title="'查看 ' + f + ' 的订阅者'">{{ f }}</button>
              <button class="filter-act" @click="startCapture(f)"
                      title="监听该过滤器的消息">监听</button>
            </div>
            <div v-if="!(detail.attributes.filters || []).length" class="hint">无订阅</div>
          </div>
        </template>
      </template>

      <template v-else>
        <div v-if="filterLoading" class="loading" style="margin-top:12px">扫描各节点客户端中…</div>
        <template v-else-if="filterDetail">
          <dl class="kv two-col" style="margin-top:10px">
            <div><dt>订阅者</dt><dd>{{ filterDetail.subscribers.length }} 个</dd></div>
            <div><dt>扫描范围</dt><dd>{{ filterDetail.scanned }} 个客户端字段</dd></div>
          </dl>
          <div v-if="filterDetail.truncated" class="banner warn" style="margin:8px 0">
            结果不完整(达到扫描或返回上限)—— 订阅数超过上报上限的客户端不会出现在这里
          </div>
          <div class="scroll" style="max-height:50vh">
            <table>
              <thead><tr><th>clientId</th><th>节点</th><th>在线时长</th></tr></thead>
              <tbody>
              <tr v-for="sub in filterDetail.subscribers"
                  :key="sub.node + '/' + sub.clientId">
                <td class="mono">{{ sub.clientId }}</td>
                <td><span class="tag">{{ sub.node }}</span></td>
                <td class="num">{{ onlineFor(sub.attributes.connectedAt) }}</td>
              </tr>
              </tbody>
            </table>
          </div>
        </template>
      </template>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { api } from '../api.js'
import { duration, num } from '../format.js'
import CaptureDialog from '../components/CaptureDialog.vue'

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
const drawer = ref({ open: false, view: 'client', client: null, filter: '' })
const detail = ref(null)
const detailLoading = ref(false)
const filterDetail = ref(null)
const filterLoading = ref(false)
const captureOpen = ref(false)
const captureFilter = ref('')
const captureClientId = ref('')

function startCapture(filter) {
  captureFilter.value = filter
  captureClientId.value = ''
  captureOpen.value = true
}

/** 客户端维度: 抓该客户端的发布与收到(pub+sub) */
function captureClient() {
  captureFilter.value = ''
  captureClientId.value = drawer.value.client?.clientId || ''
  captureOpen.value = true
}

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

function timeStr(ts) {
  return ts ? new Date(ts).toLocaleString() : '-'
}

/** 打开客户端详情抽屉: 行数据先渲染, 再向节点按需查询当下快照(含订阅) */
async function openDetail(client) {
  drawer.value = { open: true, view: 'client', client, filter: '' }
  detail.value = client
  detailLoading.value = true
  try {
    const { commandId } = await api.fetchClientDetail(client.node, client.clientId)
    // 轮询命令结果(节点 500ms 轮询队列 + 执行, 一般 1~2 秒内回来)
    for (let i = 0; i < 20; i++) {
      await new Promise((r) => setTimeout(r, 400))
      if (drawer.value.client !== client) {
        return // 抽屉已切到别的客户端, 放弃本次查询
      }
      const result = await api.command(commandId)
      if (result.state === 'COMPLETED' && result.snapshot) {
        detail.value = { node: client.node, clientId: client.clientId,
                         attributes: JSON.parse(result.snapshot) }
        return
      }
      if (result.state && result.state !== 'PENDING' && result.state !== 'RECEIVED') {
        return // 节点拒绝(如客户端已断开), 停在行数据
      }
    }
  } catch (e) {
    /* 查询失败就停在行数据 —— 列表快照可能旧了几秒, 但结构完整 */
  } finally {
    detailLoading.value = false
  }
}

/** 打开某订阅过滤器的订阅者清单(跨节点扫描) */
async function openFilter(filter) {
  drawer.value = { ...drawer.value, view: 'filter', filter }
  filterDetail.value = null
  filterLoading.value = true
  try {
    filterDetail.value = await api.filterDetail(filter)
  } catch (e) {
    filterDetail.value = { subscribers: [], scanned: 0, truncated: false, error: e.message }
  } finally {
    filterLoading.value = false
  }
}

function closeDrawer() {
  drawer.value = { open: false, view: 'client', client: null, filter: '' }
  detail.value = null
  filterDetail.value = null
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

const route = useRoute()

onMounted(async () => {
  await loadNodes()
  // 从主题页「订阅者 → 详情」跳转进来: 按 clientId 过滤并直接打开详情浮窗
  const jumpClientId = route.query.clientId
  const jumpNode = route.query.node
  if (jumpClientId) {
    prefix.value = jumpClientId
    if (jumpNode && nodeIds.value.includes(jumpNode)) {
      node.value = jumpNode
    }
  }
  await reload()
  if (jumpClientId && jumpNode) {
    const target = entries.value.find(
        (c) => c.clientId === jumpClientId && c.node === jumpNode)
    if (target) {
      openDetail(target)
    }
  }
})
</script>

<style scoped>
.clickable {
  cursor: pointer;
}

.clickable:hover {
  background: rgba(127, 127, 127, 0.08);
}

.modal-mask {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.35);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 50;
}

.modal {
  width: min(640px, 92vw);
  max-height: 86vh;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: var(--bg-card, #fff);
  border: 1px solid var(--border);
  border-radius: 10px;
  box-shadow: 0 12px 40px rgba(0, 0, 0, 0.25);
}

/* 浮窗结构: 头部固定, 内容区滚动 —— 长订阅列表不会把关闭按钮顶出屏幕 */
.modal-head {
  padding: 12px 16px;
  border-bottom: 1px solid var(--border);
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex: none;
}

.modal-body {
  overflow-y: auto;
  padding: 0 16px 20px;
  flex: 1;
}

.drawer-section {
  margin: 16px 0 8px;
  font-size: 12.5px;
  font-weight: 600;
  color: var(--text-dim);
  border-bottom: 1px solid var(--border);
  padding-bottom: 4px;
}

.kv.two-col {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 6px 16px;
}

.kv.two-col dt {
  font-size: 11.5px;
  color: var(--text-faint);
}

.kv.two-col dd {
  margin: 2px 0 0;
  font-size: 13px;
  word-break: break-all;
}

.filter-list {
  display: flex;
  flex-direction: column;
}

/* 订阅过滤器: 无边框、主题色文字, hover 下划线 —— 是链接不是标签 */
.filter-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 2px 0;
}

.filter-name {
  border: none;
  background: none;
  padding: 0;
  font-size: 12.5px;
  color: var(--primary);
  cursor: pointer;
  text-align: left;
  word-break: break-all;
}

.filter-name:hover {
  text-decoration: underline;
}

/* 监听动作: 同一基线的小号文字链接, 不做按钮框 */
.filter-act {
  border: none;
  background: none;
  padding: 0;
  font-size: 11.5px;
  color: var(--text-faint);
  cursor: pointer;
  flex: none;
}

.filter-act:hover {
  color: var(--primary);
  text-decoration: underline;
}
</style>
