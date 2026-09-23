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
  <div class="banner info">
    <h4>驱逐是什么, 以及它为什么需要四步</h4>
    <div style="font-size:12.5px">
      把某个节点上的客户端<b>迁到其他节点</b>。断连必然触发遗嘱发布, 一次性断掉大量连接会同时
      制造遗嘱风暴与重连风暴, 所以必须分批、并且先确认负载均衡确实不再向该节点调度 ——
      「停调度」这一步在控制台权限之外, 只能由人操作并确认。
    </div>
  </div>

  <div class="card">
    <div class="card-head">
      <h2>新建驱逐任务</h2>
    </div>
    <div class="card-body">
      <div class="row">
        <label class="field">
          <span>目标节点</span>
          <select v-model="form.node" :disabled="!!current">
            <option value="">请选择</option>
            <option v-for="n in onlineNodes" :key="n.node" :value="n.node">
              {{ n.node }} ({{ num(n.connections) }} 连接)
            </option>
          </select>
        </label>
        <label class="field">
          <span>方式</span>
          <select v-model="form.mode" :disabled="!!current">
            <option value="ratio">按比例</option>
            <option value="count">按数量</option>
          </select>
        </label>
        <label class="field">
          <span>{{ form.mode === 'ratio' ? '比例 (0~1)' : '数量' }}</span>
          <input v-model.number="form.value" type="number" step="0.05" min="0"
                 :max="form.mode === 'ratio' ? 1 : limits.maxEvictPerTask"
                 style="width:130px" :disabled="!!current">
        </label>
        <label class="field">
          <span>每批条数</span>
          <input v-model.number="form.batchSize" type="number" min="1" style="width:105px"
                 :disabled="!!current">
        </label>
        <label class="field">
          <span>批次间隔 (ms)</span>
          <input v-model.number="form.intervalMs" type="number" min="20" style="width:115px"
                 :disabled="!!current">
        </label>
        <label class="field">
          <span>clientId 前缀</span>
          <input v-model.trim="form.clientIdPrefix" placeholder="留空=全部" style="width:150px"
                 :disabled="!!current">
        </label>
        <button class="primary" :disabled="!!current || !form.node || busy" @click="create">
          创建并开始预检
        </button>
      </div>

      <label style="display:flex;gap:6px;align-items:center;margin-top:10px;font-size:12.5px">
        <input v-model="form.publishWill" type="checkbox" :disabled="!!current">
        <span>断开时按规范发布遗嘱消息(默认开启)</span>
      </label>

      <div class="hint">
        单次上限 {{ num(limits.maxEvictPerTask) }} 条。这不是性能参数而是安全阀:
        每批之间的间隔决定了重连压力被摊到多长时间里, 建议先用很小的量(例如
        <span class="mono">count=50 / 每批 10 / 间隔 1000ms</span>)走一遍完整流程,
        确认客户端能正常换节点之后再放大。
      </div>
    </div>
  </div>

  <template v-if="current">
    <div class="steps">
      <div v-for="(s, i) in stepView" :key="i" class="step" :class="s.cls">
        <div class="n">第 {{ i + 1 }} 步</div>
        <div class="t">{{ s.title }}</div>
        <div style="font-size:11.5px;margin-top:2px">{{ s.text }}</div>
      </div>
    </div>

    <div class="card">
      <div class="card-head">
        <h2>任务 {{ current.id.slice(0, 8) }}</h2>
        <span class="tag" :class="stateTag.cls">{{ stateTag.label }}</span>
        <button class="small" :disabled="current.terminal" @click="abort">中止</button>
      </div>
      <div class="card-body">
        <div v-if="current.message" style="font-size:13px;margin-bottom:10px">
          {{ current.message }}
        </div>

        <dl class="kv" style="margin-bottom:14px">
          <dt>目标节点</dt>
          <dd class="mono">{{ current.node }}</dd>
          <dt>方式</dt>
          <dd>{{ current.mode === 'ratio' ? '按比例 ' + current.value : '按数量 ' + current.value }}</dd>
          <dt>节奏</dt>
          <dd>每批 {{ current.batchSize }} 条 · 间隔 {{ current.intervalMs }}ms
            (约 {{ ratePerSecond }} 条/秒)
          </dd>
          <dt>发布遗嘱</dt>
          <dd>{{ current.publishWill ? '是(规范行为)' : '否(跳过)' }}</dd>
          <dt v-if="current.clientIdPrefix">clientId 前缀</dt>
          <dd v-if="current.clientIdPrefix" class="mono">{{ current.clientIdPrefix }}</dd>
        </dl>

        <!-- 预检结果 -->
        <div v-if="current.precheckEndCount >= 0" class="banner"
             :class="current.precheckSuspect ? 'warn' : 'info'" style="margin-bottom:14px">
          <h4>预检结果</h4>
          <div style="font-size:12.5px">
            观测 {{ Math.round(limits.precheckWindowMs / 1000) }} 秒:
            连接数 {{ num(current.precheckStartCount) }} → {{ num(current.precheckEndCount) }}
            (变化 {{ signed(current.precheckEndCount - current.precheckStartCount) }})
          </div>
          <div v-if="current.precheckSuspect" style="font-size:12.5px;margin-top:4px">
            仍有新连接持续进入 —— 停止向该节点调度<b>可能尚未生效</b>。
            若此时开始驱逐, 客户端会从同一个入口回连, 驱逐将变成「断开→回连」的死循环, 连接数不会下降。
          </div>
        </div>

        <!-- 动作 -->
        <div v-if="current.state === 'PRECHECKED'" class="banner warn">
          <h4>请先完成这一步: 停止向 {{ current.node }} 调度</h4>
          <div style="font-size:12.5px">
            在负载均衡 / 网关 / K8s 中把该节点的流量摘掉(例如把权重置 0、把 readiness 置否、
            或从后端组中移除), 等健康检查生效后再点击下面的按钮。
          </div>
          <label style="display:flex;gap:6px;align-items:center;margin-top:9px;font-size:12.5px">
            <input v-model="override" type="checkbox">
            <span v-if="current.precheckSuspect">我确认预检告警可以忽略(仍有新连接进入)</span>
            <span v-else>我确认已停止向该节点调度</span>
          </label>
          <button class="primary" style="margin-top:9px" :disabled="!override || busy"
                  @click="confirmLb">
            确认已停止调度
          </button>
        </div>

        <div v-else-if="current.state === 'LB_CONFIRMED'" class="banner warn">
          <h4>准备开始驱逐</h4>
          <div style="font-size:12.5px">
            将按「每批 {{ current.batchSize }} 条 / 间隔 {{ current.intervalMs }}ms」断开本节点连接。
            会话与订阅不会被删除, 客户端重连到其他节点后会恢复。
          </div>
          <button class="primary" style="margin-top:9px" :disabled="busy" @click="start">
            开始驱逐
          </button>
        </div>

        <!-- 驱逐进度 -->
        <div v-if="current.evictTarget > 0" style="margin-bottom:14px">
          <div style="font-size:12.5px;color:var(--text-dim);margin-bottom:4px">
            驱逐进度 {{ num(current.evicted) }} / {{ num(current.evictTarget) }}
          </div>
          <div style="height:6px;background:#eceff2;border-radius:3px;overflow:hidden">
            <div :style="{width: progressPct + '%', height: '100%', background: '#2b6cb0'}"></div>
          </div>
        </div>

        <!-- 连接数对照 -->
        <div v-if="countRows.length" style="margin-bottom:14px">
          <div style="font-size:13px;font-weight:600;margin-bottom:6px">连接数变化</div>
          <table>
            <thead>
            <tr>
              <th>节点</th>
              <th>基线</th>
              <th>当前</th>
              <th>变化</th>
              <th></th>
            </tr>
            </thead>
            <tbody>
            <tr v-for="row in countRows" :key="row.node">
              <td class="mono">{{ row.node }}</td>
              <td class="num">{{ num(row.before) }}</td>
              <td class="num">{{ num(row.now) }}</td>
              <td class="num">{{ signed(row.delta) }}</td>
              <td>
                <span v-if="row.node === current.node" class="tag info">目标节点</span>
                <span v-if="row.node === current.node && row.delta > 0" class="tag warn">
                  未下降 —— 客户端可能回连到同一节点
                </span>
              </td>
            </tr>
            </tbody>
          </table>
          <div class="hint">
            判定「迁走了」还是「连不上了」必须同时看两边: 只看目标节点变空, 无法区分
            「客户端成功换节点」与「客户端根本没重连上」—— 这两种情况在单节点视角下长得一样。
          </div>
        </div>

        <!-- 时间线 -->
        <div v-if="current.timeline && current.timeline.length">
          <div style="font-size:13px;font-weight:600;margin-bottom:8px">过程记录</div>
          <div class="timeline">
            <div v-for="(item, i) in current.timeline" :key="i" class="timeline-item"
                 :class="timelineCls(item)">
              {{ item.text }}
              <span class="when">{{ clock(item.at) }}</span>
            </div>
          </div>
        </div>
      </div>
    </div>
  </template>

  <div class="card">
    <div class="card-head">
      <h2>历史任务</h2>
      <button class="small" @click="loadDrains">刷新</button>
    </div>
    <div v-if="!history.length" class="empty">本次会话内没有驱逐任务</div>
    <div v-else class="card-body tight">
      <table>
        <thead>
        <tr>
          <th>任务</th>
          <th>节点</th>
          <th>状态</th>
          <th>计划 / 实际</th>
          <th>结果</th>
          <th></th>
        </tr>
        </thead>
        <tbody>
        <tr v-for="d in history" :key="d.id">
          <td class="mono">{{ d.id.slice(0, 8) }}</td>
          <td class="mono">{{ d.node }}</td>
          <td><span class="tag" :class="tag(d.state).cls">{{ tag(d.state).label }}</span></td>
          <td class="num">{{ num(d.evictTarget) }} / {{ num(d.evicted) }}</td>
          <td style="font-size:12.5px;color:var(--text-dim)">{{ d.message }}</td>
          <td>
            <button class="small" @click="current = d">查看</button>
          </td>
        </tr>
        </tbody>
      </table>
    </div>
    <div class="hint" style="padding:0 16px 14px">
      任务状态保存在控制台进程内, 重启后历史清空。驱逐本身是由 broker 执行的,
      因此控制台重启<b>不会</b>让进行中的驱逐停下来。
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { api } from '../api.js'
import { clock, drainState, num } from '../format.js'

