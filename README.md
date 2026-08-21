# LifeInbox

> Capture First, Organize Later.

LifeInbox 是一个个人信息收件箱，用统一的 InboxItem 收集文字、网页、文件和图片，再由可选 AI 帮助理解和整理。

长期产品流程：

```text
Capture → Understand → Organize → Retrieve → Action
```

AI 是增强能力，不是 Capture 的前置条件。FastAPI、OCR、网页抓取或 LLM 失败时，原始内容仍应保存并可继续查询、收藏、归档和删除。

## 当前版本

### V0.1 — Universal Inbox ✅

- TEXT、URL、FILE、IMAGE Capture
- URL 标题获取、文件/图片本地存储与图片预览
- 统一 Inbox 列表
- 收藏、取消收藏、归档和删除
- Vue 3、Spring Boot 与 MySQL 持久化
- 基础 URL、文件名、扩展名、MIME、大小和路径安全检查

### V0.2 — AI Organizer ✅

- 独立 FastAPI AI Engine 与 Java ↔ Python HTTP 集成
- TEXT 直接 Analyze
- URL 安全抓取静态 HTML 正文后 Analyze
- TXT、Markdown、带文本层 PDF 提取后 Analyze
- JPG/PNG/WEBP 本地 OCR 后 Analyze
- 一次 LLM 请求统一生成 Summary、Category、Tags、Keywords、Entities
- `NOT_PROCESSED`、`PROCESSING`、`SUCCESS`、`FAILED` 状态机
- 手工 Analyze、重新分析、FAILED Retry、stale PROCESSING Recovery
- attemptId Guard、防重复处理与迟到结果覆盖
- 可配置的 AFTER_COMMIT 进程内后台自动 Analyze（默认关闭）
- 前端状态、五类结果、重试与有限轮询

IMAGE 当前是面向截图/文字图片的 **OCR-based Analyze**，不是 General Vision。URL 不执行 JavaScript；PDF 不做 OCR；DOCX、PPTX、Excel 等格式尚未支持。

### V0.3 — Smart Search（Next / Planned）

V0.3 尚未实现。关键词搜索、Semantic Search、Embedding、Hybrid Search 和 Rerank 只是下一阶段候选，详见 [Roadmap](docs/roadmap.md)。

## 架构与职责

```text
Vue 3
  ↓ 产品 API
Spring Boot / Java 21
  ├─ MySQL（业务 Source of Truth）
  ├─ Local Files
  └─ FastAPI
       ├─ URL / FILE / OCR Extractor
       └─ AnalyzeService → LLM
```

Java 负责 InboxItem、业务状态、文件元数据、AI Attempt、结果持久化和产品 API。Python 只负责内容提取、OCR、LLM 与结构化校验，不连接业务数据库。完整说明见 [架构文档](docs/architecture.md)。

## 技术栈

- Frontend：Vue 3、Vite、JavaScript
- Backend：Java 21、Spring Boot 4.1、MyBatis-Plus、MySQL
- AI Engine：Python 3.11+、FastAPI、httpx、Beautiful Soup、pypdf、RapidOCR、ONNX Runtime、Pillow
- Storage：MySQL + 本地 `server/uploads`

项目没有引入 Redis、MQ、向量数据库、RAG、Agent、MCP 或通用 Vision。

## 目录

```text
life-inbox/
├── web/          # Vue 前端
├── server/       # Spring Boot 业务后端
├── ai-engine/    # FastAPI AI 服务
├── docs/         # 架构、数据库、API、Roadmap、验收与 SQL
├── extension/    # 预留目录，当前不是 V0.2 能力
├── deploy/       # 预留目录
├── AGENTS.md
└── README.md
```

## 本地运行

### 1. 准备数据库

全新安装直接执行：

```text
docs/sql/v0.2-schema.sql
```

已有 V0.1/V0.2 数据库不要重复执行 Fresh Schema，只按顺序执行尚未执行的历史增量 SQL。数据库说明见 [docs/database.md](docs/database.md)。本项目当前没有自动 Migration 框架。

