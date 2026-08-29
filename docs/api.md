# LifeInbox API

默认开发地址：Java `http://localhost:8080`，Python `http://localhost:8000`。浏览器只应调用 Java 产品 API；Python 路由是 Java 与 AI Engine 之间的内部协议。

## 产品 API（Spring Boot）

| Method | Path | 说明 |
| --- | --- | --- |
| GET | `/api/inbox` | 查询 ACTIVE InboxItem，并聚合 AI 状态与五类结果 |
| GET | `/api/inbox/{id}/related` | 只读查询已持久化的 ACTIVE Related InboxItems；不触发发现或 AI |
| POST | `/api/inbox/{id}/relations/discover` | 同步手动执行或重试 Relation Discovery；受独立 Attempt Guard 保护 |
| POST | `/api/inbox/{id}/relations/rediscover` | 仅对 ACTIVE + SUCCESS Source 同步重新发现；新 Attempt、增量且不删除旧关系 |
| POST | `/api/relations/backfill?limit=10` | 显式调度有界历史 ACTIVE + NOT_PROCESSED Relation 处理 |
| GET | `/api/search?q={query}` | 默认 Keyword Search；支持显式 `semantic` 与 `hybrid` 模式 |
| POST | `/api/inbox` | JSON Capture；当前支持 TEXT、URL |
| POST | `/api/inbox/file` | multipart FILE Capture |
| POST | `/api/inbox/image` | multipart IMAGE Capture |
| GET | `/api/files/{storedName}` | 读取受管本地文件 |
| POST | `/api/inbox/{id}/ai/analyze` | 四种类型共用的手工 Analyze / Retry / stale Recovery |
| POST | `/api/inbox/{id}/ai/summary` | 旧兼容入口；仍执行统一 Analyze |
| POST | `/api/inbox/{id}/action-candidates/extract` | 对 ACTIVE InboxItem 同步手动提取，并按 Attempt Guard 原子替换 PENDING Candidate |
| GET | `/api/inbox/{id}/action-candidates` | 查询 ACTIVE InboxItem 已持久化的 Action Candidate |
| POST | `/api/inbox/{id}/action-candidates/{candidateId}/accept` | 接受 Candidate，原子创建唯一 OPEN Todo |
| POST | `/api/inbox/{id}/action-candidates/{candidateId}/dismiss` | 忽略 Candidate，不创建 Todo |
| GET | `/api/todos?status=OPEN|COMPLETED` | 查询 Todo；缺省 status 时返回 OPEN |
| GET | `/api/todos/{id}/source` | 按需读取 Todo 的只读 Inbox/Candidate 来源上下文 |
| POST | `/api/todos/{id}/complete` | 幂等完成 Todo；完成时间由 Java 生成 |
| POST | `/api/todos/{id}/reopen` | 幂等重新打开 Todo，并清空完成时间 |
| PUT | `/api/inbox/{id}/favorite` | 收藏 |
| PUT | `/api/inbox/{id}/unfavorite` | 取消收藏 |
| PUT | `/api/inbox/{id}/archive` | 归档；归档项不再出现在主列表 |
| DELETE | `/api/inbox/{id}` | 删除条目；FILE/IMAGE 同时尽力清理本地文件 |
| GET | `/api/ai/health` | Browser/Client → Java → Python 健康链路 |

### Search API

`GET /api/search?q={query}` 默认由 Spring Boot 直接查询 MySQL，不调用 FastAPI，也不会触发 AI Analyze。已有只传 `q` 的调用保持兼容。

可选参数：

| 参数 | 取值 | 行为 |
| --- | --- | --- |
| `type` | `TEXT` / `URL` / `FILE` / `IMAGE` | 精确过滤 InboxItem 类型；非法类型返回 400 |
| `category` | 最长 32 个字符 | 去除首尾空白后精确过滤已持久化分类；空值等同未提供 |
| `favorite` | `true` / `false` | 分别只返回已收藏 / 未收藏条目；未提供时不过滤 |
| `mode` | `keyword` / `semantic` / `hybrid` | 默认 `keyword`；非法值返回 400 |
| `limit` | 1～50 | 控制 Semantic / Hybrid 最终结果数；默认 20 |

- 三种模式都先去除 `q` 首尾空白，空白查询返回 400；
- 最长 200 个 Java 字符，超长查询返回 400；
- Keyword 模式不依赖 FastAPI、Embedding 或 Qdrant；Semantic 故障不会影响默认路径；

#### Keyword Mode

- `mode` 缺失或为 `keyword` 时使用既有 MySQL Keyword Search；
- `status = ACTIVE`、全部已提供过滤参数与八类 OR 匹配同时生效，归档项不能通过任一匹配路径返回；
- 匹配字段为 `title`、`content`、`summary`、`category`、`tags`、`keywords`、`entities`、`searchable_content`，八类信息使用 OR 语义；
- tags、keywords、entities 通过现有关系表或子表查询；同一条目有多个匹配值时仍只返回一次；
- `%`、`_` 按普通搜索文本处理，不作为用户可控的 LIKE 通配符；
- 基础相关性顺序为：标题精确匹配、标题包含、keyword、tag、entity、summary、searchable content、content、category；
- 同一相关性按 `created_time DESC, id DESC` 确定顺序；
- 响应仍是与 `GET /api/inbox` 相同的 InboxItem 数组，合法查询无结果时返回空数组；
- 相关性只用于数据库运行时排序，不持久化或返回 Search score；
- 后端不返回高亮 HTML。Vue 将可见字段分为普通文本节点与 `<mark>` 节点，查询和内容均由 Vue 转义；
- 当前不搜索 `sourceUrl` 或 `fileUrl`，也不返回匹配原因。

