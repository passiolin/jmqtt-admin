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
  <div v-if="viewing" class="modal-mask" @click.self="viewing = null">
    <div class="modal">
      <div class="modal-head">
        <h2 class="mono" style="font-size:13px;word-break:break-all">{{ viewing.filter }}</h2>
        <button class="small" @click="viewing = null">关闭</button>
      </div>
      <div class="modal-body">
        <dl class="kv two-col" style="margin-top:10px">
          <div><dt>状态</dt><dd>{{ viewing.status }}(已抓 {{ viewing.captured }})</dd></div>
          <div><dt>节点</dt><dd>{{ viewing.node }}</dd></div>
        </dl>
        <div v-if="messagesLoading" class="loading" style="margin:10px 0">加载中…</div>
        <template v-else>
          <div v-if="!messages.length" class="empty" style="padding:12px 0">
            还没有抓到消息 —— 匹配该过滤器的消息到达后会出现在这里
          </div>
          <div class="scroll" style="max-height:56vh;margin-top:8px">
            <table>
              <thead><tr><th>时间</th><th>方向</th><th>clientId</th><th>topic</th><th>QoS</th><th>大小</th><th>payload</th></tr></thead>
              <tbody>
              <tr v-for="(m, i) in messages" :key="i">
                <td class="num" style="white-space:nowrap">{{ msgTime(m.timestamp) }}</td>
                <td>
                  <span class="tag" :class="m.direction === 'sub' ? 'info' : 'ok'">
                    {{ m.direction === 'sub' ? '收到' : '发布' }}
                  </span>
                </td>
                <td class="mono entity">{{ m.clientId || '-' }}</td>
                <td class="mono topic-link" style="word-break:break-all">{{ m.topic }}</td>
                <td class="num">{{ m.qos }}{{ m.retain ? 'R' : '' }}</td>
                <td class="num">{{ m.size }}B</td>
                <td class="mono" style="word-break:break-all;max-width:280px">{{ m.payloadPreview }}</td>
              </tr>
              </tbody>
            </table>
          </div>
        </template>
      </div>
    </div>
  </div>

  <!-- 直接创建 -->
  <CaptureDialog :open="createOpen" :node="createNode" :filter="createFilter"
                 @close="createOpen = false" @started="load"/>
</template>

<script setup>
import { onMounted, ref } from 'vue'
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
  try {
    const data = await api.captureMessages(capture.id)
    // 消息元素是 JSON 字符串, 解析为对象
    messages.value = (data.messages || []).map((raw) => {
      try {
        return JSON.parse(raw)
      } catch (e) {
        return { timestamp: 0, topic: '?', payloadPreview: raw }
      }
    })
  } finally {
    messagesLoading.value = false
  }
}

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
</style>
