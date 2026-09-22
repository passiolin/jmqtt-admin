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
/**
 * 前端渲染冒烟测试。
 *
 * <h2>它验证的是「构建成功」与「应用能跑」之间那段空白</h2>
 * 打包能过只说明语法与依赖没问题, 完全不说明: 组件树能挂载、路由解析正确、
 * 模板里的表达式能求值、以及<b>编译期常量都注入齐了</b>。
 * 后端接口可以用 curl 逐条验证, 前端不行 —— 它需要一个 DOM。
 *
 * <p>这里用 happy-dom 造一个 DOM, 把<b>真实构建产物</b>挂上去, 再断言渲染结果。
 * 编译用的是与 <code>npm run build</code> 完全同一份插件(`vue-plugin.mjs`)——
 * 否则「测试那套配置能跑」并不能说明发布的那套能跑。
 *
 * <h2>为什么要吞掉一个环境错误</h2>
 * 本环境里 WebAssembly 无法申请内存(V8 为 Wasm 预留的守卫区超过虚拟内存上限)。
 * happy-dom 加载完成后, 某个库会在后台异步预热一个 HTTP 解析器并撞上这一点,
 * 抛出一个与被测代码毫无关系的 RangeError。那个解析器我们并不使用,
 * 所以这里只吞掉这一种错误并计数 —— <b>其它异常照常让测试失败</b>。
 *
 * <h2>为什么不是真浏览器</h2>
 * 环境里没有可用的浏览器, 也拉不到 Playwright/Puppeteer 的 Chromium。
 * 取含很清楚: happy-dom 不校验 CSS 布局、不执行真实网络, 但足够抓住
 * 「页面全白」「路由跳到空白页」「模板变量未定义」这类最致命的问题。
 *
 * 用法: node tests/render-smoke.mjs
 */
import { build } from 'esbuild'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { baseOptions, createVuePlugins } from '../vue-plugin.mjs'

const here = path.dirname(fileURLToPath(import.meta.url))
const rootDir = path.resolve(here, '..')

let environmentalErrors = 0
process.on('uncaughtException', (error) => {
  if (String(error && error.message).includes('WebAssembly')) {
    environmentalErrors++
    return
  }
  console.error('\n未预期的异常: ', error)
  process.exit(1)
})

const { Window } = await import('happy-dom')

let pass = 0
let fail = 0

function check(name, condition, detail) {
  if (condition) {
    console.log('  [PASS] ' + name)
    pass++
  } else {
    console.log('  [FAIL] ' + name + (detail ? ' — ' + detail : ''))
    fail++
  }
}

// ------------------------------------------------------------------
// 构建(IIFE: 需要一个能直接 eval 的自包含产物)
// ------------------------------------------------------------------

const styleWarnings = []
const result = await build({
  ...baseOptions(rootDir),
  format: 'iife',
  globalName: 'JmqttAdminApp',
  minify: false, // 保留可读的栈, 失败时能定位到源码
  write: false,
  outfile: 'app.js',
  plugins: createVuePlugins(styleWarnings)
})
const bundle = result.outputFiles.find((f) => f.path.endsWith('.js')).text
console.log(`构建产物 ${(bundle.length / 1024).toFixed(1)} KB`)

// ------------------------------------------------------------------
// 接口桩
// ------------------------------------------------------------------

function node(nodeId, connections, extra = {}) {
  return {
    node: nodeId, online: true, ageMs: 800, connections, rawConnections: connections,
    sessions: connections, subscriptions: 13, topicNodes: 20, retainMessages: 3,
    clientEntries: connections, filterEntries: 13, broadcastMode: 'all',
    clusterEnabled: true, clusterBusEnabled: true, uplinkEnabled: true,
    startupAt: Date.now() - 60000, updatedAt: Date.now(),
    summary: { mqttPort: '1901', websocketPort: '8091' },
    warnings: [], ...extra
  }
}

function overviewBody(redisReachable, nodes) {
  return {
    success: true,
    data: {
      redis: {
        reachable: redisReachable,
        error: redisReachable ? '' : 'RedisConnectionException: 连接被拒绝',
        checkedAt: Date.now()
      },
      nodes,
      totals: {
        nodes: nodes.length,
        onlineNodes: nodes.length,
        connections: nodes.reduce((sum, n) => sum + n.connections, 0),
        sessions: nodes.reduce((sum, n) => sum + n.sessions, 0)
      },
      limits: {
        maxEvictPerTask: 5000, defaultEvictBatchSize: 200, defaultEvictIntervalMs: 500,
        reconnectTimeoutSeconds: 180, precheckWindowMs: 8000, nodeStaleAfterMs: 25000
      }
    }
  }
}

const TWO_NODES = [
  node('node-a', 12),
  node('node-b', 0, {
    broadcastMode: 'off',
    warnings: ['集群广播已关闭: 本节点发布的消息不会跨节点']
  })
]

