# LifeInbox

> **Capture first. Organize later. Retrieve when needed. Turn information into action.**

LifeInbox is an AI-driven personal information inbox for capturing, understanding, organizing, retrieving, and eventually acting on useful information.

它解决的是一个很实际的问题：

```text
看到有用的信息
      ↓
没时间整理
      ↓
先保存
      ↓
AI 自动理解
      ↓
以后还能真正找回来
      ↓
需要时转化成行动
```

LifeInbox 的长期产品主线是：

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

LifeInbox 并不首先把自己定位成传统“知识库”。

它更关注从：

```text
“I saw something useful.”
```

到：

```text
“I can find and use it again.”
```

之间的整个过程。

---

# Current Status

LifeInbox is currently under active development.

```text
V0.1 — Universal Inbox        ✅ Completed
V0.2 — AI Organizer           ✅ Completed
V0.3 — Smart Search           ✅ Completed
V0.4 — Action Extractor       🚧 In Progress
V0.5 — Relations              📋 Planned
V1.0 — Personal AI            📋 Planned
```

Current development focus:

```text
V0.4 — Action Extractor
```

V0.4 的目标是：

> 从已经保存的信息中识别真正具有行动意义的内容，并将其转化成可由用户确认的 Action Candidate。

例如：

```text
Todo
Deadline
Future Reminder Candidate
```

但 AI 产生的建议不会直接成为最终业务事实。

用户仍然决定：

```text
Accept
or
Dismiss
```

---

# Why LifeInbox?

Useful information appears everywhere:

* Web pages
* Articles
* Screenshots
* PDFs
* Documents
* Notes
* Ideas
* Technical references
* Deadlines
* Recruitment information

真正的问题通常不是第一次看到信息。

而是之后：

```text
看到好东西
↓
懒得整理

收藏了
↓
以后找不到

保存越来越多
↓
不知道哪些内容有关联

信息里有任务 / 截止日期
↓
容易忘记
```

LifeInbox 按照这些真实问题逐步发展，而不是为了堆 AI 技术而增加功能。

---

# Core Principle

## Capture First, Organize Later

Capture 应该尽可能简单。

```text
See something useful
        ↓
Save it
        ↓
Organize later
```

AI 是增强能力。

它不能成为 Capture 的前置依赖。

即使：

```text
FastAPI unavailable
LLM unavailable
OCR failure
URL extraction failure
Embedding failure
Qdrant unavailable
Semantic Search unavailable
Reranker unavailable
Action Extractor unavailable
```

原始信息仍然应该尽可能被保存。

核心原则：

```text
AI Failure
≠
Capture Failure
```

---

# What LifeInbox Can Do Today

## Universal Capture

LifeInbox 当前支持统一 Inbox：

```text
TEXT
URL
FILE
IMAGE
```

所有 Capture 内容首先进入统一核心模型：

```text
InboxItem
```

而不是建立：

```text
TextItem
UrlItem
FileItem
ImageItem
```

四套独立业务模型。

当前 Inbox 能力包括：

* Capture text
* Capture URL
* Upload file
* Upload image
* Inbox listing
* Favorite
* Archive
* Delete
* AI analysis
* Smart Search

不同内容类型可以拥有不同的解析方式，但业务状态仍统一围绕 `InboxItem` 管理。

---

# AI Organizer

LifeInbox 可以在 Capture 之后自动理解内容。

不同内容类型首先进行 Content Preparation：

```text
URL
 ↓
Web Content Extraction

FILE
 ↓
Document Text Extraction

IMAGE
 ↓
OCR

TEXT
 ↓
Original Content
```

然后统一进入：

```text
Prepared Content
      ↓
AI Analyze
      ↓
Structured Result
```

当前结构化 AI 信息包括：

```text
Summary
Category
Tags
Keywords
Entities
```

用户主要负责：

```text
Save
```

AI 帮助完成：

```text
Understand
+
Organize
```

---

# Reliable AI Processing

AI processing 被设计为一种增强能力，而不是核心业务依赖。

当前已经包含：

* AI processing status
* Manual Analyze
* Automatic Analyze after Capture
* Retry
* stale PROCESSING recovery
* Attempt Guard
* Late-result protection
* Background processing
* Failure degradation

