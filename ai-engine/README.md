# LifeInbox AI Engine

AI Engine 为 TEXT、URL、FILE、IMAGE 提供统一 Analyze，为 V0.3 提供 Embedding Generation、Qdrant Vector Index、Semantic Candidate Retrieval 与可选 Rerank，在 V0.4 Task 31 提供独立 Action Extraction，并在 V0.5 Task 42 复用已有 Source Point Vector 提供有界 Neighbor Candidate。Python 不连接 MySQL；InboxItem、Searchable Content、Action/Todo/Relation 业务状态、文件和业务生命周期仍由 Java/MySQL 管理，Qdrant 只是可以重建的派生检索索引。

## 安装依赖

```powershell
uv sync
```

## 启动服务

```powershell
$env:LIFEINBOX_LLM_API_KEY="<your-api-key>"
$env:LIFEINBOX_LLM_MODEL="<your-model>"
$env:LIFEINBOX_EMBEDDING_MODEL="<your-embedding-model>" # Embedding/Index/Semantic Search 使用
$env:LIFEINBOX_RERANK_MODEL="<your-rerank-model>" # 可选 qwen3-rerank 兼容模型
$env:LIFEINBOX_LLM_BASE_URL="https://your-provider.example/v1"
$env:LIFEINBOX_RERANK_BASE_URL="https://your-rerank-provider.example/v1"
$env:LIFEINBOX_LLM_TIMEOUT_SECONDS="20"
$env:LIFEINBOX_RERANK_TIMEOUT_SECONDS="8"
$env:LIFEINBOX_VECTOR_STORE_ENABLED="false" # 默认关闭
$env:LIFEINBOX_QDRANT_URL="http://127.0.0.1:6333"
$env:LIFEINBOX_QDRANT_COLLECTION="lifeinbox_items" # 物理 Collection 前缀
$env:LIFEINBOX_QDRANT_API_KEY="" # 本地无鉴权时留空
$env:LIFEINBOX_QDRANT_TIMEOUT_SECONDS="5"
$env:NO_PROXY="127.0.0.1,localhost" # 本地代理环境必须绕过 Qdrant

uv run uvicorn app.main:app --host 127.0.0.1 --port 8000
```

LLM Client 调用 `LIFEINBOX_LLM_BASE_URL` 下的 `/chat/completions`，Embedding 继续调用同一地址下的
`/embeddings`；Rerank 使用独立 `LIFEINBOX_RERANK_BASE_URL` 下的 `/reranks`。三者默认复用
`LIFEINBOX_LLM_API_KEY`，不要把真实 Key 写入 `.env.example` 或提交到 Git。项目没有安装
`python-dotenv`，所以 `.env.example` 只是配置清单，不会被应用自动加载。

阿里云百炼华北 2（北京）的典型配置为：

```powershell
$env:LIFEINBOX_LLM_BASE_URL="https://{WorkspaceId}.cn-beijing.maas.aliyuncs.com/compatible-mode/v1"
$env:LIFEINBOX_RERANK_BASE_URL="https://{WorkspaceId}.cn-beijing.maas.aliyuncs.com/compatible-api/v1"
$env:LIFEINBOX_RERANK_MODEL="qwen3-rerank"
```

`LIFEINBOX_RERANK_BASE_URL` 只填写到 `.../compatible-api/v1`，不要包含 `/reranks`；代码会统一拼接
Provider Path，并同时兼容 Base URL 末尾有无 `/`。

启动后访问 `http://localhost:8000/health`，应返回：

```json
{
  "status": "ok",
  "service": "life-inbox-ai"
}
```

即使缺少 LLM、Embedding 或 Rerank 环境变量，健康检查仍然可用。三类模型都在实际调用时惰性读取自己的配置；缺少 Rerank Model 不影响 Analyze、网页/文档提取、OCR、Embedding、Vector Index 或 Semantic Search。

## 分析 TEXT

```powershell
$body = @{
  title = "分析测试"
  text = "Spring AI 是 Spring 生态面向 AI 应用开发的框架。"
} | ConvertTo-Json

Invoke-RestMethod -Method Post `
  -Uri "http://localhost:8000/analyze" `
  -ContentType "application/json" `
  -Body $body
