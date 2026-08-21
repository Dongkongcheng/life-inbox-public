# LifeInbox

> Capture First, Organize Later.

LifeInbox 是一个面向个人使用的信息收件箱，用于统一收集日常生活和学习过程中遇到的文字、网页链接、文件、图片等信息。

它希望解决一个很常见的问题：

> 我记得自己以前看到过、收藏过或者保存过某个东西，但需要的时候却找不到了。

LifeInbox 的长期目标不是做一个普通收藏夹，而是逐步形成一个能够帮助用户：

**收集 → 理解 → 整理 → 检索 → 行动**

的个人信息系统。

---

## ✨ Product Philosophy

LifeInbox 的核心原则是：

**Capture First, Organize Later.**

保存信息时，用户不应该被迫立即：

* 选择文件夹
* 选择分类
* 添加标签
* 写摘要
* 整理知识结构

第一件事应该只是：

```text
看到有价值的信息
        ↓
       Save
        ↓
      Inbox
```

整理工作可以以后再完成，也可以逐步交给 AI。

---

## 🧭 Product Flow

LifeInbox 的长期产品流程：

```text
Capture
   ↓
Understand
   ↓
Organize
   ↓
Retrieve
   ↓
Action
```

对应含义：

### Capture

快速收集：

* Text
* URL
* File
* Image

### Understand

未来通过 AI 理解信息：

* 内容摘要
* 自动分类
* 标签生成
* 关键词提取
* 实体提取

### Organize

自动帮助用户整理已经保存的信息。

### Retrieve

帮助用户重新找到过去保存过的内容：

* 关键词搜索
* 语义搜索
* RAG
* 个人知识检索

### Action

进一步从信息中识别：

* Todo
* Deadline
* Reminder
* Action Item

---

# 📌 Current Status

## V0.1 — Universal Inbox ✅

V0.1 主要解决：

> **如何把不同类型的信息快速保存进统一 Inbox？**

目前已经实现的主要功能：

* ✅ TEXT 文本收集
* ✅ URL 链接收集
* ✅ URL 自动获取网页标题
* ✅ FILE 文件上传
* ✅ IMAGE 图片上传
* ✅ 图片预览
* ✅ Inbox 列表
* ✅ 收藏 / 取消收藏
* ✅ 归档
* ✅ 删除
* ✅ 本地文件存储
* ✅ 基础文件安全检查
* ✅ URL 基础安全检查
* ✅ Vue 与 Spring Boot 前后端通信
* ✅ MySQL 数据持久化

V0.1 不依赖 AI。

即使未来 AI 服务不可用，LifeInbox 的基本 Capture 和 Inbox 管理能力仍然可以正常工作。

---

## V0.2 — AI Understanding 🚧

当前正在进入 V0.2 开发阶段。

V0.2 的目标是：

> **让 LifeInbox 开始理解用户保存进去的信息。**

计划逐步实现：

* ✅ Python AI Engine 基础服务
* ✅ FastAPI `/health`
* ✅ Java ↔ Python Health Integration
* ✅ AI 服务不可用时返回结构化 503
* ✅ TEXT AI Analyze（显式触发）
  * ✅ Summary
  * ✅ Category
  * ✅ Tags
  * ✅ Keywords
  * ✅ Entities
* ✅ URL Content Extraction
* ✅ URL AI Analyze（显式触发）
* ✅ FILE Text Extraction
  * ✅ TXT
  * ✅ Markdown
  * ✅ PDF 文本层
* ✅ FILE AI Analyze（显式触发）
* ✅ IMAGE OCR
* ✅ IMAGE OCR-based AI Analyze（显式触发）
* ⏳ General Image Vision
* ✅ AI Processing Status
  * ✅ `NOT_PROCESSED`
  * ✅ `PROCESSING`
  * ✅ `SUCCESS`
  * ✅ `FAILED`
* ⏳ Retry / Failure Recovery
* ⏳ Automatic AI Analyze

> 注意：以上带有 `⏳` 的功能属于开发计划，目前尚未完成。

---

# 🏗 Architecture

LifeInbox 当前采用：

```text
                    LifeInbox
                        │
                        ▼
                    Vue 3
                     Vite
                        │
                   REST API
                        │
                        ▼
               Spring Boot 4.1
                  Java 21
                        │
             ┌──────────┴──────────┐
             ▼                     ▼
           MySQL              Local Files
         Business Data          Uploads
```

从 V0.2 开始，将逐步增加：

