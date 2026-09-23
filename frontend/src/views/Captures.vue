<!--
  Copyright (c) 2026 ipuff.online

  Licensed under the Apache License, Version 2.0 (the "License");
  you may not use this file except in compliance with the License.
  You may obtain a copy of the License at

      https://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
-->
<template>
  <div class="card">
    <div class="card-head">
      <h2>消息监听任务</h2>
      <div>
        <button class="small" style="margin-right:6px" @click="createOpen = true">直接创建</button>
        <button class="small" :disabled="loading" @click="load">刷新</button>
      </div>
    </div>
    <div v-if="loading && !captures.length" class="loading">加载中…</div>
    <div v-else-if="!captures.length" class="empty">
      没有监听任务。<br>
      <span class="hint">
        在「客户端」详情的订阅条目或「主题」列表的订阅者浮窗里可以发起监听;
        也可以点击右上角「直接创建」。
      </span>
    </div>
    <div v-else class="card-body tight">
      <div class="scroll">
        <table>
          <thead>
          <tr>
            <th>任务</th>
            <th>目标</th>
            <th>维度</th>
            <th>节点</th>
            <th>状态</th>
            <th>已抓/上限</th>
            <th>结束于</th>
            <th></th>
          </tr>
          </thead>
          <tbody>
          <tr v-for="c in captures" :key="c.id">
            <td class="mono" style="font-size:11.5px">{{ c.id }}</td>
            <td class="mono" :class="c.mode === 'client' ? 'entity' : 'topic-link'"
                style="word-break:break-all">
              {{ c.mode === 'client' ? c.clientId : c.filter }}
            </td>
            <td>
              <span class="tag" :class="c.mode === 'client' ? 'info' : ''">
                {{ c.mode === 'client' ? '客户端' : 'topic' }}
              </span>
            </td>
            <td><span class="tag">{{ c.node }}</span></td>
            <td>
              <span class="tag" :class="statusClass(c.status)">{{ c.status }}</span>
            </td>
            <td class="num">{{ c.captured }} / {{ c.maxMessages }}</td>
            <td class="num">{{ timeStr(c.endsAt) }}</td>
            <td>
              <button class="small" style="margin-right:6px" @click="openMessages(c)">消息</button>
              <button class="small danger" :disabled="deleting === c.id"
                      @click="removeCapture(c)">{{ deleting === c.id ? '删除中…' : '删除' }}</button>
            </td>
          </tr>
          </tbody>
        </table>
      </div>
    </div>
  </div>

  <!-- 消息查看浮窗 -->
  <div v-if="viewing" class="modal-mask" @click.self="closeMessages">
    <div class="modal">
      <div class="modal-head">
        <h2 class="mono" style="font-size:13px;word-break:break-all">{{ viewing.filter }}</h2>
        <button class="small" @click="closeMessages">关闭</button>
      </div>
      <div class="modal-body">
        <dl class="kv two-col" style="margin-top:10px">
          <div><dt>状态</dt><dd>{{ viewing.status }}(已抓 {{ viewing.captured }})</dd></div>
          <div><dt>节点</dt><dd>{{ viewing.node }}</dd></div>
        </dl>
        <div v-if="messagesLoading" class="loading" style="margin:10px 0">加载中…</div>
        <template v-else>
          <div class="row" style="gap:12px;margin:12px 0 0;align-items:flex-end">
            <label class="field">
              <span>方向</span>
              <select v-model="directionFilter" style="width:100px">
                <option value="">全部</option>
                <option value="pub">发布</option>
                <option value="sub">订阅</option>
              </select>
            </label>
            <label class="field">
              <span>QoS</span>
              <select v-model="qosFilter" style="width:80px">
                <option value="">全部</option>
                <option value="0">0</option>
                <option value="1">1</option>
                <option value="2">2</option>
              </select>
            </label>
            <label class="auto-refresh" title="开启后每 5 秒重新拉取一次消息, 已选的筛选不受影响">
              <input type="checkbox" v-model="autoRefresh" @change="onAutoRefreshChange">
              5s 自动刷新
            </label>
            <span class="hint" style="margin:0">
              筛出 {{ shownMessages.length }} / {{ messages.length }} 条
            </span>
          </div>
          <div v-if="!messages.length" class="empty" style="padding:12px 0">
            还没有抓到消息 —— 匹配该过滤器的消息到达后会出现在这里
          </div>
          <div v-else-if="!shownMessages.length" class="empty" style="padding:12px 0">
            没有匹配当前筛选的消息
          </div>
          <div v-else class="scroll" style="max-height:56vh;margin-top:8px">
            <table>
              <thead><tr><th>时间</th><th>方向</th><th>clientId</th><th>topic</th><th>QoS</th><th>大小</th><th>payload</th></tr></thead>
              <tbody>
              <tr v-for="m in shownMessages" :key="m._id">
                <td class="num" style="white-space:nowrap">{{ msgTime(m.timestamp) }}</td>
                <td>
                  <span class="tag" :class="m.direction === 'sub' ? 'info' : 'ok'">
                    {{ m.direction === 'sub' ? '订阅' : '发布' }}
                  </span>
                </td>
                <td class="mono entity">{{ m.clientId || '-' }}</td>
                <td class="mono topic-link" style="word-break:break-all">{{ m.topic }}</td>
                <td class="num">{{ m.qos }}{{ m.retain ? 'R' : '' }}</td>
                <td class="num">{{ m.size }}B</td>
                <td style="max-width:280px">
                  <!-- 单行截断占位, 点击弹出 payload 浮窗(行内展开会把表格撑变形) -->
                  <button v-if="m.payloadPreview" class="payload-act payload-preview mono"
                          title="点击查看 payload(JSON 自动格式化)"
                          @click="viewingPayload = m">
                    {{ truncatedPayload(m.payloadPreview) }}
                  </button>
                  <span v-else style="color:var(--text-faint)">-</span>
                </td>
              </tr>
              </tbody>
            </table>
          </div>
        </template>
      </div>
    </div>
  </div>

  <!-- payload 查看浮窗: 独立弹出(盖在消息浮窗上), 行内只留截断占位不撑表格 -->
  <div v-if="viewingPayload" class="payload-mask" @click.self="viewingPayload = null">
    <div class="modal payload-modal">
      <div class="modal-head">
        <h2 class="mono" style="font-size:13px;word-break:break-all">{{ viewingPayload.topic }}</h2>
        <button class="small" @click="viewingPayload = null">关闭</button>
      </div>
      <div class="modal-body">
        <dl class="kv two-col" style="margin-top:10px">
          <div><dt>时间</dt><dd>{{ msgTime(viewingPayload.timestamp) }}</dd></div>
          <div><dt>方向</dt>
            <dd>{{ viewingPayload.direction === 'sub' ? '订阅' : '发布' }}</dd></div>
          <div><dt>QoS</dt><dd>{{ viewingPayload.qos }}{{ viewingPayload.retain ? 'R' : '' }}</dd></div>
          <div><dt>大小</dt><dd>{{ viewingPayload.size }}B</dd></div>
        </dl>
        <pre class="payload-pre mono">{{ viewingPayload.payloadPretty }}</pre>
      </div>
    </div>
  </div>

  <!-- 直接创建 -->
  <CaptureDialog :open="createOpen" :node="createNode" :filter="createFilter"
                 @close="createOpen = false" @started="load"/>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { api } from '../api.js'