基本流程：

```text
Capture
   ↓
Database Commit
   ↓
Background AI Processing
```

外部 AI 调用不会长时间占用核心业务事务。

如果新的 Analyze 失败：

```text
Existing InboxItem
+
Previous Successful Result
```

不会因为一次失败而被无意义删除。

---

# AI Attempt Guard

每次 Analyze 都有自己的处理所有权。

概念：

```text
Attempt A
   ↓
timeout

Attempt B
   ↓
takes ownership
```

如果旧 Attempt A 后来才返回：

```text
A != Current Attempt
```

它不能覆盖：

```text
New AI Result
New Searchable Content
New Processing State
```

这样可以避免：

```text
old slow result
↓
overwrite newer result
```

---

# Smart Search

V0.3 完成了 LifeInbox 的 Retrieve 阶段。

LifeInbox 不再只依赖类似：

```sql
WHERE title LIKE '%keyword%'
```

的基础查询。

当前 Search Pipeline：

```text
                         User Query
                             │
                ┌────────────┴────────────┐
                ▼                         ▼
        Keyword Retrieval         Semantic Retrieval
                │                         │
              MySQL                Query Embedding
                                          │
                                          ▼
                                       Qdrant
                │                         │
                └────────────┬────────────┘
                             ▼
                            RRF
                             │
                             ▼
                     Hybrid Candidates
                             │
                             ▼
                         Reranker
                             │
                             ▼
                       Final Results
```

V0.3 已完成：

```text
Keyword Search
AI-derived Field Search
Filters
Basic Ranking
Safe Highlight
Searchable Content
Embedding
Qdrant Vector Index
Semantic Search
Hybrid Search
RRF
Rerank
Failure Degradation
```

---

# Keyword Search

Keyword Search 由 Spring Boot + MySQL 完成。

当前可以根据实际持久化信息检索：

```text
Title
Content
Summary
Category
Tags
Keywords
Entities
Searchable Content
```

并支持业务过滤，例如：

```text
Type
Category
Favorite
```

Keyword Search 使用确定性的字段级排序。

因此当用户记得明确关键词时：

```text
Exact / Strong Text Match
```

仍然拥有很高价值。

---

# Searchable Content

不同 Capture 类型被统一准备成可供检索和后续 AI 使用的文本。

概念：

```text
TEXT
→ Original Content

URL
→ Extracted Web Content

FILE
→ Extracted Document Text

IMAGE
→ OCR Text
```

其中 URL / FILE / IMAGE 可以形成：

```text
searchable_content
```

Searchable Content 是：

```text
Derived / Rebuildable Data
```

而不是：

```text
Original Business Source of Truth
```

它目前可以服务于：

```text
Keyword Search
Embedding
Semantic Retrieval
Action Extraction
```

---

# Semantic Search

Semantic Search 解决的是：

> 用户记得“意思”，但是已经忘了原文使用了什么关键词。

例如用户搜索：

```text
那个讲 Redis 防止重复请求的文章
```

保存的内容可能实际写的是：

```text
接口幂等
Redisson
Redis Lua
分布式锁
```

即使不存在完全一致的文本，

Semantic Search 仍可能找到相关内容。

当前流程：

```text
Query
   ↓
Embedding
   ↓
Qdrant
   ↓
Candidate InboxItem IDs
   ↓
Spring Boot
   ↓
MySQL
   ↓
Authoritative Results
```

Qdrant 只负责 Retrieval Candidate。

最终业务结果仍然由 MySQL 决定。

---

# Hybrid Search

Keyword Search 与 Semantic Search 各有所长。

```text
Keyword Search
→ 用户记得具体词

Semantic Search
→ 用户记得大概意思
```

LifeInbox 将两个 Retrieval Branch 结合：

```text
Keyword Results
       +
Semantic Results
       ↓
Reciprocal Rank Fusion
       ↓
Hybrid Candidates
```

当前使用：

```text
RRF
=
Reciprocal Rank Fusion
```

融合不同检索结果。

同一个 InboxItem 即使：

```text
Keyword hit
+
Semantic hit
```

