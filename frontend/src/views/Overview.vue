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
  <div v-if="loading" class="loading">加载中…</div>

  <template v-else>
    <div class="tiles">
      <div class="tile">
        <div class="label">在线节点</div>
        <div class="value">{{ totals.onlineNodes }}<span
            style="font-size:14px;color:var(--text-faint)"> / {{ totals.nodes }}</span></div>
        <div class="sub">心跳新鲜才算在线</div>
      </div>
      <div class="tile">
        <div class="label">连接数</div>
        <div class="value">{{ num(totals.connections) }}</div>
        <div class="sub">已注册 MQTT 连接合计</div>
      </div>
      <div class="tile">
        <div class="label">会话数</div>
        <div class="value">{{ num(totals.sessions) }}</div>
        <div class="sub">含离线保留的持久会话</div>
      </div>
      <div class="tile">
        <div class="label">单次驱逐上限</div>
        <div class="value">{{ num(limits.maxEvictPerTask) }}</div>
        <div class="sub">分批慢速驱逐的安全阀</div>
      </div>
    </div>

    <div class="card">
      <div class="card-head">
        <h2>节点</h2>
        <button class="small" @click="load">刷新</button>
      </div>
      <div v-if="!nodes.length" class="empty">
        没有发现任何节点。<br>
        <span class="hint">
          节点由 broker 主动注册到 Redis(启动时写入并在心跳中续期)。
          若 broker 已启动却看不到节点, 请检查两者的
          <span class="mono">redis.key-prefix</span> 是否一致。
        </span>
      </div>
      <div v-else class="card-body">
        <div class="grid">
          <div v-for="node in nodes" :key="node.node" class="card" style="margin:0">
            <div class="card-head">
              <h2 class="mono" style="font-size:13.5px">{{ node.node }}</h2>
              <span class="tag" :class="node.online ? 'ok' : 'danger'">
                {{ node.online ? '在线' : '离线' }}
              </span>
              <button v-if="!node.online" class="small danger" :disabled="removing === node.node"
                      @click="removeNode(node.node)" :title="removingTitle(node.node)">
                {{ removing === node.node ? '删除中…' : '删除' }}
              </button>
            </div>
            <div class="card-body">
              <dl class="kv">
                <dt>连接数</dt>
                <dd>{{ num(node.connections) }}</dd>
                <dt>会话数</dt>
                <dd>{{ num(node.sessions) }}</dd>
                <dt>订阅数</dt>
                <dd>{{ num(node.subscriptions) }}</dd>
                <dt>主题过滤器</dt>
                <dd>{{ num(node.filterEntries) }}</dd>
                <dt>保留消息</dt>
                <dd>{{ num(node.retainMessages) }}</dd>
                <dt>MQTT / WS</dt>
                <dd>{{ node.summary.mqttPort || '-' }} / {{ node.summary.websocketPort || '-' }}</dd>
                <dt>心跳</dt>
                <dd>{{ timeAgo(node.updatedAt) }}</dd>
              </dl>

              <div style="margin-top:10px">
                <span class="tag" :class="mode(node.broadcastMode).cls">
                  集群{{ mode(node.broadcastMode).label }}
                </span>
                <span v-if="node.uplinkEnabled" class="tag info" style="margin-left:5px">数据面上行已开</span>
              </div>

              <div v-if="node.warnings && node.warnings.length" class="banner warn"
                   style="margin:11px 0 0;padding:8px 10px">
                <ul style="margin:0;padding-left:16px">
                  <li v-for="(w, i) in node.warnings" :key="i" style="font-size:12px">{{ w }}</li>
                </ul>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!--
      跨节点订阅重叠检测: 这是「关闭集群广播」这个决定唯一的风险点, 也是最难自查的一点。
      重叠意味着那些订阅者需要跨节点投递, 广播一旦关掉它们就永远收不到消息。
    -->
    <div class="card">
      <div class="card-head">
        <h2>跨节点订阅重叠检查</h2>
        <button class="small" :disabled="overlapBusy" @click="runOverlap">
          {{ overlapBusy ? '检查中…' : '开始检查' }}
        </button>
      </div>
      <div class="card-body">
        <div class="hint" style="margin:0 0 10px">
          同一个 topic 过滤器若在<b>多个节点</b>上都有订阅者, 那些订阅者需要跨节点投递。
          集群广播关闭(或收敛为白名单)时, 这类过滤器<b>必须</b>留在广播范围内,
          否则消息会静默不到 —— 没有报错、没有日志。
        </div>

        <div v-if="!overlap" class="empty" style="padding:16px">尚未检查</div>

        <template v-else>
          <div v-if="overlap.truncated" class="banner warn">
            结果已截断(扫描 {{ num(overlap.scanned) }} 条): 为了不让控制台拖慢 Redis,
            聚合扫描有上限。请缩小范围或分批确认。
          </div>
          <div v-if="!overlap.overlapping.length" class="banner info">
            未发现跨节点重叠的订阅过滤器 —— 在当前订阅分布下, 关闭集群广播不会造成跨节点消息丢失。
          </div>
          <div v-else class="banner danger">
            <h4>发现 {{ overlap.overlapping.length }} 个跨节点的过滤器</h4>
            <div style="font-size:12.5px">
              若集群广播已关闭, 这些过滤器上的跨节点消息将不会送达。
            </div>
          </div>

          <div v-if="overlap.overlapping.length" class="scroll" style="max-height:320px">
            <table>
              <thead>
              <tr>
                <th>主题过滤器</th>
                <th>订阅者</th>
                <th>分布</th>
              </tr>
              </thead>
              <tbody>
              <tr v-for="item in overlap.overlapping" :key="item.topicFilter">
                <td class="mono">{{ item.topicFilter }}</td>
                <td class="num">{{ item.subscribers }}</td>
                <td>
                  <span v-for="(count, node) in item.perNode" :key="node" class="tag"
                        style="margin-right:4px">
                    {{ node }}:{{ count }}
                  </span>
                </td>
              </tr>
              </tbody>
            </table>
          </div>
          <div class="hint">
            扫描范围: {{ overlap.nodes.join(', ') || '-' }} · 共扫描 {{ num(overlap.scanned) }} 条过滤器
          </div>
        </template>
      </div>
    </div>
  </template>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { api } from '../api.js'