```text
                    LifeInbox
                        │
                        ▼
                     Vue 3
                        │
                        ▼
                  Spring Boot
                        │
             ┌──────────┴──────────┐
             │                     │
             ▼                     ▼
           MySQL               FastAPI
                                  │
                                  ▼
                             AI Processing
```

---

# 🧩 Java + Python Responsibilities

LifeInbox 采用：

> **Java 负责业务，Python 负责 AI。**

## Java / Spring Boot

Java 是业务数据的 **Source of Truth**。

主要负责：

* InboxItem
* 业务状态
* 数据持久化
* 文件元数据
* 收藏
* 归档
* 删除
* 产品 API
* AI 任务状态
* AI 结果持久化
* 业务异常处理

---

## Python / FastAPI

Python AI Service 主要负责未来的 AI 处理能力，例如：

* 内容理解
* 摘要生成
* 自动标签
* 自动分类
* 关键词提取
* 实体提取
* 文档解析
* OCR
* Embedding
* Rerank

Python 不负责重复实现 InboxItem CRUD。

Python 不应该成为核心业务数据的 Source of Truth。

---

# 🧱 Core Model

LifeInbox 当前最重要的业务模型是：

```text
InboxItem
```

所有捕获的信息首先进入统一 Inbox。

当前主要类型：

```text
TEXT
URL
FILE
IMAGE
```

例如：

```text
                InboxItem
                    │
       ┌────────────┼────────────┐
       ▼            ▼            ▼
      TEXT          URL         FILE
                                  │
                                IMAGE
```

当前不会为不同 Capture 类型分别创建：

```text
TextItem
UrlItem
FileItem
ImageItem
```

等独立核心业务模型。

除非未来出现明确需求，否则优先保持统一 InboxItem 模型。

---

# 🛠 Tech Stack

## Frontend

* Vue 3
* Vite
* JavaScript

## Backend

* Java 21
* Spring Boot 4.1
* Spring MVC
* MyBatis-Plus
* MySQL

## AI Service

V0.2 开始逐步引入：

* Python
* FastAPI

## Storage

当前：

* MySQL
* Local File Storage

未来根据真实需求可能增加：

* Vector Database
* Redis
* Object Storage

但不会为了技术栈而提前引入。

---

# 📂 Project Structure

```text
life-inbox/
│
├── web/
│   └── Vue 3 frontend
│
├── server/
│   └── Spring Boot backend
│
├── ai-engine/
│   └── Python AI service
│
├── extension/
│   └── Future browser extension
│
├── docs/
│   └── Project documents
│
├── deploy/
│   └── Deployment configuration
│
├── AGENTS.md
│   └── Repository development instructions
│
├── .gitignore
│
└── README.md
```

> 部分目录目前可能仍处于预留或开发阶段，以仓库实际代码为准。

---

# 🗄 Database

数据库名称：

```text
life_inbox
```

核心表：

```text
inbox_item
```

主要字段：

```text
id
user_id
type
title
content
summary
category
source_url
file_url
status
favorite
created_time
updated_time
```

AI Tags 使用统一关系模型，不按 Capture 类型拆表：

```text
tag
inbox_tag
```

Keywords 和 Entities 是每条 InboxItem 的分析结果，分别使用简单的一对多表：

```text
inbox_keyword
inbox_entity
```

其中：

```text
type
```

当前主要用于区分：

```text
TEXT
URL
FILE
IMAGE
```

`status` 用于表示 InboxItem 当前状态，例如：

```text
ACTIVE
ARCHIVED
```

`favorite` 用于表示收藏状态。

---

# 🚀 Local Development

## Requirements

建议准备以下环境：

```text
Java 21
Node.js
npm
MySQL
Git
```

V0.2 AI Engine 开始后还需要：

```text
Python
uv
```

---

# 1. Clone Repository

```bash
git clone <your-repository-url>

cd life-inbox
```

---

# 2. Prepare MySQL

创建数据库：

```sql
CREATE DATABASE life_inbox
DEFAULT CHARACTER SET utf8mb4
COLLATE utf8mb4_unicode_ci;
```

然后选择数据库：

```sql
USE life_inbox;
```

根据项目当前数据库结构创建：

```text
inbox_item
```

表。

如果数据库来自 V0.1，请在启动新版后端前手工执行本仓库的迁移文件：

```text
docs/sql/v0.2-task2-add-summary.sql
docs/sql/v0.2-task3-add-analysis.sql
docs/sql/v0.2-task4-add-keywords-entities.sql
```