import { num } from '../format.js'
import CaptureDialog from '../components/CaptureDialog.vue'

const captures = ref([])
const loading = ref(false)
const viewing = ref(null)
const messages = ref([])
const messagesLoading = ref(false)
const deleting = ref('')
const createOpen = ref(false)
const createNode = ref('')
const createFilter = ref('')

/* 消息流筛选; payload 点击后在独立浮窗查看(viewingPayload) */
const directionFilter = ref('')
const qosFilter = ref('')
const viewingPayload = ref(null)

/** 方向筛选与标签显示同一口径: 非 sub 一律算发布 —— 旧构建节点的记录没有 direction 字段 */
const shownMessages = computed(() => messages.value.filter((m) =>
    (!directionFilter.value
        || (directionFilter.value === 'sub'
            ? m.direction === 'sub'
            : m.direction !== 'sub'))
    && (!qosFilter.value || String(m.qos) === qosFilter.value)))

/** 能解析成 JSON 的格式化输出; 解析失败(纯文本或 2KB 预览截断)就原样展示 */
function prettyPayload(text) {
  if (typeof text !== 'string' || !text) {
    return text || ''
  }
  try {
    return JSON.stringify(JSON.parse(text), null, 2)
  } catch (e) {
    return text
  }
}

/** 折叠态占位: 首行截断, 多行的加省略提示 */
function truncatedPayload(text) {
  const firstLine = String(text).split('\n')[0]
  const clipped = firstLine.length > 60 ? firstLine.slice(0, 60) + '…' : firstLine
  return text.includes('\n') ? clipped + ' …' : clipped
}

function statusClass(status) {
  if (status === 'RUNNING') return 'ok'
  if (status === 'FULL') return 'warn'
  return ''
}

function timeStr(ts) {
  return ts ? new Date(Number(ts)).toLocaleString() : '-'
}