最终也只出现一次。

---

# Reranking

Hybrid Retrieval 得到有限 Candidate 后，

可以进一步进行：

```text
Hybrid Candidates
       ↓
Reranker
       ↓
Final Ranking
```

Reranker：

```text
不搜索整个数据库
不扩大 Candidate Set
不成为业务 Source of Truth
```

它只负责：

```text
Query
↔
Candidate
```

之间更精细的相关性判断。

Rerank Score 只属于当前查询，

不会写入 MySQL 或 Qdrant 作为永久业务字段。

---

# Search Failure Degradation

Search 采用 Graceful Degradation。

```text
Reranker unavailable
        ↓
Hybrid / RRF Results
```

如果 Semantic Retrieval 不可用：

```text
Embedding / Qdrant unavailable
        ↓
Keyword Search
```

如果整个 AI Engine 不可用：

```text
Capture
Inbox
Favorite
Archive
Delete
Keyword Search
```

仍应该尽可能正常工作。

---

# Qdrant

LifeInbox 当前使用 Qdrant 完成向量检索。

最重要的数据边界：

```text
MySQL
=
Business Source of Truth

Qdrant
=
Derived / Rebuildable Retrieval Index
```

Qdrant 可以保存用于检索的数据，例如：

```text
InboxItem ID
Embedding Vector
Embedding Model
Content Hash
Minimal Retrieval Metadata
```

但是它不是以下内容的业务 Owner：

```text
InboxItem
Favorite
Archive
Original Content
AI Status
Action Candidate
Todo Business State
```

理论上：

```text
MySQL
↓
Searchable Content
↓
Embedding
↓
Rebuild Qdrant
```

应该能够重新构建 Vector Index。

---

# V0.4 — Action Extractor

当前正在开发：

```text
V0.4 — Action Extractor
```

V0.4 解决的新问题是：

```text
“我保存的信息里面，
有没有什么是以后真的需要去做的？”
```

例如保存：

```text
软件工程课程设计
8月25日前交报告
```

已有 OCR / Content Preparation 可以先得到文本：

```text
软件工程课程设计
8月25日前交报告
```

Action Extractor 可以进一步识别出类似：

```json
{
  "hasAction": true,
  "actionType": "DEADLINE",
  "title": "提交软件工程课程设计报告",
  "deadline": "2026-08-25"
}
```

注意：

这只是概念示例。

最终 Schema 由实际 V0.4 Task 和当前仓库实现决定。

---

# Action Candidate

V0.4 一个非常重要的产品边界是：

```text
AI Suggestion
≠
Confirmed Business Action
```

Task 35 已实现的后端产品流程是：

```text
InboxItem
    ↓
Action Extraction
    ↓
Structured Action Candidate
    ↓
User Decision
   ↙      ↘
Accept   Dismiss
   ↓
 Todo
```

AI 发现：

```text
“这里可能存在一个 Action。”
```

不意味着系统可以直接：

```text
INSERT Todo
```

重要业务状态仍然需要明确确认。

---

# Todo Core Model and Deadline Direction

V0.4 Task 34 已建立独立的 Todo 持久化基础：

```text
Todo
 ├── title
 ├── description
 ├── status
 ├── optional due_date
 └── optional source links
```

Todo 是 Java / MySQL 拥有的业务状态；来源 InboxItem 或 ActionCandidate 被删除时只清空追溯引用，
不会级联删除 Todo。同一个 ActionCandidate 最多关联一个 Todo。

Task 35 已实现 Candidate Accept / Dismiss 与 Candidate → Todo Conversion；Accept 会在一个短事务中创建唯一 Todo
并把 Candidate 标记为 `ACCEPTED`，Dismiss 只保留 `DISMISSED` 用户决定。当前仍没有独立 Todo 列表或生命周期 API。

而不是一开始创建：

```text
Todo
+
Deadline
```

两个生命周期高度重叠的独立模型。

例如：

```text
8月25日前交报告
```

更自然地表示为：

```text
Todo:
  title = 提交报告
  due_date = 2026-08-25
```

只有未来真正出现独立 Deadline 生命周期需求时，

再评估是否需要：