#### Semantic Mode

`GET /api/search?q={query}&mode=semantic` 执行：

```text
Query → FastAPI Query Embedding → Qdrant Cosine Top K
      → Candidate IDs/Scores → Java 一次批量查询 MySQL
      → ACTIVE + type/category/favorite → 按 Candidate 顺序返回 InboxItem[]
```

- Query 与文档索引复用同一 `EmbeddingService`、模型和向量空间；
- Java 请求最多 `min(limit × 2, 100)` 个 Qdrant Candidate，再按 MySQL 业务过滤取前 `limit` 条；过滤后允许不足；
- Qdrant Score 只用于恢复本次语义排序，不保存 MySQL、不加入 InboxItem、也不展示给前端；
- 删除、不存在、ARCHIVED 或不满足 `type/category/favorite` 的 stale Candidate 会被安全忽略；
- MySQL 使用一条 `id IN (...)` 批量查询，不为每个候选单独查询主表；
- Semantic 不执行 Keyword 排名，不与 Keyword 结果合并，也不使用 Score Threshold；
- 响应仍是原有 `InboxItem[]`。字面查询刚好出现在可见字段时仍可安全高亮，否则不生成假高亮；
- Vector Store 关闭、Embedding/Qdrant 故障、Collection 缺失或模型/维度不兼容时返回受控 503；不会静默回退为 Keyword；
- 当前只可召回已经建立 Point 的条目，没有 Startup/Batch Backfill。

#### Hybrid Mode

`GET /api/search?q={query}&mode=hybrid` 由 Java 同时协调现有两条检索路径：

```text
Keyword Rank ──┐
               ├─ RRF by InboxItem.id ─ Hybrid Candidates ─ Optional Rerank ─ InboxItem[]
Semantic Rank ─┘
```

- Keyword 和 Semantic 各取最多 `min(limit × 2, 100)` 个候选；Keyword 的 `LIMIT` 在 MySQL 执行，Semantic 的
  Top K 在 Qdrant 执行，均不会读取全部数据；
- Fusion 使用 Reciprocal Rank Fusion：`RRFScore(d) = Σ 1 / (60 + rank_i(d))`，rank 从 1 开始；`60` 是集中定义的
  排名平滑参数，不是相似度阈值；
- Keyword 字段权重、Qdrant Cosine Score 和 RRF Score 不直接混加。三种 Score 都不持久化，产品响应也不返回；
- 同一条目按 `InboxItem.id` 去重；同时在两边出现时累加两份 RRF contribution，Keyword-only 与 Semantic-only
  条目也都会保留；
- RRF 同分时先比较最佳分支排名，再优先保持 Keyword 排名，然后比较 Semantic 排名和 ID，保证结果确定；
- `ACTIVE/type/category/favorite` 复用两个现有分支的 MySQL 条件，不建立第三套 Hybrid Filter；stale Qdrant Point、
  ARCHIVED 和已删除条目仍会被忽略；
- Java 的 `LIFEINBOX_RERANK_ENABLED` 默认 `false`。关闭时直接裁剪并返回原 RRF 顺序；开启时 RRF 先保留
  `min(limit × 2, 100)` 条候选，再一次批量调用内部 `/rerank`，最后裁到产品 `limit`；
- Rerank 文本只组合 title、summary 与 Task 24 检索正文，每条最多 2,000 个 Java 字符；不发送 favorite、status、
  createdTime、sourceUrl 或 fileUrl，也不重新查询 MySQL、Qdrant 或扩大候选；
- Rerank Score 只决定本次最终顺序。同分保持原 RRF 次序；Provider 少返回的条目按原 RRF 顺序追加；未知/重复 ID、
  非有限 Score、未配置、超时或服务故障都会完整回退原 RRF 顺序；
- Semantic/Embedding/Qdrant 不可用时降级为 Keyword-only；Keyword 分支失败但 Semantic 成功时降级为
  Semantic-only；两边都失败返回受控 503，不会用 HTTP 200 空数组伪装系统故障；
- 两边都正常但都没有候选时返回正常空数组；显式 `mode=semantic` 继续保持原来的受控失败，不自动降级；
- 最终仍返回原有 `InboxItem[]`，最多 `limit` 条；产品响应不增加 Score 或匹配原因。RRF 负责召回融合，Reranker
  只负责有限候选的最终相关性排序，不使用 Chat LLM。

### Related Items Product API

`GET /api/inbox/{id}/related` 只读取 MySQL 中已经持久化的 `content_relation` 和 `inbox_item`：

```http
GET /api/inbox/123/related?limit=10
```

