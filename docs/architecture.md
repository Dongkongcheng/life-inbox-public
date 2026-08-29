# LifeInbox 架构

## 1. 产品定位

LifeInbox 是一个 AI 驱动的个人信息收件箱。

它的核心目标不是构建一个传统意义上的“知识库”，而是解决信息从出现到真正产生价值之间的断层：

```text
看到有价值的信息
        ↓
先快速保存
        ↓
AI 帮助理解和整理
        ↓
以后能够重新找回
        ↓
必要时转化成行动
```

长期产品闭环：

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
   ↓
Relations
   ↓
Personal AI
```

对应版本路线：

```text
V0.1  Universal Inbox   → Capture
V0.2  AI Organizer      → Understand / Organize
V0.3  Smart Search      → Retrieve
V0.4  Action Extractor  → Action
V0.5  Relations         → Relations / Personal Memory
V1.0  Personal AI       → Personal AI / Agent
```

当前阶段：

```text
V0.1 — Universal Inbox       ✅ Completed
V0.2 — AI Organizer          ✅ Completed
V0.3 — Smart Search          ✅ Completed
V0.4 — Action Extractor      ✅ Completed
V0.5 — Relations             🚧 Current
V1.0 — Personal AI           📋 Planned
```

当前稳定架构基线：

```text
V0.5 Task 6 / Overall Task 46
— Frontend Related Items UI
```

V0.5 当前已完成 Relation 持久化基础、有界运行时候选发现、有界 AI Relation 判断、建议到正式 Relation 的内部持久化、只读 Related Items Product API，以及前端 Related Items 体验；自动处理尚未实现。

---

# 2. 核心架构原则

LifeInbox 始终遵循：

```text
Capture First, Organize Later.
```

也就是：

> Capture 必须首先可靠完成，AI、搜索、向量检索、Action Extraction 等高级能力都属于后续增强。

即使以下能力不可用：

```text
FastAPI
LLM
OCR
网页正文提取
Embedding
Qdrant
Semantic Search
Reranker
Action Extractor
```

基础 Inbox 仍然应该尽可能可用。

核心原则：

```text
AI Failure
≠
Capture Failure
```

---

# 3. 当前总体架构

当前 LifeInbox 采用：

```text
Vue 3
+
Spring Boot
+
MySQL
+
FastAPI
+
Qdrant
+
Local File Storage
```

总体结构：

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
            ┌───────────────────┼───────────────────┐
            │                   │                   │
            ▼                   ▼                   ▼
          MySQL            Local Files          FastAPI
    Business Source        FILE / IMAGE         AI Engine
       of Truth                                    │
                                                   │
                      ┌────────────────────────────┼────────────────────────────┐
                      │                            │                            │
                      ▼                            ▼                            ▼
               Content Processing             Embedding                    Rerank
                      │                            │
          ┌───────────┼───────────┐                ▼
          ▼           ▼           ▼              Qdrant
     URL Extractor  Document      OCR        Derived Vector Index
                    Extractor
```

当前主要业务通信路径：

```text
Vue
 ↓
Spring Boot
```

前端不直接依赖 FastAPI。

AI 相关调用主要保持：

```text
Spring Boot
 ↓
FastAPI
```

---

# 4. 当前基础设施

当前实际使用：

```text
MySQL
Spring Boot
FastAPI
Vue
Local File Storage
Qdrant
```

其中：

```text
MySQL
=
Business Source of Truth
```

```text
Qdrant
=
Derived / Rebuildable Retrieval Index
```

目前没有因为长期 Roadmap 而提前加入：

```text
Redis
RabbitMQ
RocketMQ
Kafka
Elasticsearch
Milvus
Neo4j
```

基础设施始终遵循：

```text
Requirement
    ↓
Choose Infrastructure
```

而不是：

```text
Choose Technology
    ↓
Find a Problem
```

---

# 5. Java 与 Python 的职责边界

这是 LifeInbox 最重要的架构边界之一。

## 5.1 Java / Spring Boot

Java 负责：

> 产品与业务状态。

包括：

```text
InboxItem
Business Data
MySQL Persistence
File Metadata
Capture
Favorite
Archive
Delete

AI Processing State
AI Attempt Ownership
AI Result Persistence

Search Product API
Keyword Retrieval
Hybrid Search Orchestration
Business Filters
Final Result Composition

V0.4 Action Candidate Business State
Todo Core Business State
Future Deadline

Future Authentication
Future Permission
```

Java 是：

```text
Business Source of Truth
```

---

## 5.2 Python / FastAPI

Python 负责：

> 理解信息以及提供 AI / Retrieval 能力。

当前包括：

```text
URL 正文提取
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
```

V0.4 开始逐步加入：

```text
Action Extraction
Deadline Extraction
```

未来可能加入：

```text
Vision
Relation Discovery
Personal AI Support
```

Python 不应该：

```text
拥有 InboxItem 业务状态
实现独立 InboxItem CRUD
成为业务数据库 Source of Truth
直接成为 Todo / Deadline 的业务所有者
```

核心关系保持：

```text
Java：
这里有一条信息，
这是系统当前的业务事实。

Python：
我来理解这条信息是什么意思，
并返回结构化建议。
```

---

# 6. 统一 Inbox 模型

LifeInbox 最重要的业务模型始终是：