请按 Task 顺序执行。Task 2 增加可空的 `summary`；Task 3 增加可空的 `category`，并创建统一的 `tag`、`inbox_tag` 标签关系表；Task 4 创建 `inbox_keyword` 和 `inbox_entity`。迁移不会自动分析或回填历史数据。

---

# 3. Backend Configuration

后端配置位于：

```text
server/src/main/resources/application.yaml
```

数据库密码等敏感信息不要直接提交到 Git。

推荐通过环境变量提供，例如：

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/life_inbox?useUnicode=true&characterEncoding=utf8
    username: root
    password: ${MYSQL_PASSWORD}
```

然后在本地提供：

```text
MYSQL_PASSWORD
```

环境变量。

---

# 4. Start Backend

进入：

```bash
cd server
```

Windows：

```bash
.\mvnw.cmd spring-boot:run
```

后端默认运行：

```text
http://localhost:8080
```

---

# 5. Start Frontend

打开新的终端：

```bash
cd web
```

安装依赖：

```bash
npm install
```

启动：

```bash
npm run dev
```

前端默认运行：

```text
http://localhost:5173
```

---

# 🧪 Build

## Backend

Windows：

```bash
cd server

.\mvnw.cmd clean compile
```

---

## Frontend

```bash
cd web

npm run build
```

---

# 🤖 AI Service

LifeInbox 从 V0.2 开始逐步加入独立的：

```text
ai-engine
```

Python 服务。

V0.2 Task 7 让 IMAGE 与 TEXT、URL、FILE 复用同一套 AI Analyze Pipeline。Capture 仍然先独立保存，只有用户点击“AI 分析”时才调用 Python 和 LLM；截图或文字图片由 Python 使用本地 RapidOCR 临时提取文字，再进入现有 Analyze Service。一次调用返回 `summary`、有限 `category`、最多 5 个 `tags`、最多 8 个 `keywords` 和最多 10 个 `entities`，Java 二次校验后在短事务中统一持久化。

V0.2 Task 8 在 Java 业务层为统一 `InboxItem` 增加 AI Processing Status。更新后端前需先执行：

```text
docs/sql/v0.2-task8-add-ai-processing-status.sql
```

状态只有四种：`NOT_PROCESSED`、`PROCESSING`、`SUCCESS`、`FAILED`。新 Capture 仍立即保存为 `NOT_PROCESSED`，不会自动调用 AI；手工 Analyze 在基础校验后用数据库条件 UPDATE 原子领取任务并提交 `PROCESSING`，因此同一条记录的重复请求会返回 409。远程 Python/LLM 调用不处于数据库事务中；五类结果全部持久化成功后，才在同一个短事务中设置 `SUCCESS`。任何提取、OCR、LLM、校验或持久化失败都会另用短事务设置 `FAILED`，但不会清空上一次成功结果。

进入 `ai-engine` 后安装依赖，并在当前 PowerShell 会话配置一个 OpenAI-compatible Chat Completions 服务：

```powershell
uv sync

$env:LIFEINBOX_LLM_API_KEY="<your-api-key>"
$env:LIFEINBOX_LLM_MODEL="<your-model>"
$env:LIFEINBOX_LLM_BASE_URL="https://your-provider.example/v1"
$env:LIFEINBOX_LLM_TIMEOUT_SECONDS="20"