const CLIENT_ENTRY = {
  node: 'node-a',
  clientId: 'drain-0',
  attributes: {
    clientId: 'drain-0', addr: '/127.0.0.1:51000', ver: 4, keepAlive: 60,
    connectedAt: Date.now() - 30000, persistent: true, expiry: 7200,
    lastActiveAt: Date.now(), hasWill: false, subs: 2,
    filters: ['drain/+/data', 'drain/drain-0/status']
  }
}

const TOPIC_ENTRIES = [
  { topicFilter: 'drain/+/data', subscribers: 12, perNode: { 'node-a': 12 }, nodes: ['node-a'] },
  { topicFilter: 'app/+/cmd', subscribers: 2, perNode: { 'node-a': 1, 'node-b': 1 }, nodes: ['node-a', 'node-b'] }
]

function jsonResponse(body, status = 200) {
  return { ok: status < 400, status, text: async () => JSON.stringify(body) }
}

function routeFor(url, state) {
  if (url.includes('/api/redis/status')) {
    // 必须与 overview 的口径一致: 线上这两个接口读的是同一个 AdminRedis.status(),
    // 桩里若一个说可达一个说不可达, 「不可达」那条分支就永远测不到
    const redis = state.overview.data.redis
    return jsonResponse({
      success: true,
      data: { reachable: redis.reachable, error: redis.error, checkedAt: redis.checkedAt }
    })
  }
  if (url.includes('/api/overview')) {
    return jsonResponse(state.overview)
  }
  if (url.includes('/clients')) {
    if (/\/api\/nodes\/[^/]+\/clients/.test(url)) {
      return jsonResponse({
        success: true,
        data: { entries: [CLIENT_ENTRY], cursor: '0', finished: true, total: 12 }
      })
    }
    return jsonResponse({
      success: true,
      data: { entries: [CLIENT_ENTRY], perNode: [], perNodeLimit: 300, truncated: false }
    })
  }
  if (url.includes('/topics')) {
    if (/\/api\/nodes\/[^/]+\/topics/.test(url)) {
      return jsonResponse({
        success: true,
        data: { entries: TOPIC_ENTRIES, cursor: '0', finished: true, total: 13, scanned: 13, truncated: false }
      })
    }
    return jsonResponse({ success: true, data: TOPIC_ENTRIES })
  }
  if (url.includes('/api/drains')) {
    return jsonResponse({ success: true, data: [] })
  }
  if (url.includes('/api/nodes')) {
    return jsonResponse({ success: true, data: state.overview.data.nodes })
  }
  return jsonResponse({ success: true, data: {} })
}

// ------------------------------------------------------------------
// 挂载
// ------------------------------------------------------------------

/** 需要从 happy-dom 同步到 Node 全局的名字 */
const GLOBALS = [
  'document', 'location', 'navigator', 'history', 'localStorage', 'sessionStorage',
  'HTMLElement', 'HTMLInputElement', 'HTMLSelectElement', 'Element', 'Node', 'Text',
  'Comment', 'DocumentFragment', 'SVGElement', 'customElements', 'Event', 'CustomEvent',
  'KeyboardEvent', 'MouseEvent', 'MutationObserver', 'getComputedStyle', 'performance',
  'requestAnimationFrame', 'cancelAnimationFrame', 'CSS', 'screen'
]

async function mount(options = {}) {
  const captured = []
  const state = { overview: options.overview || overviewBody(true, TWO_NODES) }
  const url = 'http://localhost:9100/#' + (options.route || '/overview')

  const win = new Window({ url, settings: { disableJavaScriptEvaluation: true } })
  const div = win.document.createElement('div')
  div.id = 'app'
  win.document.body.appendChild(div)
  if (options.token !== false) {
    win.localStorage.setItem('jmqtt-admin-token', 'smoke-token')
    win.localStorage.setItem('jmqtt-admin-user', 'jmqtt')
  }

  // Vue 的 runtime-dom 在模块求值时就抓取 document/window, 因此必须先铺全局再 eval 产物
  const saved = new Map()
  for (const key of GLOBALS) {
    saved.set(key, globalThis[key])
    try {
      globalThis[key] = win[key]
    } catch (e) {
      /* 只读全局跳过 */
    }
  }
  globalThis.window = win
  globalThis.fetch = async (input) => routeFor(String(input), state)
  win.fetch = globalThis.fetch

  const originalError = win.console.error
  win.console.error = (...args) => {
    captured.push('console.error: ' + args.map(String).join(' '))
    originalError.apply(win.console, args)
  }
  win.console.warn = (...args) => captured.push('console.warn: ' + args.map(String).join(' '))

  try {
    // eslint-disable-next-line no-eval
    ;(0, eval)(bundle)
  } catch (e) {
    captured.push('脚本抛错: ' + e.message)
  }
  // 首屏要等 fetch 的 Promise 落地后才填数据; 两轮足够, 多等一会儿也不贵
  await new Promise((resolve) => setTimeout(resolve, 80))
  await new Promise((resolve) => setTimeout(resolve, 80))

  const html = win.document.getElementById('app').innerHTML
  const restore = () => {
    for (const [key, value] of saved) {
      try {
        globalThis[key] = value
      } catch (e) {
        /* 忽略 */
      }
    }
    try {
      win.happyDOM.abort()
    } catch (e) {
      /* 忽略 */
    }
  }
  return { win, html, captured, restore }
}