```

成功响应为明确的 JSON：

```json
{
  "summary": "这段内容介绍了 Spring AI 的基本定位。",
  "category": "技术学习",
  "tags": ["Spring AI", "AI开发"],
  "keywords": ["ChatModel", "EmbeddingModel"],
  "entities": [
    {"name": "Spring AI", "type": "TECHNOLOGY"}
  ]
}
```

允许的 Category 为：`技术学习`、`学习成长`、`工作`、`求职`、`生活`、`财务`、`想法`、`资讯`、`其他`。Tags 必须有 1～5 个，每个最长 64 个字符，不能是空字符串或大小写不同的重复标签。

Keywords 允许 0～8 个，每项最长 64 个字符。Python 会执行 NFKC 和空白清理，并按大小写不敏感规则去重。Entities 允许 0～10 个，每项包含最长 128 个字符的 `name`，以及固定类型：`PERSON`、`ORGANIZATION`、`LOCATION`、`TECHNOLOGY`、`PRODUCT`、`EVENT`、`OTHER`。相同名称和类型的实体只保留第一项。

`text` 去除首尾空白后不能为空，最大 20,000 个字符；摘要最大 2,000 个字符。Prompt 集中在 `app/prompts.py`。LLM 请求使用 OpenAI-compatible JSON Mode；针对千问的结构化抽取场景，请求会关闭思考模式以减少等待和 Token 消耗。Python 还会使用 Pydantic 严格验证所有字段，异常结果不会交给 Java。

旧 `POST /summarize` 暂时保留相同请求和 `{ "summary": "..." }` 响应，用于兼容已有调用方；它内部复用包含全部五个字段的 Analyze Service，不会维护第二套 Prompt 或再次调用 LLM。

## 提取 Action 建议

`POST /action/extract` 是 V0.4 的独立内部能力，接收已经准备好的纯文本和可选稳定参考日期：

```powershell
$body = @{
  text = "明天之前提交软件工程课程设计报告。"
  referenceDate = "2026-08-24"
} | ConvertTo-Json

Invoke-RestMethod -Method Post `
  -Uri "http://localhost:8000/action/extract" `
  -ContentType "application/json" `
  -Body $body
```

成功响应示例：

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

契约与边界：

- `text` trim 后不能为空，最多 20,000 个字符；缺失、空白、超长或非法 JSON 返回 422；
- `referenceDate` 是可选 ISO 日期。Java 正常调用会从 `InboxItem.created_time.toLocalDate()` 取得；它是解释相对日期的稳定 Source Context，而不是请求执行当天；
- 允许 0～10 个候选。没有明确行动是正常成功结果：`{"hasAction": false, "actions": []}`；
- `actionType` 第一版只支持 `TODO` 与 `DEADLINE`。标题最长 200 字符，`deadlineText` 最长 100 字符，`evidence` 最长 500 字符；
- `TODO` 不携带截止信息；`DEADLINE` 必须保留来自原文、且同时出现在 evidence 中的 `deadlineText`；evidence 必须是输入正文中的纯文本片段；
- 纯 `DeadlineNormalizer` 使用标准库确定性处理完整日期、今天/明天/后天、本周或下周星期、本月底/月底/下月底、今年/明年；完整原始表达始终保留在 `deadlineText`；
- 没有 `referenceDate` 时只处理完整绝对日期。缺少年份的月日、单独“周五”和模糊表达保持 `deadline=null`，不会使用机器日期、系统时区或 Provider 猜测；
- 日期本身不等于行动。Prompt 明确排除出版/发布日期等描述性日期，也允许模型返回零个行动；
- `hasAction` 不接受 LLM 输入，而是由应用根据校验后的 `actions` 计算；未知类型、非法日期、字段越界、伪造 evidence、额外字段和畸形 JSON 都按无效结构化输出处理；
- Action Extraction 复用现有 `LIFEINBOX_LLM_*` 配置、OpenAI-compatible `/chat/completions`、JSON Mode、Timeout 与安全错误映射，不新增 Action 专属 Key、Model 或 Base URL；
- LLM 配置缺失/服务错误返回 503，超时返回 504，非法 Provider 或结构化响应返回 502；响应不包含 API Key、Provider 正文或内部堆栈；
- 当前端点只返回 AI 建议，不连接 MySQL、不保存 Action Candidate、不创建 Todo、不触发现有 Analyze/Attempt Guard，也不自动接入 Capture 或 Search。