```text
InboxItem
```

当前主要内容类型：

```text
TEXT
URL
FILE
IMAGE
```

不同内容来源统一遵循：

```text
Different Sources
       ↓
    InboxItem
       ↓
Content Processing
       ↓
AI / Search / Action
```

不要发展成：

```text
网页一套核心业务模型
PDF 一套核心业务模型
图片一套核心业务模型
文字一套核心业务模型
GitHub 一套核心业务模型
```

不同类型可以拥有不同：

```text
Parser
Extractor
OCR
Processing Strategy
```

但业务层仍然围绕：

```text
InboxItem
```

组织。

---

# 7. Existing Repository Is the Source of Truth

总体规划中的：

```text
raw_content
file_id
created_at
document_chunk
```

等名称属于早期概念设计。

当前仓库实际实现可能已经使用不同名称。

原则：

```text
Current Repository Implementation
>
Old Placeholder Naming
```

因此：

* 不为了匹配旧规划而重命名已经工作的字段。
* 不为了匹配旧架构图而重写已经稳定的 Service。
* 不因为旧规划出现某张表，就自动创建该表。
* 不因为长期规划出现某项技术，就提前引入该技术。

旧规划决定方向。

当前仓库决定事实。

---

# 8. Unified Analyze Pipeline

V0.2 已形成统一 Analyze Pipeline：

```text
TEXT ───────────────────────┐
                            │
URL   → Web Extraction ─────┤
                            │
FILE  → Document Extraction ┤
                            │
IMAGE → OCR ────────────────┤
                            ▼
                    Prepared Content
                            │
                            ▼
                     AnalyzeService
                            │
                            ▼
                      AnalyzeResult
```

四种内容类型的主要差异发生在：

```text
Content Preparation
```

完成之后统一进入 AI Analyze。

结构化结果包括：

```text
Summary
Category
Tags
Keywords
Entities
```

原则上使用统一结构化 Analyze，

而不是：

```text
Summary    → 单独一次 LLM
Category   → 单独一次 LLM
Tags       → 单独一次 LLM
Keywords   → 单独一次 LLM
Entities   → 单独一次 LLM
```

---

# 9. Capture 与 AI 解耦

Capture 的优先级始终高于 AI。

正确流程：

```text
User Capture
     ↓
Spring Boot
     ↓
Save InboxItem
     ↓
MySQL COMMIT
     ↓
Return Capture Result
     ↓
Background AI Processing
```

而不是：

```text
User Capture
     ↓
等待 URL Extract
     ↓
等待 OCR
     ↓
等待 LLM
     ↓
等待 Embedding
     ↓
最后才保存
```

核心保证：

```text
AI Failure
≠
Capture Failure
```

---

# 10. Automatic Analyze

当前 Automatic Analyze 使用：

```text
@TransactionalEventListener(AFTER_COMMIT)
```

概念流程：

```text
Capture Transaction
        ↓
      COMMIT
        ↓
 AFTER_COMMIT Event
        ↓
 Bounded Executor
        ↓
      Analyze
```

当前使用：

```text
Spring In-Process
Bounded Executor
```

而不是：

```text
Persistent MQ
```

这是当前阶段有意选择的简单方案。

只有未来真正出现：

```text
AI Task 不能丢
任务规模明显增加
需要跨进程 Worker
需要持久化重试
需要多实例协调
```

等问题时，

才评估：

```text
Redis
MQ
Persistent Task Queue
```

---

# 11. AI Processing State

当前 AI Processing State：

```text
NOT_PROCESSED
PROCESSING
SUCCESS
FAILED
```

基本流程：

```text
NOT_PROCESSED
      ↓
PROCESSING
   ↙      ↘
SUCCESS   FAILED
```

Retry / Re-analyze：

```text
FAILED / SUCCESS
       ↓
   PROCESSING
       ↓
SUCCESS / FAILED
```

---

# 12. Transaction 与 Attempt Guard

外部 AI 调用不能长期占用数据库事务。

当前架构：

```text
短事务：
领取 Processing Ownership
写 PROCESSING + attemptId
        ↓
      COMMIT

事务外：
URL / FILE / OCR
FastAPI
LLM
        ↓

短事务：
检查 attemptId
保存新结果
更新 SUCCESS / FAILED
```

每个 Analyze Attempt 对应：

```text
aiAttemptId
```

例如：

```text
Attempt A
id = AAA
```

如果 A 超时，

新的：

```text
Attempt B
id = BBB
```

接管。

如果旧 A 后来返回：

```text
AAA != 当前 BBB
```

则 A 不允许：

```text
覆盖 B 的结果
覆盖新的 Searchable Content
改变 B 的状态
覆盖由 B 产生的新结果
```

迟到结果不能覆盖新的 Processing Ownership。

---

# 13. AI Failure Degradation

如果：

```text
FastAPI Down
LLM Error
Timeout
URL Extraction Failure
Document Extraction Failure
OCR Failure
```

则：

```text
Original InboxItem
```

仍然必须存在。

Re-analyze 开始时也不能先清空已有：

```text
Summary
Category
Tags
Keywords
Entities
Searchable Content
```

正确策略：

```text
Old Successful Result
        ↓
Keep
        ↓
New Processing
        ↓
New Complete Success
        ↓
Replace
```

如果新 Analyze 失败：