uv run uvicorn app.main:app --host 127.0.0.1 --port 8000
```

`.env.example` 只提供变量名示例；项目没有加载 `.env` 的额外依赖，因此本地启动时仍需由终端或部署环境注入变量。缺少 LLM 配置不会影响 `/health`，但 `/analyze`、`/analyze/url`、`/analyze/file`、`/analyze/image` 和兼容的 `/summarize` 会返回 503。

Python 健康检查：

```text
GET http://localhost:8000/health
```

Spring Boot 集成检查：

```text
GET http://localhost:8080/api/ai/health
```

Python Analyze 接口：

```text
POST http://localhost:8000/analyze
Request:  { "title": "可选标题", "text": "需要分析的正文" }
Response: {
  "summary": "生成后的摘要",
  "category": "技术学习",
  "tags": ["Spring AI", "Java"],
  "keywords": ["ChatModel", "Tool Calling"],
  "entities": [
    { "name": "Spring AI", "type": "TECHNOLOGY" }
  ]
}
```

URL 使用独立的 Python 内部接口，但返回同一个 AnalyzeResult：

```text
POST http://localhost:8000/analyze/url
Request:  { "title": "可选标题", "url": "https://example.com/article" }
Response: 与 POST /analyze 相同
```

URL 抓取只允许 `http` 和 `https`，手工跟随并逐跳检查最多 5 次重定向；DNS 解析出的地址必须全部为公网地址。响应必须是 HTML，实际读取上限为 1 MiB，清洗后的正文最多向 Analyze Service 传递 20,000 个字符。动态 JavaScript 页面、PDF、图片和其他二进制内容当前不支持。

FILE 使用 multipart Python 内部接口，并返回同一个 AnalyzeResult：

```text
POST http://localhost:8000/analyze/file
Parts: file=<TXT/MD/PDF 文件内容>, title=<可选标题>
Response: 与 POST /analyze 相同
```

文件仍由 Java 安全管理和读取，Python 不接收服务器绝对路径。FILE Analyze 仅支持 TXT、Markdown 和具有文本层的 PDF；文件最大 10 MiB、PDF 最多 100 页，规范化后的正文最多 20,000 个字符。UTF-8 文本支持 BOM；超长文档会明确失败而不是静默截断。扫描版 PDF、加密 PDF、PDF OCR、DOC/DOCX、PPT/PPTX 和 Excel 当前不支持。

IMAGE 使用 multipart Python 内部接口，并返回同一个 AnalyzeResult：

```text
POST http://localhost:8000/analyze/image
Parts: file=<JPG/PNG/WEBP 图片内容>, title=<可选标题>
Response: 与 POST /analyze 相同
```

图片仍由 Java 安全管理和读取，Python 不接收服务器绝对路径。IMAGE Analyze 使用本地 RapidOCR + ONNX Runtime CPU，不需要 Tesseract、CUDA、云 OCR 或新的 API Key。当前支持 JPG/JPEG、PNG、WEBP，最大 10 MiB；宽高分别不能超过 10,000，总像素不能超过 20,000,000。OCR 文字最多 20,000 个字符，少于 4 个有效字母、数字或中文字符时不会调用 LLM。GIF/BMP 虽可 Capture，但当前不支持 OCR Analyze；普通照片的视觉描述、PDF OCR 和通用 Vision 仍未实现。

允许的 Category 为：`技术学习`、`学习成长`、`工作`、`求职`、`生活`、`财务`、`想法`、`资讯`、`其他`。Tags 必须有 1～5 个，每个最长 64 个字符；Keywords 可以有 0～8 个；Entities 可以有 0～10 个，类型只能是 `PERSON`、`ORGANIZATION`、`LOCATION`、`TECHNOLOGY`、`PRODUCT`、`EVENT`、`OTHER`。旧 `POST /summarize` 暂时保留原请求和 `{ "summary": "..." }` 响应，但底层复用同一次 Analyze，不维护第二套 Prompt。

产品接口：

```text
POST http://localhost:8080/api/inbox/{id}/ai/analyze
```

该产品接口同时支持 TEXT、URL、FILE 和 IMAGE。前端不需要知道 Java 内部调用的是 Python `/analyze`、`/analyze/url`、`/analyze/file` 还是 `/analyze/image`；IMAGE 当前只做 OCR-based Analyze，不做通用 Vision。

`GET /api/inbox` 和 Analyze 响应会直接返回 `aiStatus`、`aiErrorMessage`、`aiStartedTime`、`aiFinishedTime`，前端无需额外查询状态接口。`FAILED` 只表示最近一次尝试失败；如果条目已有旧结果，页面会继续展示并提示“正在显示上一次成功的 AI 结果”。当前 Analyze 仍是同步请求，不使用后台线程、Redis 或 MQ。若 Spring Boot 在 `PROCESSING` 后突然退出，状态可能暂时保留为 `PROCESSING`；stale PROCESSING 检测、Retry 和 Failure Recovery 留给后续 Task。

旧 `POST /api/inbox/{id}/ai/summary` 也暂时保留为兼容入口，并委托同一个 Analyze Service。

可以在 PowerShell 中手工验证完整链路：

```powershell
$captureBody = @{
  type = "TEXT"
  title = "分析测试"
  content = "Spring AI 是 Spring 生态面向 AI 应用开发的框架。"
} | ConvertTo-Json

$item = Invoke-RestMethod -Method Post `
  -Uri "http://localhost:8080/api/inbox" `
  -ContentType "application/json" `
  -Body $captureBody

Invoke-RestMethod -Method Post `
  -Uri "http://localhost:8080/api/inbox/$($item.id)/ai/analyze"