## 分析 URL

`POST /analyze/url` 接收网页 URL 和可选标题：

```powershell
$body = @{
  url = "https://example.com/article"
  title = "可选的 InboxItem 标题"
} | ConvertTo-Json

Invoke-RestMethod -Method Post `
  -Uri "http://localhost:8000/analyze/url" `
  -ContentType "application/json" `
  -Body $body
```

响应继续使用与 TEXT 完全相同的 AnalyzeResult。Python 使用普通 HTTP 请求和 Beautiful Soup 提取 `article`、`main` 或 `body` 中的正文，不执行 JavaScript；动态页面若没有可用的初始 HTML 正文，会返回 `URL_CONTENT_EMPTY`。

URL 抓取采用以下边界：

- URL 最长 1,000 个字符，只允许 `http`、`https`，拒绝含账号密码的地址；
- 每一跳都解析并检查全部 DNS 地址，只允许公网 IP；连接固定到已检查 IP，同时保留原始 Host 和 HTTPS SNI，避免 DNS 重绑定绕过；
- 关闭环境代理和自动重定向，最多手动处理 5 次重定向，每一跳重新执行安全检查；
- Connect Timeout 为 3 秒，Read Timeout 为 8 秒；
- 只接收 HTML，流式读取上限为 1 MiB；
- 提取后的正文最多 20,000 个字符，再交给现有 Analyze Service；提取失败时不会调用 LLM。

网页读取错误使用固定的 `{ "code": "...", "detail": "..." }` 结构：

| HTTP | code | 含义 |
| --- | --- | --- |
| 400 | `URL_INVALID` | URL 无效或 Scheme 不支持 |
| 403 | `URL_BLOCKED` | URL 被 SSRF 安全策略阻止 |
| 408 | `URL_FETCH_TIMEOUT` | 网页请求超时 |
| 424 | `URL_FETCH_FAILED` | 网页访问失败 |
| 415 | `URL_CONTENT_TYPE_UNSUPPORTED` | 不是受支持的 HTML |
| 413 | `URL_RESPONSE_TOO_LARGE` | 网页响应超过 1 MiB |
| 422 | `URL_CONTENT_EMPTY` | 无法提取有效正文 |

## 分析 FILE

`POST /analyze/file` 使用 multipart 接收文件内容和可选标题：

```powershell
Invoke-RestMethod -Method Post `
  -Uri "http://localhost:8000/analyze/file" `
  -Form @{ file = Get-Item ".\notes.txt"; title = "学习笔记" }
```

响应继续使用与 TEXT/URL 完全相同的 AnalyzeResult。Java 读取自己管理的文件并发送内容，Python 不接收服务器本地路径；提取出的正文只在本次请求中使用，不持久化。

文档提取边界：

- 仅支持 `.txt`、`.md` 和 `.pdf`；
- TXT/Markdown 必须是 UTF-8，支持 UTF-8 BOM；Markdown 作为普通文本读取，不渲染或执行；
- PDF 使用 pypdf 提取真实文本层，最多 100 页，不执行 OCR；
- 文件最大 10 MiB，规范化正文最大 20,000 字符；超限明确失败，不静默截断；
- 空文件、加密/损坏 PDF、扫描版或其他无文本 PDF 均不会调用 LLM。

文档错误使用固定的 `{ "code": "...", "detail": "..." }` 结构：

| HTTP | code | 含义 |
| --- | --- | --- |
| 415 | `FILE_TYPE_UNSUPPORTED` | 文件类型或 MIME 不支持 |
| 413 | `FILE_TOO_LARGE` | 文件超过 10 MiB |
| 422 | `FILE_ENCODING_UNSUPPORTED` | 文本不是 UTF-8 |
| 422 | `FILE_CONTENT_EMPTY` | 文档为空 |
| 422 | `FILE_PDF_ENCRYPTED` | PDF 已加密 |
| 422 | `FILE_PDF_NO_TEXT` | PDF 没有可提取文本，可能需要 OCR |
| 413 | `FILE_DOCUMENT_TOO_LONG` | 页数或正文超过当前限制 |
| 422 | `FILE_EXTRACTION_FAILED` | 文档损坏或解析失败 |

## 分析 IMAGE

`POST /analyze/image` 使用 multipart 接收图片内容和可选标题：

```powershell
Invoke-RestMethod -Method Post `
  -Uri "http://localhost:8000/analyze/image" `
  -Form @{ file = Get-Item ".\course-notice.png"; title = "课程通知" }