```text
Old Successful Data Remains
```

---

# 14. V0.3 Smart Search — Completed Architecture

V0.3 已经完成：

```text
Retrieve
```

最终架构：

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

V0.3 已完成的主要阶段：

```text
Basic Keyword Search
        ↓
AI-derived Field Search
        ↓
Filter / Ranking / Highlight
        ↓
Searchable Content
        ↓
Embedding Pipeline
        ↓
Vector Storage / Indexing
        ↓
Semantic Search
        ↓
Hybrid Search
        ↓
RRF
        ↓
Rerank
        ↓
Final Acceptance
```

V0.3 已完成。

V0.4 不应该重新设计该 Search Pipeline，

除非当前 Task 明确要求修复 Search Bug。

---

# 15. Keyword Search Architecture

Keyword Search：

```text
Vue Search UI
      ↓
Spring Boot Search API
      ↓
MySQL
      ↓
Keyword Retrieval
      ↓
InboxItem Results
```

当前可搜索的持久化信息包括：

```text
title
content
summary
category
tags
keywords
entities
searchable_content
```

具体字段以当前仓库实现为准。

查询始终由 Spring Boot：

```text
parameterized SQL
+
ACTIVE business condition
+
business filters
```

控制。

Tags、Keywords、Entities 等关联数据的匹配保持数据库侧完成。

避免：

```text
N+1
重复主表结果
SELECT 全部以后 Java Filter
```

---

# 16. Searchable Content

V0.3 引入 Searchable Content 作为统一检索正文。

当前概念：

```text
TEXT
→ Original Content

URL
→ Extracted Web Body

FILE
→ Extracted Document Text

IMAGE
→ OCR Text
```

TEXT 继续直接使用原业务字段，

不为了 Search 重复保存整份原文。

URL / FILE / IMAGE 可以产生：

```text
searchable_content
```

该数据属于：

```text
Derived / Rebuildable Retrieval Data
```

不是：

```text
Original Business Source of Truth
```

当前实现仍保持：

```text
Item-level Searchable Content
```

没有：

```text
document_chunk
Chunk Retrieval
Chunk Embedding
```

---

# 17. Searchable Content Pipeline

概念流程：

```text
InboxItem
    ↓
Content Preparation
    ↓
Searchable Content
    ↓
┌───────────────┬────────────────┐
▼               ▼                ▼
Keyword       Embedding       Action
Search                         Extraction
```

这也是 V0.4 的重要基础。

原则：

> Action Extractor 应优先复用已经存在的可用文本准备能力，而不是重新建立第二套 URL / FILE / OCR 提取体系。

---

# 18. Embedding Pipeline

Embedding 架构：

```text
Text
 ↓
EmbeddingService
 ↓
Embedding Provider
 ↓
EmbeddingResult
```

EmbeddingResult 包含：

```text
model
dimension
vector
```

Embedding 属于：

```text
Derived Retrieval Capability
```

它不属于业务 Source of Truth。

Embedding Failure：

```text
≠
Analyze Failure
≠
Capture Failure
```

---

# 19. Vector Index Architecture

当前：

```text
Searchable Content
        ↓
EmbeddingService
        ↓
EmbeddingResult
        ↓
VectorIndexService
        ↓
VectorStoreService
        ↓
Qdrant
```

Qdrant 使用：

```text
Item-level Point
```

同一个 InboxItem 使用稳定 Point Identity。

Re-analyze：

```text
same InboxItem
   ↓
upsert
```

而不是无限创建新 Point。

Archive / Delete 根据当前真实实现执行：

```text
best-effort vector cleanup
```

Vector 操作失败：

```text
只影响 Vector Enhancement
```

不能：

```text
回滚 InboxItem
改变 AI SUCCESS
删除 Searchable Content
```

---

# 20. Qdrant Ownership

Qdrant 是：

```text
Derived / Rebuildable Vector Index
```

MySQL 是：

```text
Authoritative Business Database
```

Qdrant 可以保存：

```text
InboxItem ID
Embedding Vector
Embedding Model
Content Hash
Minimal Retrieval Metadata
```

不能成为：

```text
InboxItem Business Database
Favorite Source of Truth
Archive Source of Truth
AI Status Source of Truth
Todo Source of Truth
Deadline Source of Truth
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

应该能够重新构建索引。

---

# 21. Semantic Search Architecture

Semantic Search：

```text
User Query
     ↓
Spring Boot
     ↓
FastAPI Semantic Retrieval
     ↓
Query Embedding
     ↓
Qdrant
     ↓
Candidate IDs + Scores
     ↓
Spring Boot
     ↓
MySQL Batch Resolution
     ↓
ACTIVE + Business Filters
     ↓
Authoritative InboxItems
```

Qdrant 返回：

```text
Retrieval Candidate
```

而不是最终业务对象。

最终结果必须重新回到：

```text
MySQL
```

解析当前真实业务状态。

因此：

```text
Stale Qdrant Point
+
Deleted / Archived MySQL Item
=
Do Not Return
```

---

# 22. Hybrid Search Architecture

Hybrid Search 由 Java 协调。

```text
                         Query
                           │
              ┌────────────┴────────────┐
              ▼                         ▼
     MySQL Keyword Retrieval   Semantic Retrieval
              │                         │
              └────────────┬────────────┘
                           ▼
                  Reciprocal Rank Fusion
                           ↓
                  Hybrid Candidates