### 2. 启动 AI Engine

```powershell
Set-Location .\ai-engine
uv sync

$env:LIFEINBOX_LLM_API_KEY = "<your-api-key>"
$env:LIFEINBOX_LLM_MODEL = "<your-model>"
$env:LIFEINBOX_LLM_BASE_URL = "https://your-provider.example/v1"
$env:LIFEINBOX_LLM_TIMEOUT_SECONDS = "20"

uv run uvicorn app.main:app --host 127.0.0.1 --port 8000
```

`.env.example` 只列变量名；项目没有自动加载 `.env`。不要把真实 Key 提交到 Git。缺少 LLM 配置不影响 `/health`，但 Analyze 会安全失败。

### 3. 启动 Java

在新的 PowerShell 会话：

```powershell
Set-Location .\server
$env:MYSQL_PASSWORD = "<your-mysql-password>"
$env:AI_SERVICE_BASE_URL = "http://127.0.0.1:8000"

.\mvnw.cmd spring-boot:run
```

后端默认运行在 `http://localhost:8080`。自动 Analyze 默认关闭；如需开启，必须在启动 Java 前显式设置：

```powershell
$env:LIFEINBOX_AI_AUTO_ANALYZE_ENABLED = "true"
```

常用 Java 配置：

| 环境变量 | 对应配置 | 默认值 |
| --- | --- | --- |
| `MYSQL_PASSWORD` | 数据库密码 | 无 |
| `AI_SERVICE_BASE_URL` | `life-inbox.ai.base-url` | `http://localhost:8000` |
| `LIFE_INBOX_AI_ANALYSIS_READ_TIMEOUT` | Analyze 读取超时 | `30s` |
| `LIFE_INBOX_AI_PROCESSING_STALE_AFTER` | PROCESSING stale 阈值 | `5m` |
| `LIFEINBOX_AI_AUTO_ANALYZE_ENABLED` | 自动 Analyze | `false` |

### 4. 启动前端

```powershell
Set-Location .\web
npm install
npm run dev
```

前端默认运行在 `http://localhost:5173`。

### 5. 健康检查

```powershell
Invoke-RestMethod http://127.0.0.1:8000/health
Invoke-RestMethod http://127.0.0.1:8080/api/ai/health
```

## 构建与测试

```powershell
# Java
Set-Location .\server
.\mvnw.cmd clean test

# Python（不请求真实网页或 LLM，不消耗 Token）
Set-Location ..\ai-engine
uv run pytest -p no:cacheprovider

# Frontend
Set-Location ..\web
npm run build
```

前端当前没有自动化测试框架，因此以 Vite build 和 [人工验收清单](docs/manual-acceptance.md) 补充验证。

## 文档

- [Architecture](docs/architecture.md)
- [Database](docs/database.md)
- [API](docs/api.md)
- [Roadmap](docs/roadmap.md)
- [V0.2 Manual Acceptance](docs/manual-acceptance.md)
- [V0.2 Fresh Install Schema](docs/sql/v0.2-schema.sql)
- [AI Engine 说明](ai-engine/README.md)

开发约束与长期原则见 [AGENTS.md](AGENTS.md)。

## 已知限制

- 自动 Analyze 是 Java 进程内有界线程池，不是持久任务队列；进程中断后由 stale Recovery 手工接管；
- 不做启动扫描、历史回填或无限自动重试；
- URL 仅分析无需登录的静态 HTML；
- FILE 仅支持 UTF-8 TXT/MD 与带文本层 PDF；
- IMAGE 只做文字 OCR，不理解普通照片场景；
- 删除 FILE/IMAGE 会清理当前关联文件，但不会扫描和删除历史版本可能已经遗留的孤立文件；
- Tags 当前使用全局字典，删除 InboxItem 后不主动清理无引用 Tag；
- 本地文件存储适合个人开发环境，不是分布式对象存储方案。

LifeInbox 继续遵循：小步迭代、简单优先、真实需求优先。V0.2 封版后停止扩展 AI Organizer，下一阶段是否进入 V0.3 由后续任务决定。
