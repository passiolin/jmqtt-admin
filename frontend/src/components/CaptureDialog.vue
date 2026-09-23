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
  <div v-if="open" class="modal-mask" @click.self="$emit('close')">
    <div class="modal">
      <div class="modal-head">
        <h2 class="mono" style="font-size:13.5px;word-break:break-all">{{ filter }}</h2>
        <button class="small" @click="$emit('close')">关闭</button>
      </div>
      <div class="modal-body">
        <dl class="kv two-col" style="margin-top:10px">
          <div><dt>节点</dt><dd><span class="tag">{{ node }}</span></dd></div>
          <div v-if="clientId"><dt>客户端</dt><dd class="mono">{{ clientId }}</dd></div>
          <div v-else><dt>过滤器</dt><dd class="mono" style="word-break:break-all">{{ filter }}</dd></div>
        </dl>
        <div class="drawer-section">监听参数</div>
        <div class="row" style="gap:12px;flex-wrap:wrap">
          <label class="field">
            <span>时长(分钟, 1~1440)</span>
            <input v-model.number="duration" type="number" min="1" max="1440" style="width:130px">
          </label>
          <label class="field">
            <span>条数上限(1~10000)</span>
            <input v-model.number="maxMessages" type="number" min="1" max="10000" style="width:130px">
          </label>
        </div>
        <div class="hint" style="margin:10px 0">
          {{ clientId
              ? '客户端维度: 监听该客户端发布(pub)与订阅(sub)的全部消息, 记录带方向标识。'
              : 'topic 维度: 监听匹配该过滤器的发布消息。' }}
          消息(含 2KB payload 预览)写入 Redis, 3 天后自动过期。
          默认 10 分钟 / 1000 条, 到时或满员自动停止。
        </div>
        <button class="primary" :disabled="submitting" @click="submit">
          {{ submitting ? '下发中…' : '开始监听' }}
        </button>
        <div v-if="result" class="banner" :class="result.ok ? 'info' : 'warn'" style="margin-top:10px">
          {{ result.text }}
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '../api.js'

const props = defineProps({
  open: Boolean,
  node: String,
  filter: String,
  clientId: String
})
const emit = defineEmits(['close', 'started'])

const router = useRouter()
const duration = ref(10)
const maxMessages = ref(1000)
const submitting = ref(false)
const result = ref(null)

async function submit() {
  submitting.value = true
  result.value = null
  try {
    const { commandId, captureId } = await api.startCapture(props.node, props.filter,
        duration.value, maxMessages.value, props.clientId)
    // 轮询命令确认(与踢下线同一模式)
    for (let i = 0; i < 20; i++) {
      await new Promise((r) => setTimeout(r, 400))
      const cmd = await api.command(commandId)
      if (cmd.state === 'COMPLETED') {
        result.value = { ok: true, text: '监听已开始, 正在跳转…' }
        emit('started', captureId)
        // 统一跳转: 成功即去消息监听页并打开该任务的消息流, 不让用户自己找
        setTimeout(() => {
          emit('close')
          router.push({ path: '/captures', query: { capture: captureId } })
        }, 600)
        return
      }
      if (cmd.state && cmd.state !== 'PENDING' && cmd.state !== 'RECEIVED') {
        result.value = { ok: false, text: '节点拒绝: ' + (cmd.message || cmd.state) }
        return
      }
    }
    result.value = { ok: false, text: '等待节点确认超时, 请到「消息监听」页确认' }
  } catch (e) {
    result.value = { ok: false, text: '下发失败: ' + e.message }
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped>
.modal-mask {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.35);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 60;
}

.modal {
  width: min(560px, 92vw);
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
}
</style>
