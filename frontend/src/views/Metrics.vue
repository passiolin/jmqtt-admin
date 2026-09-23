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
      <h2>节点运行时指标</h2>
    </div>
    <div class="card-body">
      <div class="row" style="gap:12px;flex-wrap:wrap">
        <label class="field">
          <span>节点</span>
          <select v-model="node" style="width:180px">
            <option v-for="n in nodeIds" :key="n" :value="n">{{ n }}</option>
          </select>
        </label>
        <label class="field">
          <span>开始日期</span>
          <input v-model="dateStart" type="date" style="width:160px">
        </label>
        <label class="field">
          <span>结束日期</span>
          <input v-model="dateEnd" type="date" style="width:160px">
        </label>
        <label class="field">
          <span>时间范围(同日内)</span>
          <select v-model="rangeFilter" style="width:140px" :disabled="dateStart !== dateEnd"
                  :title="dateStart !== dateEnd ? '跨天查询按天粒度展示, 无需日内范围' : ''">
            <option value="all">全部</option>
            <option value="1800000">最近 30 分钟</option>
            <option value="3600000">最近 1 小时</option>
            <option value="10800000">最近 3 小时</option>
            <option value="21600000">最近 6 小时</option>
          </select>
        </label>
        <button class="primary" :disabled="loading || !node" @click="query">
          {{ loading ? '加载中…' : '刷新' }}
        </button>
        <span class="hint" style="margin:0">
          历史由控制台采集器定时(30s/帧)写入 Redis, 这里直接读历史。
          跨度 ≤1 小时显示分钟数据, ≤24 小时显示小时数据, 更长按天 —— 分钟帧保留 12 小时, 小时/天帧保留 30 天。
        </span>
      </div>
    </div>
  </div>

  <div v-if="error" class="banner warn">{{ error }}</div>

  <template v-else-if="snapshot">
    <div v-if="!samples.length" class="banner info">
      所选窗口内没有采集数据 —— 分钟帧保留 12 小时, 小时/天帧保留 30 天;
      采集器每 30s 采一帧, 刚重启的控制台需要等几个周期。
    </div>
    <template v-else>
      <div class="chart-row">
        <div v-for="c in summaryCharts" :key="c.title" class="card chart-card">
          <div class="card-head" style="padding:10px 14px">
            <h2 style="font-size:12.5px">{{ c.title }}</h2>
            <span class="rate-now">{{ displayValue(c.keys, 'counter') }}</span>
          </div>
          <div class="card-body" style="padding:6px 14px 12px">
            <div class="spark-row">
              <div class="spark-yaxis">
                <span>{{ compactNum(yMaxOf(c.keys, 'counter')) }}</span>
                <span>{{ compactNum(yMaxOf(c.keys, 'counter') / 2) }}</span>
                <span>0</span>
              </div>
              <div class="spark-wrap">
                <svg class="spark" viewBox="0 0 120 32" preserveAspectRatio="none"
                     @mousemove="onHoverMove" @mouseleave="onHoverLeave">
                  <line x1="0" y1="16" x2="120" y2="16" class="spark-grid"
                        vector-effect="non-scaling-stroke"/>
                  <polygon v-if="areaOf(c.keys, 'counter')" :points="areaOf(c.keys, 'counter')" class="spark-area"/>
                  <polyline v-if="lineOf(c.keys, 'counter')" :points="lineOf(c.keys, 'counter')"
                            class="spark-line" vector-effect="non-scaling-stroke"/>
                  <line v-if="crossX !== null" :x1="crossX" :y1="0" :x2="crossX" :y2="32"
                        class="spark-cross" vector-effect="non-scaling-stroke"/>
                </svg>
                <span v-if="dotStyle(c.keys, 'counter')" class="spark-dot" :style="dotStyle(c.keys, 'counter')"></span>
                <div v-if="hoverIndex !== null" class="spark-tip" :style="tooltipStyle">
                  {{ tooltipText(c.keys, 'counter') }}
                </div>
              </div>
            </div>
            <div class="spark-axis">
              <span v-for="(t, i) in axisTicks" :key="i">{{ t }}</span>
            </div>
            <div class="hint" style="margin:4px 0 0">
              每帧跨越 1 个{{ snapshot.granularityLabel }}粒度间隔
            </div>
          </div>
        </div>
      </div>

      <div class="card">
        <div class="card-head">
          <h2>
            全部指标
            <span style="font-weight:400;color:var(--text-dim);font-size:12.5px">
              ({{ chartDefs.length }} 项 · {{ samples.length }} 帧 · {{ snapshot.granularityLabel }}粒度 · {{ rangeText }})
            </span>
          </h2>
          <span class="hint" style="margin:0">
            {{ hoverIndex !== null ? '查看时刻 ' + hoverTs + ' (移开鼠标恢复)' : '速率类 = 相邻帧差分; 数值类 = 当帧值; 悬停可跨图联动对齐同一时刻' }}
          </span>
        </div>
        <div class="card-body chart-grid">
          <div v-for="c in chartDefs" :key="c.key" class="metric-chart">
            <div class="metric-head">
              <span class="metric-label" :title="c.key">{{ c.label }}</span>
              <span class="metric-now" :class="{ hovering: hoverIndex !== null }">{{ displayValue(c.key, c.type) }}</span>
            </div>
          <div class="spark-row">
            <div class="spark-yaxis">
              <span>{{ compactNum(yMaxOf(c.key, c.type)) }}</span>
              <span>{{ compactNum(yMaxOf(c.key, c.type) / 2) }}</span>
              <span>0</span>
            </div>
            <div class="spark-wrap">
              <svg class="spark" viewBox="0 0 120 32" preserveAspectRatio="none"
                   @mousemove="onHoverMove" @mouseleave="onHoverLeave">
                <line x1="0" y1="16" x2="120" y2="16" class="spark-grid"
                      vector-effect="non-scaling-stroke"/>
                <polygon v-if="areaOf(c.key, c.type)" :points="areaOf(c.key, c.type)" class="spark-area"/>
                <polyline v-if="lineOf(c.key, c.type)" :points="lineOf(c.key, c.type)"
                          class="spark-line" vector-effect="non-scaling-stroke"/>
                <line v-if="crossX !== null" :x1="crossX" :y1="0" :x2="crossX" :y2="32"
                      class="spark-cross" vector-effect="non-scaling-stroke"/>
              </svg>
              <span v-if="dotStyle(c.key, c.type)" class="spark-dot" :style="dotStyle(c.key, c.type)"></span>
              <div v-if="hoverIndex !== null" class="spark-tip" :style="tooltipStyle">
                {{ tooltipText(c.key, c.type) }}
              </div>
            </div>
          </div>
          <div class="spark-axis">
            <span v-for="(t, i) in axisTicks" :key="i">{{ t }}</span>
          </div>
          <div class="metric-foot">{{ c.type === 'counter' ? '速率/秒' : '数值' }}</div>
          </div>
        </div>
      </div>
    </template>
  </template>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { api } from '../api.js'