```

验证 URL 时只需把 Capture 请求改为：

```powershell
$captureBody = @{
  type = "URL"
  title = "公开文章"
  sourceUrl = "https://example.com/article"
} | ConvertTo-Json
```

验证 FILE 时先上传，再调用同一个产品 Analyze 接口：

```powershell
$item = Invoke-RestMethod -Method Post `
  -Uri "http://localhost:8080/api/inbox/file" `
  -Form @{ file = Get-Item ".\notes.txt"; title = "学习笔记" }

Invoke-RestMethod -Method Post `
  -Uri "http://localhost:8080/api/inbox/$($item.id)/ai/analyze"
```

> Windows PowerShell 5.1 的 `Invoke-RestMethod` 不支持 `-Form`，可直接通过网页上传，或使用 PowerShell 7 执行上述示例。

随后刷新页面或重新请求 `GET /api/inbox`，应能看到数据库中的 `summary`、`category`、`tags`、`keywords`、`entities` 和 AI 状态字段。再次调用会把五项作为一组原子替换，而不是追加旧结果。Java 默认访问 `http://localhost:8000`，可通过 `AI_SERVICE_BASE_URL` 覆盖；健康检查读取超时为 5 秒，Analyze 读取超时为 30 秒。若提高 Python 的 LLM 超时，应同步把 Spring 属性 `life-inbox.ai.analysis-read-timeout` 调得更大。Python 的 LLM 故障仍使用原有 502/503/504 语义；URL、FILE 和 IMAGE 接口会额外区分各自的读取、解析或 OCR 错误。Java 只映射受控错误码，不向前端暴露网页、文件内容或上游内部响应。所有失败路径都会保留已有分析结果和原始 InboxItem，原有 Capture 功能仍可使用。

目标架构：

```text
Spring Boot
     │
     │ JSON API
     ▼
   FastAPI
     │
     ▼
AI Processing
```

AI 服务失败时：

```text
TEXT
URL
FILE
IMAGE
```

等基础 Capture 功能仍然应该正常工作。

AI 是增强能力，而不是基础功能的前置条件。

---

# 🗺 Roadmap

## V0.1 — Capture ✅

```text
TEXT
URL
FILE
IMAGE

+
Favorite
Archive
Delete
```

---

## V0.2 — Understand 🚧

```text
Python AI Engine
        ↓
TEXT + URL + FILE + IMAGE OCR AI Analyze
Summary + Category + Tags
  + Keywords + Entities
        ↓
AI Processing Status
```

---

## V0.3 — Retrieve

计划：

```text
Keyword Search
       +
Semantic Search
       +
Embedding
       +
Rerank
```

目标：

> 不需要记住标题，也能重新找到以前保存过的信息。

---

## V0.4 — Action

计划：

```text
Information
     ↓
AI Understanding
     ↓
Action Extraction
     ↓
Todo / Deadline / Reminder
```

例如：

```text
“软件工程实验报告 8 月 25 日前提交”
```

未来 LifeInbox 可以识别：

```text
Todo:
提交软件工程实验报告

Deadline:
8 月 25 日
```

---

## V0.5 — Relations

未来尝试自动发现不同 InboxItem 之间的关系。

例如：

```text
Redis
├── Redis Lua
├── Redisson
├── Distributed Lock
└── Cache Consistency
```

---

## V1.0 — Personal AI

长期目标是让 AI 能够基于用户自己的 LifeInbox 数据：

* 搜索
* 回顾
* 总结
* 问答
* 推荐
* 发现关系
* 提取行动

最终形成一个真正属于用户自己的个人信息系统。

---

# 🔒 Security Principles

当前开发过程中重点关注：

* 文件路径穿越
* 不安全文件访问
* 文件上传校验
* SSRF
* 不可信 URL
* 不可信文件名
* 密码和 API Key 泄露
* AI 输出未经校验直接进入业务系统

LifeInbox 是个人项目，不追求过度工程化，但不会忽略明显的安全风险。

---

# 📖 Development Principles

LifeInbox 的开发遵循：

```text
Small Steps
Simple First
Real Requirement First
```

即：

* 小步迭代
* 优先简单实现
* 不为不存在的需求提前设计复杂系统
* 不为了技术栈而引入技术
* 每次只解决一个清晰的问题
* 完成功能后进行构建和测试
* 保持代码能够被自己重新看懂

更详细的开发约束请查看：

```text
AGENTS.md
```

---

# 🎯 Long-Term Goal

LifeInbox 最终希望实现：

```text
Capture
   ↓
Understand
   ↓
Organize
   ↓
Retrieve
   ↓
Action
```

用户只需要负责：

> **把值得留下的东西放进来。**

剩下的整理、理解、关联和重新发现，可以逐步交给 LifeInbox。