```text
deadline
```

独立业务实体。

---

# Action Source Traceability

Action Candidate 和 Todo 应尽可能保留其来源：

```text
InboxItem
    ↓
Action Candidate
    ↓
Todo
```

这样 LifeInbox 可以回答：

```text
这个 Todo 为什么出现？

这个 Deadline 来自哪里？
```

原始信息仍然由 InboxItem 保存。

Action 数据不需要复制整份：

```text
Web Body
PDF Text
OCR Text
Searchable Content
```

---

# User Decision Is Authoritative

V0.4 开始需要更加明确数据优先级：

```text
User-confirmed Business State
            >
Current Business Data
            >
AI-generated Candidate
```

例如：

```text
AI:
Deadline = 8月25日

User:
改成 8月28日
```

未来再次运行 AI 时，

不能：

```text
8月28日
↓
silent overwrite
↓
8月25日
```

用户确认和修改后的业务数据拥有更高优先级。

---

# Deadline Normalization

日期抽取需要区分：

```text
Original Expression
```

和：

```text
Normalized Deadline
```

例如：

```text
Original:
下周五之前

Reference Date:
2026-08-24

Normalized:
2026-09-04
```

当前实现由 Java 从 `InboxItem.created_time` 提供稳定 `referenceDate`，Python 使用确定性标准库逻辑解析今天、
明天、后天、本周/下周星期、月底和今年/明年等明确相对表达。重新提取不会因为执行日期变化而漂移。

缺少年份的 `8月25日`、单独 `周五`、模糊表达，或没有 `referenceDate` 的相对日期仍保持
`deadline = null`；原始表达继续保存在 `deadlineText`。

如果信息不足以确定：

```text
year
week
timezone
exact time
```

系统不应该无依据生成一个看似精确的时间。

优先：

```text
Keep uncertainty
+
Ask / require confirmation
```

而不是：

```text
Fabricate precision
```

---

# Java / Python Responsibility Boundary

LifeInbox 始终保持明确的 Java / Python 边界。

## Spring Boot — The Product

Java 负责：

```text
InboxItem
Business Data
MySQL Persistence

Capture
Favorite
Archive
Delete

AI Processing State
AI Attempt Ownership
AI Result Persistence

Search Product API
Keyword Retrieval
Hybrid Orchestration
Final Business Results

Action Candidate State
Todo Business State
Future Deadline Business State
```

Java 是：

```text
Business Source of Truth
```

---

## FastAPI — Understanding Information

Python 负责：

```text
Web Content Extraction
Document Parsing
OCR

Summary
Category
Tags
Keywords
Entities

Embedding
Semantic Retrieval Support
Reranking

Action Extraction
Deadline Extraction

Future Relation Discovery
```

核心关系：

```text
Java:
“There is an InboxItem.
This is the current business state.”

Python:
“I can understand the information
and return structured suggestions.”
```

Python 不拥有最终：

```text
InboxItem
Todo
Deadline
Favorite
Archive
```

业务状态。

---

# Architecture

Current high-level architecture:

```text
                           LifeInbox
                               │
                               ▼
                          Vue 3 / Vite
                               │
                          Product API
                               │
                               ▼
                       Spring Boot / Java
                               │
              ┌────────────────┼────────────────┐
              ▼                ▼                ▼
            MySQL         Local Files        FastAPI
      Business Source     FILE / IMAGE       AI Engine
         of Truth                                │
                         ┌───────────────────────┼───────────────────────┐
                         ▼                       ▼                       ▼
                  Content Processing         Embedding                Rerank
                         │                       │
             ┌───────────┼───────────┐           ▼
             ▼           ▼           ▼         Qdrant
          URL           Doc         OCR     Derived Vector Index
       Extraction    Extraction
```

V0.4 在此基础上增加：

```text
InboxItem
    │
    ▼
Usable / Prepared Content
    │
    ▼
FastAPI Action Extraction
    │
    ▼
Structured Action Candidate
    │
    ▼
Spring Boot
    │
    ▼
Future User Confirmation
    │
    ▼
Todo
└── optional due_date
```

---

# Technology Stack

## Frontend

