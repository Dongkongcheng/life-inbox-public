# LifeInbox 架构

## 1. 产品主线

LifeInbox 是一个 AI 驱动的个人信息收件箱。

它解决的核心问题不是单纯建立一个“知识库”，而是：

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
V0.3 — Smart Search / Retrieve
```

---

# 2. 核心架构原则

LifeInbox 始终遵循：

```text
Capture First, Organize Later.
```

也就是：

> Capture 必须首先可靠完成，AI 和其他高级能力都是后续增强。

即使以下服务不可用：

```text
FastAPI
LLM
OCR
网页正文提取
未来 Embedding
未来 Vector Search
未来 Reranker
```

也不能让基础 Inbox 完全不可使用。

---

# 3. 当前实际架构

当前项目实际架构：

```text
                         LifeInbox
                              │
                              ▼
                        Vue 3 / Vite
                              │
                         Product API
                              │
                              ▼
                    Spring Boot / Java 21
                              │
              ┌───────────────┼───────────────┐
              │               │               │
              ▼               ▼               ▼
            MySQL       Local Uploads       FastAPI
       Source of Truth    FILE / IMAGE       AI Engine
                                              │
                                ┌─────────────┼─────────────┐
                                │             │             │
                                ▼             ▼             ▼
                           URL Extractor  Doc Extractor      OCR
                                │             │             │
                                └─────────────┼─────────────┘
                                              ▼
                                        AnalyzeService
                                              │
                                   Structured LLM Request
                                              │
                                              ▼
                                         AnalyzeResult
                                              │
                            ┌─────────────────┼─────────────────┐
                            ▼                 ▼                 ▼
                         Summary       Category / Tags    Keywords / Entities
```

当前核心基础设施：

```text
Vue
+
Spring Boot
+
MySQL
+
FastAPI
+
Local File Storage
```

目前没有为了长期 Roadmap 提前加入：

```text
Redis
MQ
Qdrant
Milvus
Elasticsearch
Neo4j
```

---

# 4. 长期目标架构

随着 LifeInbox 逐渐发展，目标架构可能演进为：

```text
                              LifeInbox
                                  │
                         ┌────────┴────────┐
                         │     Vue 3       │
                         │   Web / PWA     │
                         └────────┬────────┘
                                  │
                           REST / Future SSE
                                  │
                                  ▼
                       Spring Boot / Java
                                  │
             ┌────────────────────┼────────────────────┐
             │                    │                    │
             ▼                    ▼                    ▼
           MySQL             File Storage       AI Integration
      Business Source             │                    │
          of Truth                │                    ▼
             │                    │                 FastAPI
             │                    │                AI Engine
             │                    │                    │
             │                    │        ┌───────────┼───────────┐
             │                    │        ▼           ▼           ▼
             │                    │     Parser      Embedding    Reranker
             │                    │        │           │           │
             │                    │        └───────────┼───────────┘
             │                    │                    │
             │                    │                    ▼
             │                    │          Future Vector Store
             │                    │
             └────────────────────┴────────────────────┘
```

未来在出现真实需求后，可能逐步加入：

```text
Redis
→ Cache / Coordination / Task Support

Vector Store
→ Semantic Retrieval Index

MQ
→ Persistent Asynchronous AI Tasks
```

这些是：

```text
Future Infrastructure
```

不是当前架构的必需组件。

---

# 5. Java 与 Python 的边界

这是 LifeInbox 最重要的架构边界之一。

## Java / Spring Boot

Java 负责：

> 这个产品本身。

包括：

```text
InboxItem
业务数据
MySQL
文件元数据
Capture
Favorite
Archive
Delete
AI Processing State
AI Attempt
AI Result Persistence
Product API
Search API
Future Todo
Future Deadline
Future Auth / Permission
```

Java 是：

```text
Business Source of Truth
```

---

## Python / FastAPI

Python 负责：

> 理解这些信息。

当前包括：

```text
URL 正文提取
文档解析
OCR
Summary
Category
Tags
Keywords
Entities
Structured Analyze
```

未来可以逐渐加入：

```text
Vision
Embedding
Semantic Retrieval
Rerank
Relation Discovery
Action Extraction
Deadline Extraction
```

Python 不应该：

```text
拥有 InboxItem 业务状态
实现一套独立 InboxItem CRUD
成为业务数据库 Source of Truth
```

核心关系始终保持：

```text
Java：
这里有一条信息。