import { num } from '../format.js'

const nodes = ref([])
const node = ref('')
const loading = ref(false)
const snapshot = ref(null)
const error = ref(null)
/* 窗口 = [开始日期 00:00, 结束日期 23:59:59]; 同日内再由「时间范围」取最近的 N 分钟 */
const dateStart = ref(new Date().toLocaleDateString('en-CA'))
const dateEnd = ref(new Date().toLocaleDateString('en-CA'))
const rangeFilter = ref('1800000')

/* 跨图联动: 悬停的帧下标 + 光标纵向位置(提示框跟随), 所有图表共用 */
const hoverIndex = ref(null)
const hoverYFrac = ref(null)

const nodeIds = computed(() => nodes.value.map((n) => n.node))
const samples = computed(() => snapshot.value?.samples || [])

/** 汇总卡片: keys 是求和表达式(逐帧速率) */
const summaryCharts = [
  { title: '发布 / 秒', keys: ['qos.publishedQos0', 'qos.publishedQos1', 'qos.publishedQos2'] },
  { title: '投递 / 秒', keys: ['qos.deliveredQos0', 'qos.deliveredQos1', 'qos.deliveredQos2'] },
  {
    title: '丢弃 / 秒',
    keys: ['backpressure.qos0NotWritableDropped', 'backpressure.sendQueueFullDropped',
           'connections.closedAbnormal']
  },
  { title: '连接建立 / 秒', keys: ['connections.opened'] }
]

