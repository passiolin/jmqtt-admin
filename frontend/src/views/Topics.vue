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
          <span>过滤器前缀</span>
          <input v-model.trim="prefix" placeholder="例如 device/" style="width:200px"
                 @keyup.enter="reload">
        </label>
        <label class="field">
          <span>排序</span>
          <select v-model="sortByCount" @change="reload">
            <option :value="false">按名称</option>
            <option :value="true">按订阅者数</option>
          </select>
        </label>
        <button class="primary" :disabled="loading" @click="reload">
          {{ loading ? '查询中…' : '查询' }}
        </button>
      </div>

      <div class="hint">
        这里的「主题」指<b>主题过滤器</b>(订阅关系), 也就是各节点本地主题树上实际存在的条目。
        订阅者数按节点分开显示: 「3 个节点各 1 个」与「1 个节点 3 个」对集群的含义完全不同 ——
        前者需要跨节点投递, 后者不需要。
      </div>
    </div>
  </div>

  <div v-if="truncated" class="banner warn">
    结果不完整: 按订阅者数排序需要对扫描到的数据整体排序, 而聚合扫描有上限。
    当前基于 {{ num(scanned) }} 条过滤器的排序结果, 请结合前缀缩小范围后再确认。
  </div>

  <div class="card">
    <div class="card-head">
      <h2>
        主题过滤器
        <span v-if="total >= 0" style="font-weight:400;color:var(--text-dim);font-size:12.5px">
          (共 {{ num(total) }} 个)
        </span>
      </h2>
      <button v-if="node && cursor && !finished" class="small" :disabled="loading"
              @click="loadMore">
        加载更多
      </button>
    </div>

    <div v-if="loading && !entries.length" class="loading">加载中…</div>
    <div v-else-if="!entries.length" class="empty">没有匹配的主题过滤器</div>
    <div v-else class="card-body tight">
      <div class="scroll">
        <table>
          <thead>
          <tr>
            <th>主题过滤器</th>
            <th>订阅者</th>
            <th>节点分布</th>
          </tr>
          </thead>
          <tbody>
          <tr v-for="t in entries" :key="t.topicFilter"
              class="clickable" @click="openSubscribers(t.topicFilter)"
              title="点击查看订阅该过滤器的客户端">
            <td class="mono topic-link">{{ t.topicFilter }}</td>
            <td class="num">
              {{ t.subscribers }}
              <span v-if="t.nodes.length > 1" class="tag warn" style="margin-left:4px">
                跨 {{ t.nodes.length }} 节点
              </span>
            </td>
            <td>
              <span v-for="(count, n) in t.perNode" :key="n" class="tag" style="margin-right:4px">
                {{ n }}:{{ count }}
              </span>
            </td>
          </tr>
          </tbody>
        </table>
      </div>
    </div>

    <div v-if="node && cursor && !finished" class="card-body"
         style="border-top:1px solid var(--border)">
      <button class="small" :disabled="loading" @click="loadMore">加载更多</button>
    </div>
  </div>

  <!-- 订阅者浮窗: 点击主题过滤器的客户端清单, 点客户端跳转客户端页 -->
  <div v-if="subOpen" class="modal-mask" @click.self="closeSubscribers">
    <div class="modal">
      <div class="modal-head">
        <h2 class="mono" style="font-size:13.5px;word-break:break-all">{{ subFilter }}</h2>
        <button class="small" :disabled="!captureNode"
                @click="captureOpen = true" :title="captureNode ? '' : '多节点分布时请在上方选择节点'">
          监听
        </button>
      </div>
      <div class="modal-body">
        <div v-if="subLoading" class="loading" style="margin-top:12px">扫描各节点客户端中…</div>
        <template v-else-if="subDetail">
          <dl class="kv two-col" style="margin-top:10px">
            <div><dt>订阅者</dt><dd>{{ subDetail.subscribers.length }} 个</dd></div>
            <div><dt>扫描范围</dt><dd>{{ subDetail.scanned }} 个客户端字段</dd></div>
          </dl>
          <div v-if="subDetail.truncated" class="banner warn" style="margin:8px 0">
            结果不完整(达到扫描或返回上限)—— 订阅数超过上报上限的客户端不会出现在这里
          </div>
          <div class="scroll" style="max-height:50vh">
            <table>
              <thead><tr><th>clientId</th><th>节点</th><th>在线时长</th><th></th></tr></thead>
              <tbody>
              <tr v-for="sub in subDetail.subscribers"
                  :key="sub.node + '/' + sub.clientId" class="clickable"
                  @click="gotoClient(sub)" title="跳转客户端页查看详情">
                <td class="mono entity">{{ sub.clientId }}</td>
                <td><span class="tag">{{ sub.node }}</span></td>
                <td class="num">{{ onlineFor(sub.attributes.connectedAt) }}</td>
                <td><span style="color:var(--accent,#4a90d9);font-size:12px">详情 →</span></td>
              </tr>
              </tbody>
            </table>
          </div>
          <div class="hint" style="margin-top:8px">
            点击订阅者跳转客户端页(按该 clientId 前缀过滤), 在那里可查看连接/会话/订阅详情。
          </div>
        </template>
      </div>
    </div>
  </div>
  <CaptureDialog :open="captureOpen" :node="captureNode" :filter="subFilter"
                 @close="captureOpen = false"
                 @started="closeSubscribers"/>