Python：
我来理解这条信息是什么意思。
```

---

# 6. 统一 Inbox 模型

LifeInbox 最重要的业务模型是：

```text
InboxItem
```

当前内容类型：

```text
TEXT
URL
FILE
IMAGE
```

未来如果出现真实需求，还可以增加其他类型。

但是原则始终是：

```text
不同来源
    ↓
统一进入 Inbox
    ↓
再进行后续处理
```

不要变成：

```text
网页一套核心模型
PDF 一套核心模型
图片一套核心模型
笔记一套核心模型
GitHub 一套核心模型
```

不同内容可以有不同的：

```text
Parser
Extractor
OCR
Processing Strategy
```

但是业务层仍然围绕：

```text
InboxItem
```

组织。

---

# 7. Unified Analyze Pipeline

V0.2 已经形成统一 Analyze Pipeline：

```text
TEXT ───────────────────────┐
                            │
URL   → Web Extraction ─────┤
                            │
FILE  → Document Extraction ┤
                            │
IMAGE → OCR ────────────────┤
                            ▼
                     AnalyzeService
                            │
                            ▼
                     AnalyzeResult
```

四种类型主要区别发生在：

```text
Content Preparation
```

阶段。

得到可分析文本后，

统一进入：

```text
AnalyzeService
```

并生成：

```text
Summary
Category
Tags
Keywords
Entities
```

原则上使用一次结构化 Analyze，

而不是：

```text
Summary 调一次 LLM
Category 调一次 LLM
Tags 再调一次 LLM
Keywords 再调一次 LLM
Entities 再调一次 LLM
```

---

# 8. Capture 与 AI 的解耦

Capture 的优先级始终高于 AI Analyze。

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
Background Analyze
```

而不是：

```text
User Capture
     ↓
等待 OCR
     ↓
等待网页
     ↓
等待 LLM
     ↓
最后才保存
```

这保证了：

```text
AI Failure
≠
Capture Failure
```

---

# 9. Automatic Analyze

当前 Automatic Analyze 使用：

```text
@TransactionalEventListener(AFTER_COMMIT)
```

数据库提交以后才触发后台分析：

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

当前使用 Spring 进程内有界线程池。

当前设计重点是：

```text
简单
可理解
适合个人项目
```

而不是提前引入复杂任务基础设施。

未来只有当：

```text
任务不能丢
AI 任务规模明显增加
需要跨进程 Worker
需要可靠重试
```

等真实需求出现时，

才考虑：

```text
Redis
MQ
Persistent Task Queue
```

---

# 10. AI Processing State

当前 AI Processing 状态：

```text
NOT_PROCESSED
PROCESSING
SUCCESS
FAILED
```

基本状态流：

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

# 11. Transaction 与 Attempt Guard

AI 外部调用不能占用数据库长事务。

当前流程：

```text
短事务：
领取 Processing Ownership
写 PROCESSING + attemptId
        ↓
事务提交
        ↓
事务外：
URL / FILE / OCR / FastAPI / LLM
        ↓
短事务：
匹配 attemptId
保存 AnalyzeResult
写 SUCCESS / FAILED
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

如果 A 超时并成为 stale，

新的：

```text
Attempt B
id = BBB
```

可以接管。

如果旧 A 后来返回：

```text
AAA != 当前 BBB
```

它不能：

```text
覆盖 B 的结果
把 B 改成 SUCCESS
把 B 改成 FAILED
```

因此迟到结果不能覆盖新的 Processing Ownership。

---

# 12. AI Failure Degradation

AI Failure 不能破坏原始数据。

如果：

```text
FastAPI Down
LLM Error
Timeout
URL Extraction Failure
PDF Extraction Failure
OCR Failure
```

则：

```text
Original InboxItem
```

仍然必须存在。

Re-analyze 开始时：

也不会先删除已有：

```text
Summary
Category
Tags
Keywords
Entities
```

只有完整的新 AnalyzeResult 成功后，

才替换旧结果。

因此：

```text
新的 Analyze 失败
```

不会自动导致：

```text
旧的成功结果消失
```

---

# 13. V0.3 Search Architecture

V0.3 的目标是：

> 用户记得内容讲了什么，即使忘记准确标题和关键词，也能重新找到它。

因此 V0.3 最终不能停留在：

```sql
WHERE title LIKE '%keyword%'
```

长期目标：

```text
User Query
    │
    ├─────────────────┐
    ▼                 ▼