- Source 必须存在且为 `ACTIVE`；不存在或已归档都沿用 ACTIVE-only nested API 的 404；
- `limit` 默认 10，允许 `1..20`，越界返回 400；
- 查询同时覆盖 Source 位于 Canonical Pair 左侧或右侧的情况，只读取 `RELATED_TO`；
- Target 必须当前为 `ACTIVE`；归档 Relation Row 继续保留，但普通 Related Items 不显示归档 Target；历史缺失 Target 被 JOIN 安全过滤；
- 数据库按 `relation.created_time DESC, relation.id DESC, relatedInboxItem.id DESC` 稳定排序并直接应用 limit；
- 整个读取使用一次 Source 校验和一次有界 JOIN，不为每个 Target 单独查询；
- 空列表是正常 `200 []`，不会顺便触发 Relation Discovery；
- 此 GET 不调用 FastAPI、LLM、Embedding、Qdrant 或 Rerank，也不 INSERT、UPDATE、DELETE Relation。

成功响应示例：

```json
[
  {
    "relationType": "RELATED_TO",
    "relatedInboxItem": {
      "id": 456,
      "type": "TEXT",
      "title": "Redisson 分布式锁",
      "summary": "介绍 Redisson 的分布式锁实现",
      "category": "技术学习",
      "preview": "Redisson 提供了...",
      "favorite": false,
      "createdTime": "2026-08-28T10:00:00"
    }
  }
]
```

`preview` 最多 300 个 Unicode Code Point：TEXT 来自 `content`，URL/FILE/IMAGE 来自已经持久化的
`searchable_content`，为空时回退 title。响应不包含完整正文、left/right Canonical Storage、Relation ID、Score、Evidence、
Provider 信息、AI/Action Attempt 或其他内部处理状态。

### Relation Discovery Processing API

`POST /api/inbox/{id}/relations/discover` 是 Task 47 的同步手动入口：

```http
POST /api/inbox/123/relations/discover
```

- Source 必须存在且为 `ACTIVE`；
- `NOT_PROCESSED`、`FAILED` 和 stale `PROCESSING` 可领取新的 UUID Attempt；
- fresh `PROCESSING` 与 `SUCCESS` 返回 409，不会重复调用 Provider；
- Source Vector 必须已经就绪。未就绪返回受控 409 并把当前 Attempt 安全结束为 `FAILED`，之后可手动重试；
- Vector 已就绪但没有候选或 LLM 返回空关系是合法 SUCCESS；
- Qdrant/LLM 在事务外执行，最终短事务原子提交 additive Relation 和 `SUCCESS`；
- 失败、空结果和重试都不删除既有 `content_relation`。

成功响应只包含状态和计数：

```json
{
  "relationStatus": "SUCCESS",
  "discoveredCount": 2,
  "persistedNewCount": 1,
  "alreadyExistingCount": 1,
  "skippedInvalidCount": 0
}
```

`POST /api/inbox/{id}/relations/rediscover` 是独立的同步重新发现入口：

```http
POST /api/inbox/123/relations/rediscover
```

- Source 必须存在、为 `ACTIVE`，并且当前 `relation_status=SUCCESS`；NOT_PROCESSED/FAILED/PROCESSING 返回 409，继续使用原 discover/retry/recovery 入口；
- Rediscover 原子执行 `SUCCESS → PROCESSING` 并创建新的 UUID Attempt，并发请求只有一个能取得 Owner；
- Source Vector 必须已经存在。缺失时不生成 Embedding、不重建 Vector、不标记 SUCCESS，而是返回现有受控 409 并让当前 Attempt 进入 FAILED；
- 后续同步复用 Task 42 候选、Task 43 AI 判断、Task 44 增量持久化和 Task 47 Attempt Guard；
- 空结果是 SUCCESS；Provider/Qdrant/持久化失败是 guarded FAILED；两者都不删除已有 `content_relation`；
- “本次未再次发现某个旧关系”不是删除信号，响应结构与 discover 相同且不包含 Attempt、Vector、Score 或 Provider 信息。

`POST /api/relations/backfill` 是显式、有界的历史调度入口：

```http
POST /api/relations/backfill?limit=10
```

- `limit` 默认 10，只允许 `1..20`，越界或非整数返回 400；
- 数据库只选择 `ACTIVE + NOT_PROCESSED`，按 `id ASC` 稳定排序，一次最多扫描 `min(limit * 5, 100)` 条；
- 每个候选只通过现有 `/vector/neighbors` 读取 Source Point 就绪状态；Vector 缺失就跳过并保持 NOT_PROCESSED，不调用 Embedding 或 Vector Index；
- Vector 已就绪后复用 Task 47 的原子 NOT_PROCESSED Claim，再投递现有有界 `aiTaskExecutor`；自动触发、手动请求或重复 Backfill 先取得 Owner 时，本批只记录 Claim 冲突；
- Endpoint 返回调度摘要，不等待所有 LLM 调用完成。队列拒绝会按当前 Attempt 安全结束为 FAILED，不会永久停在 PROCESSING；
- 不处理 FAILED、PROCESSING、SUCCESS 或 ARCHIVED，不自动重试，不删除既有 Relation。

调度响应示例：

```json
{
  "requestedLimit": 10,
  "scannedCount": 18,
  "scheduledCount": 8,
  "skippedNotReadyCount": 7,
  "claimConflictCount": 3
}
```