function scriptErrors(captured) {
  return captured.filter((c) => c.startsWith('脚本抛错') || c.startsWith('console.error'))
}

// ------------------------------------------------------------------
// 用例
// ------------------------------------------------------------------

console.log('\n########## 1. 未登录 → 登录页')
{
  const { html, captured, restore } = await mount({ route: '/overview', token: false })
  check('渲染出登录卡片', html.includes('jmqtt-admin'))
  check('有密码输入框与自动填充提示',
      html.includes('type="password"') && html.includes('autocomplete="username"'))
  check('有登录按钮', html.includes('登录'))
  check('提示默认账号', html.includes('jmqtt / jmqtt'))
  check('没有脚本错误', scriptErrors(captured).length === 0, scriptErrors(captured).join(' | '))
  restore()
}

console.log('\n########## 2. 已登录 → 集群总览')
{
  const { html, captured, restore } = await mount({ route: '/overview' })
  check('渲染侧边栏导航', html.includes('集群总览') && html.includes('节点排水') && html.includes('主题'))
  check('渲染两个节点卡片', html.includes('node-a') && html.includes('node-b'))
  check('渲染在线节点计数', html.includes('2') && html.includes('在线节点'))
  check('渲染连接数合计', html.includes('12'))
  check('标出广播形态', html.includes('广播全部') && html.includes('广播已关闭'))
  check('展示节点告警文本', html.includes('不会跨节点'))
  check('渲染跨节点重叠检查入口', html.includes('跨节点订阅重叠检查'))
  check('没有脚本错误', scriptErrors(captured).length === 0, scriptErrors(captured).join(' | '))
  restore()
}

console.log('\n########## 3. Redis 不可达 → 横幅, 而不是空列表')
{
  const { html, restore } = await mount({
    route: '/overview',
    overview: overviewBody(false, [])
  })
  check('显示不可读横幅', html.includes('无法读取集群数据'))
  check('横幅带具体错误', html.includes('连接被拒绝'))
  check('横幅说明数据不代表真实状态', html.includes('不代表真实状态'))
  check('同时提示没有发现节点', html.includes('没有发现任何节点'))
  restore()
}

console.log('\n########## 4. 客户端页 → 表格渲染')
{
  const { html, captured, restore } = await mount({ route: '/clients' })
  check('渲染客户端行', html.includes('drain-0'))
  check('渲染来源地址', html.includes('/127.0.0.1:51000'))
  check('渲染协议版本标签', html.includes('v3.1.1'))
  check('标出持久会话', html.includes('持久'))
  check('提供踢下线操作', html.includes('踢下线'))
  check('说明前缀过滤在 Redis 侧完成', html.includes('不会把全部客户端拉回来再筛'))
  check('没有脚本错误', scriptErrors(captured).length === 0, scriptErrors(captured).join(' | '))
  restore()
}

console.log('\n########## 5. 主题页 → 表格渲染 + 跨节点标注')
{
  const { html, captured, restore } = await mount({ route: '/topics' })
  check('渲染两个过滤器', html.includes('drain/+/data') && html.includes('app/+/cmd'))
  check('标出跨节点过滤器', html.includes('跨 2 节点'))
  check('按节点展示订阅者分布', html.includes('node-a:12'))
  check('解释「按节点分开看」的理由', html.includes('前者需要跨节点投递'))
  check('没有脚本错误', scriptErrors(captured).length === 0, scriptErrors(captured).join(' | '))
  restore()
}

console.log('\n########## 6. 排水页 → 向导渲染')
{
  const { html, captured, restore } = await mount({ route: '/drain' })
  check('渲染向导入口', html.includes('新建排水任务'))
  check('目标节点可选', html.includes('node-a') && html.includes('node-b'))
  check('说明单次上限', html.includes('单次上限') && html.includes('5,000'))
  check('提示先小批量试跑', html.includes('count=50'))
  check('解释为何要分四步', html.includes('为什么需要四步'))
  check('没有脚本错误', scriptErrors(captured).length === 0, scriptErrors(captured).join(' | '))
  restore()
}

console.log('\n########## 结果')
console.log(`  通过 ${pass} 项, 失败 ${fail} 项`)
if (environmentalErrors) {
  console.log(`  (环境限制: 吞掉了 ${environmentalErrors} 次与代码无关的 WebAssembly 报错)`)
}
if (styleWarnings.length) {
  console.log('  SFC 告警: ' + styleWarnings.join('; '))
}
process.exit(fail === 0 ? 0 : 1)