</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '../api.js'
import { duration, num } from '../format.js'
import CaptureDialog from '../components/CaptureDialog.vue'

const PAGE_SIZE = 100
const AGGREGATE_LIMIT = 300

const nodes = ref([])
const node = ref('')
const prefix = ref('')
const sortByCount = ref(true)
const entries = ref([])
const cursor = ref('0')
const finished = ref(false)
const total = ref(-1)
const scanned = ref(0)
const truncated = ref(false)
const loading = ref(false)

const nodeIds = computed(() => nodes.value.map((n) => n.node))

const router = useRouter()
const subOpen = ref(false)
const subFilter = ref('')
const subDetail = ref(null)
const subLoading = ref(false)
const captureOpen = ref(false)

/** 监听要发往具体节点: 订阅者全在一个节点时用它, 否则用当前筛选的节点(全部时禁用) */
const captureNode = computed(() => {
  const subs = subDetail.value?.subscribers || []
  if (subs.length === 1) {
    return subs[0].node
  }
  return node.value || ''
})

function onlineFor(connectedAt) {
  return connectedAt ? duration(Date.now() - connectedAt) : '-'
}

/** 点主题过滤器 → 订阅者清单(跨节点扫描) */
async function openSubscribers(topicFilter) {
  subFilter.value = topicFilter
  subOpen.value = true
  subDetail.value = null
  subLoading.value = true
  try {
    subDetail.value = await api.filterDetail(topicFilter)
  } catch (e) {
    subDetail.value = { subscribers: [], scanned: 0, truncated: false, error: e.message }
  } finally {
    subLoading.value = false
  }
}

function closeSubscribers() {
  subOpen.value = false
  subDetail.value = null
}

/** 点订阅者 → 跳转客户端页并按 clientId 精确过滤 */
function gotoClient(sub) {
  closeSubscribers()
  router.push({ path: '/clients', query: { clientId: sub.clientId, node: sub.node } })
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
  truncated.value = false
  scanned.value = 0
  try {
    if (node.value) {
      await fetchPage()
    } else {
      entries.value = await api.topicsAcrossNodes({
        limit: AGGREGATE_LIMIT,
        prefix: prefix.value,
        sortByCount: sortByCount.value
      })
      total.value = -1
      scanned.value = entries.value.length
    }
  } catch (e) {
    entries.value = []
  } finally {
    loading.value = false
  }
}

async function fetchPage() {
  const page = await api.topics(node.value, {
    cursor: cursor.value,
    count: PAGE_SIZE,
    prefix: prefix.value,
    sortByCount: sortByCount.value
  })
  entries.value = cursor.value === '0' ? (page.entries || []) : entries.value.concat(page.entries || [])
  cursor.value = page.cursor
  finished.value = page.finished
  total.value = page.total
  scanned.value = page.scanned
  truncated.value = page.truncated
}

async function loadMore() {
  loading.value = true
  try {
    await fetchPage()
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  await loadNodes()
  await reload()
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
</style>