const busy = ref(false)
const nodes = ref([])
const history = ref([])
const current = ref(null)
const override = ref(false)
const limits = ref({ maxEvictPerTask: 5000, precheckWindowMs: 8000 })
const error = ref('')

const form = ref({
  node: '',
  mode: 'ratio',
  value: 1,
  batchSize: 200,
  intervalMs: 500,
  clientIdPrefix: '',
  publishWill: true
})

const tag = drainState

const onlineNodes = computed(() => nodes.value.filter((n) => n.online))
const ratePerSecond = computed(() =>
    current.value ? Math.round(current.value.batchSize * 1000 / Math.max(1, current.value.intervalMs)) : 0)
const progressPct = computed(() => {
  const c = current.value
  if (!c || !c.evictTarget) {
    return 0
  }
  return Math.min(100, Math.round((c.evicted / c.evictTarget) * 100))
})
const stateTag = computed(() => tag(current.value ? current.value.state : ''))

const stepView = computed(() => {
  const c = current.value
  const order = ['CREATED', 'PRECHECKING', 'PRECHECKED', 'LB_CONFIRMED', 'EVICTING',
    'WAITING_RECONNECT', 'COMPLETED']
  const idx = order.indexOf(c.state)
  const steps = [
    { title: '建立基线', text: '记录各节点连接数' },
    { title: '预检', text: '观测是否仍有新连接' },
    { title: '停止调度', text: '人工确认已完成' },
    { title: '分批驱逐', text: '按节奏断开连接' },
    { title: '等待重连', text: '校验迁移结果' }
  ]
  const map = [0, 1, 1, 2, 3, 4, 4]
  const currentStep = map[Math.max(0, idx)]
  return steps.map((s, i) => {
    let cls = ''
    if (c.terminal && c.state === 'COMPLETED') {
      cls = 'done'
    } else if (i < currentStep) {
      cls = 'done'
    } else if (i === currentStep) {
      cls = 'active'
    }
    return { ...s, cls }
  })
})