自动入口没有额外 HTTP API：只有 Qdrant 索引明确返回 `indexed=true` 才投递后台首次发现，且自动 Claim 仅允许
`NOT_PROCESSED`。系统不会在启动时或定时扫描历史数据、自动重试 FAILED 或自动重新处理 SUCCESS；Task 48 的 Backfill 和 Rediscover
都必须显式调用。普通
`GET /api/inbox/{id}/related` 与前端展开/读取仍不会触发发现。

### JSON Capture

TEXT：

```json
{
  "type": "TEXT",
  "title": "可选标题",
  "content": "正文"
}
```

URL：

```json
{
  "type": "URL",
  "title": "可选标题",
  "sourceUrl": "https://example.com/article"
}
```

### InboxItem 的 AI 字段

`GET /api/inbox`、Capture 和 Analyze 都使用统一 InboxItem 响应。AI 相关字段为：

```json
{
  "summary": "摘要",
  "category": "技术学习",
  "tags": ["Java"],
  "keywords": ["Spring Boot"],
  "entities": [{"name": "Spring", "type": "ORGANIZATION"}],
  "aiStatus": "SUCCESS",
  "aiAttemptId": "uuid",
  "aiErrorMessage": null,
  "aiStartedTime": "2026-08-21T10:00:00",
  "aiFinishedTime": "2026-08-21T10:00:02",
  "aiProcessingStale": false,
  "actionStatus": "SUCCESS",
  "actionProcessingStale": false,
  "relationStatus": "SUCCESS",
  "relationProcessingStale": false
}
```

fresh PROCESSING 的重复请求返回 409。失败只更新 Attempt 状态，旧的成功结果仍可能继续出现在响应中。Java 不向浏览器透传 Python Traceback、SQL Exception、上游正文或 API Key。
Action 与 Relation 的 Attempt ID、错误摘要和内部时间不向产品 JSON 暴露。

### Action Candidate 产品 API

`POST /api/inbox/{id}/action-candidates/extract` 对指定 ACTIVE InboxItem 执行一次显式 Action Extraction：

```text
Load ACTIVE InboxItem → Claim Action Attempt
→ TEXT 使用 content，URL/FILE/IMAGE 使用 searchable_content
→ 可选 title 上下文 + 有界正文（总计最多 20,000 字符）
→ created_time.toLocalDate() 生成稳定 referenceDate
→ FastAPI /action/extract
→ Java 校验类型、数量、字段长度、日期与 hasAction 一致性
→ 短事务检查 Attempt Owner，只替换 PENDING 并原子标记 SUCCESS
```

成功响应和 `GET /api/inbox/{id}/action-candidates` 都返回产品侧 Candidate 数组：

```json
[
  {
    "id": 1,
    "inboxItemId": 100,
    "actionType": "DEADLINE",
    "title": "提交软件工程课程设计报告",
    "deadlineText": "2026年8月25日前",
    "deadline": "2026-08-25",
    "evidence": "2026年8月25日前提交软件工程课程设计报告",
    "status": "PENDING",
    "createdTime": "2026-08-24T10:00:00",
    "updatedTime": "2026-08-24T10:00:00"
  }
]
```

- 所有新 Candidate 都是 `PENDING`；只有下面的显式 Accept API 才会创建 Todo；
- FastAPI 成功返回空 `actions` 时，旧 PENDING Candidate 会在短事务中清除，并返回空数组；
- 重新提取不会删除 `ACCEPTED` 或 `DISMISSED`；这些终态只由用户 Accept / Dismiss 产生；
- 与现有 ACCEPTED/DISMISSED 在类型、规范化标题、日期原文和归一化日期上完全相同的新结果不会再次创建 PENDING；不做语义去重；
- FastAPI 超时、5xx、非法类型、非法日期、字段越界或矛盾 `hasAction` 返回受控 503，且不会修改旧 Candidate；
- InboxItem 不存在或已归档返回 404；title 与可用正文都为空返回 400，并且不会调用 FastAPI；
- Archive 不自动删除 Candidate；真正删除 Source 时由 MySQL Foreign Key `ON DELETE CASCADE` 清理；
- 外部 AI 调用不在数据库事务内。每次手动/自动提取都使用独立 Action Attempt Guard；fresh PROCESSING 返回 409，stale PROCESSING 可由新 Attempt 接管，迟到成功或失败不能覆盖新结果。
- Todo List / Complete / Reopen 已由下面的独立产品 API 提供；当前没有 Edit / Delete / Manual Create API。

自动入口不新增产品 API：TEXT 在 Capture 事务提交后投递；URL/FILE/IMAGE 在现有内容准备成功写入
`searchable_content` 后投递。两者都通过 AFTER_COMMIT 与现有有界 AI Executor 执行。队列拒绝发生在 Claim
前，因此不会让 Capture 失败，也不会留下无人处理的 PROCESSING；用户仍可通过本同步 API 手动重试。

#### Accept Candidate

`POST /api/inbox/{id}/action-candidates/{candidateId}/accept` 无 Request Body。它确认已经持久化的 Candidate，
不会再次调用 FastAPI、LLM 或日期归一化器：

```text
SELECT Candidate FOR UPDATE
→ 校验状态
→ TodoService.create
→ Candidate PENDING → ACCEPTED
→ 同一短事务提交
```

首次成功响应：

