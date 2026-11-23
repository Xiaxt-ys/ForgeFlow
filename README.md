# ForgeFlow

面向 Agent 长任务的服务端执行平台：把模型与工具调用抽象成可提交、可查询、可取消、可恢复的任务，
API 与 Worker 分离，支持多 Agent 编排、工具治理、审批挂起恢复与会话记忆。

Agent 循环使用 [agents-flex](https://github.com/agents-flex/agents-flex) 的 `AgentRunner`（工具调用、审批、挂起/恢复），
ForgeFlow 自己实现任务 API、RabbitMQ 分发、MySQL 状态机、Redis 租约与 fencing、DAG 编排、工具治理、
会话记忆（bge-m3 + Qdrant）和模型首包降级。

## 模块

```
forgeflow-common     任务状态、DTO、AgentExecutor/Dispatcher 扩展点
forgeflow-store      ff_task / ff_outbox schema、条件更新仓储、Redis/内存租约
forgeflow-scheduler  RabbitMQ 交换机/队列/DLX、分发器、悬挂任务扫描
forgeflow-api        POST/GET/cancel/result 四类任务接口
forgeflow-worker     TaskWorker、AgentRunner 适配、上下文治理、首包路由、工具权限
forgeflow-bootstrap  Spring Boot 启动、本地/生产 profile
vendor/agents-flex   基座源码（Gitee 镜像，Apache-2.0）；仅本地参考，已在 .gitignore 排除
```

## 本地跑（无需中间件）

```bash
mvn clean package -DskipTests
java -jar forgeflow-bootstrap/target/forgeflow-bootstrap-0.1.0-SNAPSHOT.jar --server.port=18080
```

默认使用 H2 内存库、内存租约、进程内分发、EchoAgentExecutor。

```bash
# 提交
curl -X POST http://localhost:18080/api/tasks \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: demo-1" \
  -d '{"tenantId":"tenant-a","prompt":"hello forgeflow"}'

# 查询 / 结果 / 取消
curl http://localhost:18080/api/tasks/{id}
curl http://localhost:18080/api/tasks/{id}/result
curl -X POST http://localhost:18080/api/tasks/{id}/cancel
```

取消演示：用 `--forgeflow.agent.echo.delay-ms=3000` 启动，提交后 0.5 秒取消，观察 `CANCELLING -> CANCELLED`。

## 生产 profile（MySQL + Redis + RabbitMQ）

```bash
docker compose up -d
java -jar forgeflow-bootstrap/target/forgeflow-bootstrap-0.1.0-SNAPSHOT.jar \
  --spring.profiles.active=prod
```

`prod` 使用 MySQL `forgeflow` 库、Redis 租约、RabbitMQ 分发、`forgeflow.agent.executor=agents-flex`。
需要提供一个 `ChatModel` Bean；只验证基础设施链路时加 `--forgeflow.agent.executor=echo`。

已实测（Docker Desktop，MySQL 8 / Redis 7 / RabbitMQ 3）：

```bash
java -jar forgeflow-bootstrap/target/forgeflow-bootstrap-0.1.0-SNAPSHOT.jar   --spring.profiles.active=prod --server.port=18084 --forgeflow.agent.executor=echo
```

- 提交任务后状态走 `QUEUED -> CLAIMED -> SUCCEEDED`；
- MySQL `ff_task` 落库，`worker_id=local-worker-1`，result 正确；
- RabbitMQ `ff.task.queue` 消费后清空，`ff.task.retry.queue`/`ff.task.dlq` 为 0；
- Redis `ff:task:lease:*` 任务结束后释放；
- Trace 的 `task.submit -> task.execute -> claim/lease/agent/commit` 共享同一个 traceId；
- `ff_outbox` 两行均为 `SENT`，说明任务与投递同事务；
- OutboxRelay 用 **Java 侧时间**比较 `next_retry_at`：MySQL 会话时区与驱动写入时区不一致时，
  `CURRENT_TIMESTAMP` 会把到期时间看成 8 小时后，relay 永远不触发（Rabbit 停机测试发现，已修）；
- `max-inflight=1` 时，第二个任务返回 429，首个任务成功后第三个任务恢复 202，Redis 计数回到 0。

MySQL 建表脚本是 `forgeflow-store/src/main/resources/schema-mysql.sql`；本地 H2 仍用 `schema.sql`。

RabbitMQ 拓扑：

```
task_exchange --task.created--> task_queue --消费失败 nack--> retry_exchange
                                                              --TTL 5s--> task_exchange
                                                              --超过重试--> dlq
```

## 接入 DeepSeek（真实模型）

`agents-flex-chat-deepseek` 走 OpenAI 兼容协议，配置在 `forgeflow.llm.deepseek.*`；密钥只从环境变量读。

```bash
export DEEPSEEK_API_KEY=sk-xxx            # PowerShell: $env:DEEPSEEK_API_KEY="sk-xxx"
export DEEPSEEK_ENABLED=true
java -jar forgeflow-bootstrap/target/forgeflow-bootstrap-0.1.0-SNAPSHOT.jar \
  --forgeflow.agent.executor=agents-flex --forgeflow.mcp.enabled=true
```

关键参数（都可用环境变量或 `--forgeflow.llm.deepseek.*` 覆盖）：

| 参数 | 默认 | 说明 |
|---|---|---|
| `model` | `deepseek-v4-flash` | 只用 flash，最便宜的档位 |
| `fallback-model` | 空 | 默认不降级；填 `deepseek-v4-pro` 才启用候选切换 |
| `thinking` | `true` | 服务端默认开启思考模式 |
| `reasoning-effort` | `low` | 最低档；可选 `low` / `high` / `max` |
| `temperature` | `0.3` | 仅在 `thinking=false` 时发送，思考模式下服务端会忽略 |
| `first-packet-timeout-ms` | `180000` | 思考模式首包慢，别设成几十秒 |

每次调用由 `DeepSeekModelConfig` 的拦截器补 `reasoning_effort` 和 `max_tokens`；
`thinking` 走 agents-flex 的 `deepseek` 协议（请求体 `{"thinking":{"type":"enabled"}}`）。
`RoutingChatModel` 在外面包一层，负责候选切换、首包超时和三态熔断。

链路已验证（用无效 key 跑一次真实请求）：任务状态从 `RUNNING` 落为 `FAILED`，
错误信息为 DeepSeek 返回的 `Authentication Fails ... (request_id: ...)`，说明请求体、鉴权头、路径都正确；
换成有效 key 即可跑通完整 Agent 回合。请求体断言见 `DeepSeekModelConfigTest`（thinking / reasoning_effort / temperature 规则）。

### 怎么确认当前调用的是哪个模型、什么档位

**1. 启动日志**（每次启动打印一次，`api-key` 不出现）：

```text
deepseek configured model=deepseek-v4-flash fallback=(none) thinking=true effort=low
                    endpoint=https://api.deepseek.com/chat/completions timeout=180000ms
```

**2. 每次请求的明细**（默认 DEBUG，按需打开）：

```bash
--logging.level.com.forgeflow.bootstrap.DeepSeekModelConfig=DEBUG
```

```text
llm.request model=deepseek-v4-flash thinking=true effort=low maxTokens=4096
```

思考模式下还会带 `{"thinking":{"type":"enabled"}}` 和 `reasoning_effort`，非思考模式则改发 `temperature`。

**3. 账户侧核对**：`GET https://api.deepseek.com/models`（带 Bearer key）能看到账户实际可用的模型名；
DeepSeek 控制台的用量统计也会按模型分列，可以和任务时间对得上。

**4. 任务侧核对 token**：trace 日志里 `agent.execute` 会带 `promptTokens` / `completionTokens`，
真实调用一定大于 0；如果为 0，说明走的是 Echo 或 mock。

**5. 区分真实与 mock**：mock 返回固定文本 `mock-final: done`，真实模型返回的是它自己的回答。

### 一步一步跑（本地 profile，不需要 Docker）

```powershell
# 窗口 1：启动
$env:DEEPSEEK_API_KEY = "sk-..."
$env:DEEPSEEK_ENABLED = "true"
$env:FORGEFLOW_LOCAL_TOOLS = "true"
java -Dfile.encoding=UTF-8 -jar forgeflow-bootstrap\target\forgeflow-bootstrap-0.1.0-SNAPSHOT.jar `
  --server.port=18080 --forgeflow.agent.executor=agents-flex `
  --forgeflow.mcp.enabled=false --management.health.rabbit.enabled=false `
  --logging.level.com.forgeflow.bootstrap.DeepSeekModelConfig=DEBUG

# 窗口 2：提交并轮询（脚本会打印 taskId / 状态 / result）
.\scripts\try-task.ps1 -Prompt "SEARCH 一下 redis 租约"
```

> Windows + Java 17 默认用系统编码（中文系统是 GBK）写日志，重定向到文件后中文会乱码；
> `-Dfile.encoding=UTF-8` 让日志文件保持 UTF-8。

窗口 1 里先确认两行：`Started ForgeFlowApplication` 和 `deepseek configured ...`。

常用日志过滤：

```powershell
Select-String -Path run.log -Pattern "deepseek configured|llm.request|tool.audit|trace.end name=agent.execute"
```

| 日志 | 含义 |
|---|---|
| `deepseek configured model=... effort=...` | 本次启动实际生效的模型与档位 |
| `llm.request model=... thinking=... effort=...` | 每次真正发出的模型请求（需 DEBUG） |
| `tool.audit decision=ALLOW ... tool=search capability=READ` | 工具调用与权限判定 |
| `trace.end name=agent.execute ... promptTokens=...` | 本轮 token 消耗，为 0 说明没走真实模型 |
| `plan incomplete: x/y subtasks succeeded` | 编排失败，x 成功数 / y 总数 |

### 接真 key 的推荐顺序

```bash
export DEEPSEEK_API_KEY=sk-...
export DEEPSEEK_ENABLED=true
export FORGEFLOW_LOCAL_TOOLS=true          # 先不接 MCP，减少变量

java -jar forgeflow-bootstrap/target/forgeflow-bootstrap-0.1.0-SNAPSHOT.jar \
  --forgeflow.agent.executor=agents-flex \
  --forgeflow.mcp.enabled=false \
  --management.health.rabbit.enabled=false
```

按顺序跑三条：

| # | 提交内容 | 期望 |
|---|---|---|
| 1 | `SEARCH 一下 redis 租约` | 模型调用 `search` 工具，审计 `ALLOW ... capability=READ`，任务 `SUCCEEDED` |
| 2 | `请完成代码任务：1. 分析现有实现 2. 修复问题 3. 写总结` | 拆成 3 个子任务，日志出现 `plan.incomplete` 之外的正常汇总 |
| 3 | 任意单句闲聊 | 走单轮，不拆任务 |

打开 DEBUG 后，第 1 条应该能看到 `llm.request model=... effort=low` 以及后续的 `tool.audit`。

### 常见失败与判断

| 现象 | 判断 |
|---|---|
| `401 Authentication Fails` | key 错误或没带 `Bearer` |
| `402 / Insufficient Balance` | 账户余额 |
| `400 model not found` | 账户没有该模型；换 `DEEPSEEK_MODEL` 或用 `/models` 查 |
| `400` 且提到 `thinking` / `reasoning_effort` | 该模型/版本不支持思考参数，改 `--forgeflow.llm.deepseek.thinking=false` |
| 一直 `WAITING_FOR_MODEL` 到超时 | 首包超时太短或网络不通；默认 180s，先确认能 `curl` 通 |

## MCP 工具

`forgeflow-bootstrap/src/main/resources/mcp-servers.json` 默认接 `@modelcontextprotocol/server-everything`。
Windows 用 `npx.cmd`，Linux/macOS 改成 `npx`。

```bash
java -jar forgeflow-bootstrap/target/forgeflow-bootstrap-0.1.0-SNAPSHOT.jar   --server.port=18082 --forgeflow.mcp.enabled=true
```

启动日志会列出发现的 MCP 工具及能力分级。实测 16 个工具：13 个 READ、3 个 WRITE；
写工具在 `McpToolApprovalPolicy` 中默认 `REQUIRE_APPROVAL`（挂起等人工批准，见下文），
`forgeflow.mcp.auto-approve-writes=true` 仅用于本地演示。
Echo 模式只做发现，不会真正调用 MCP；要调用需 `forgeflow.agent.executor=agents-flex` 并提供 `ChatModel` Bean。

## Trace

每条任务生成 W3C 风格 traceId/spanId；提交时把上下文写进 `TaskMessage`，Worker 从消息恢复父 span，
再为 claim、lease、agent、commit 建子 span。日志可直接 grep：

```bash
grep "trace.start\|trace.end" forgeflow-run.log
grep "traceId=<id>" forgeflow-run.log
```

样例（同一 traceId 贯穿全链）：

```text
trace.start name=task.submit traceId=4d0f... spanId=4686... parentSpanId=null
trace.start name=task.execute traceId=4d0f... spanId=c41a... parentSpanId=4686...
trace.start name=task.claim   traceId=4d0f... spanId=dfd4... parentSpanId=c41a...
trace.start name=lease.acquire traceId=4d0f... spanId=67d1... parentSpanId=c41a...
trace.start name=agent.execute traceId=4d0f... spanId=53cb... parentSpanId=c41a...
trace.start name=task.commit  traceId=4d0f... spanId=c0a6... parentSpanId=c41a...
```

当前导出到日志；接 OTLP/Langfuse 只需替换 `TaskTracer` 的出口，不改业务调用点。

## 审批接口

```http
POST /api/tasks/{id}/approvals
Content-Type: application/json

{"turnId":"<agent turn id>","toolName":"refund","argsHash":"<canonical args hash>","ttlSeconds":120}
```

返回绑定 task/turn/tool/argsHash 的 capability token；写工具只有持有效 token 才放行，参数变化即失效。

## 编排层（入口路由 → 依赖调度 → 汇总）

入口有两种实现，行为契约一致（都产出 `TaskPlan`）：

| 模式 | 实现 | 说明 |
|---|---|---|
| `rule`（默认） | `TaskPlanner` | 关键词路由 + 分隔符/长度拆步；确定性、零成本 |
| `llm` | `LlmTaskPlanner` | 让模型输出 JSON 计划，严格校验后使用 |

`LlmTaskPlanner` 的约束：

- 要求模型只返回 `{mode, route, confidence, subtasks[{id,prompt,dependsOn}]}`；
- 依赖只能指向更早的子任务，子任务数受 `max-subtasks` 限制，任何不合法的计划都**回退到规则版**；
- 置信度低于 `clarify-threshold` 时返回单轮 `general`，不硬猜。

出口（汇总）同样可选：

| 模式 | 行为 |
|---|---|
| `concat`（默认） | 按计划顺序拼接分节结果 |
| `llm` | 把子任务结果交给模型合成一段最终回答；模型失败时自动回退拼接，`metadata.aggregator` 标明实际用了哪个 |

`DagOrchestrator`：依赖全部成功才放行；**依赖子任务启动前会把上游结果作为 `[upstream results]` 拼进 prompt**；独立兄弟节点并行（默认并发 4）；依赖失败级联标记 `SKIPPED`；整体超时或 `ExecutionContext.cancelled()`（含用户取消）停止派发；每个子任务独立 `Agent`、独立上下文。

```yaml
forgeflow:
  orchestration:
    enabled: true
    planner: llm          # rule | llm
    aggregator: llm       # concat | llm
    simple-max-chars: 200
    max-subtasks: 8
    clarify-threshold: 0.5
    concurrency: 4
    timeout-ms: 300000
    max-output-chars: 20000
    validate-json: true
```

实测（真实 DeepSeek）：

- 两步 prompt：`llm plan mode=COMPLEX route=general confidence=0.85 subtasks=2`，汇总 `aggregator=llm`，任务 `SUCCEEDED`；
- 三步链式 prompt（先搜索 → 再总结 → 最后风险提示）：`subtasks=3`，依赖链执行，聚合仍走 LLM；
- 长上下文：3 个 35KB prompt 并发，accept ~330ms，任务耗时约 4s，3/3 成功。

## 会话记忆（多轮上下文 + 记忆生命周期）

提交任务时可带可选 `sessionId`；带上后：

- **入口**：按**与本次 prompt 的相关度**选取事实，和滚动摘要一起作为 `[session memory]` 块交给规划器；
- **出口**：任务成功后，由 memory learner 抽取新摘要与事实，异步写回（失败只告警，不影响任务）；
- **子 Agent 不直接读记忆**：规划器决定把哪些事实写进子任务 prompt，隔离边界不变。

```bash
curl -X POST http://localhost:8080/api/tasks \
  -H "Content-Type: application/json" \
  -d '{"tenantId":"tenant-a","sessionId":"demo-session-1","prompt":"My name is Alice ..."}'

curl http://localhost:8080/api/sessions/demo-session-1
# 事实带 importance / hits / lastUsedAt / status / version
curl http://localhost:8080/api/sessions/demo-session-1/facts/city/history   # 冲突/被覆盖的历史
curl -X DELETE http://localhost:8080/api/sessions/demo-session-1/facts/user_name   # 记忆污染时手动清理
```

存储：

| 表 | 作用 |
|---|---|
| `ff_session` | 会话归属（session → tenant） |
| `ff_session_summary` | 滚动摘要 + 结构化状态 + `version`（CAS 写，避免并发互相覆盖） |
| `ff_session_fact` | 带 `source_task_id` 的事实；`status`（ACTIVE/ARCHIVED）、`importance`、`hits`、`last_used_at`、`embedding`、`version` |
| `ff_session_fact_history` | 被覆盖/冲突的旧值 + 来源 taskId（REFINED / CONFLICT） |
| `ff_task_session` | 任务到会话的绑定 |

三个生命周期机制：

1. **冲突合并**：新值与旧值同时存在时，包含关系记为 `REFINED`（新值胜出）；
   互不兼容记为 `CONFLICT`，旧值写进 `ff_session_fact_history` 并提高 importance。
   `conflict=latest`（默认）新值胜出，`conflict=keep-both` 合并成 `旧 | 新`；两种策略都保留历史，
   可追溯、可回滚，不会静默丢失。
2. **衰减**：`MemoryDecayScanner` 定期归档“又老又没人用又不重要”的事实
   （超过 `retention-days`、`hits < min-hits`、`importance < max-importance`），
   并把每个 session 的活跃事实限制在 `max-facts-per-session` 以内（按 importance → hits → 时间排序）。
   归档不是删除，行还在，可查可复活。
3. **强化**：每次真正注入 prompt 的事实 `hits++`、`last_used_at=now`；被反复使用的事实不会因为时间流逝被衰减，
   排序里 hits 也高于纯新度——这是“用进废退”的落点。

召回模式 `recall`：

| 模式 | 行为 |
|---|---|
| `recent` | 最新 N 条（旧行为） |
| `vector` | 只按相似度取，低于阈值的相关事实被丢弃 |
| `hybrid`（默认） | 最新 1~3 条做锚点（身份/决策类事实不因提问措辞丢失），其余按相似度补满 |

向量是**可替换的**：

| provider | 行为 |
|---|---|
| `hash`（应用默认） | 本地无依赖：ASCII 词 + 中文二元组签名特征哈希到 256 维（去停用词），L2 归一后算余弦。不跨同义词，零网络开销 |
| `http`（prod 默认） | 任意 OpenAI 兼容 `POST {base-url}/embeddings`，默认 SiliconFlow `BAAI/bge-m3`（1024 维）；任何失败（网络/非 2xx/坏响应）在 `cooldown-ms` 内自动降级到 hash，任务与入口读取都不受影响 |

每条事实的向量旁边存 `embedding_model`。切换 provider 后第一次召回会发现型号不一致，
用**一次批量调用**把候选事实全部重算并写回，之后正常复用——不会在每次召回都重算，
也不会把两个模型的向量混在一个空间里比较。
（踩过的坑：`decode` 曾把外部 64/1536 维向量补齐成 256 维，导致余弦恒为 0；已有回归测试。）

召回时如果 embedding 服务整体不可用，入口退化为“最新 N 条”，并记录
`memory.recall failed ... falling back to newest facts`。

向量库是可选的加速层（`forgeflow.memory.vector-store`）：

| 值 | 行为 |
|---|---|
| `none`（默认） | 候选集在内存里算余弦 |
| `qdrant` | 事实写入时同步索引，召回走 ANN（按 `session_id` 过滤）；返回的 id 会回表校验 `status='ACTIVE'`，归档/删除的事实不会因为旧向量复活；集合按 `<model>_<dim>` 命名，换 embedding 模型自动新建集合；进程内首次访问某会话时若索引条数少于 SQL 活跃事实数，会批量重算补齐；Qdrant 不可用时自动退回内存余弦 |

`docker-compose.yml` 已带 `qdrant/qdrant:v1.12.4`（6333 端口）。

**默认模型：SiliconFlow `BAAI/bge-m3`（1024 维）**。prod profile 默认 `provider=http`，
只要给了 `FORGEFLOW_EMBEDDING_API_KEY` 就直接生效；没给 key 会 401 并自动降级为 hash：

```bash
FORGEFLOW_EMBEDDING_API_KEY=$SILICONFLOW_API_KEY \
java -jar forgeflow-bootstrap/target/forgeflow-bootstrap-0.1.0-SNAPSHOT.jar \
  --spring.profiles.active=prod
```

实测：自动建集合 `ff_facts_BAAI_bge-m3_1024`；`who am I?` 召回了只存过
`user_name: Alice` 的事实（两者没有共享任何内容词）。语义模型的余弦分布很窄
（相关 0.50 / 无关 0.41），所以召回用**相对阈值**（接近最佳命中才保留），
不用固定绝对值——这一点在 `SessionMemoryStore.select` 里按模型区分。
API key 只走环境变量，不写进仓库。

**事实因果校验**：learner 抽出的每条事实都要能在来源材料（上一版摘要 + 本轮 prompt + 回答）
中找到依据——整值包含，或至少 70% 的 token 出现；同一批里重复的 key 也会被丢弃。
抽不出来就丢，日志记 `session.memory ... rejected=N`。这是对“模型编造记忆”的兜底，
比事后人工删可靠。

```yaml
forgeflow:
  memory:
    enabled: true
    learner: llm            # rule | llm；llm 失败自动回退规则
    recall: hybrid          # recent | vector | hybrid
    conflict: latest        # latest | keep-both
    vector-store: none      # none | qdrant
    qdrant:
      base-url: http://localhost:6333
      api-key: ${QDRANT_API_KEY:}
      timeout-ms: 3000
    embedding:
      provider: hash        # hash | http（prod 默认 http）
      model: BAAI/bge-m3
      base-url: https://api.siliconflow.cn/v1
      path: /embeddings
      api-key: ${EMBEDDING_API_KEY:}
      timeout-ms: 3000
      cooldown-ms: 60000    # 失败后多久内直接用 hash
    max-context-chars: 2000
    max-facts: 30
    max-summary-chars: 2000
    decay:
      enabled: true
      retention-days: 7
      min-hits: 2
      max-importance: 0.8
      max-facts-per-session: 200
      scan-interval-ms: 600000
```

**实测（真实 DeepSeek）**：

- 第一轮：“My name is Alice and I am working on ForgeFlow…” → 成功后写出摘要与事实
  `user_name=Alice / project_name=ForgeFlow / project_description=...`，均带来源 taskId；
- 第二轮（同 session）：“What is my name and what project am I working on?” →
  `Your name is Alice, and you're working on ForgeFlow`；
- 另一 session 问同样的问题 → “I don't know your name…”（会话之间隔离）；
- `tenant-b` 提交到 `tenant-a` 的 session → **400** `session ... belongs to another tenant`。

**实测（mock 模型，`scripts/memory-lifecycle-check.py`）**：

| 场景 | 结果 |
|---|---|
| 同一 key 先 Beijing 后 Shanghai | `city=Shanghai v2`，history 一条 `CONFLICT(old=Beijing, new=Shanghai)`，importance 0.5→0.7 |
| 5 条事实、`max-facts=3`，问 “what is my name” | 命中 `user_name` 且 hits 增长；`topic_alpha/beta` 未被注入、hits 不变（相关度筛选生效） |
| 5 条事实、`max-facts-per-session=2` | 扫描后 `memory.decay archived_overflow=6 sessions=3`，每 session 只剩 2 条 ACTIVE |
| 真实容器删除 | `sandbox.mode requested=AUTO resolved=container docker=29.9.3`；`sandbox.container command=rm -f /work/demo.txt exit=0 output=container-deleted demo.txt` |

边界：因果校验是词面级的（同义改写可能被误杀，取不到就丢）；向量库是可选加速层，默认仍在内存算余弦；
衰减按时间窗而非 token 预算；embedding 的语义能力取决于所接模型，hash 模式不跨同义词。

## 取消与 SSE

```bash
curl -N http://localhost:8080/api/tasks/{id}/events
```

实测（3 秒 Echo 任务，先订阅 1 秒后取消）：

```text
data:{"status":"RUNNING","detail":"current",...}       ← 订阅瞬间的 DB 快照
data:{"status":"QUEUED","detail":"submitted",...}
data:{"status":"RUNNING","detail":"worker local-worker-1",...}
data:{"status":"CANCELLING","detail":"cancel requested",...}
data:{"status":"CANCELLED","detail":"cancelled",...}
```

终态后连接自动关闭。单轮 Agent 现在跑在辅助线程上，每 200ms 检查一次取消标记并中断；
不响应中断的 HTTP 客户端会被放弃，不再阻塞 Worker。

### 跨进程：Redis 事件总线

`forgeflow.events.mode` 选实现：

| 模式 | 实现 | 适用 |
|---|---|---|
| `memory`（默认） | 进程内 Reactor Sinks | 单 JVM 本地开发、单测 |
| `redis`（prod 默认） | Redis Pub/Sub，频道 `ff:task:events:{id}` | API 与 Worker 分进程部署 |

每个 SSE 连接订阅一个频道，连接断开即退订。Pub/Sub 没有回放，所以首帧仍然是数据库快照。

实测（prod profile，真实 Redis）：订阅后收到 `RUNNING current` → `SUCCEEDED completed`。

一个值得记的坑：手工 `new RedisMessageListenerContainer()` 只调用 `afterPropertiesSet()`
不会启动它，`addMessageListener` 静默不生效、SSE 只收到快照；显式调用 `start()` 才正常。

另一个坑：客户端断开后如果流上再没有事件，服务端不会感知，Redis 订阅会一直挂着。
现在每 15 秒发一个 SSE 注释帧（`forgeflow.sse.heartbeat-ms`）并给连接设 30 分钟上限，
心跳写失败即释放订阅。实测：断开后 `PUBSUB NUMSUB` 从 1 回到 0。

### 补偿落库

`forgeflow.compensation.mode` 同样两档：`memory`（本地）和 `jdbc`（prod 默认）。

`jdbc` 模式写入 `ff_compensation`（状态 `PENDING → RUNNING → DONE/FAILED`，按 `seq` 逆序回滚），
`CompensationLog.recoverStale()` 每分钟扫一次"任务已 `FAILED`/`CANCELLED` 但仍有 `PENDING`"的记录，
把 Worker 崩溃时没做完的回滚补上。

两条防重：
- 回滚前先 `UPDATE ... SET status='RUNNING' WHERE status='PENDING'` **CAS 认领**，多进程不会重复执行同一行；
- 每条记录带稳定的 **`operationId`**（首次登记时生成、落库、重试时原样传回），undo 用它做幂等键。

没有 compensator 的工具（如 `delete`）会标 `FAILED` 并计入人工处理，而不是静默跳过。

实测（MySQL）：`COMPENSATE` 任务失败后，`write_note` 行 `status=DONE, attempts=1`，`operation_id` 已落库。

### 转人工超时回收

`WAITING_HUMAN` 期间任务仍占着租户配额。`HumanTimeoutScanner` 每 60 秒扫一次，
超过 `forgeflow.handoff.waiting-timeout-minutes`（默认 30）的任务自动 `REJECT` 并释放配额：

```yaml
forgeflow:
  handoff:
    enabled: true
    waiting-timeout-minutes: 30
    scan-interval-ms: 60000
```

实测：把 `WAITING_HUMAN` 任务的 `updated_at` 调到 2 分钟前，扫描后状态变 `FAILED`、
`errorCode=HUMAN_REJECTED`、`errorMessage=human timeout after 1 minutes`，日志输出 `human timeout task=... tenant=...`。

### 双进程部署（API 与 Worker 分离）

```bash
# API 节点：不消费消息、不做恢复扫描、不跑 outbox relay
java -jar forgeflow-bootstrap-...jar --spring.profiles.active=prod \
  --server.port=18098 --forgeflow.worker.id=api-node \
  --forgeflow.worker.enabled=false --forgeflow.recovery.enabled=false \
  --forgeflow.outbox.enabled=false

# Worker 节点：正常消费
java -jar forgeflow-bootstrap-...jar --spring.profiles.active=prod \
  --server.port=18099 --forgeflow.worker.id=worker-node
```

`forgeflow.worker.enabled=false` 会关掉 `TaskQueueListener`。**不能只用**
`spring.rabbitmq.listener.simple.auto-startup=false` —— 项目自定义了
`SimpleRabbitListenerContainerFactory`，Boot 的自动配置属性不会作用到这个手工创建的工厂上。

单个 Worker 默认只有 **1 个消费者**（prefetch=1）。并发执行需要显式调大
`--forgeflow.worker.concurrency=10`，它同时设置 `concurrentConsumers` 和 `maxConcurrentConsumers`。

实测（API 节点 18098 + Worker 节点 18099，共享 MySQL/Redis/RabbitMQ）：

| 检查项 | 结果 |
|---|---|
| 从 API 节点提交 | 任务在 DB 中 `worker_id=worker-node` |
| API 节点是否执行 | 日志中 `task.execute` 次数为 0 |
| API 节点 SSE | 收到 `QUEUED current → RUNNING worker worker-node → SUCCEEDED completed`（后两条经 Redis 从 Worker 推回） |
| 跨进程补偿 | Worker 写入并回滚 `ff_compensation`，`write_note` 行 `DONE`，attempts=1 |

## 死循环三信号

`LoopGuard` 在每次工具调用和每次模型调用时检查，三个信号独立触发：

| 信号 | 检查点 | 判定 | 默认阈值 |
|---|---|---|---|
| repeat | `LoopGuardToolInterceptor` | 同工具 + 同参数（key 排序后比较）累计次数 | 3 |
| stable-state | `LoopGuardChatModel` | 发给模型的完整 prompt 哈希连续相同 | 4 |
| step-cap | `LoopGuardToolInterceptor` | 单任务工具调用总数 | 20 |

```yaml
forgeflow:
  loop:
    enabled: true
    max-steps: 20
    repeat-threshold: 3
    stable-state-threshold: 4
```

任一命中抛 `LoopDetectedException("loop detected [signal]: ...")`，Worker 把它记成 `FAILED`，
错误信息里带信号名和触发内容，便于定位是模型在重复调用还是上下文没推进。
计数器按 taskId 维护，任务开始前 `reset`，不会跨任务串味。

## 工具治理：RBAC 与沙箱

每个工具调用先过 `ToolGovernanceInterceptor`：查能力 → 查角色 → 审计 → （危险工具）进沙箱。

| 角色 | READ | WRITE | DANGEROUS |
|---|---|---|---|
| VIEWER | ✅ | ❌ | ❌ |
| OPERATOR | ✅ | ✅ | ❌ |
| ADMIN | ✅ | ✅ | ✅ |

```yaml
forgeflow:
  rbac:
    enabled: true
    default-role: OPERATOR
    unknown-capability: DANGEROUS   # 未登记的工具默认按危险处理
    tenant-roles:
      tenant-a: ADMIN
  sandbox:
    enabled: true
    mode: auto              # auto | container | thread
    timeout-ms: 10000
    container:
      image: alpine:3.20
      timeout-ms: 10000
```

沙箱模式是显式配置，不是隐藏默认：

| mode | 行为 |
|---|---|
| `auto`（默认） | 启动时探测 Docker：可用就走容器，不可用退回线程沙箱并打 **WARN**（明确说“危险工具没有进程级隔离”） |
| `container` | 强制容器；探测不到 Docker 直接**启动失败**（fail-closed），绝不悄悄在宿主机执行危险命令 |
| `thread` | 明确接受线程 + 超时预算（旧行为），启动日志同样说明没有隔离 |

容器模式下，危险工具的副作用走 `Sandbox.runCommand`：`docker run --rm --network none
--memory 128m --cpus 0.5 -v workspace:/work`，只挂载工作目录；启动日志打印
`sandbox.mode requested=... resolved=... docker=...`，每次分发打印 `sandbox.dispatch tool=... mode=container`。

前提：`docker pull alpine:3.20` 提前拉好镜像（首次拉取会吃掉 10s 预算，实测过）；
`forgeflow.sandbox.mode=container` 适合生产，`auto` 适合本地开发。

角色目前按租户映射（任务契约里还没有调用方身份）。接身份系统时只改 `RbacPolicy.roleFor`。

审计日志每次调用一行，允许和拒绝都记录：

```text
tool.audit decision=DENY  task=... tenant=tenant-a role=VIEWER   tool=write_note capability=WRITE     detail=role does not allow capability
tool.audit decision=ALLOW task=... tenant=tenant-a role=OPERATOR tool=write_note capability=WRITE     detail=permitted
tool.audit decision=DENY  task=... tenant=tenant-a role=OPERATOR tool=delete     capability=DANGEROUS detail=role does not allow capability
```

沙箱的边界要说清楚：`thread` 模式只隔离线程和超时预算，**没有**文件系统和网络的进程级隔离；
`container`/`auto`（Docker 可用时）才真正把危险命令放进一次性容器（无网络、128MB、0.5 CPU、只挂工作目录）。
拦截点始终是 `ToolGovernanceInterceptor`，换实现不影响治理链。

实测（全部用真实 DeepSeek；LOOP 通过 prompt 诱导模型重复调用）：

| 场景 | 结果 |
|---|---|
| MCP READ：`Use the echo tool with message hello ...` | `SUCCEEDED`，返回 `Echo: hello`；审计 `ALLOW ... tool=echo capability=READ` |
| MCP WRITE：`Call the toggle-simulated-logging tool ...` | 任务挂起为 `WAITING_APPROVAL`，`approval.audit decision=REQUIRE_APPROVAL`；批准后恢复并执行，拒绝则 `FAILED/APPROVAL_REJECTED` |
| WRITE：`Write a note: Redis lease uses SET NX PX ...` | `SUCCEEDED`；审计 `ALLOW ... tool=write_note capability=WRITE`；`compensation.recorded` 登记了 undo，成功后自动清除 |
| DANGEROUS：`Delete the file redis-lease.md`（沙箱 1s，工具 3s） | `FAILED`：`sandbox timeout after 1000ms running dangerous tool delete`；审计 `ALLOW ... capability=DANGEROUS` |
| LOOP：`Call the search tool with q='redis' exactly five times ... Do not change the query.` | `FAILED`：`loop detected [repeat]: ... called search(q=redis) 3 times`（三次调用间隔约 0.5s，均为模型自主发起） |

MCP 写工具的完整流程见上面"审批挂起与恢复"：挂起 → 批准 → 恢复同一个 turn → 执行；
拒绝则任务 `FAILED/APPROVAL_REJECTED`。`turn-store=jdbc` 时 turn 快照落在 `ff_agent_turns`，
任意 worker 都能恢复（已实测 kill 原 worker 后由另一台恢复）。

## 端到端验证（mock 模型）

不依赖真实 API key，用本地 mock 跑完整 Agent 回合（模型 → 工具调用 → 治理 → 回填 → 收尾）：

```bash
python -I scripts/mock-openai-server.py --port 18888
```

```bash
DEEPSEEK_ENABLED=true DEEPSEEK_API_KEY=mock DEEPSEEK_BASE_URL=http://127.0.0.1:18888 \
DEEPSEEK_MODEL=mock-1 DEEPSEEK_THINKING=false FORGEFLOW_LOCAL_TOOLS=true \
java -jar forgeflow-bootstrap/target/forgeflow-bootstrap-0.1.0-SNAPSHOT.jar \
  --forgeflow.agent.executor=agents-flex --forgeflow.mcp.enabled=false \
  --management.health.rabbit.enabled=false \
  --forgeflow.sandbox.timeout-ms=1000 --forgeflow.tools.local.delete-sleep-ms=3000
```

mock 根据 prompt 关键词返回不同的 tool call：`SEARCH` / `WRITE` / `DANGEROUS` / `LOOP`，
其余直接给最终答案；一旦历史里出现 tool 结果就收尾（`LOOP` 模式除外）。

实测四条链路：

| 场景 | 结果 |
|---|---|
| `tenant-a` + `SEARCH ...` | `SUCCEEDED`，`mock-final: done`；审计 `ALLOW ... role=ADMIN tool=search capability=READ` |
| `tenant-b`（OPERATOR）+ `DANGEROUS ...` | `FAILED`：`role OPERATOR may not call DANGEROUS tool delete`；审计 `DENY` |
| `tenant-a`（ADMIN）+ `DANGEROUS ...`，沙箱 1s、工具 3s | `FAILED`：`sandbox timeout after 1000ms running dangerous tool delete` |
| `tenant-a` + `LOOP ...` | `FAILED`：`loop detected [repeat]: ... called search(q=same-query) 3 times` |

这轮验证还暴露并修掉一个真问题：agents-flex 在自己的线程池里跑模型和工具，
`TaskContextHolder` 的 ThreadLocal 传不过去，导致审计/RBAC/审批看到的 task 是 `null`。
现在 `AgentRunner` 的 tool/model executor 用 `TaskContextExecutor` 包装，提交时捕获身份、执行时还原。

### 记忆生命周期 + 容器沙箱（mock）

mock 增加了 learner 分支（`MYNAME/PROJECT/CITY/TOPIC` → 事实），配合
`--forgeflow.memory.learner=llm --forgeflow.memory.max-facts=3
--forgeflow.memory.decay.max-facts-per-session=2` 一条命令验证四种行为：

```bash
docker pull alpine:3.20        # 容器沙箱镜像，首次拉取会超过 10s 预算
python -I scripts/memory-lifecycle-check.py http://localhost:18080
```

```text
[1] city = Shanghai，history: CONFLICT(old=Beijing, new=Shanghai)，importance 0.7
[2] 注入相关度筛选：user_name / topic_gamma / topic_delta 的 hits 增长，topic_alpha/beta 不变
[3] memory.decay archived_overflow=6 sessions=3 → 每个 session 只剩 2 条 ACTIVE
[4] sandbox.container command=rm -f /work/demo.txt exit=0 output=container-deleted demo.txt
```

### 超长上下文与长任务 + 故障

```bash
# 250KB × 5，同 session，真实 Agent 路径（planner → 模型 → 校验 → 记忆写回）
python -I scripts/long-context-test.py http://localhost:18080 --count 5 --kb 250

# 双进程：45s 长任务运行中 kill worker；同时停/启 RabbitMQ
python -I scripts/long-task-fault-test.py
```

实测：

| 场景 | 结果 |
|---|---|
| 250KB × 5 并发（mock 模型） | 5/5 `SUCCEEDED`，accept ≈0.85s，端到端 ≈1.9s；会话摘要仍是 learner 输出的 27 字符，不随输入膨胀 |
| 45s 任务运行中 kill w1 | 约 31s 后 `TaskRecoveryScanner re-dispatch stale task` → w2 `task.claim fromStatus=RUNNING` 接管，任务 `SUCCEEDED`，DB `worker_id=w2` |
| 同一窗口停 RabbitMQ 再提交 | API 直接投递失败 → outbox 指数退避（2s/4s/8s/16s）→ broker 恢复后 relay 投递 → 任务完成 |
| 200KB prompt 落 MySQL | `CHAR_LENGTH(prompt)=200000`（`TEXT` 上限 64KB，已改 `MEDIUMTEXT`） |

长任务能安全接管依赖两个细节：心跳续租的同时 `touch` 任务行（否则 30s 后被恢复扫描误判为死任务），
以及 lease-first + version fencing（接管方的终态写不会被原 worker 覆盖）。

## 转人工与补偿

失败四层的后两层。

**转人工**：模型输出没通过校验（空结果、JSON 解析失败）时，不直接判失败，而是把任务停在 `WAITING_HUMAN`：

```text
WAITING_HUMAN  errorCode=WAITING_HUMAN  errorMessage="output validation failed: output is empty"
```

人工通过同一个任务接口给结论：

```bash
curl -X POST http://localhost:8080/api/tasks/{id}/resolution \
  -H "Content-Type: application/json" \
  -d '{"decision":"RESUME","answer":"SEARCH now"}'
```

- `RESUME` → 状态回到 `QUEUED`，`answer` 以 `[human] ...` 追加到 prompt，重新投递；
- `REJECT` → `FAILED`，`errorCode=HUMAN_REJECTED`。

**补偿**：写类工具成功后登记一个 undo 动作（`CompensationLog`），回合以异常结束时按**逆序**回滚：

```text
compensation.recorded task=... tool=write_note action=remove note demo
compensation.undo     task=... tool=write_note note=demo
compensation.done     task=... actions=[write_note: remove note demo]
```

边界要说清楚：补偿日志是**进程内**的，Worker 崩溃会丢；`delete` 这类不可逆工具没有 compensator，
只能标成不可逆而不是假装能回滚。真实环境把 undo 换成远端补偿 API 即可。

实测（mock 模型）：

| 场景 | 结果 |
|---|---|
| `HANDOFF` → 空输出 | `WAITING_HUMAN`，`output validation failed: output is empty` |
| 对上述任务 `RESUME` + `SEARCH now` | `QUEUED` → `SUCCEEDED` |
| 对 `HANDOFF` 任务 `REJECT` | `FAILED`，`HUMAN_REJECTED` |
| `COMPENSATE`（先写后失败） | `FAILED`，回滚 `write_note` 并记录 `compensation.done` |

## 压测与多 Agent 入口

```bash
# 20 并发 / 1000 任务，报告 P50/P95/P99
python scripts/bench-submit.py http://localhost:18086/api/tasks

# 100 轮 × 20 子任务，每项双投递，返回 CAS 认领报告
curl -X POST 'http://localhost:18088/api/multi-agent/run?rounds=100&subtasks=20&concurrency=4'

# kill/restart 循环，测接管恢复时间（默认 20 轮）
python scripts/worker-crash-test.py --cycles 20
```

实测（本机 H2 + 内存租约 + 本地分发）：
- 预热后 20 并发/1000 任务，P95 32.6–52.1ms；首轮冷启动 178.6ms。
- 多 Agent 100×20 双投递：2000 claimed / 2000 executed / 2000 succeeded，0 重复、0 遗漏，38ms。
- 20 轮 kill/restart：20/20 SUCCEEDED，avg 39.5s，max 43.1s。
- 30 任务 × 3 次上下文对照：Token 111,592 → 28,545（-74.4%），关键事实保留 100%。

## 基线能力（对应简历四条）

1. **任务服务化**：四接口、`202 + taskId`、先落库后投递、查询与执行解耦。实测 20 并发 / 1000 任务，预热后受理 P95 32.6–52.1ms（首轮冷启动 178ms）。
2. **可靠分发与接管**：RabbitMQ 手动 ack + 重试/死信，Redis 30s 租约 + 5s 扫描，DB `status+version` 条件更新。20 轮 kill/restart 全部恢复：avg 39.5s，max 43.1s（1s 任务），脚本 `scripts/worker-crash-test.py`。
3. **三层上下文**：长期记忆 `MEMORY.md`、主题索引 `topics.json`、执行轨迹 `trace.jsonl`；工具结果可外置。30 任务 × 3 次实测 Token 降 74.4%，关键事实保留率 100%。
4. **多 Agent**：独立上下文、Reactor Sinks 事件总线、MCP 工具读写路由、Hooks 审批、CAS 认领。100 轮 × 20 子任务、每项双投递，2000 个 claimed/executed/succeeded，0 重复、0 遗漏。

## 已实现

- 任务四接口，`202 + taskId`；幂等键 + `(tenant_id, idempotency_key)` 唯一索引。
- `QUEUED -> CLAIMED -> RUNNING -> SUCCEEDED/FAILED/CANCELLED` 状态机；所有迁移带 `status + version` 条件。
- Redis 租约 30s、10s 续期、释放用 Lua compare-and-delete；DB version 做 fencing。
- RabbitMQ 手动 ack、TTL 重试队列、DLQ；悬挂任务扫描重投。
- Outbox：任务行与待发消息同事务提交；提交后直接投递，失败由 relay 指数退避重投。
- 租户配额：按租户并发上限准入，超限 429；任务终态释放配额，Redis 计数带 TTL 防泄漏。
- 上下文治理：`ToolResultLimiter` 已接 Agent 工具调用链，大结果替换为可重取占位；历史裁剪/压缩组件保留。
- 模型容错：`RoutingChatModel` 把候选路由、首包超时和三态熔断包进 Agent 的模型调用；提供 Bean 即生效。
- 工具权限：READ/WRITE/DANGEROUS 分级、`ApprovalTokenStore` 绑定 task/turn/tool/argsHash/过期时间，并暴露审批签发接口。
- agents-flex `AgentRunner` 适配：ReAct 回合、工具调用、审批、快照由基座负责。
- MCP 工具注册：从 `mcp-servers.json` 发现工具，按 `readOnlyHint/destructiveHint` 分成 READ/WRITE/DANGEROUS；写工具默认 fail-closed，只读工具直接放行。
- 全链路 Trace：每条任务一个 traceId，随 `TaskMessage` 跨线程/队列传递；`task.submit → task.execute → task.claim → lease.acquire → agent.execute → task.commit` 共享 traceId，日志带 spanId/耗时/状态/标签。
- 多 Agent：`Subtask` CAS 认领、独立 `AgentContext`、`AgentEventBus`（Reactor Sinks）解耦推理/工具/审批；`ToolRouter` 读工具信号量并发、写工具按 workspace 串行、危险工具走 `ApprovalHook`。
- 编排层：`TaskPlanner` 入口路由（规则 + 置信度，低置信度不硬猜）判断 `SIMPLE/COMPLEX`；复杂任务拆成带依赖的子任务，`DagOrchestrator` 按 DAG 调度（无依赖并行、有依赖串行、失败级联跳过、超时/取消可停）；`ResultAggregator` 汇总写回任务结果。
- 取消与流式：`GET /api/tasks/{id}/events` 用 SSE 推送 `QUEUED/RUNNING/CANCELLING/SUCCEEDED/FAILED/CANCELLED` 与子任务事件；单轮 Agent 在辅助线程执行，每 200ms 检查取消标记，用户取消可中断。
- 结果校验：输出非空、长度上限、看起来是 JSON 就必须能解析；校验失败任务落 `FAILED`，不把模型自称的“完成”当成完成。
- 死循环三信号：同一工具同一参数重复、提示词哈希连续不变、单任务工具调用步数上限；任一触发抛 `LoopDetectedException`，任务落 `FAILED` 而不是耗到模型自己停。
- 工具治理：RBAC 按角色限制工具能力（VIEWER 只读 / OPERATOR 可写 / ADMIN 可执行危险工具），每次调用写一条 `tool.audit` 审计日志；危险工具按 `forgeflow.sandbox.mode` 执行——`auto`/`container` 下进一次性容器（无网络、128MB、0.5 CPU、只挂工作目录），无 Docker 时 `auto` 退回线程+超时并告警，`container` 直接启动失败。
- 三层上下文：`LongTermMemory`（MEMORY.md）+ `TopicIndex`（topics.json）+ `ExecutionTrace`（trace.jsonl）；工具结果可外置到 workspace，只回填路径和摘要。

## 审批挂起、流式输出与容器沙箱

### 审批挂起与恢复

MCP 写工具没有票据时不再直接拒绝，而是走 agents-flex 的挂起机制：

```text
RUNNING → (policy REQUIRE_APPROVAL) → WAITING_APPROVAL
        → POST /api/tasks/{id}/approvals          → QUEUED → 恢复同一个 AgentTurn → SUCCEEDED
        → POST /api/tasks/{id}/approvals/reject   → FAILED (APPROVAL_REJECTED)
```

挂起时执行器把 `turnId / callId / tool / argsHash` 写入 `ff_approval`；批准接口签发绑定
`task+turn+tool+args` 的 capability token，再把任务重新入队；worker 用 `runner.resume(turn, APPROVE_TOOL)`
从暂停点继续，**模型不会重新生成参数**。

```bash
curl -X POST http://localhost:8080/api/tasks/{id}/approvals
# {"token":"...","expiresInSeconds":600}

curl -X POST http://localhost:8080/api/tasks/{id}/approvals/reject \
  -H "Content-Type: application/json" -d '{"reason":"not allowed"}'
```

turn 默认存在进程内；`forgeflow.agent.turn-store=jdbc`（prod 默认）会把快照落到
`ff_agent_turns`，配合 `AgentLoader` 就能**跨 worker 恢复**。实测：任务在 w1 挂起，
kill w1 后批准，由 w2 恢复并执行成功。本地默认 memory，进程重启即失效。

```yaml
forgeflow:
  agent:
    turn-store: jdbc        # prod 默认；本地默认 memory
    turn-store-binary: MEDIUMBLOB
```

### 流式输出

`forgeflow.streaming.enabled=true` 后，单轮 Agent 用 `AgentTurnOptions.streaming(true)` 调模型，
agents-flex 的 `MODEL_TEXT_DELTA` 事件被转发到任务事件总线，SSE 的消费方看到的就是逐段文本：

```text
data:{"type":"delta","status":null,"detail":"**","source":"s1"}
data:{"type":"delta","status":null,"detail":"用","source":"s1"}
...
data:{"type":"delta","status":null,"detail":"目前","source":"s2"}
data:{"status":"SUCCEEDED","detail":"completed"}
```

单轮实测一次回答 270 个 delta；编排路径也已接入，每个 delta 带 `source` 标明来自哪个子任务
（两步任务实测 947 个 delta，其中 905 段来自 s1），客户端可按来源分流。

### 容器级沙箱

危险工具由 `forgeflow.sandbox.mode` 决定隔离级别（详见上面的“工具治理”）：

```yaml
forgeflow:
  sandbox:
    mode: auto               # auto | container | thread
    container:
      image: alpine:3.20     # 任意带 sh 的镜像
      timeout-ms: 10000
  tools:
    local:
      workspace: ./workspace # 仅这个目录被挂载进容器
```

实际命令：

```text
docker run --rm --network none --memory 128m --cpus 0.5 \
  -v <workspace>:/work <image> sh -c "rm -f /work/note-1.md"
```

实测（`mode=auto`，本机 Docker 29.9.3）：`write_note` 落文件 → `delete` 在容器内删除，
日志 `sandbox.container command=rm -f /work/demo.txt exit=0 output=container-deleted demo.txt`，宿主 workspace 清空。
`mode=container` 时 Docker 不可用会**启动失败**；`mode=auto` 则会显式告警后退回线程沙箱。
代价是每次调用一次容器启动，镜像需提前 pull（首次拉取会吃掉 10s 预算，实测踩到过）。

## 验证矩阵

| 能力 | 真实模型 | mock | 单测 |
|---|---|---|---|
| 工具调用（本地 + MCP） | ✅ | ✅ | ✅ |
| 多步编排 + 上游结果传递 | ✅ | ✅ | ✅ |
| 编排部分失败（1/2） | — | ✅ | ✅ |
| 取消（RUNNING / QUEUED / WAITING_HUMAN / 编排中） | — | ✅ | ✅ |
| 转人工 + RESUME/REJECT + 超时回收 | — | ✅ | ✅ |
| 结果校验（空 / 非法 JSON） | — | ✅ | ✅ |
| RBAC 拒绝（VIEWER / MCP 写） | ✅ | ✅ | ✅ |
| 沙箱超时 | ✅ | ✅ | ✅ |
| 死循环三信号（repeat） | ✅（诱导） | ✅ | ✅ |
| 首包超时降级 | — | ✅ | ✅ |
| SSE（Redis 跨进程 / 终端任务 / 断线退订） | — | — | — （运行时实测） |
| Outbox 重投（Rabbit 停机恢复） | — | — | — （运行时实测） |
| 补偿扫描器定时回收 | — | — | — （运行时实测） |
| Redis / MySQL 停机与恢复 | — | — | — （运行时实测：503 + 恢复可用） |
| 审批挂起 → 跨 worker 恢复 | ✅（kill 原 worker 后由另一台恢复） | — | ✅ |
| 审批拒绝 → `APPROVAL_REJECTED` | ✅ | — | ✅ |
| 真实流式 delta（单轮 + 编排 source 分流） | ✅（270 / 947 段） | — | ✅（包装层） |
| 容器级沙箱执行 | ✅（redis:7 镜像） | — | — |
| 真实模型并发（20 并发、10 消费者） | ✅ 20/20，accept p50 153ms / p95 726ms | — | — |
| 模型版入口路由 + LLM 汇总 | ✅（2 步 / 3 步链） | ✅ | ✅ |
| 长上下文并发（35KB × 3） | ✅ 3/3，约 4s | — | — |
| 会话记忆多轮（真实模型） | ✅ 第二轮召回 name/project | — | ✅ |
| 会话隔离 / 租户隔离 | ✅（另一 session 不知道；跨租户 400） | — | ✅ |
| 记忆冲突合并（history + 版本） | ✅（mock，CONFLICT 记录 old/new/sourceTaskId） | ✅ | ✅ |
| 记忆衰减 / 每会话上限 | ✅（mock，archived_overflow=6） | ✅ | ✅ |
| 相似度召回 + 强化（hits） | ✅（mock，只注入相关事实） | ✅ | ✅ |
| 外部 embedding（http）语义召回 | ✅（`who am I` 命中 user_name） | ✅ | ✅ |
| Qdrant ANN 召回（真实容器） | ✅ 集合自动创建、5 个点、语义命中 | ✅ | ✅ |
| 真实 bge-m3（SiliconFlow）+ Qdrant | ✅ 1024 维集合，`who am I` 命中 `user_name` | — | — |
| Qdrant 停机 → 退回内存余弦 | ✅ 任务仍成功，日志 WARN | ✅ | ✅ |
| 索引自愈（冷启动/换模型批量重算） | — | ✅（模拟索引） | ✅ |
| embedding 故障降级到 hash | ✅（500 / 坏响应 / 冷却窗口） | ✅ | ✅ |
| 事实因果校验（丢弃编造/重复 key） | ✅ | ✅ | ✅ |
| 超长上下文并发（250KB × 5，同 session） | ✅ 5/5，accept ≈0.85s，总计 ≈1.9s | — | — |
| 长任务 + kill worker + Rabbit 停启 | ✅ 45s 任务 w2 接管；outbox 恢复后第二单完成 | — | — |
| 200KB prompt 落 MySQL（MEDIUMTEXT） | ✅ CHAR_LENGTH=200000 | — | — |
| 沙箱 mode 解析（auto/container/thread） | ✅ 启动日志 resolved=container / thread | ✅ | ✅ |
| container 模式无 Docker → 启动失败 | ✅ | ✅ | — |

**仍然未覆盖**：未知工具 fail-closed 端到端（agents-flex 在工具查找阶段先拒绝）；
真实模型下的 250KB 级上下文与长任务故障组合（超长上下文与长任务故障已用 mock/echo 验证）。

依赖故障行为：Redis / MySQL 停机时提交返回 **503**（`Dependency unavailable`），
MySQL 场景由 Hikari `connection-timeout: 3s` 保证快速失败；恢复后无需重启即可继续提交。

## 测试

```bash
mvn test
```

178 个单测覆盖：幂等唯一索引、条件认领、成功写入 fencing、悬挂 reclaim、取消状态机、Outbox 重投、租户配额、上下文裁剪/压缩、工具结果限制、首包降级、RoutingChatModel 候选切换、审批票据绑定与过期、MCP 读写分级与 REQUIRE_APPROVAL、Trace 编解码与上下文恢复、多 Agent 100×20 双投递零重复/零遗漏、读并发/写串行/危险工具审批、DeepSeek 请求体（thinking / reasoning_effort / temperature 规则）、编排入口路由与拆分、DAG 依赖顺序/并行/失败级联/取消/超时、子任务上游结果传递、结果汇总与元数据、任务事件总线、输出校验、死循环三信号（重复/状态哈希/步数上限）、RBAC 权限矩阵与租户覆盖、危险工具沙箱超时、沙箱 mode 解析（auto/container/thread、container 无 Docker 失败）、未知工具 fail-closed、任务上下文跨线程传递、补偿逆序回滚与失败隔离（内存 + JDBC 两套实现）、补偿 operationId 重试稳定性、人工等待的恢复/拒绝/超时回收、本地演示笔记库（搜索/写入/删除）、审批记录 CAS、WAITING_APPROVAL 迁移与恢复、心跳 touch（长任务不被误判为陈旧）、会话记忆（摘要/事实/租户隔离/规则与 LLM 学习回退/冲突合并与历史/衰减与强化/溢出裁剪/相似度与混合召回/摘要版本 CAS/embedding 切换重算/事实因果校验）、文本向量化（中英文 token、余弦、维度保真、编码往返）、HTTP embedding（响应解析/索引顺序/失败降级/冷却窗口）、Qdrant 向量库（建集合/索引/会话过滤检索/计数、不可达抛错、索引自愈与不可用回退）。

## 下一步

- 把 `ContextManager` 的历史裁剪/压缩接到 AgentRunner 的 conversationHistory。
- 向量库运维：清理归档事实留下的孤儿点（召回已靠回表过滤，不影响正确性）。
- OpenTelemetry：把 `TaskTracer` 的日志出口替换为 OTLP/Langfuse。
- 租户调度升级：当前是并发配额 429，后续可做加权公平队列。

## 简历版：项目描述 + 6 条职责

**项目描述**：ForgeFlow 是一个把 Agent 执行做成服务端任务的平台。一次模型与工具调用被提交为后台任务，
可查询、可取消、执行中断后能由其他 Worker 接管重跑；平台同时提供多 Agent 编排、工具权限治理和会话记忆，
解决 Agent 长任务不可恢复、工具越权、上下文膨胀的问题。

（2 条 Java 后端 + 4 条 Agent）

- 使用**任务状态机 + Redis 租约 + 数据库版本号**，完成 **Worker 宕机后 31s 内的任务自动接管**，避免旧节点重复写入。
- 通过**审批挂起票据 + turn 快照持久化**，完成写工具在**任意 Worker** 上的恢复执行，避免审批绑定单机。
- 使用 **Outbox 本地消息表 + 指数退避重投**，完成数据库与消息队列的**最终一致性**，Broker 恢复后自动补投。
- 通过**首包超时探测 + 三态熔断**，完成模型故障时的**自动切换**，避免请求悬挂与流式中断。
- 封装**会话记忆生命周期组件**，完成**摘要滚动、事实冲突合并与衰减归档**，避免长期记忆只增不减。
- 通过**规则与模型双路规划配合依赖 DAG**，完成复杂任务的拆分与串/并行执行，避免子任务重复探索。