```

Hybrid 不直接混加：

```text
Keyword Weight
+
Cosine Score
```

因为两类 Score 并不是同一量纲。

当前使用：

```text
Reciprocal Rank Fusion
```

公式概念：

```text
RRFScore(d)
=
Σ 1 / (k + rank_i(d))
```

当前：

```text
RRF_K = 60
```

RRF Score：

```text
Runtime Only
```

不保存到：

```text
MySQL
Qdrant
InboxItem
```

---

# 23. Hybrid Failure Degradation

Hybrid 的设计目标之一是可靠降级。

概念：

```text
Semantic Success
+
Keyword Success
→ RRF
```

如果 Semantic / Vector 失败：

```text
Hybrid
 ↓
Keyword-only
```

如果 Keyword 失败但 Semantic 成功：

```text
Hybrid
 ↓
Semantic-only
```

两个 Retrieval 分支都失败：

```text
Controlled Search Error
```

显式 Semantic Mode：

```text
mode=semantic
```

保持自己的错误语义，

不应该悄悄伪装成 Keyword Search。

---

# 24. Rerank Architecture

Rerank 只处理：

```text
Bounded Hybrid Candidates
```

不会重新搜索整个 Inbox。

流程：

```text
RRF Candidates
      ↓
Build Bounded Candidate Text
      ↓
FastAPI Batch Reranker
      ↓
Candidate IDs + Runtime Score
      ↓
Spring Boot
      ↓
Final Ordering
```

Reranker 不负责：

```text
访问 MySQL
访问 Qdrant
重新执行 Retrieval
扩大 Candidate Set
```

它只负责：

```text
Candidate Relevance Refinement
```

---

# 25. Rerank Failure Degradation

Rerank 是增强能力。

如果：

```text
Rerank Disabled
Rerank Provider Down
Timeout
429
5xx
Invalid Response
Unknown ID
Duplicate ID
Invalid Score
```

则：

```text
Hybrid
 ↓
Original RRF Order
```

继续可用。

因此：

```text
Reranker Down
≠
Hybrid Search Down
```

进一步：

```text
Qdrant / Embedding Down
→ Keyword Search

FastAPI Down
→ Capture + Inbox + Keyword Search
```

---

# 26. External AI Provider Boundary

LifeInbox 不应该把具体 Provider 的 API 细节扩散到业务层。

概念：

```text
Spring Boot
    ↓
FastAPI Internal Capability
    ↓
Provider Client
    ↓
External AI Provider
```

Chat / Embedding / Rerank 可以拥有不同 Provider Endpoint 配置。

例如当前百炼兼容接入中：

```text
Chat / Embedding
→ compatible-mode/v1

Rerank
→ compatible-api/v1/reranks
```

这属于：

```text
AI Engine Provider Integration Detail
```

而不是：

```text
Spring Boot Product Architecture
```

具体环境变量和 Provider 配置以：

```text
ai-engine/README.md
.env.example
```

为准。

---

# 27. Search 与 RAG 的边界

V0.3 完成的是：

```text
Retrieve
```

Search 回答：

```text
哪些保存过的信息和 Query 相关？
```

RAG 回答：

```text
根据这些信息应该生成什么答案？
```

两者不同。

因此已经拥有：

```text
Embedding
Qdrant
Semantic Search
Hybrid Search
Rerank
```

并不意味着项目现在应该自动加入：

```text
RAG
Chat With Data
Agent
Memory Framework
MCP
```

这些仍属于未来阶段。

---

# 28. V0.4 Action Extractor — Implemented Architecture

V0.4 已实现的主链路：

```text
Information
     ↓
Action Candidate
     ↓
User Decision
     ↓
Business Action
```

概念架构：

```text
InboxItem
    │
    ▼
Usable Content
    │
    ▼  AFTER_COMMIT / bounded executor
Claim Action Attempt
    │
    ▼  external call, no DB transaction
Action Extraction
    │
    ▼
Structured Action Candidate
    │
    ▼  ownership check + short transaction
Replace PENDING + Mark SUCCESS
    │
    ▼
User Confirmation / Ignore
    │
    ▼