function msgTime(ts) {
  return new Date(ts).toLocaleTimeString()
}

async function load() {
  loading.value = true
  try {
    const list = await api.captures()
    captures.value = list || []
    // 「直接创建」需要默认节点: 取第一个在线节点
    if (!createNode.value && captures.value.length) {
      createNode.value = captures.value[0].node
    }
  } catch (e) {
    captures.value = []
  } finally {
    loading.value = false
  }
}

async function openMessages(capture) {
  viewing.value = capture
  messages.value = []
  messagesLoading.value = true
  directionFilter.value = ''
  qosFilter.value = ''
  viewingPayload.value = null
  try {
    applyMessages(await api.captureMessages(capture.id))
    if (autoRefresh.value) {
      startAutoRefresh()
    }
  } finally {
    messagesLoading.value = false
  }
}

function closeMessages() {
  viewing.value = null
  stopAutoRefresh()
}

/** 只替换消息列表, 不动筛选与 payload 浮窗 —— 自动刷新走这条路径 */
function applyMessages(data) {
  // 消息元素是 JSON 字符串, 解析为对象; _id 作为行 key
  messages.value = (data.messages || []).map((raw, i) => {
    let m
    try {
      m = JSON.parse(raw)
    } catch (e) {
      m = { timestamp: 0, topic: '?', payloadPreview: raw }
    }
    m._id = i
    m.payloadPretty = prettyPayload(m.payloadPreview)
    return m
  })
}

// ---- 5s 自动刷新: 打开详情时按开关启动, 关闭/换任务/卸载即停 ----
const autoRefresh = ref(true)
let refreshTimer = null

function startAutoRefresh() {
  stopAutoRefresh()
  refreshTimer = setInterval(async () => {
    // 加载中或浮窗已关就不叠一轮请求; 偶发失败静默, 下一轮再取
    if (!viewing.value || messagesLoading.value) {
      return
    }
    try {
      applyMessages(await api.captureMessages(viewing.value.id))
    } catch (e) {
      /* 忽略 */
    }
  }, 5000)
}

function stopAutoRefresh() {
  if (refreshTimer) {
    clearInterval(refreshTimer)
    refreshTimer = null
  }
}

function onAutoRefreshChange() {
  if (autoRefresh.value && viewing.value) {
    startAutoRefresh()
  } else {
    stopAutoRefresh()
  }
}

onUnmounted(stopAutoRefresh)

async function removeCapture(capture) {
  if (!confirm(`确认删除监听任务「${capture.mode === 'client' ? capture.clientId : capture.filter}」?\n已监听到的消息会一并删除, 不可恢复。`)) {
    return
  }
  deleting.value = capture.id
  try {
    await api.deleteCapture(capture.id, capture.node)
    await load()
  } catch (e) {
    alert('删除失败: ' + e.message)
  } finally {
    deleting.value = ''
  }
}

const route = useRoute()

onMounted(async () => {
  await load()
  // 从监听发起跳转进来: 直接打开该任务的消息流
  const target = route.query.capture
  if (target) {
    const found = captures.value.find((c) => c.id === target)
    if (found) {
      openMessages(found)
    }
  }
})
</script>

<style scoped>
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
  width: min(860px, 94vw);
  max-height: 86vh;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: var(--bg-card, #fff);
  border: 1px solid var(--border);
  border-radius: 10px;
  box-shadow: 0 12px 40px rgba(0, 0, 0, 0.25);
}

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
}

/* payload 折叠: 折叠态是单行截断的文字按钮, 展开态才是格式化内容 */
.payload-act {
  border: none;
  background: none;
  padding: 0;
  font-size: 11.5px;
  color: var(--text-faint);
  cursor: pointer;
}

.payload-act:hover {
  color: var(--primary);
  text-decoration: underline;
}

.payload-preview {
  display: block;
  max-width: 260px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  text-align: left;
}

/* payload 浮窗: 盖在消息浮窗(z-50)之上 */
.payload-mask {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.35);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 60;
}

.payload-modal {
  width: min(720px, 90vw);
  max-height: 86vh;
}

/* payload 内容: 独立浮窗里给足展示空间 */
.payload-pre {
  margin: 10px 0 0;
  padding: 10px 12px;
  background: rgba(127, 127, 127, 0.08);
  border: 1px solid var(--border);
  border-radius: 6px;
  font-size: 11.5px;
  line-height: 1.5;
  max-height: 52vh;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-all;
}

.auto-refresh {
  display: flex;
  align-items: center;
  gap: 5px;
  margin-bottom: 8px;
  font-size: 12px;
  color: var(--text-dim);
  cursor: pointer;
  user-select: none;
  white-space: nowrap;
}
</style>