```text
Vue 3
Vite
```

## Backend

```text
Java 21
Spring Boot
MyBatis-Plus
MySQL
```

## AI Engine

```text
Python
FastAPI
```

## Retrieval

```text
Qdrant
```

Qdrant 是增强型 Derived Retrieval Infrastructure。

核心 Capture / Inbox 不依赖 Qdrant 才能运行。

---

# Repository Structure

LifeInbox uses a monorepo structure.

```text
life-inbox/
│
├── web/
│   └── Vue 3 frontend
│
├── server/
│   └── Spring Boot product backend
│
├── ai-engine/
│   └── FastAPI AI engine
│
├── docs/
│   ├── architecture.md
│   ├── database.md
│   ├── api.md
│   ├── roadmap.md
│   └── history/
│
├── extension/
│   └── Future browser Capture integration
│
├── deploy/
│   └── Deployment-related configuration
│
├── AGENTS.md
├── README.md
└── .gitignore
```

目录应随着真实需求增长。

不要因为未来 Roadmap 中存在：

```text
todo
relation
agent
rag
memory
```

就提前创建大量空模块。

---

# Running LifeInbox Locally

LifeInbox 当前主要由以下组件组成：

```text
MySQL
Spring Boot
FastAPI
Vue
```

Semantic Search 额外需要：

```text
Embedding Provider
Qdrant
```

Reranking 额外需要：

```text
Rerank Provider
```

---

## 1. Start MySQL

创建：

```text
life_inbox
```

数据库。

具体 Schema 和迁移说明见：

```text
docs/database.md
docs/sql/
```

始终以当前仓库 SQL 为准。

---

## 2. Start Spring Boot

进入：

```text
server/
```

Windows：

```powershell
.\mvnw.cmd spring-boot:run
```

---

## 3. Start FastAPI

进入：

```text
ai-engine/
```

同步依赖后运行：

```powershell
uv run uvicorn app.main:app --host 127.0.0.1 --port 8000
```

AI Engine 的 Provider 配置见：

```text
ai-engine/README.md
.env.example
```

不要把真实 API Key 提交到仓库。

---

## 4. Start Qdrant

Semantic Search / Hybrid Search 的 Semantic Branch 需要 Qdrant。

常见本地地址：

```text
http://127.0.0.1:6333
```

具体配置以：

```text
ai-engine/README.md
```

为准。

Qdrant 不可用时，

Keyword Search 仍应尽可能正常工作。

---

## 5. Start Frontend

进入：

```text
web/
```

运行：

```bash
npm install
npm run dev
```

使用 Vite 输出的开发地址访问 LifeInbox。

---

# AI Configuration

外部 AI Provider 通过环境变量配置。

当前主要配置组包括：

```text
LLM
Embedding
Qdrant / Vector Store
Rerank
```

真实变量名称以：

```text
.env.example
ai-engine/README.md
```

为准。

禁止提交：

```text
API Key
Token
Private Credential
Secret
```

---

# Alibaba Cloud Model Studio Compatibility

当前如果使用阿里云百炼，

Chat / Embedding 与 Rerank 可能使用不同的 Compatible API Base。

概念：

```text
Chat
↓
compatible-mode/v1/chat/completions

Embedding
↓
compatible-mode/v1/embeddings
```

当前接入的 Rerank 能力可能使用：

```text
Rerank
↓
compatible-api/v1/reranks
```

具体配置不要硬编码在业务代码中。

以：

```text
.env.example
ai-engine/README.md
```

为准。

---

# Testing

## Java

进入：

```text
server/
```

执行：

```powershell
.\mvnw.cmd clean test
```

---

## Python

进入：

```text
ai-engine/
```

执行：

```powershell
uv run pytest -p no:cacheprovider
```

自动测试应尽量 Mock：

```text
LLM Provider
Embedding Provider
Rerank Provider
External AI Service
```

避免测试意外消耗付费额度。

---

## Frontend

进入：

```text
web/
```

执行：

```bash
npm run build
```

不要把：

```text
没有真正执行过
```

的测试报告成：

```text
PASS
```

---

# Roadmap

## V0.1 — Universal Inbox ✅

Goal:

```text
Anything useful can be captured quickly.
```

主要能力：

```text
TEXT
URL
FILE
IMAGE
Unified Inbox
Favorite
Archive
Delete
```

重点：

```text
Capture
```

---

## V0.2 — AI Organizer ✅

Goal:

```text
Save first.
AI organizes later.
```

主要能力：

```text
Content Extraction
OCR

Summary
Category
Tags
Keywords
Entities

Reliable AI Processing
Retry
Attempt Guard
Automatic Analyze
```

重点：

```text
Understand
+
Organize
```

---

## V0.3 — Smart Search ✅

Goal:

```text
Previously captured information
can actually be found again.
```

主要能力：

```text
Keyword Search
AI-derived Search
Filters
Ranking
Safe Highlight

Searchable Content

Embedding
Qdrant

Semantic Search
Hybrid Search
RRF
Rerank

Failure Degradation
```

重点：

```text
Retrieve
```

---

## V0.4 — Action Extractor 🚧

Goal:

```text
Information can become actionable.
```

当前计划逐步实现：

```text
Structured Action Candidate

Todo Extraction
Deadline Extraction

User Confirmation
Dismiss / Ignore

Source Traceability
Safe Reprocessing
```

当前推荐业务流：

```text
InboxItem
    ↓
Action Extraction
    ↓
Action Candidate
    ↓
User Confirm / Dismiss
    ↓
Todo
    └── optional due time
```

V0.4 不自动意味着：

```text
Google Calendar
Push Notification
Autonomous Todo Creation
Workflow Engine
Agent Execution
```

这些能力必须等到出现真实需求后再决定。

---

## V0.5 — Relations 📋

Goal:

```text
Discover useful relationships
between captured information.
```

未来可能形成：

```text
InboxItem A
     ↓
 RELATED_TO
     ↓
InboxItem B
```

第一阶段优先考虑简单关系模型。

不要因为：

```text
Relations
```

就直接引入：

```text
Neo4j
```

---

## V1.0 — Personal AI 📋

Goal:

```text
AI can work with accumulated
personal information.
```

未来可能包括：

```text
Personal Information Analysis
Personal RAG
Conversational Retrieval
Long-term Topic Summaries
Project Discovery
Personal Agent
```

Agent 应该建立在：

```text
Reliable Capture
+
Reliable Understanding
+
Reliable Retrieval
+
Reliable Action Boundary
+
Useful Personal Data
```

之上。

---

# Browser Extension

Browser Extension 仍然是未来重要的 Capture Enhancement。

目标体验：

```text
See useful webpage
       ↓
Click Save
       ↓
LifeInbox
```

它被延后是：

```text
Priority Adjustment
```

不是：

```text
Abandoned Direction
```

目前优先完成：

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

核心闭环。

---

# Inspiration

LifeInbox is inspired by ideas from several personal information, knowledge-management, retrieval, and action-oriented projects.

Different projects are useful references for different stages of LifeInbox.

## Capture / Organize

* **DropMind** — frictionless universal capture and Inbox-first product thinking.
* **NoteGen** — the `Capture First, Organize Later` philosophy and AI-assisted organization.
* **My-Brain-System** — AI-assisted organization of accumulated personal knowledge.
* **Eclaire** — unified personal-data concepts across notes, files, bookmarks, and tasks.

## Content / Retrieval

* **Karakeep** — practical bookmark capture, content preservation, search, OCR, and AI-assisted organization.
* **4DPocket** — content enrichment and retrieval-pipeline ideas.
* **Khoj** — semantic retrieval over personal information.

## V0.4 — Action

The current Action Extractor stage especially references ideas from:

* **Personal OS + Personal Wiki (`lawyer112/personal-os-wiki`)** — moving messy captured information toward explicit, reviewable work while keeping source knowledge and action state conceptually separate.
* **PersonalOS (`amanaiproduct/personal-os`)** — turning unstructured backlog information into structured tasks with simple task context and optional deadline information.
* **work-os (`guo-yichen/work-os`)** — useful inspiration for distinguishing actions from other extracted information such as decisions and ideas.

These projects are:

```text
Inspiration
```

not:

```text
LifeInbox Architecture Source of Truth
```

LifeInbox does **not** copy any single project's:

```text
Technology Stack
Database Model
Agent Framework
Task Worker
Knowledge Graph
MCP Integration
Reminder System
Workflow Engine
```

The authority order remains:

```text
Current User Task
        ↓
Root AGENTS.md
        ↓
Current Repository Implementation
        ↓
Current LifeInbox Documentation
        ↓
Reference Projects
```

For V0.4, LifeInbox keeps its own product boundary:

```text
InboxItem
    ↓
Action Extraction
    ↓
Structured Action Candidate
    ↓
User Confirm / Dismiss
    ↓
Todo
    └── optional due time
```

Java / MySQL remain the:

```text
Business Source of Truth
```

Python only provides structured AI understanding and Action suggestions.

---

# What LifeInbox Is Not Yet

LifeInbox intentionally does not try to implement every AI concept at once.

Current development does not require:

```text
GraphRAG
Multi-Agent
Workflow Engine
Neo4j
Kafka
Complex Distributed Infrastructure
```

And although V0.3 already contains:

```text
Embedding
Vector Retrieval
Hybrid Search
Rerank
```

this does not automatically mean LifeInbox currently needs:

```text
RAG
Chat with your data
Agent
MCP
```

Those belong to later stages.

---

# Design Principles

### Capture must survive AI failure

```text
AI Down
≠
Capture Down
```

### Java owns business state

```text
Spring Boot / MySQL
=
Business Source of Truth
```

### Python understands information

```text
FastAPI
=
AI Processing Capability
```

### Vector data is derived

```text
Qdrant
=
Rebuildable Retrieval Index
```

### AI suggestions are not user decisions

```text
AI Candidate
≠
Confirmed Todo
```

### User decisions have higher authority

```text
User-confirmed State
>
AI-generated Suggestion
```

### Infrastructure follows requirements

```text
Requirement First
Technology Second
```

### Development stays incremental

```text
One Clear Task
      ↓
Implement
      ↓
Verify
      ↓
Stop
```

---

# Known Limitations

Current known scope limitations include:

* Some older InboxItems may need reprocessing before a vector index exists.
* Retrieval is currently primarily item-level rather than document-chunk-level.
* Semantic Search requires an Embedding Provider and Qdrant.
* Reranking requires an explicitly configured Rerank Provider.
* Action Extractor is currently under development.
* Manual Action Candidate extraction、Accept/Dismiss 与 Candidate → Todo conversion 已实现；前端确认 UI 和 Todo 列表/完成/编辑 API 尚未实现。
* Relations are not implemented yet.
* Personal RAG is not implemented yet.
* Personal Agent functionality is not implemented yet.
* Browser Extension Capture is postponed.
* Reminder / Calendar integration is not currently implemented.

These are intentional scope boundaries.

---

# Documentation

More detailed technical documentation is available under:

```text
docs/
```

Important files:

```text
docs/architecture.md
docs/database.md
docs/api.md
docs/roadmap.md
```

AI Engine configuration and internal capability documentation:

```text
ai-engine/README.md
```

Historical development prompts may exist under:

```text
docs/history/
```

Historical files explain how LifeInbox evolved.

They do not override:

```text
Current Task
AGENTS.md
Current Repository
Current Documentation
```

---

# Development Philosophy

LifeInbox should grow from real problems,

not from a checklist of AI technologies.

Prefer:

```text
A smaller product
that works well
```

over:

```text
RAG
Agent
MCP
GraphRAG
Multi-Agent
Knowledge Graph
Workflow Engine
```

all implemented only partially.

The goal is not to make LifeInbox look technically complicated.

The goal is to make it genuinely useful.

---

# Current Focus

```text
V0.4 — Action Extractor
```

The main question is no longer:

```text
“Can I find what I saved?”
```

V0.3 already addressed that.

The next question is:

```text
“Can LifeInbox recognize
when saved information requires action?”
```

That is the focus of V0.4.

The immediate product direction is:

```text
Saved Information
       ↓
Action Understanding
       ↓
Structured Candidate
       ↓
Human Decision
       ↓
Useful Action
```