Todo Core Model / Confirmed Business State
```

这里最重要的架构边界是：

```text
AI Candidate
≠
Confirmed Business Action
```

Task 33 的日期上下文沿现有边界传递：Java 从 `InboxItem.created_time` 取 `LocalDate`，作为
`referenceDate` 发送给 Python；Python 的纯 `DeadlineNormalizer` 只根据 `deadlineText + referenceDate`
做确定性日期运算。两端都不使用当前执行日期解释 Source，因此同一条 InboxItem 重新提取时语义稳定。

Task 34 已在 Java / MySQL 建立独立 Todo 核心模型。Todo 只保存自身的 title、description、OPEN/COMPLETED、
可选 due_date 与可空 Source 引用；Source 删除使用 `ON DELETE SET NULL`。Task 35 已在 Java 业务层实现
`PENDING → ACCEPTED / DISMISSED`：Accept 在同一短事务中锁定 Candidate、创建唯一 Todo 并更新状态，Dismiss
只记录用户决定。重复同向请求幂等，两个终态之间不能互转；整个确认过程不调用 Python 或 LLM。

Task 37 在不建立第二套 Parser/OCR 的前提下复用可用正文：TEXT Capture 提交后登记 Action 事件，URL/FILE/IMAGE
则在现有 `searchable_content` 成功写入后登记。监听器只在事务 `AFTER_COMMIT` 后向已有有界 AI Executor 投递，
队列拒绝和 Provider 失败都不会传播回 Capture。Action 使用独立于 `ai_status` 的状态与 UUID Attempt Guard；迟到
成功/失败都没有覆盖新 Attempt 的权限，成功时 Candidate Replacement 与 `action_status=SUCCESS` 原子提交。

Task 38 继续把 Todo 作为独立业务状态：`GET /api/todos` 只查询 Todo 表，来源删除不会让列表项消失；
Complete / Reopen 使用 Todo 行锁和短事务维护 `OPEN ↔ COMPLETED`，第一次 Complete 的 Java 业务时间保持稳定，
Reopen 清空完成时间。这个生命周期不调用 AI，也不反向修改保持 `ACCEPTED` 的 ActionCandidate。

Task 39 通过独立 `GET /api/todos/{id}/source` 延迟读取来源，保持 Todo List 与来源解析的依赖隔离。来源 Service
只按现有外键读取 MySQL：TEXT 从 `content` 生成有界预览，URL/FILE/IMAGE 只使用已持久化
`searchable_content`；它不调用 FastAPI、Parser/OCR 或向量检索，也没有写事务。ARCHIVED 来源允许只读查看，
删除或脏引用返回空/部分上下文，Todo 仍可独立完成与重新打开。

```text
ActionCandidate ACCEPTED
        ↓ User Accept
Todo OPEN ←──────── Reopen ──────── Todo COMPLETED
          ──────── Complete ───────→
```

---

# 29. Action Extraction Input

Action Extractor 不应该重新建立第二套：

```text
URL Fetcher
PDF Parser
OCR Pipeline
```

而应该优先复用已经存在的内容准备结果。

当前候选输入来自：

```text
TEXT
→ original content

URL
→ extracted web body

FILE
→ extracted document text

IMAGE
→ OCR text
```

其中 URL / FILE / IMAGE 复用已持久化的：

```text
Searchable Content
```

当前 Java 使用现有 `InboxSearchableContentService` 准备文本，可选合并标题后作有界截断；Action 链路不重复 Fetch、Parse 或 OCR。

原则：

```text
Reuse Existing Prepared Content
```

而不是：

```text
Build Another Extraction Stack
```

---

# 30. Action Extractor 与 Python

Python 负责：

```text
Action Detection
Deadline Detection
Structured Action Suggestion
```

例如：

```text
软件工程课程设计
8月25日前交报告
```

可能得到：

```json
{
  "hasAction": true,
  "actions": [
    {
      "actionType": "DEADLINE",
      "title": "提交软件工程课程设计报告",
      "deadlineText": "8月25日前",
      "deadline": null,
      "evidence": "8月25日前交报告"
    }
  ]
}
```

这是当前 Python 内部协议。`hasAction` 由应用根据 `actions` 计算；Python 只返回建议，Java 校验后才持久化 Candidate。

---

# 31. Action Business Ownership

Java 负责：

```text
Action Candidate Persistence
User Confirmation
Ignore / Dismiss
Todo Business State
Deadline Business State
Source Relationship
```

Python 不应该：

```text
直接创建最终 Todo
直接修改用户确认的 Deadline
直接完成 Todo
直接删除用户任务
直接调用 Calendar 创建事件
```

核心关系：

```text
Python:
“我认为这里可能存在一个 Action。”

Java:
“这是一个候选，我负责产品和业务规则。”

User:
“是否真正接受，由我决定。”
```

---

# 32. User Decision Priority

V0.4 必须遵守：

```text
User-confirmed State
        >
Current Business State
        >
AI-generated Suggestion
```

例如：

AI 第一次识别：

```text
Deadline = 2026-08-25
```

用户手动修改：

```text
Deadline = 2026-08-28
```

后续 Re-analyze 不应该：

```text
AI Again
→ 2026-08-25
→ Silent Overwrite
```

用户确认后的业务状态拥有更高优先级。

---

# 33. Action Source Traceability

Action Candidate / Todo 当前能够按需追溯到原始 InboxItem。

概念：

```text
Action
   ↓
sourceInboxItemId
   ↓