```

> Windows PowerShell 5.1 的 `Invoke-RestMethod` 没有 `-Form` 参数，可使用 PowerShell 7、浏览器产品界面或其他 multipart 客户端。

处理规则：

- 使用本地 RapidOCR + ONNX Runtime CPU 识别中英文截图，不调用云 OCR，不需要额外系统程序或 API Key；
- 仅支持 `.jpg`、`.jpeg`、`.png`、`.webp`，并由 Pillow 实际打开和验证内容，不能只依赖扩展名；
- 图片最大 10 MiB，宽高分别不能超过 10,000，总像素不能超过 20,000,000；Pillow 解压炸弹保护保持开启；
- OCR 前使用 EXIF Orientation 做简单方向归一化；
- OCR 文字执行 NFKC 和空白清理，最多 20,000 个字符，超限明确失败；
- 少于 4 个有效字母、数字或中文字符时视为无有效文字，不调用 LLM；
- OCR 原文只在当前请求中临时使用，不持久化；成功后复用现有 Analyze Service 和统一五字段结果。

图片错误使用固定的 `{ "code": "...", "detail": "..." }` 结构：

| HTTP | code | 含义 |
| --- | --- | --- |
| 415 | `IMAGE_TYPE_UNSUPPORTED` | 不是当前支持的 JPG/PNG/WEBP |
| 413 | `IMAGE_TOO_LARGE` | 图片超过 10 MiB |
| 413 | `IMAGE_DIMENSIONS_TOO_LARGE` | 宽高或总像素超过限制 |
| 422 | `IMAGE_INVALID` | 图片损坏、伪装或内容无效 |
| 422 | `IMAGE_OCR_FAILED` | 本地 OCR 执行失败 |
| 422 | `IMAGE_TEXT_EMPTY` | 未识别到足够的文字，不调用 LLM |
| 413 | `IMAGE_TEXT_TOO_LONG` | OCR 文字超过 Analyze 输入上限 |

当前只做 OCR-based IMAGE Analyze。普通照片内容理解、图片描述、Qwen-VL 等通用 Vision，以及扫描 PDF OCR 均未实现。

## 生成 Embedding

`POST /embedding` 把 Task 24 已准备好的文本转换为瞬时向量：

```powershell
$body = @{ text = "Redis 分布式锁需要正确处理锁过期与误释放。" } | ConvertTo-Json

Invoke-RestMethod -Method Post `
  -Uri "http://localhost:8000/embedding" `
  -ContentType "application/json" `
  -Body $body
```

响应结构：

```json
{
  "model": "provider-returned-embedding-model",
  "dimension": 3,
  "embedding": [0.0123, -0.0456, 0.0789]
}
```

- 使用 `LIFEINBOX_EMBEDDING_MODEL` 独立配置 Embedding Model；不会把 `LIFEINBOX_LLM_MODEL` 当作向量模型；
- Base URL、API Key 和 Timeout 复用 `LIFEINBOX_LLM_BASE_URL`、`LIFEINBOX_LLM_API_KEY`、`LIFEINBOX_LLM_TIMEOUT_SECONDS`；
- 调用 OpenAI-compatible `POST {base_url}/embeddings`，请求体为 `{model, input}`；
- 文本只做 trim 和基础校验，最大 20,000 字符；超限返回 422，不截断、不分块；
- `dimension` 按真实向量长度计算；空向量、多个向量、缺失模型、null、NaN、Infinity 和非数字元素全部拒绝；
- 未配置模型返回 503，超时返回 504，Provider HTTP 错误返回 503，非法响应返回 502；
- 日志和错误不记录 API Key、完整输入、完整 Provider 响应或完整向量。