/** 全部指标的图表清单: counter 画速率(差分), value 画原始值 */
const BASE_CHARTS = [
  { key: 'qos.publishedQos0', label: '发布 QoS 0', type: 'counter' },
  { key: 'qos.publishedQos1', label: '发布 QoS 1', type: 'counter' },
  { key: 'qos.publishedQos2', label: '发布 QoS 2', type: 'counter' },
  { key: 'qos.deliveredQos0', label: '投递 QoS 0', type: 'counter' },
  { key: 'qos.deliveredQos1', label: '投递 QoS 1', type: 'counter' },
  { key: 'qos.deliveredQos2', label: '投递 QoS 2', type: 'counter' },
  { key: 'qos.deliveredQos12Permille', label: '投递 QoS 1/2 占比(‰)', type: 'value' },
  { key: 'qos.publishedQos12Permille', label: '发布 QoS 1/2 占比(‰)', type: 'value' },
  { key: 'backpressure.sendQueueEnqueued', label: '进入排队(窗口用尽)', type: 'counter' },
  { key: 'backpressure.qos0NotWritableDropped', label: 'QoS 0 写缓冲满丢弃', type: 'counter' },
  { key: 'backpressure.sendQueueFullDropped', label: '发送队列满丢弃', type: 'counter' },
  { key: 'connections.opened', label: '连接建立', type: 'counter' },
  { key: 'connections.closedGraceful', label: '断开(优雅)', type: 'counter' },
  { key: 'connections.closedAbnormal', label: '断开(异常/超时)', type: 'counter' },
  { key: 'connections.rejectedAuth', label: '拒绝(认证失败)', type: 'counter' },
  { key: 'connections.rejectedClientId', label: '拒绝(clientId 非法)', type: 'counter' },
  { key: 'connections.takeoverLocal', label: '连接接管(本节点顶旧)', type: 'counter' },
  { key: 'connections.takeoverRemote', label: '连接接管(他节点迁入)', type: 'counter' },
  { key: 'connections.active', label: '当前连接数', type: 'value' },
  { key: 'sessions.active', label: '当前会话数', type: 'value' },
  { key: 'subscriptions.active', label: '当前订阅数', type: 'value' }
]

/** 节点实际上报了哪些键就画哪些(基准清单在前, 节点附加的 bus.* 等跟在后面) */
const chartDefs = computed(() => {
  const latest = latestFrame.value?.values || {}
  if (!Object.keys(latest).length) return []
  const known = new Set(BASE_CHARTS.map((c) => c.key))
  const extras = Object.keys(latest)
      .filter((k) => !known.has(k))
      .map((k) => ({
        key: k,
        label: '集群总线 · ' + k.slice(4),
        type: /size|capacity|threads/i.test(k) ? 'value' : 'counter'
      }))
  return [...BASE_CHARTS, ...extras].filter((c) => c.key in latest)
})

const latestFrame = computed(() => {
  const s = samples.value
  return s.length ? s[s.length - 1] : null
})

const rangeText = computed(() => {
  const s = samples.value
  if (s.length < 1) return ''
  const hhmm = (ts) => {
    const d = new Date(ts)
    return s.length > 1 && d.getHours() === 0 && d.getMinutes() === 0
        ? d.toLocaleDateString()
        : d.toTimeString().slice(0, 5)
  }
  return s.length === 1
      ? hhmm(s[0].ts)
      : `${hhmm(s[0].ts)} – ${hhmm(s[s.length - 1].ts)}`
})

const hoverTs = computed(() => {
  if (hoverIndex.value === null || !samples.value.length) return ''
  const i = Math.min(hoverIndex.value, samples.value.length - 1)
  return new Date(samples.value[i].ts).toLocaleTimeString()
})

/** 查询窗口。粒度由后端按跨度决定(≤1h 分钟 / ≤24h 小时 / 更长按天) */
function computeWindow() {
  let start = new Date(`${dateStart.value}T00:00:00`).getTime()
  let end = new Date(`${dateEnd.value}T23:59:59.999`).getTime()
  if (Number.isNaN(start) || Number.isNaN(end)) {
    return null
  }
  if (start > end) {
    ;[start, end] = [end, start]
  }
  if (dateStart.value === dateEnd.value && rangeFilter.value !== 'all') {
    const cap = Math.min(Date.now(), end)
    end = cap
    start = Math.max(start, cap - Number(rangeFilter.value))
  }
  return { from: start, to: end }
}

/**
 * 与帧对齐的等长序列(长度 = 帧数), 这是跨图联动的基础 ——
 * 所有图表的横轴都是同一组帧时刻, 悬停下标一致即时刻一致。
 * counter 首帧没有「上一帧」可差分, 记 0; 数值回绕(节点重启)按 0 处理。
 */
function frameSeries(keys, asCounter) {
  const s = samples.value
  const out = new Array(s.length)
  for (let i = 0; i < s.length; i++) {
    if (!asCounter) {
      out[i] = s[i].values[keys[0]] ?? 0
      continue
    }
    if (i === 0) {
      out[i] = 0
      continue
    }
    const dt = s[i].ts - s[i - 1].ts
    let d = 0
    if (dt > 0) {
      for (const k of keys) {
        d += (s[i].values[k] ?? 0) - (s[i - 1].values[k] ?? 0)
      }
    }
    out[i] = dt > 0 ? Math.max(0, d) * 1000 / dt : 0
  }
  return out
}