InboxItem
```

用户应该能够理解：

```text
这个 Todo 为什么出现？
这个 Deadline 来自哪里？
```

不要为了追溯而复制整份原始内容。

当前前端只在用户点击 Todo 的“查看来源”后请求单条来源，上下文成功后在页面会话内缓存。普通 Todo List 不
JOIN Source，也不为列表中的每条 Todo 发来源请求；来源加载失败只影响当前展开区域。由于当前应用没有 Inbox
详情路由，来源区域展示有界只读预览，并仅在已有安全 URL / 受管文件地址时提供链接。

优先引用：

```text
Authoritative InboxItem
```

---

# 34. Deadline Architecture Principle

日期抽取必须区分：

```text
Original Expression
```

和：

```text
Normalized Date / Time
```

例如：

```text
“下周五之前”
```

现在会在存在稳定 `referenceDate` 时根据 ISO Monday→Sunday 周规则转换为具体日期。今天/明天/后天、
本周/下周星期、本月底/月底/下月底、今年/明年也使用标准日历运算；原始表达保存在 `deadlineText`。

缺少年份的月日、单独星期和模糊表达不会补全；没有 `referenceDate` 时相对表达返回 `deadline = null`。

但是不能无依据猜测：

```text
year
timezone
exact time
```

如果语义存在不确定性，

应该优先：

```text
Expose Uncertainty
or
Require Confirmation
```

而不是制造虚假的精确值。

---

# 35. Action Failure Degradation

如果：

```text
Action Extractor Down
LLM Error
Timeout
Invalid Structured Response
Deadline Parse Failure
```

不能导致：

```text
Capture Failure
InboxItem Loss
AI Organizer Result Loss
Searchable Content Loss
Vector Index Loss
Search Failure
```

Action Extractor 是增强能力。

正确关系：

```text
Action Extraction Failure
≠
Inbox Failure
```

---

# 36. Action Reprocessing

如果 Action Extraction 被重新运行：

```text
Re-analyze
Retry
Manual Re-extract
```

必须检查当前业务状态。

原则：

```text
New AI Suggestion
```

不能自动覆盖：

```text
User-confirmed Todo
User-edited Deadline
Dismissed Candidate
Completed Task
```

如果已有 Attempt Ownership 机制适用于该流程，

应优先复用，

避免：

```text
Older Action Extraction
```

覆盖：

```text
Newer Action Extraction
```

---

# 37. V0.4 Scope Boundary

V0.4 Action Extractor 并不自动意味着：

```text
Google Calendar Integration
Calendar Sync
Push Notification
Reminder Scheduler
Recurring Task
Autonomous Agent
Workflow Engine
External Tool Execution
Email Sending
```

第一阶段优先完成：

```text
Detect
   ↓
Structure
   ↓
Present
   ↓
Confirm
```

之后再决定：

```text
Execute
```

---

# 38. V0.5 Relation Architecture

Task 41 已实现第一版 Relation Foundation：

```text
InboxItem A
      ↕
  RELATED_TO
      ↕
InboxItem B
```

当前契约：

```text
Endpoint          = InboxItem only
Relation Type     = RELATED_TO only
Directionality    = Symmetric
Canonical Pair    = left_inbox_item_id < right_inbox_item_id
Duplicate Guard   = UNIQUE(left, right, relation_type)
New Creation      = both endpoints currently ACTIVE
Archive           = relation row remains
Delete            = either endpoint cascades relation row
Persistence Owner = Java + MySQL
```

`content_relation` 是持久化派生产品状态。第一版没有 `RelationCandidate`、score、evidence、provider metadata
或 Relation processing state。创建服务按 Canonical ID 顺序锁住两个 InboxItem，使创建与 Archive/Delete
拥有明确顺序；数据库唯一约束是并发重复的最终防线。

Task 42 在这个持久化基础旁增加了独立的运行时候选流：

```text
ACTIVE Source InboxItem (MySQL)
        ↓
Existing Qdrant Point + stored vector
        ↓
Bounded nearest-neighbor search (no re-embedding)
        ↓
Candidate IDs + transient semantic score
        ↓
Java batch resolution through MySQL
        ↓
Filter self / missing / non-ACTIVE / existing RELATED_TO
        ↓
Final bounded RelationDiscoveryCandidate list
```

Qdrant 仍只是可重建候选基础设施，不是 InboxItem 或 Relation 的事实来源。请求 `limit` 为 `1..20`；Python 使用
`min(limit * 3, 100)` 的内部有界 over-fetch，Java 完成权威过滤后再应用最终 limit。Source Point 不存在返回
`sourceIndexed=false + results=[]`；Vector Store 关闭、Collection 缺失/不兼容、超时或不可用仍是受控基础设施错误。

Task 42 不调用 LLM，不把 `semanticScore` 写入数据库，不创建或确认 `content_relation`，也不增加产品 API、前端、自动发现或
Relation processing state。它没有修改 V0.3 Search Pipeline。出现关系数据仍不等于需要 Neo4j；只有真实出现复杂图遍历、
图原生查询或图算法需求时才重新评估。

Task 43 在 Task 42 的有限候选之后增加独立判断层：

```text
Task 42 bounded candidates
        ↓
Java revalidates ACTIVE items + existing relations
        ↓
Title + Summary + existing usable content
        ↓
Source <= 4,000 chars; Candidate <= 1,000 chars; Count <= 20
        ↓
One batch LLM call (semanticScore excluded)
        ↓
Strict relatedTargetInboxItemIds validation
        ↓
Runtime RELATED_TO suggestions only
```

Java/MySQL 仍负责 Source 与 Candidate 的当前存在性、ACTIVE 状态和既有 Relation 竞态过滤。Python 将正文视为不可信数据，
采用“精度优先、不确定则不关联”的 Prompt，只能从给定 Candidate ID 中返回结果。空列表是正常成功；未知、重复、Source ID、
多余字段或越界数量使整次 LLM 输出失效。Task 43 不调用 `ensureRelatedTo`，不写 `content_relation`，不新增产品 Controller、
前端、后台状态或 Migration；LLM 失败也不影响 Capture、Search、Todo 或既有 Relation。

Task 44 在不改变 Task 43 职责的前提下增加独立持久化编排：

```text
Task 43 bounded AI discovery (outside transaction)
        ↓