Keyword            Semantic
Search              Search
    │                 │
    └────────┬────────┘
             ▼
       Hybrid Retrieval
             ↓
           Rerank
             ↓
       Final Results
```

---

# 14. V0.3 渐进式 Search Pipeline

Search 不一次性完成。

推荐演进：

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
Vector Retrieval
        ↓
Semantic Search
        ↓
Hybrid Search
        ↓
Rerank
```

已实现第一步：

```text
Basic Keyword Search
```

只是 V0.3 的起点，

不是 V0.3 的最终完成条件。

---

# 15. 当前 Keyword Search 架构

Task 1 当前实现：

```text
Vue Search UI
      ↓
Spring Boot Search API
      ↓
MySQL
      ↓
Keyword Search
      ↓
InboxItem Results
```

当前只查询业务主表中已持久化的：

```text
title
content
summary
category
```

查询由 Spring Boot 参数化调用 MySQL，限制为 ACTIVE，并按创建时间倒序返回现有 InboxItem 表示。
tags、keywords、entities 等字段留给后续独立 Task。

不要一次把所有 Search 功能全部实现。

---

# 16. Future Semantic Search

后续可能发展为：

```text
User Query
     ↓
Spring Boot
     │
     ├──────────────→ MySQL
     │                Keyword Search
     │
     └──────────────→ FastAPI
                      Query Embedding
                           ↓
                      Vector Store
                           ↓
                     Semantic Results
```

随后：

```text
Keyword Results
       +
Semantic Results
       ↓
Candidate Merge
       ↓
Rerank
       ↓
Final Results
```

例如：

```text
Query:
那个讲 Redis 防止重复请求的文章
```

即使原文没有完全相同的关键词，

Semantic Retrieval 仍应有机会根据：

```text
幂等
Redis Lua
分布式锁
Redisson
```

等概念找到相关内容。

---

# 17. Search Data Ownership

即使未来加入 Vector Store：

MySQL 仍然是：

```text
Business Source of Truth
```

关系：

```text
MySQL
=
Authoritative Business Data

Vector Store
=
Derived / Rebuildable Retrieval Index
```

Vector Store 可以保存：

```text
InboxItem ID
Embedding
Minimal Retrieval Metadata
```

但是不能成为：

```text
InboxItem Business Database
```

如果 Vector Index 全部丢失：

```text
LifeInbox Business Data
```

仍然必须完整存在。

理论上应该能够：

```text
MySQL
 ↓
重新生成 Embedding
 ↓
重建 Vector Index
```

---

# 18. Search Result Ownership

未来 Semantic Search 可能返回：

```text
inboxItemId
score
```

例如：

```text
123 → 0.92
456 → 0.86
```

这些只是：

```text
Retrieval Candidates
```

最终业务结果应该由 Java 根据：

```text
inboxItemId
```

重新解析真实：

```text
InboxItem
```

也就是：

```text
Vector Search
      ↓
Candidate IDs
      ↓
Spring Boot
      ↓
MySQL
      ↓
Authoritative InboxItem
```

---

# 19. Search Failure Degradation

未来如果：

```text
Embedding Service
Vector Store
Semantic Search
Reranker
```

发生故障，

应该尽量降级为：

```text
Keyword Search
```

而不是：

```text
整个 Search API 完全不可用
```

同时 Search 故障不能破坏：

```text
Capture
Inbox
Favorite
Archive
Delete
Existing AI Results
```

---

# 20. Search 与 RAG 的边界

V0.3 当前目标是：

```text
Retrieve
```

Search 回答：

```text
哪些保存过的信息和 Query 相关？
```

RAG 回答：

```text
根据检索出的信息生成什么答案？
```

两者不是同一个能力。

因此加入：

```text
Embedding
Vector Search
Semantic Search
```

并不意味着应该自动加入：

```text
RAG
Chat
Agent
Memory
MCP
```

这些能力应该在 Retrieve 足够可靠以后再考虑。

---

# 21. V0.4 Action Architecture

V0.4 将在已有 AI Understanding 基础上增加：

```text
Captured Content
       ↓
Content Preparation
       ↓
Action Extraction
       ↓
Structured Action Candidate
       ↓
Java Business Confirmation
       ↓
Todo / Deadline
```

例如：

```text
软件工程课程设计
8月25日前交报告
```

Python 可以返回：