import { broadcastMode, num, timeAgo } from '../format.js'

const loading = ref(true)
const nodes = ref([])
const totals = ref({ nodes: 0, onlineNodes: 0, connections: 0, sessions: 0 })
const limits = ref({})
const overlap = ref(null)
const overlapBusy = ref(false)
const removing = ref('')

function removingTitle(nodeId) {
  return removing.value === nodeId ? '正在删除' : '从注册表移除该离线节点及其遗留键'
}

async function removeNode(nodeId) {
  if (!confirm(`确认删除离线节点「${nodeId}」？\n将移除它在注册表中的记录与全部遗留键, 不可恢复。`)) {
    return
  }
  removing.value = nodeId
  try {
    await api.removeNode(nodeId)
    await load()
  } catch (e) {
    alert('删除失败: ' + e.message)
  } finally {
    removing.value = ''
  }
}

const mode = broadcastMode

async function load() {
  loading.value = true
  try {
    const data = await api.overview()
    nodes.value = data.nodes || []
    totals.value = data.totals || totals.value
    limits.value = data.limits || {}
  } catch (e) {
    nodes.value = []
  } finally {
    loading.value = false
  }
}

async function runOverlap() {
  overlapBusy.value = true
  try {
    overlap.value = await api.overlap({ limit: 200, maxScan: 20000 })
  } catch (e) {
    overlap.value = { overlapping: [], nodes: [], scanned: 0, truncated: false }
  } finally {
    overlapBusy.value = false
  }
}

onMounted(load)
</script>
