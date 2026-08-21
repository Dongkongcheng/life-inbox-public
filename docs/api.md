# LifeInbox V0.3 API

默认开发地址：Java `http://localhost:8080`，Python `http://localhost:8000`。浏览器只应调用 Java 产品 API；Python 路由是 Java 与 AI Engine 之间的内部协议。

## 产品 API（Spring Boot）

| Method | Path | 说明 |
| --- | --- | --- |
| GET | `/api/inbox` | 查询 ACTIVE InboxItem，并聚合 AI 状态与五类结果 |
| GET | `/api/search?q={keyword}` | Basic Keyword Search；查询 ACTIVE InboxItem |
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

### Basic Keyword Search

`GET /api/search?q={keyword}` 由 Spring Boot 直接查询 MySQL，不调用 FastAPI，也不会触发 AI Analyze。

- `q` 会先去除首尾空白，空白查询返回 400；
- 最长 200 个 Java 字符，超长查询返回 400；
- 仅匹配 ACTIVE InboxItem 的 `title`、`content`、`summary`、`category`，四个字段使用 OR 语义；
- `%`、`_` 按普通搜索文本处理，不作为用户可控的 LIKE 通配符；
- 结果按 `created_time DESC, id DESC` 排序；
- 响应仍是与 `GET /api/inbox` 相同的 InboxItem 数组，合法查询无结果时返回空数组；
- 当前不搜索 tags、keywords、entities、sourceUrl 或 fileUrl。

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

## 主要限制

- URL：只读取静态 HTML，不执行 JavaScript；
- FILE：只分析 UTF-8 TXT/MD 和带文本层 PDF；
- IMAGE：只分析 JPG/PNG/WEBP 中的文字，不做通用 Vision；
- Python `/summarize` 和 Java `/ai/summary` 仅为兼容保留，不是第二套分析流程。