Validated RELATED_TO suggestions
        ↓
One short Java/MySQL transaction
        ↓
Batch-lock Source + Targets in canonical ID order
        ↓
Abort for invalid Source; skip invalid Targets
        ↓
One existing-Relation query + additive canonical inserts
        ↓
RelationPersistenceResult
```

最终事务重新确认 Source 和 Target 的存在性与 `ACTIVE` 状态。Source 已删除或归档时整次持久化失败；单个 Target 已删除、归档、
为 Source 自身、ID 无效或重复时只跳过该 Target。既有正向或反向 `RELATED_TO` 都视为幂等成功；Canonical Pair、顺序行锁和数据库
唯一约束共同保护并发。Task 44 从不根据空发现结果、Provider 失败或“本次未再次发现”删除旧 Relation，也不新增 Schema、产品 API、
前端、自动触发、Relation processing state、score、evidence 或 provider metadata。

Task 45 在写入链路之外建立独立产品读取路径：

```text
Persisted content_relation
        ↓
Validate ACTIVE Source in MySQL
        ↓
One bounded symmetric Relation JOIN
        ↓
ACTIVE related InboxItem filtering
        ↓
Stable relation recency ordering
        ↓
Minimal RelatedInboxItemResponse
        ↓
GET /api/inbox/{id}/related
```

读取查询用 `UNION ALL` 分别覆盖 Canonical Pair 的 left/right 索引方向，再 JOIN `inbox_item` 过滤 ACTIVE Target，并在数据库层应用
`limit`。Service 防御性过滤异常、自关联和重复 Target，只构建最多 300 Unicode Code Point 的预览与最小产品字段。Relation Read 与
Relation Discovery 是两条独立路径；普通 GET 不依赖 FastAPI、LLM、Embedding、Qdrant 或 Rerank，也不修改 `content_relation`。

Task 46 只在现有 Inbox 卡片上增加读取体验：

```text
User expands one InboxItem
        ↓
Lazy GET /api/inbox/{id}/related?limit=10
        ↓
Loading / Empty / Isolated Error / Success
        ↓
Compact Related Item rows
        ↓
Focus existing InboxItem card
        ↓
User Rediscovery
```

折叠的主 Inbox 列表不会为每条数据预取 Relation。任一时刻只展开一个 Related 面板；点击 Related Item 会聚焦当前已有的完整
Inbox 卡片，而不是嵌套新的详情视图。每个面板用请求序号丢弃关闭、重开或切换条目后的迟到响应。Related 文本使用 Vue 文本插值，
不使用 `v-html`，也不展示内部 Relation 方向、语义 Score、置信度或证据。

```text
UI Read
≠
AI Relation Discovery
```

浏览器只调用 Java Product API。展开、空结果和重试都不会调用 FastAPI、LLM、Embedding、Qdrant 或 Relation Discovery。

---

# 39. V1.0 Personal AI

Personal AI 应建立在稳定的：

```text
Capture
+
Understand
+
Organize
+
Retrieve
+
Action
+
Relations
```

之上。

未来可能形成：

```text
User Question
      ↓
LifeInbox Retrieval
      ↓
Personal Data
      ↓
AI Reasoning
      ↓
Personal Answer / Action
```

例如：

```text
我最近一个月主要在研究什么？
```

或者：

```text
我保存过哪些值得做成项目的东西？
```

这时 AI 才真正基于：

```text
Personal LifeInbox Data
```

工作。

Agent 不应该早于：

```text
Reliable Data
+
Reliable Retrieval
+
Reliable Action Boundary
```

---

# 40. Infrastructure Evolution

基础设施跟随真实需求增长。

当前：

```text
MySQL
+
Spring Boot
+
FastAPI
+
Qdrant
```

如果未来出现：

```text
需要 Cache / Coordination
→ Redis

需要可靠异步任务
→ MQ / Persistent Queue

需要复杂 Graph Traversal
→ Evaluate Graph Database
```

再选择对应技术。

因此：

```text
进入 V0.4
≠
必须加入 Redis

做 Todo
≠
必须加入 MQ

做 Deadline
≠
必须立刻接 Calendar

做 Relations
≠
必须加入 Neo4j

使用 Qdrant
≠
整个系统必须围绕 Vector Database 设计
```

---

# 41. Browser Extension

Browser Extension 仍然属于未来重要的 Capture Enhancement。

目标体验：

```text
看到网页
 ↓
Click Save
 ↓
LifeInbox
```

它最初曾出现在较早版本规划中，

现在被延后，

但这属于：

```text
Priority Adjustment
```

不是：

```text
Architecture Drift
```

目前优先完成核心闭环：

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

之后再增强 Capture 入口。

---

# 42. Repository Architecture

LifeInbox 保持 Monorepo：

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
│   └── Future browser Capture enhancement
│
├── deploy/
│   └── Deployment-related configuration
│
├── AGENTS.md
├── README.md
└── .gitignore
```

未来模块只有在出现真实需求时才创建。

不要因为总体架构里存在：

```text
todo
deadline
action
relation
agent
rag
memory
```

就提前创建大量空模块。

---

# 43. Current Capability Boundary

## Capture

当前已完成：

