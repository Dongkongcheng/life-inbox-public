# LifeInbox V0.3 API

默认开发地址：Java `http://localhost:8080`，Python `http://localhost:8000`。浏览器只应调用 Java 产品 API；Python 路由是 Java 与 AI Engine 之间的内部协议。

## 产品 API（Spring Boot）

| Method | Path | 说明 |
| --- | --- | --- |
| GET | `/api/inbox` | 查询 ACTIVE InboxItem，并聚合 AI 状态与五类结果 |
| GET | `/api/search?q={keyword}` | Keyword Search；支持可选过滤、基础相关性排序，查询 ACTIVE InboxItem |
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

### Keyword Search

`GET /api/search?q={keyword}` 由 Spring Boot 直接查询 MySQL，不调用 FastAPI，也不会触发 AI Analyze。已有只传 `q` 的调用保持兼容。

可选参数：

| 参数 | 取值 | 行为 |
| --- | --- | --- |
| `type` | `TEXT` / `URL` / `FILE` / `IMAGE` | 精确过滤 InboxItem 类型；非法类型返回 400 |
| `category` | 最长 32 个字符 | 去除首尾空白后精确过滤已持久化分类；空值等同未提供 |
| `favorite` | `true` / `false` | 分别只返回已收藏 / 未收藏条目；未提供时不过滤 |

- `q` 会先去除首尾空白，空白查询返回 400；
- 最长 200 个 Java 字符，超长查询返回 400；
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

这是 Java → Python 的内部能力，不是浏览器产品 API。Task 25 不保存向量、不自动处理 InboxItem，也不修改 Keyword Search。

## 主要限制

- URL：只读取静态 HTML，不执行 JavaScript；
- FILE：只分析 UTF-8 TXT/MD 和带文本层 PDF；
- IMAGE：只分析 JPG/PNG/WEBP 中的文字，不做通用 Vision；
- Python `/summarize` 和 Java `/ai/summary` 仅为兼容保留，不是第二套分析流程。