const countRows = computed(() => {
  const c = current.value
  if (!c || !c.baseline || !Object.keys(c.baseline).length) {
    return []
  }
  const rows = []
  Object.keys(c.latestCounts || {}).forEach((node) => {
    const before = c.baseline[node] ?? 0
    const now = c.latestCounts[node]
    rows.push({ node, before, now, delta: now - before })
  })
  // 基线里有、当前没有的节点(失联)也要显示出来, 否则「少了一个节点」这件事会被静默吞掉
  Object.keys(c.baseline).forEach((node) => {
    if (!(node in (c.latestCounts || {}))) {
      rows.push({ node, before: c.baseline[node], now: null, delta: null })
    }
  })
  rows.sort((a, b) => (a.node === c.node ? -1 : b.node === c.node ? 1 : a.node.localeCompare(b.node)))
  return rows
})

function signed(value) {
  if (value === null || value === undefined) {
    return '-'
  }
  return (value > 0 ? '+' : '') + num(value)
}

function timelineCls(item) {
  const text = item.text || ''
  if (item.state === 'TIMEOUT' || item.state === 'FAILED' || text.includes('疑似')
      || text.includes('告警') || text.includes('可能')) {
    return 'warn'
  }
  if (item.state === 'COMPLETED') {
    return 'ok'
  }
  return ''
}