```json
{
  "candidate": {
    "id": 1,
    "inboxItemId": 100,
    "actionType": "DEADLINE",
    "title": "提交软件工程课程设计报告",
    "deadlineText": "2026年8月25日前",
    "deadline": "2026-08-25",
    "evidence": "2026年8月25日前提交软件工程课程设计报告",
    "status": "ACCEPTED",
    "createdTime": "2026-08-24T10:00:00",
    "updatedTime": "2026-08-24T10:01:00"
  },
  "todo": {
    "id": 200,
    "sourceInboxItemId": 100,
    "sourceActionCandidateId": 1,
    "title": "提交软件工程课程设计报告",
    "description": null,
    "status": "OPEN",
    "dueDate": "2026-08-25",
    "completedTime": null,
    "createdTime": "2026-08-24T10:01:00",
    "updatedTime": "2026-08-24T10:01:00"
  }
}
```

字段只复制一次：`title → Todo.title`、`deadline → Todo.dueDate`、Inbox/Candidate ID → 两个 Source ID；
`Todo.description=null`、`status=OPEN`、`completedTime=null`。`evidence`、`deadlineText` 和 `actionType` 不复制到 Todo。
无法确定具体日期的 DEADLINE 也可以接受，此时 `dueDate=null`。

- 对已经 `ACCEPTED` 且关联 Todo 存在的 Candidate 重复 Accept，会返回同一个 Todo，不会新建第二条；
- `DISMISSED → ACCEPTED` 返回 409；Candidate 或嵌套路由中的 Inbox ID 不匹配时返回 404；
- `ACCEPTED` 但关联 Todo 缺失属于数据完整性异常，返回受控 500 并记录安全日志，不会偷偷重建；
- MySQL Candidate 行锁负责串行化并发决策，`UNIQUE(todo.source_action_candidate_id)` 是最后一道重复保护。

#### Dismiss Candidate

`POST /api/inbox/{id}/action-candidates/{candidateId}/dismiss` 同样无 Request Body。`PENDING` 会在短事务中变为
`DISMISSED` 并返回完整 Candidate 产品 DTO；不会删除 Candidate，也不会创建或删除 Todo。

- 对 `DISMISSED` 重复 Dismiss 幂等返回当前 Candidate；
- `ACCEPTED → DISMISSED` 返回 409，已有 Todo 保持不变；
- Candidate 不存在或 Inbox ID 不匹配返回 404；
- 普通 Re-extraction 仍只替换 `PENDING`，不会删除 `ACCEPTED` 或 `DISMISSED` 用户决定。

### Todo 产品 API

#### List Todo

`GET /api/todos` 默认等价于 `GET /api/todos?status=OPEN`。`status` 只允许精确的 `OPEN` 或
`COMPLETED`，其他值返回受控 400；正常无数据返回 `[]`。

OPEN 排序为：有 `dueDate` 的记录优先，按 `dueDate ASC`，再按 `createdTime DESC, id DESC`；没有
`dueDate` 的记录排在其后。COMPLETED 按 `completedTime DESC`，历史异常的空完成时间排在非空值之后，
再以 `createdTime DESC, id DESC` 稳定排序。查询只读取 `todo`，不 JOIN InboxItem 或 ActionCandidate，
因此来源引用为空或来源已删除后 Todo 仍可见。

响应是 Todo 产品 DTO 数组：

```json
[
  {
    "id": 200,
    "sourceInboxItemId": 100,
    "sourceActionCandidateId": 1,
    "title": "提交软件工程课程设计报告",
    "description": null,
    "status": "OPEN",
    "dueDate": "2026-08-25",
    "completedTime": null,
    "createdTime": "2026-08-24T10:01:00",
    "updatedTime": "2026-08-24T10:01:00"
  }
]
```

`dueDate` 是 Calendar Date 字符串，不代表 UTC 时间戳。

#### Todo Source Context

`GET /api/todos/{id}/source` 只在用户查看单条 Todo 来源时调用，不属于列表查询：

```json
{
  "todoId": 200,
  "sourceAvailable": true,
  "inboxItem": {
    "id": 100,
    "type": "TEXT",
    "title": "软件工程课程设计",
    "preview": "课程设计报告需要在2026年8月25日前提交",
    "sourceUrl": null,
    "fileUrl": null,
    "status": "ARCHIVED",
    "createdTime": "2026-08-20T09:30:00"
  },
  "actionCandidate": {
    "id": 1,
    "actionType": "DEADLINE",
    "title": "提交软件工程课程设计报告",
    "deadlineText": "2026年8月25日前",
    "deadline": "2026-08-25",
    "evidence": "课程设计报告需要在2026年8月25日前提交",
    "status": "ACCEPTED"
  }
}
```

- Todo 不存在时沿用 404；Todo 没有来源时返回 200、`sourceAvailable=false` 且两个摘要为 `null`；
- 某个引用失效时返回仍存在的部分；两个来源都不存在时同样安全返回不可用，不返回 500；
- ARCHIVED InboxItem 可作为来源上下文读取，但不会重新进入普通 Inbox 列表；
- Inbox `preview` 是最多 300 个 Unicode 字符的纯文本。TEXT 来自 `content`；URL/FILE/IMAGE 只读已经持久化的
  `searchable_content`，为空时回退 title；不会抓取 URL、解析文件、执行 OCR 或调用 AI；
- 响应不包含完整 `searchable_content`、AI/Action Attempt 状态或本地文件路径；只返回合法 HTTP(S) 原链接和
  `/api/files/...` 受管文件地址；
