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

export function num(value) {
  if (value === null || value === undefined || value === '') {
    return '-'
  }
  const n = Number(value)
  if (!Number.isFinite(n)) {
    return String(value)
  }
  if (n < 0) {
    // 负数在本控制台里专门用来表达「读不到」(例如条目数 -1),
    // 显示成 -1 会让它看起来像一个合法数值
    return '未知'
  }
  return n.toLocaleString('zh-CN')
}

export function duration(ms) {
  if (ms === null || ms === undefined || ms < 0) {
    return '-'
  }
  const seconds = Math.floor(ms / 1000)
  if (seconds < 60) {
    return seconds + ' 秒'
  }
  const minutes = Math.floor(seconds / 60)
  if (minutes < 60) {
    return minutes + ' 分 ' + (seconds % 60) + ' 秒'
  }
  const hours = Math.floor(minutes / 60)
  if (hours < 24) {
    return hours + ' 时 ' + (minutes % 60) + ' 分'
  }
  return Math.floor(hours / 24) + ' 天 ' + (hours % 24) + ' 时'
}

export function timeAgo(epochMs) {
  if (!epochMs) {
    return '-'
  }
  const diff = Date.now() - epochMs
  if (diff < 2000) {
    return '刚刚'
  }
  return duration(diff) + '前'
}

export function clock(epochMs) {
  if (!epochMs) {
    return '-'
  }
  const d = new Date(epochMs)
  const pad = (n) => String(n).padStart(2, '0')
  return `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

export function dateTime(epochMs) {
  if (!epochMs) {
    return '-'
  }
  const d = new Date(epochMs)
  const pad = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} `
      + `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

/** 广播形态的中文说明与标签样式 */
export function broadcastMode(mode) {
  switch (mode) {
    case 'all':
      return { label: '广播全部', cls: 'ok', hint: '所有主题都跨节点同步' }
    case 'filtered':
      return { label: '按过滤器广播', cls: 'info', hint: '只有命中白名单的主题跨节点' }
    case 'off':
      return { label: '广播已关闭', cls: 'danger', hint: '消息不跨节点 —— 需确认没有跨节点 MQTT 订阅' }
    case 'cluster-disabled':
      return { label: '单机模式', cls: '', hint: '未启用集群' }
    default:
      return { label: '未知', cls: 'warn', hint: '' }
  }
}

/** 排水状态的中文与标签样式 */
export function drainState(state) {
  switch (state) {
    case 'CREATED':
      return { label: '已创建', cls: 'info' }
    case 'PRECHECKING':
      return { label: '预检中', cls: 'info' }
    case 'PRECHECKED':
      return { label: '待确认停调度', cls: 'warn' }
    case 'LB_CONFIRMED':
      return { label: '待开始驱逐', cls: 'warn' }
    case 'EVICTING':
      return { label: '驱逐中', cls: 'info' }
    case 'WAITING_RECONNECT':
      return { label: '等待重连', cls: 'info' }
    case 'COMPLETED':
      return { label: '已完成', cls: 'ok' }
    case 'TIMEOUT':
      return { label: '超时', cls: 'danger' }
    case 'ABORTED':
      return { label: '已中止', cls: '' }
    case 'FAILED':
      return { label: '失败', cls: 'danger' }
    default:
      return { label: state || '未知', cls: '' }
  }
}