async function loadNodes() {
  try {
    const data = await api.overview()
    nodes.value = data.nodes || []
    limits.value = data.limits || limits.value
  } catch (e) {
    nodes.value = []
  }
}

async function loadDrains() {
  try {
    history.value = await api.drains()
  } catch (e) {
    history.value = []
  }
}

async function refreshCurrent() {
  if (!current.value) {
    return
  }
  try {
    const updated = await api.drain(current.value.id)
    current.value = updated
    if (updated.terminal) {
      loadDrains()
    }
  } catch (e) {
    /* 忽略单次刷新失败 */
  }
}

async function create() {
  busy.value = true
  error.value = ''
  override.value = false
  try {
    current.value = await api.createDrain({
      node: form.value.node,
      mode: form.value.mode,
      value: form.value.value,
      batchSize: form.value.batchSize,
      intervalMs: form.value.intervalMs,
      publishWill: form.value.publishWill,
      clientIdPrefix: form.value.clientIdPrefix || null
    })
    loadDrains()
  } catch (e) {
    error.value = e.message
    window.alert('创建失败: ' + e.message)
  } finally {
    busy.value = false
  }
}

async function confirmLb() {
  busy.value = true
  try {
    current.value = await api.confirmLb(current.value.id, override.value)
  } catch (e) {
    window.alert('确认失败: ' + e.message)
  } finally {
    busy.value = false
  }
}

async function start() {
  busy.value = true
  try {
    current.value = await api.startDrain(current.value.id)
  } catch (e) {
    window.alert('启动失败: ' + e.message)
  } finally {
    busy.value = false
  }
}

async function abort() {
  if (!window.confirm('确认中止? 已断开的客户端不会回滚 —— 也不需要: 它们会重连并恢复会话。')) {
    return
  }
  busy.value = true
  try {
    current.value = await api.abortDrain(current.value.id)
    loadDrains()
  } catch (e) {
    window.alert('中止失败: ' + e.message)
  } finally {
    busy.value = false
  }
}

let timer = null

onMounted(async () => {
  await loadNodes()
  await loadDrains()
  // 驱逐是异步流程, 页面靠轮询推进展示。2 秒与后端的推进节奏一致 ——
  // 更快只是重复读同一份状态, 更慢会让「等待重连」这一段看起来像卡住了
  timer = setInterval(refreshCurrent, 2000)
})

onUnmounted(() => {
  if (timer) {
    clearInterval(timer)
  }
})
</script>