- `deadlineText` 保留原表达，`deadline` 是可空 Calendar Date；客户端必须把空值显示为“未确定”，不能猜年份；
- 该 API 无事务写入，不修改 Todo、ActionCandidate 或 InboxItem。Todo List 继续只查询 `todo`，避免 Source JOIN 与 N+1。

#### Complete Todo

`POST /api/todos/{id}/complete` 无 Request Body。Service 在短事务中锁定 Todo 行，首次执行
`OPEN → COMPLETED`，并使用 Java 当前业务时间设置 `completedTime`。已是 COMPLETED 时幂等返回当前 DTO，
不会刷新第一次成功完成时间；不存在返回 404。

#### Reopen Todo

`POST /api/todos/{id}/reopen` 无 Request Body。Service 在短事务中执行 `COMPLETED → OPEN` 并把
`completedTime` 清空；已是 `OPEN + completedTime=null` 时幂等返回当前 DTO；不存在返回 404。

Complete / Reopen 都只修改 Todo，不修改关联 ActionCandidate，不重新运行 Action Extraction，也不调用
FastAPI、LLM 或 Qdrant。当前没有 Todo Edit、Delete、Manual Create、Reminder 或 Calendar API。

## Java → Python 内部 API

| Method | Path | 输入 | 输出 |
| --- | --- | --- | --- |
| GET | `/health` | 无 | `{status, service}` |
| POST | `/analyze` | JSON `{title?, text}` | AnalyzeResult |
| POST | `/action/extract` | JSON `{text, referenceDate?}` | `{hasAction, actions[]}`；只返回 Action 建议 |
| POST | `/embedding` | JSON `{text}` | `{model, dimension, embedding}`；只生成并校验瞬时向量 |
| POST | `/vector/index` | JSON `{inboxItemId, text}` | 生成 Embedding 并按稳定 Point ID Upsert 到 Qdrant |
| DELETE | `/vector/index/{inboxItemId}` | 路径 ID | 幂等删除该 Collection 前缀下的受管 Point |
| POST | `/vector/search` | JSON `{query, limit?}` | Query Embedding + Qdrant Top K，返回 `{inboxItemId, score}` 候选 |
| POST | `/vector/neighbors` | JSON `{inboxItemId, limit?}` | 复用已有 Source Point Vector，返回有界邻居 ID/Score |
| POST | `/rerank` | JSON `{query, documents, topK}` | 一次批量重排已有 Candidate，返回 `{id, score}` 排名 |
| POST | `/prepare/url` | JSON `{title?, url}` | `{title?, text}`；只复用安全网页提取，不调用 LLM |
| POST | `/prepare/file` | multipart `file`, `title?` | `{title?, text}`；只复用文档提取，不调用 LLM |
| POST | `/prepare/image` | multipart `file`, `title?` | `{title?, text}`；只复用 OCR，不调用 LLM |
| POST | `/analyze/url` | JSON `{title?, url}` | AnalyzeResult |
| POST | `/analyze/file` | multipart `file`, `title?` | AnalyzeResult |
| POST | `/analyze/image` | multipart `file`, `title?` | AnalyzeResult |
| POST | `/summarize` | JSON `{title?, text}` | 兼容响应 `{summary}` |

AnalyzeResult 固定包含：

```json
{
  "summary": "...",
  "category": "技术学习",
  "tags": ["..."],
  "keywords": ["..."],
  "entities": [{"name": "...", "type": "TECHNOLOGY"}]
}
```

Category 只能是：`技术学习`、`学习成长`、`工作`、`求职`、`生活`、`财务`、`想法`、`资讯`、`其他`。Tags 为 1～5 个，Keywords 为 0～8 个，Entities 为 0～10 个；Python 和 Java 都会校验。

URL、FILE、IMAGE 提取失败使用有限的 `code/detail` 协议。Java 只允许已知 code 与预期 HTTP 状态组合进入产品响应，未知或畸形错误统一降级为安全的 AI 503。

Java 的当前 Analyze 流程对 URL/FILE/IMAGE 先调用对应 `/prepare/*`，以 Attempt Guard 保存可重建正文，再把正文交给 `/analyze`。
因此 LLM 失败时已成功提取的 Searchable Content 仍可保留；`/prepare/*` 是内部协议，不是浏览器产品 API。

### Action Extraction 内部协议

`POST /action/extract` 是 V0.4 的独立 AI 能力。它接收准备好的纯文本和可选稳定参考日期，不接收完整 InboxItem、favorite、archive、权限或其他业务字段：

```json
{
  "text": "明天之前提交软件工程课程设计报告。",
  "referenceDate": "2026-08-24"
}
```

成功响应：

```json
{
  "hasAction": true,
  "actions": [
    {
      "actionType": "DEADLINE",
      "title": "提交软件工程课程设计报告",
      "deadlineText": "明天之前",
      "deadline": "2026-08-25",
      "evidence": "明天之前提交软件工程课程设计报告"
    }
  ]
}
```

