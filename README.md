# jmqtt-admin

MQTT 集群管理台。看到集群里有什么、把某个节点的客户端迁走。

由两部分组成：

```
jmqtt-admin/
├── backend/    Spring Boot 2.7.18 + Java 21, 只提供 /api, 不含任何页面
└── frontend/   Vue 3 + Vite, 产物在 frontend/dist 由前端独立部署(Nginx/CDN)
```

---

## 一、它不直连 broker

控制台的数据全部来自 Redis（broker 发布的状态），要执行的动作也写进 Redis（broker 轮询的命令队列）。它**没有**任何直连 broker 的调用。

这么做的理由、以及 Redis 键布局的完整约定，见
[`../jmqtt-broker/docs/admin-console.md`](../jmqtt-broker/docs/admin-console.md)。

一个直接后果：**控制台只需要能连上 Redis**，不需要访问任何 broker 的管理端口。

---

## 二、前置条件

1. **Redis**：与控制台、各 broker 指向**同一个库、同一个 key 前缀**。
2. **broker 侧开启管理面**（`jmqtt-broker/src/main/resources/application.yml`）：

```yaml
jmqtt:
  broker:
    redis:
      enabled: true        # 管理面依赖它
      key-prefix: jmqtt    # ★ 必须与控制台的 jmqtt.admin.redis.key-prefix 一致
    admin:
      enabled: true        # 默认就是 true, 但只有 Redis 可用时才真正生效
```

> ⚠️ `key-prefix` 配错**不会报错**，只会表现为「控制台里一个节点都没有」。两个工程之间唯一的契约就是键长什么样。

---

## 三、构建与运行

### 后端

```bash
cd backend
mvn clean package
java -jar target/jmqtt-admin-backend-0.1.0-SNAPSHOT.jar
```

启动后监听 `9100`，前端已包含在 jar 内：<http://127.0.0.1:9100>

### 前端

开发模式（带代理，改完热更新）：

```bash
cd frontend
npm install
npm run dev        # http://127.0.0.1:5173, /api 代理到 9100
```

生产构建，两条路径产出一致（都输出到 `backend/src/main/resources/static`）：

```bash
npm run build        # 标准路径: Vite
npm run build:nowasm # 备用路径: esbuild + @vue/compiler-sfc
```

**为什么有两条构建路径。** Vite 的构建链路会经 undici 触碰 WebAssembly。在某些受限环境里（虚拟内存被 ulimit 限制、或禁用 Wasm 的运行沙箱）Wasm 无法申请内存，构建会以
`WebAssembly.instantiate(): Out of memory` 失败 —— 一个与前端代码毫无关系的错误。备用路径全程不碰 Wasm 也不发网络请求。

已知差异：备用路径不支持 `<style scoped>`（遇到会警告并按全局样式输出）。本项目所有样式都在 `src/styles.css`，因此这是有意的取舍。

### 前端测试

```bash
npm test        # 渲染冒烟测试(tests/render-smoke.mjs)
```

用 happy-dom 造 DOM，把**真实构建产物**挂上去，断言 6 组共 35 项渲染结果：
登录页、集群总览、**Redis 不可达横幅**、客户端表格、主题表格、排水向导；每组都断言无脚本错误。

它验证的是「构建成功」与「应用能跑」之间那段空白 —— 打包能过完全不说明组件树挂得住、
路由解析正确、编译期常量注入齐了。写这个测试的过程立刻抓到一个真实缺陷：
备用构建路径漏了 `process.env.NODE_ENV`，Vue 的开发期检查在浏览器里直接 `ReferenceError`，
表现为**构建成功、页面全白**。

> 编译用的是与 `npm run build` 完全同一份插件（`vue-plugin.mjs`），否则「测试那套配置能跑」
> 并不能说明发布的那套能跑。

---

## 四、登录

账号密码来自后端配置文件（`jmqtt.admin.username` / `password`），默认 `jmqtt` / `jmqtt`。

登录换一个内存令牌（`Authorization: Bearer <token>`）。令牌只存内存，因此**控制台重启即撤销所有登录态** —— 对管理台而言这可以接受，甚至更好。

> 这套鉴权只适用于内网管理台：没有密码强度策略、失败锁定、审计留痕。若要暴露到更大范围，应当接企业统一登录，而不是在这里加字段。

---

## 五、功能

### 集群总览 `/overview`

节点卡片（连接数 / 会话数 / 订阅数 / 主题过滤器数 / 心跳 / **集群广播形态**）、需要关注的告警、以及**跨节点订阅重叠检查**。

后者用于验证「关闭集群广播」是否安全：同一个过滤器若在多个节点上都有订阅者，那些订阅者需要跨节点投递，广播关掉后它们会静默收不到消息。

### 客户端 `/clients`

按节点或跨节点列出在线客户端：来源地址、协议版本、心跳、已在线时长、订阅数、在途/排队积压。支持 clientId 前缀过滤（在 Redis 侧用 MATCH 完成，不拉全量再筛）与单条**踢下线**。

> 跨节点视图是「每节点各取前 N 条后合并」，**不是**「全集群前 N 条」—— 页面上会如实标注每节点的取数情况。

### 主题 `/topics`

按节点或跨节点列出**主题过滤器**（订阅关系），订阅者数**按节点分开显示**：「3 个节点各 1 个」与「1 个节点 3 个」对集群的含义完全不同 —— 前者需要跨节点投递，后者不需要。

### 节点排水 `/drain`

把某节点的客户端迁到其他节点。四步流程：**预检 → 人工确认已停调度 → 分批驱逐 → 等待重连并校验**。

- 预检观测「是否仍有新连接进入」，用于发现「停调度其实没生效」—— 否则驱逐会变成「断开→从同一入口回连」的死循环；
- 驱逐分批、限速，单次有硬上限（默认 5000 条）；
- 校验同时看目标节点下降量与其他节点上升量 —— 只看前者无法区分「迁走了」与「连不上了」；
- 全过程有时间线与连接数对照表，超时时给出具体诊断。

---

## 六、配置

`backend/src/main/resources/application.yml`。要点：

| 配置 | 说明 |
|---|---|
| `jmqtt.admin.username` / `password` | 登录凭据 |
| `jmqtt.admin.redis.key-prefix` | ★ 必须与 broker 一致 |
| `jmqtt.admin.max-evict-per-task` | 单次驱逐上限。与 broker 的同名配置是双重闸门：控制台先拦一次并给出建议，broker 再兜一次防止绕过控制台 |
| `jmqtt.admin.node-stale-after-ms` | 心跳超过多久视为离线。不能只靠「键是否存在」：进程卡死时键还没到 TTL |
| `jmqtt.admin.precheck-window-ms` / `precheck-growth-threshold` | 预检窗口与告警阈值 |
| `jmqtt.admin.reconnect-timeout-seconds` | 等待客户端重连到其他节点的时限 |

**控制台没有「节点列表」配置**：节点集合是从 Redis 里发现的。静态列出会带来三个问题 —— 扩容要改两处并重启控制台（重启会丢失进行中的排水流程状态）、「配置里有但实际不存在」的节点会被显示成离线而不是不存在、节点下线后配置项永远留着。
