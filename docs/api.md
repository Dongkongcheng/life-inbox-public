# LifeInbox V0.3 API

默认开发地址：Java `http://localhost:8080`，Python `http://localhost:8000`。浏览器只应调用 Java 产品 API；Python 路由是 Java 与 AI Engine 之间的内部协议。

## 产品 API（Spring Boot）

| Method | Path | 说明 |
| --- | --- | --- |
| GET | `/api/inbox` | 查询 ACTIVE InboxItem，并聚合 AI 状态与五类结果 |
| GET | `/api/search?q={query}` | 默认 Keyword Search；支持显式 `semantic` 与 `hybrid` 模式 |
| POST | `/api/inbox` | JSON Capture；当前支持 TEXT、URL |
| POST | `/api/inbox/file` | multipart FILE Capture |
| POST | `/api/inbox/image` | multipart IMAGE Capture |
| GET | `/api/files/{storedName}` | 读取受管本地文件 |
| POST | `/api/inbox/{id}/ai/analyze` | 四种类型共用的手工 Analyze / Retry / stale Recovery |
| POST | `/api/inbox/{id}/ai/summary` | 旧兼容入口；仍执行统一 Analyze |
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
               ├─ RRF by InboxItem.id ─ MySQL-authoritative InboxItem[]
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
- Semantic/Embedding/Qdrant 不可用时降级为 Keyword-only；Keyword 分支失败但 Semantic 成功时降级为
  Semantic-only；两边都失败返回受控 503，不会用 HTTP 200 空数组伪装系统故障；
- 两边都正常但都没有候选时返回正常空数组；显式 `mode=semantic` 继续保持原来的受控失败，不自动降级；
- 最终仍返回原有 `InboxItem[]`，最多 `limit` 条。RRF 不等于 Rerank，本任务没有调用 Chat LLM 或 Reranker。

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
  "aiProcessingStale": false
}
```

fresh PROCESSING 的重复请求返回 409。失败只更新 Attempt 状态，旧的成功结果仍可能继续出现在响应中。Java 不向浏览器透传 Python Traceback、SQL Exception、上游正文或 API Key。

## Java → Python 内部 API

| Method | Path | 输入 | 输出 |
| --- | --- | --- | --- |
| GET | `/health` | 无 | `{status, service}` |
| POST | `/analyze` | JSON `{title?, text}` | AnalyzeResult |
| POST | `/embedding` | JSON `{text}` | `{model, dimension, embedding}`；只生成并校验瞬时向量 |
| POST | `/vector/index` | JSON `{inboxItemId, text}` | 生成 Embedding 并按稳定 Point ID Upsert 到 Qdrant |
| DELETE | `/vector/index/{inboxItemId}` | 路径 ID | 幂等删除该 Collection 前缀下的受管 Point |
| POST | `/vector/search` | JSON `{query, limit?}` | Query Embedding + Qdrant Top K，返回 `{inboxItemId, score}` 候选 |
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

## 主要限制

- URL：只读取静态 HTML，不执行 JavaScript；
- FILE：只分析 UTF-8 TXT/MD 和带文本层 PDF；
- IMAGE：只分析 JPG/PNG/WEBP 中的文字，不做通用 Vision；
- Python `/summarize` 和 Java `/ai/summary` 仅为兼容保留，不是第二套分析流程。