- `text` trim 后不能为空，最多 20,000 字符；请求缺失、空白、超长或非法 JSON 返回 422；
- `referenceDate` 可省略；Java 正常调用始终使用 `InboxItem.created_time.toLocalDate()`。省略时，相对日期保持未解析，绝不回退到服务器当前日期；
- `actions` 允许为空，最多 10 项；`hasAction` 由 Python 根据校验后的数组计算，不信任 Provider 布尔值；
- `actionType` 仅允许 `TODO` 与 `DEADLINE`。TODO 的 `deadlineText/deadline` 都为 `null`；DEADLINE 必须保留来自原文的截止表达；
- 标题最长 200 字符，`deadlineText` 最长 100 字符，纯文本 `evidence` 最长 500 字符并且必须可追溯到输入；
- 应用层 `DeadlineNormalizer` 确定性处理完整日期、今天/明天/后天、本周或下周星期、本月底/月底/下月底、今年/明年；缺少年份、单独“周五”和模糊表达只保留 `deadlineText`，`deadline` 为 `null`；
- 日期必须与行动语义关联，出版/发布日期等描述性日期不自动产生 Action；零 Action 是正常成功结果；
- 该能力复用现有 `LIFEINBOX_LLM_*` 配置、Chat Completions JSON Mode 与错误模型。配置/服务错误为 503，超时为 504，无效 Provider/结构化结果为 502；
- Java 的手动与自动 Action Candidate 编排都会调用该内部协议，并在 Java 再次校验后按独立 Action Attempt Guard 写入 MySQL `action_candidate`；它不复用 Analyze 状态、不写 Qdrant，也不自动创建或修改 Todo。

### Embedding 内部协议

`POST /embedding` 只接收 Task 24 已准备好的文本：

```json
{"text": "Redis 分布式锁需要避免误释放。"}
```

成功响应中的 `dimension` 由 Provider 返回向量的真实长度计算：

```json
{"model": "configured-model", "dimension": 3, "embedding": [0.1, -0.2, 0.3]}
```

输入去除首尾空白后不能为空，最多 20,000 字符，不会被静默截断或分块。Python 拒绝空向量、多个向量、
缺失模型、非法数值、NaN 和 Infinity。未配置 Embedding Model 返回 503；超时返回 504；Provider 状态错误返回 503；
畸形响应返回 502。错误不会包含 API Key、完整输入、Provider 正文或完整向量。

这是 Java → Python 的内部 Embedding 能力，不是浏览器产品 API。`/embedding` 本身仍不保存向量或理解 InboxItem。

### Vector Index 内部协议

`POST /vector/index` 只接收 Java 从当前 MySQL 状态解析出的稳定 ID 和 Task 24 Searchable Content：

```json
{"inboxItemId": 123, "text": "Redis 分布式锁需要避免误释放。"}
```

Vector Store 启用且 Upsert 成功时返回：

```json
{
  "inboxItemId": 123,
  "indexed": true,
  "collection": "lifeinbox_items__m_<model-sha256>__d_1024",
  "model": "configured-embedding-model",
  "dimension": 1024,
  "contentHash": "<searchable-content-sha256>"
}
```

默认关闭时返回 `indexed=false`，其余向量元数据为 `null`，并且不会调用 Embedding Provider。Point ID 直接使用
`InboxItem.id`；Payload 只有 `inboxItemId`、`embeddingModel`、`contentHash`、`indexedTime`，不保存完整正文。
Collection 使用 Cosine，首次索引时以真实 `dimension` 惰性创建；配置名作为前缀，物理名称同时绑定完整模型 Hash
与维度。已有同名 Collection 的维度或距离不匹配会返回 409，绝不自动 DROP。

`DELETE /vector/index/{inboxItemId}` 会从该配置前缀下所有 LifeInbox 管理的模型/维度 Collection 中幂等删除同一
Point ID；Vector Store 关闭时返回 `deleted=false`。Qdrant 超时返回 504，配置或服务故障返回 503，不兼容返回
409，非法响应返回 502；响应不会包含 API Key、正文、完整向量或 Qdrant 内部响应。

Spring Boot 只在 Capture 提交或 Attempt-guarded 正文更新后异步触发 Index，并在 Archive/Delete 后异步触发
Delete。Point 生命周期故障不会修改业务状态或破坏默认 Keyword Search。

### Semantic Search 内部协议

`POST /vector/search` 只供 Java 调用：

```json
{"query": "那个防止接口重复请求的 Redis 方案", "limit": 40}
```

成功响应：

```json
{
  "results": [
    {"inboxItemId": 123, "score": 0.91},
    {"inboxItemId": 456, "score": 0.82}
  ]
}
```

`query` trim 后不能为空，最长 200 字符；`limit` 默认 20、范围 1～100。服务复用现有 EmbeddingService，按返回的
`model + dimension` 计算 Task 26 物理 Collection，验证单向量配置、真实维度和 Cosine 后调用 Qdrant
`query_points`。请求只取 ID/Score，不读取 Payload 或完整 Vector，也不创建缺失 Collection。

Vector Store 关闭返回 503，Collection 不存在返回 404，模型/维度/距离不兼容返回 409，Qdrant 超时返回 504，
其他安全封装的服务故障返回 502/503。Java 不向产品调用方透传 Python、Provider 或 Qdrant 的内部响应。

### Vector Neighbor 内部协议

`POST /vector/neighbors` 只供 Java 的 Task 42 Relation Candidate Discovery 调用。它接收已存在的 Source InboxItem ID：