```text
TEXT
URL
FILE
IMAGE
```

---

## AI Understanding

当前已完成：

```text
Summary
Category
Tags
Keywords
Entities
```

---

## Content Extraction

当前根据真实实现主要包含：

```text
URL
→ Web Content Extraction

FILE
→ Supported Document Text Extraction

IMAGE
→ OCR

TEXT
→ Original Content
```

复杂 JavaScript Rendering、扫描 PDF OCR、General Vision 等能力是否支持，

以当前实现和相关文档为准，

不要把计划能力描述成已完成。

---

## Background AI

当前：

```text
Spring In-Process
Bounded Executor
```

不是：

```text
Persistent MQ Worker System
```

---

## Search

当前：

```text
V0.3 Smart Search
✅ Completed
```

已完成主要能力：

```text
Keyword Search
AI-derived Field Search
Filter
Basic Ranking
Safe Highlight
Searchable Content
Embedding Pipeline
Qdrant Vector Index
Semantic Search
Hybrid Search
RRF
Rerank
Failure Degradation
Final Acceptance
```

V0.3 边界：

```text
Retrieve
```

不包含：

```text
RAG
Agent
Action Extractor
Relations
```

---

## Action

当前：

```text
V0.4 Action Extractor
✅ Completed
```

已完成：

```text
Action Extraction
Action Candidate Persistence
Accept / Dismiss
Todo OPEN / COMPLETED Lifecycle
Source Traceability
```

Reminder 与 Calendar 仍未实现。

---

## Relations

当前：

```text
🚧 V0.5 Current
✅ Task 1 Relation Persistence Foundation
✅ Task 2 Bounded Relation Candidate Discovery
✅ Task 3 AI Relation Discovery Foundation
✅ Task 4 Relation Persistence Integration
✅ Task 5 Related Items Product API
✅ Task 6 Frontend Related Items UI
```

当前已具备 Java/MySQL 核心模型、Task 42 有界候选、Task 43 运行时 AI 判断、Task 44 非破坏性正式 Relation 转换、Task 45 只读 Product API，以及 Task 46 懒加载 Related Items UI；自动处理仍未实现。

---

## Personal AI

当前：

```text
📋 Planned
```

---

# 44. Architecture Priority Hierarchy

发生设计冲突时，

优先级应保持：

```text
User-confirmed Business State
            ↓
Current MySQL Business Data
            ↓
Current Repository Implementation
            ↓
Current Architecture Rules
            ↓
AI-generated Suggestions
            ↓
Old Planning Documents
```

其中：

```text
MySQL
```

继续拥有业务事实。

AI 输出属于：

```text
Derived / Suggested Information
```

除非经过明确业务流程转化为已确认状态。

---

# 45. Long-Term Architecture Principle

LifeInbox 的架构不应该由：

```text
RAG
Agent
MCP
GraphRAG
Multi-Agent
Neo4j
Kafka
Redis
Qdrant
Workflow
```

这些技术名字决定。

应该由真实问题决定：

```text
怎么更快 Capture？
        ↓
怎么更准确 Understand？
        ↓
怎么减少手工 Organize？
        ↓
怎么真正 Retrieve？
        ↓
怎么把信息变成 Action？
        ↓
怎么发现信息之间的 Relations？
        ↓
什么时候 Personal AI 才真正有价值？
```

整个系统继续围绕：

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

逐步演进。

优先：

```text
一个简单、可靠、
真正能够每天使用的 LifeInbox
```

而不是：

```text
一个拥有大量 AI 技术名词，
但每一项功能都只完成 30% 的系统。
```

---

# 46. 当前架构结论

截至 V0.4 完成并进入 V0.5 Task 5 后，

LifeInbox 已经形成：

```text
Universal Capture
        ↓
AI Understanding
        ↓
Structured Organization
        ↓
Searchable Content
        ↓
Keyword + Semantic Retrieval
        ↓
Hybrid / RRF
        ↓
Rerank
        ↓
Reliable Retrieve
```

V0.4 已在此基础上完成：

```text
Reliable Retrieve
       ↓
Understand Action Intent
       ↓
Structured Action Candidate
       ↓
User Confirmation
       ↓
Todo / Deadline
```

V0.5 Task 1 到 Task 5 进一步建立：

```text
InboxItem
   ↕
RELATED_TO
   ↕
InboxItem
   ↓
Java / MySQL Persisted Relation State
        +
Bounded Candidate Discovery
        +
Runtime AI RELATED_TO Suggestions
        ↓
Additive / Idempotent Persistence
        ↓
Bounded MySQL Related Items Product API
        ↓
Lazy Vue Related Items Section
        ↓
User Rediscovery
```

因此当前架构主线仍然没有偏离最初设计。

变化主要来自实际开发过程中的合理演进：

```text
Qdrant
从 Future Infrastructure
变成真实 Derived Retrieval Index

Redis
因为没有真实需求
没有被强行加入

Hybrid Search
从最初简单概念
发展为 RRF + Failure Degradation

Rerank
从概念阶段
发展为独立可选 Provider Capability

Browser Extension
从早期优先项
调整为未来 Capture Enhancement
```

这些变化都符合 LifeInbox 最核心的架构原则：

```text
Requirement First
Technology Second
```

以及：

```text
Capture First,
Organize Later.
```