function seriesFor(keys, type) {
  return frameSeries(Array.isArray(keys) ? keys : [keys], type === 'counter')
}

function lineOf(keys, type) {
  return linePoints(seriesFor(keys, type))
}

/** 纵轴刻度上限: 窗口内最大值 */
function yMaxOf(keys, type) {
  const vals = seriesFor(keys, type)
  return vals.length ? Math.max(...vals, 0) : 0
}

/** 纵轴数字紧凑化: 避免长数字挤在窄轴上 */
function compactNum(v) {
  if (v >= 1e8) return (v / 1e8).toFixed(1).replace(/\.0$/, '') + '亿'
  if (v >= 1e4) return (v / 1e4).toFixed(1).replace(/\.0$/, '') + '万'
  if (v >= 100) return String(Math.round(v))
  if (Number.isInteger(v)) return String(v)
  return v.toFixed(1)
}

function areaOf(keys, type) {
  const line = lineOf(keys, type)
  return line ? `${line} 120,31 0,31` : ''
}

/** 折线点坐标: 归一化到 120x32 视窗 */
function linePoints(vals) {
  if (!vals || vals.length < 2) return ''
  const max = Math.max(...vals, 0.0001)
  const step = 120 / (vals.length - 1)
  return vals.map((v, i) => {
    const x = (i * step).toFixed(1)
    const y = (31 - Math.min(1, v / max) * 30).toFixed(1)
    return `${x},${y}`
  }).join(' ')
}

/** 悬停十字线的 x 坐标(所有图表一致) */
const crossX = computed(() => {
  if (hoverIndex.value === null || samples.value.length < 2) {
    return null
  }
  return (hoverIndex.value / (samples.value.length - 1) * 120).toFixed(1)
})

/** 坐标轴刻度: 全部图表共用同一组帧时刻, 取首尾加中间均分点 */
const axisTicks = computed(() => {
  const s = samples.value
  if (s.length < 2) return []
  const byDay = snapshot.value?.granularity === 'day'
  const fmt = (ts) => {
    const d = new Date(ts)
    return byDay ? `${d.getMonth() + 1}-${d.getDate()}` : d.toTimeString().slice(0, 5)
  }
  const ticks = []
  const segments = 4
  for (let k = 0; k <= segments; k++) {
    ticks.push(fmt(s[Math.round((s.length - 1) * k / segments)].ts))
  }
  return ticks
})

/** 提示框位置: 跟随光标, 靠边时收进来, 上方空间不足时落到光标下方 */
const tooltipStyle = computed(() => {
  if (hoverIndex.value === null || !samples.value.length) {
    return {}
  }
  const n = samples.value.length
  const frac = n > 1 ? hoverIndex.value / (n - 1) : 0
  const yFrac = hoverYFrac.value ?? 0.5
  const xShift = frac < 0.15 ? '-10%' : (frac > 0.85 ? '-90%' : '-50%')
  const transform = yFrac < 0.25 ? `translate(${xShift}, 14px)` : `translate(${xShift}, -110%)`
  return { left: frac * 100 + '%', top: yFrac * 100 + '%', transform }
})

/** 提示框内容: 时刻 + 该图在这一帧的具体值 */
function tooltipText(keys, type) {
  const vals = seriesFor(keys, type)
  if (!vals.length) return ''
  const i = hoverIndex.value !== null ? Math.min(hoverIndex.value, vals.length - 1) : 0
  const ts = samples.value[i] ? new Date(samples.value[i].ts).toLocaleTimeString() : ''
  const v = type === 'counter' ? `${fmtRate(vals[i])}/秒` : num(vals[i])
  return `${ts} · ${v}`
}

/** 悬停圆点(HTML 覆盖层, 百分比定位 —— SVG 拉伸会把圆点变成椭圆) */
function dotStyle(keys, type) {
  if (hoverIndex.value === null) return null
  const vals = seriesFor(keys, type)
  if (vals.length < 2) return null
  const i = Math.min(hoverIndex.value, vals.length - 1)
  const max = Math.max(...vals, 0.0001)
  const ratio = Math.min(1, vals[i] / max)
  const left = (i / (vals.length - 1)) * 100
  const top = ((31 - ratio * 30) / 32) * 100
  return { left: left + '%', top: top + '%' }
}

