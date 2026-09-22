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
          <tr v-for="t in entries" :key="t.topicFilter">
            <td class="mono">{{ t.topicFilter }}</td>
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
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { api } from '../api.js'
import { num } from '../format.js'

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