```json
{"inboxItemId": 123, "limit": 20}
```

`inboxItemId` 必须为正整数；`limit` 默认 20、范围 `1..20`。服务根据当前 Embedding Model 定位已有模型/维度
Collection，读取 Source Point 保存的 Vector，再交给 Qdrant 执行近邻搜索。它不会把 Source 正文塞入
`/vector/search`，不会调用 Embedding Provider，也不会创建 Relation 专属 Collection。

成功且 Source 已索引时返回 ID 与瞬时相似度：

```json
{
  "sourceIndexed": true,
  "results": [
    {"inboxItemId": 456, "score": 0.91},
    {"inboxItemId": 789, "score": 0.84}
  ]
}
```

Python 使用 `min(limit * 3, 100)` 作为内部 over-fetch 上限并过滤 Source 自身；Java 还会批量回查 MySQL，过滤
失效/删除、非 ACTIVE、self 和已存在 `RELATED_TO` 的条目，再应用最终 limit。响应不含正文、Payload 或完整 Vector。

Source Point 尚未索引是正常状态：

```json
{"sourceIndexed": false, "results": []}
```

这不等于基础设施故障。Vector Store 关闭、Collection 缺失、模型/维度不兼容、Qdrant 超时或不可用继续使用现有
受控 Vector 错误协议。该接口只产生运行时候选，不执行 AI Relation Judgment、不写 `content_relation`，也不是浏览器
可调用的产品 Relation API。

### Relation Discovery 内部协议

`POST /relation/discover` 只供 Java 的 Task 43 `RelationDiscoveryService` 调用。Java 先复用 Task 42 候选，再按 MySQL
当前状态重新过滤非 ACTIVE、已删除和已经建立 `RELATED_TO` 的条目，最后发送一次有界批量请求：

```json
{
  "source": {"inboxItemId": 123, "text": "标题：Spring 事务\n正文：事务失效排查"},
  "candidates": [
    {"inboxItemId": 456, "text": "标题：代理调用\n正文：同类问题记录"},
    {"inboxItemId": 789, "text": "标题：Redis\n正文：缓存笔记"}
  ]
}
```

Source 文本最多 4,000 个字符，每个 Candidate 最多 1,000 个字符，Candidate 最多 20 条，因此正文总量最多约 24,000
字符。文本只来自 title、summary 与既有可用正文：TEXT 使用 `content`，URL/FILE/IMAGE 使用 `searchable_content`；不会重新
抓取 URL、解析文件、执行 OCR 或 Embedding。Task 42 的 `semanticScore` 不进入此请求。

成功响应必须且只能包含：

```json
{"relatedTargetInboxItemIds": [456]}
```

`[]` 是“没有足够明确关系”的正常成功结果，空 Candidate 请求也直接返回空列表且不调用 LLM。返回 ID 必须为正整数、唯一、
不等于 Source、数量有界且全部来自本次 Candidates；未知、重复、Source ID、多余字段或畸形 JSON 会使整次结果无效，而不是
静默删除非法项。Provider 未配置/不可用返回 503，超时返回 504，非法结构化结果返回 502，响应不会泄露 Prompt、正文、密钥
或 Provider 原始内容。

该接口只产生运行时 `RELATED_TO` 建议，不写 `content_relation`，不是产品 API，也不代表用户已确认 Relation。

### Rerank 内部协议

`POST /rerank` 只接收 Java 已完成业务过滤与 RRF 融合的有限候选：

```json
{
  "query": "怎样防止接口重复提交",
  "documents": [
    {"id": 123, "text": "标题：接口幂等\n摘要：同一请求只执行一次"},
    {"id": 456, "text": "标题：Redis 分布式锁\n正文：锁获取与释放"}
  ],
  "topK": 2
}
```

成功响应：

```json
{
  "results": [
    {"id": 123, "score": 0.93},
    {"id": 456, "score": 0.71}
  ]
}
```

- Query trim 后不能为空且最多 200 字符；最多 100 个 Document，每个 ID 必须唯一且为正数，文本最多 2,000 字符；
- Python 使用 `LIFEINBOX_RERANK_MODEL`、`LIFEINBOX_RERANK_BASE_URL` 和
  `LIFEINBOX_RERANK_TIMEOUT_SECONDS`，API Key 复用 `LIFEINBOX_LLM_API_KEY`；当前 Provider 实现调用百炼
  `qwen3-rerank` 兼容的批量 `/reranks`，不会逐条发送 N 次请求，也不会改变 Chat/Embedding Base URL；
- 空 Documents 返回空结果且不读取 Provider 配置；少返回的结果允许由 Java 按原 RRF 顺序补齐；
- 越界/重复 Provider index、NaN/Infinity 或畸形响应返回 502；未配置和 Provider 状态错误返回 503；超时返回 504；
- Java 对 ID、重复项与 Score 再做一次校验。任何 Rerank 失败只影响精排，Hybrid 继续以原 RRF 顺序成功返回。

## 主要限制

- URL：只读取静态 HTML，不执行 JavaScript；
- FILE：只分析 UTF-8 TXT/MD 和带文本层 PDF；
- IMAGE：只分析 JPG/PNG/WEBP 中的文字，不做通用 Vision；
- Python `/summarize` 和 Java `/ai/summary` 仅为兼容保留，不是第二套分析流程。