function onHoverMove(evt) {
  const n = samples.value.length
  if (!n) return
  const rect = evt.currentTarget.getBoundingClientRect()
  if (rect.width > 0) {
    const frac = (evt.clientX - rect.left) / rect.width
    hoverIndex.value = Math.max(0, Math.min(n - 1, Math.round(frac * (n - 1))))
  }
  if (rect.height > 0) {
    hoverYFrac.value = Math.max(0, Math.min(1, (evt.clientY - rect.top) / rect.height))
  }
}

function onHoverLeave() {
  hoverIndex.value = null
  hoverYFrac.value = null
}

/** 卡片右上角的数值: 悬停时显示悬停帧的值, 否则显示最新值 */
function displayValue(keys, type) {
  const vals = seriesFor(keys, type)
  if (!vals.length) return '-'
  const i = hoverIndex.value !== null
      ? Math.min(hoverIndex.value, vals.length - 1)
      : vals.length - 1
  const v = vals[i]
  return type === 'counter' ? fmtRate(v) : num(v)
}

function fmtRate(v) {
  if (v == null) return '-'
  return v >= 10 ? num(Math.round(v)) : v.toFixed(1)
}

async function query() {
  if (!node.value) return
  const win = computeWindow()
  if (!win) return
  loading.value = true
  error.value = null
  hoverIndex.value = null
  try {
    const data = await api.nodeMetricsSeries(node.value, win.from, win.to)
    snapshot.value = {
      node: data.node,
      // 旧版后端没有粒度字段: 兜底成分钟粒度, 只影响提示文字不影响数据
      granularityLabel: data.granularityLabel || '分钟',
      sampleIntervalMs: data.intervalMs || data.collectIntervalMs || 30000,
      samples: data.samples || []
    }
  } catch (e) {
    snapshot.value = null
    error.value = '读取失败: ' + e.message
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  try {
    nodes.value = await api.nodes()
    if (nodeIds.value.length >= 1) {
      node.value = nodeIds.value[0]
      await query()
    }
  } catch (e) {
    nodes.value = []
  }
})

/* 筛选条件变化自动重查 */
watch([dateStart, dateEnd, rangeFilter], () => {
  if (node.value) {
    query()
  }
})
</script>

<style scoped>
.chart-row {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
  margin-bottom: 10px;
}

.chart-card {
  color: var(--primary);
}

.rate-now {
  font-size: 13px;
  font-weight: 600;
  color: var(--text);
}

.spark-row {
  display: flex;
  gap: 6px;
}

.spark-yaxis {
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  align-items: flex-end;
  font-size: 9.5px;
  color: var(--text-faint);
  flex: none;
  min-width: 30px;
  text-align: right;
}

.spark-wrap {
  position: relative;
  flex: 1;
  border-bottom: 1px solid var(--border);
}

.spark {
  width: 100%;
  height: 160px;
  display: block;
  cursor: crosshair;
}

.chart-card .spark {
  height: 170px;
}

.spark-axis {
  display: flex;
  justify-content: space-between;
  font-size: 10px;
  color: var(--text-faint);
  margin-top: 3px;
}

.spark-tip {
  position: absolute;
  z-index: 5;
  background: var(--bg-card, #fff);
  border: 1px solid var(--border);
  border-radius: 6px;
  padding: 3px 8px;
  font-size: 11px;
  font-weight: 600;
  color: var(--text);
  white-space: nowrap;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.15);
  pointer-events: none;
}

.chart-card .spark {
  height: 170px;
}

.spark-area {
  fill: currentColor;
  opacity: 0.12;
  stroke: none;
}

.spark-grid {
  stroke: var(--border);
  stroke-width: 1px;
  stroke-dasharray: 3 3;
}

.spark-line {
  fill: none;
  stroke: currentColor;
  stroke-width: 2px;
}

.spark-cross {
  stroke: var(--text-faint);
  stroke-width: 1px;
  stroke-dasharray: 4 4;
}

.spark-dot {
  position: absolute;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: currentColor;
  border: 2px solid var(--bg-card, #fff);
  transform: translate(-50%, -50%);
  pointer-events: none;
}

.chart-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
  padding-top: 12px;
}

.metric-chart {
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 8px 10px;
  color: var(--primary);
}

.metric-head {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  gap: 6px;
}

.metric-label {
  font-size: 12px;
  font-weight: 500;
  color: var(--text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.metric-now {
  font-size: 12.5px;
  font-weight: 600;
  color: var(--text);
  flex: none;
}

.metric-now.hovering {
  color: var(--primary);
}

.metric-foot {
  font-size: 10.5px;
  color: var(--text-faint);
  margin-top: 2px;
}
</style>