`POST /embedding` 仍只提供 `Text → EmbeddingResult`，不会单独保存向量或触发搜索。Task 26 的 VectorIndexService 在另一条内部路由中协调该能力与 Qdrant。

## Qdrant Vector Index

本地开发可以使用官方镜像和命名卷启动 Qdrant：

```powershell
docker volume create lifeinbox_qdrant_data
docker run --name lifeinbox-qdrant -p 6333:6333 -p 6334:6334 `
  -v lifeinbox_qdrant_data:/qdrant/storage qdrant/qdrant
```

REST API 位于 `http://127.0.0.1:6333`，Dashboard 位于 `http://127.0.0.1:6333/dashboard`。未启用鉴权的本地端口不要暴露到不可信网络。

启用后，Java 通过内部接口索引当前 Searchable Content：

```powershell
$body = @{
  inboxItemId = 123
  text = "Redis 分布式锁需要正确处理锁过期与误释放。"
} | ConvertTo-Json

Invoke-RestMethod -Method Post `
  -Uri "http://localhost:8000/vector/index" `
  -ContentType "application/json" `
  -Body $body
```

成功响应包含 `inboxItemId`、`indexed`、物理 `collection`、`model`、真实 `dimension` 和 `contentHash`，不返回完整向量。删除使用：

```powershell
Invoke-RestMethod -Method Delete -Uri "http://localhost:8000/vector/index/123"
```

内部语义候选检索使用同一模型：

```powershell
$body = @{
  query = "那个防止接口重复请求的 Redis 方案"
  limit = 20
} | ConvertTo-Json

Invoke-RestMethod -Method Post `
  -Uri "http://localhost:8000/vector/search" `
  -ContentType "application/json" `
  -Body $body
```

响应只包含按 Cosine 相似度排列的候选：

```json
{
  "results": [
    {"inboxItemId": 123, "score": 0.91}
  ]
}
```

实现边界：

- `LIFEINBOX_VECTOR_STORE_ENABLED` 默认 `false`；关闭时 Index/Delete 返回 skip，不调用 Embedding 或 Qdrant；
- 首次真实 Index 才惰性连接 Qdrant，并按 EmbeddingResult 的真实维度创建 Cosine Collection；
- `LIFEINBOX_QDRANT_COLLECTION` 是逻辑前缀，物理名称为 `<prefix>__m_<model-sha256>__d_<dimension>`；不同模型或维度不会静默混入同一向量空间；
- 已有 Collection 的维度或距离不兼容时明确失败，不会自动 DROP；
- Point ID 直接使用 InboxItem ID；重复 Index 是同一点 Upsert，不产生版本点；
- Payload 只有 `inboxItemId`、`embeddingModel`、`contentHash`、`indexedTime`，不保存 Searchable Content 或业务 JSON；
- Delete 会清理该前缀下所有 LifeInbox 管理的模型/维度 Collection，因此 Archive/Delete 后不会因切回旧模型而重新出现；
- Qdrant 或 Embedding 故障只影响派生索引，不改变 MySQL、AI Status 或当前 Keyword Search；
- Semantic Search 复用现有 EmbeddingService；Query 返回的模型与维度共同确定唯一物理 Collection；
- Search 只读取已存在 Collection，不会自动创建空 Collection；目标缺失、模型/维度/距离不兼容均受控失败；
- 内部 `limit` 默认 20、最大 100，不设置固定 Score Threshold，不返回 Payload 或完整 Vector；
- Python 只返回 ID/Score Candidate，Java 再用 MySQL 解析 ACTIVE InboxItem 和业务过滤；
- 本地若设置了 HTTP(S) 代理，应保留 `NO_PROXY=127.0.0.1,localhost`，否则 Python Client 可能无法访问已启动的 Qdrant；
- 没有 Startup Backfill、Batch Reindex 或 Chunk；Hybrid Fusion 与 Rerank 降级由 Java 协调，Python 不增加
  反向调用 Java 的 Product Search API。