```json
{
  "has_action": true,
  "action_type": "deadline",
  "title": "提交软件工程课程设计报告",
  "deadline": "2026-08-25"
}
```

Python 负责：

```text
识别 Action
```

Java 负责：

```text
Todo / Deadline 的业务状态和持久化
```

---

# 22. V0.5 Relation Architecture

V0.5 可以逐渐建立：

```text
InboxItem
    │
    ├── RELATED_TO
    ├── SAME_TOPIC
    └── SUPPORTS
```

初期优先使用：

```text
MySQL
```

例如：

```text
content_relation
────────────────
source_id
target_id
relation_type
score
```

不要因为出现内容关系就立即引入：

```text
Neo4j
```

只有真正需要复杂图遍历或图查询时，

再评估 Graph Database。

---

# 23. V1.0 Personal AI

Personal AI 应建立在已经稳定的：

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

未来：

```text
User Question
      ↓
LifeInbox Search
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
我以前保存过哪些值得做成项目的东西？
```

这时 AI 才真正基于：

```text
Personal LifeInbox Data
```

工作。

Agent 不应该早于可靠 Search 和可靠个人数据。

---

# 24. Infrastructure Evolution

基础设施应该跟着真实问题增长。

当前：

```text
MySQL
+
Spring Boot
+
FastAPI
```

未来如果出现明确问题：

```text
需要 Cache / Coordination
→ Redis

需要 Semantic Retrieval
→ Vector Store

需要可靠异步任务
→ MQ / Persistent Queue
```

不要按固定阶段强行安装技术。

例如：

```text
进入 V0.3
≠
必须安装 Redis

做 Semantic Search
≠
必须同时安装 MQ

做 Relations
≠
必须安装 Neo4j
```

始终坚持：

```text
Requirement
   ↓
Choose Infrastructure
```

而不是：

```text
Choose Technology
   ↓
再寻找使用场景
```

---

# 25. Repository Architecture

LifeInbox 保持 Monorepo：

```text
life-inbox/
├── web/                 # Vue frontend
├── server/              # Spring Boot product backend
├── ai-engine/           # FastAPI AI engine
├── docs/                # Architecture / DB / API / Roadmap
├── extension/           # Future browser extension
├── deploy/              # Future deployment
├── AGENTS.md
├── README.md
└── .gitignore
```

未来的模块只有在出现真实实现时才创建。

不要因为总体规划中存在：

```text
search
todo
relation
embedding
reranker
action
```

就提前创建大量空目录。

---

# 26. Browser Extension

最初规划中的 Browser Extension 仍然属于有价值的 Capture Enhancement。

未来目标：

```text
看到网页
 ↓
点击 Save
 ↓
LifeInbox
```

但是它和当前：

```text
V0.3 Smart Search
```

没有直接依赖关系。

因此当前没有实现 Browser Extension，

不代表整体架构发生偏移。

只是优先级被延后。

---

# 27. Current Capability Boundary

## Capture

当前：

```text
TEXT
URL
FILE
IMAGE
```

---

## AI Understanding

当前：

```text
Summary
Category
Tags
Keywords
Entities
```

---

## URL

当前主要支持：

```text
Static HTML
```

暂不执行复杂 JavaScript Rendering。

---

## FILE

当前主要支持：

```text
TXT
Markdown
Text-layer PDF
```

扫描 PDF 暂不 OCR。

---

## IMAGE

当前：

```text
OCR-based Analyze
```

不是：

```text
General Vision
```

---

## Background AI

当前：

```text
Spring In-Process Bounded Executor
```

不是：

```text
Persistent MQ
```

---

## Search

当前正在进入：

```text
V0.3 Smart Search
```

第一步：

```text
Basic Keyword Search
```

后续目标：

```text
Embedding
Semantic Search
Hybrid Search
Rerank
```

---

## Future

当前尚未进入：

```text
Todo / Deadline
Relations
Personal RAG
Personal Agent
MCP
Knowledge Graph
```

---

# 28. Long-Term Architecture Principle

LifeInbox 的架构不应该由：

```text
RAG
Agent
MCP
GraphRAG
Multi-Agent
Neo4j
Kafka
Qdrant
Redis
```

这些技术名称决定。

应该由真实产品问题决定：

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
```

整个系统始终围绕：

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
一个简单、可靠、真正能够每天使用的 LifeInbox
```

而不是：

```text
一个拥有大量 AI 技术名词，
但每项功能都没有真正完成的系统。
```