## Vector Neighbor 候选

`POST /vector/neighbors` 是 Java → Python 的 Task 42 内部能力：

```json
{"inboxItemId": 123, "limit": 20}
```

服务根据当前 `LIFEINBOX_EMBEDDING_MODEL` 定位现有模型/维度 Collection，使用 Point ID 读取已经保存的 Source
Vector，再调用 Qdrant `query_points`。它不会调用 Embedding Provider，不创建 Relation 专属 Collection，也不会加载
全部向量到 Python 计算。请求 limit 默认 20、最大 20；内部 over-fetch 为 `min(limit * 3, 100)`，Source 自身会被过滤。

Source 已索引时只返回邻居 ID 和瞬时 Score：

```json
{"sourceIndexed": true, "results": [{"inboxItemId": 456, "score": 0.91}]}
```

Source Point 不存在时返回正常结果 `{"sourceIndexed": false, "results": []}`，不会自动 Re-index。Vector Store
关闭、Collection 缺失/不兼容、超时或不可用继续按现有 Vector 错误模型返回受控错误。Python 不读取 MySQL，不判断
`RELATED_TO`，不持久化 Candidate 或 Score；Java 负责 ACTIVE、stale Point 与已有 Relation 的最终过滤。

## Rerank 候选

`POST /rerank` 是 Java → Python 的内部批量精排能力。它只接收 Task 28 已经召回、过滤并按 RRF 排序的有限候选：

```powershell
$body = @{
  query = "怎样防止接口重复提交"
  documents = @(
    @{ id = 123; text = "标题：接口幂等`n摘要：同一请求只执行一次" }
    @{ id = 456; text = "标题：Redis 缓存雪崩`n正文：缓存过期策略" }
  )
  topK = 2
} | ConvertTo-Json -Depth 4

Invoke-RestMethod -Method Post `
  -Uri "http://localhost:8000/rerank" `
  -ContentType "application/json" `
  -Body $body
```

成功响应只包含已有 Candidate ID 与瞬时相关性 Score：

```json
{
  "results": [
    {"id": 123, "score": 0.93},
    {"id": 456, "score": 0.71}
  ]
}
```

配置和边界：

- `LIFEINBOX_RERANK_MODEL` 独立于 Chat 与 Embedding Model；Rerank Base URL 使用
  `LIFEINBOX_RERANK_BASE_URL`，API Key 继续复用 `LIFEINBOX_LLM_API_KEY`；
- `LIFEINBOX_RERANK_TIMEOUT_SECONDS` 默认 8 秒、范围大于 0 且不超过 60 秒；
- 当前 Provider 协议针对百炼 `qwen3-rerank`：`POST {rerank_base_url}/reranks`，一次发送
  `model/query/documents/top_n`；百炼返回的 `results` 位于顶层；
- Query 最多 200 字符；一次最多 100 个 Document，每个文本最多 2,000 字符，ID 必须唯一且为正数；
- 空 Documents 直接返回空结果，不加载 Provider 配置；不逐条请求、不重新生成 Embedding、不访问 Qdrant；
- Provider 的 `index + relevance_score` 会映射回输入 ID；越界/重复 index、NaN/Infinity 或畸形响应受控失败；
- 未配置独立 Rerank Base URL 或 Model 时按调用返回 503，不影响 FastAPI 启动；Provider 状态错误返回 503，
  超时返回 504，非法响应返回 502；所有错误都不包含 Key、Query、
  Candidate 文本或 Provider 原始正文；
- Java 使用 `LIFEINBOX_RERANK_ENABLED=false` 作为 Hybrid 产品开关。Rerank 失败时 Java 使用原 RRF 顺序，Python
  不负责 MySQL 业务过滤、最终 InboxItem 解析或失败降级。

## 运行测试

```powershell
uv run pytest
```

自动化测试使用本地文档 fixture、Fake Service、假 DNS 和 `httpx.MockTransport`，不会请求真实网页或 LLM，也不会消耗付费 Token。
