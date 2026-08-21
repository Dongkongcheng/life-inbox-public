# LifeInbox Repository Instructions

## Project

LifeInbox is an AI-driven personal information inbox.

Its purpose is to help the user:

* Capture useful information quickly.
* Understand captured information automatically.
* Organize information with minimal manual work.
* Retrieve information later even when exact wording is forgotten.
* Turn useful information into future actions.

The long-term product flow is:

```text
Capture → Understand → Organize → Retrieve → Action
```

The core principle is:

```text
Capture First, Organize Later.
```

LifeInbox is not primarily a traditional knowledge-base product.

It is designed around several practical problems:

* Useful information is often not saved because organizing it takes effort.
* Saved information is often never found again.
* More captured information makes relationships harder to see.
* Tasks and deadlines hidden inside information are easy to forget.

All major product decisions should remain aligned with this flow.

---

# Current Development Stage

Current stage:

```text
V0.3 — Smart Search / Retrieve
```

Current roadmap:

```text
V0.1 — Universal Inbox        ✅ Completed
V0.2 — AI Organizer           ✅ Completed
V0.3 — Smart Search           🚧 Current
V0.4 — Action Extractor       📋 Planned
V0.5 — Relations              📋 Planned
V1.0 — Personal AI            📋 Planned
```

The current six-version roadmap is authoritative.

Older planning notes may contain broader or overlapping version definitions.

Do not reintroduce old version boundaries if they conflict with the current roadmap.

---

# Version Goals

## V0.1 — Universal Inbox

Goal:

```text
Anything useful can be captured quickly.
```

Core capabilities include:

* TEXT
* URL
* FILE
* IMAGE
* Inbox listing
* Favorite
* Archive
* Delete
* Basic file/image handling

Capture must work without AI.

---

## V0.2 — AI Organizer

Goal:

```text
The user saves information.
AI helps understand and organize it.
```

Existing capabilities may include:

* Java ↔ FastAPI integration
* URL content extraction
* Document text extraction
* OCR
* Summary
* Category
* Tags
* Keywords
* Entities
* Structured AnalyzeResult
* AI processing state
* Manual Analyze
* Retry
* stale PROCESSING Recovery
* Attempt Guard
* Automatic Analyze after Capture

Preserve these capabilities while developing later versions.

---

## V0.3 — Smart Search

Goal:

```text
Previously captured information can actually be found again.
```

The long-term V0.3 search direction is:

```text
User Query
    │
    ├──────────────┐
    ▼              ▼
Keyword         Semantic
Search           Search
    │              │
    └──────┬───────┘
           ▼
      Hybrid Result
           ▼
         Rerank
           ▼
         Result
```

Keyword Search is the first implementation step.

Current implementation status:

```text
V0.3 Task 1 — Basic Keyword Search  ✅ Completed
V0.3 Task 2 — AI-derived Field Search  ✅ Completed
```

The Vue UI calls `GET /api/search?q=<keyword>` through Spring Boot. MySQL matches
ACTIVE InboxItems on persisted `title`, `content`, `summary`, `category`, `tags`, `keywords`,
or `entities`. Relation-table matches use `EXISTS`, so one item remains one result even when
several AI-derived values match. Semantic retrieval, hybrid retrieval, and reranking remain future work.

It is not the final definition of V0.3.

V0.3 should gradually move toward meaningful semantic retrieval so that queries do not depend entirely on exact words.

Example:

```text
Query:
那个讲 Redis 防止重复请求的文章

Possible related concepts:
幂等
分布式锁
Redis Lua
Redisson
```

The system should eventually be able to retrieve conceptually related information even when exact query words are absent from the title.

Implement this incrementally.

Do not build the whole Search stack in one task.

---

## V0.4 — Action Extractor

Goal:

```text
Information can become actionable.
```

Potential future capabilities include:

* Todo extraction
* Deadline extraction
* Reminder candidates
* Action confirmation
* Structured action schema

Example:

```text
Captured content:
软件工程课程设计
8月25日前交报告

Potential Action:
提交软件工程课程设计报告
Deadline: 2026-08-25
```

Do not implement Action features during V0.3 unless explicitly requested.

---

## V0.5 — Relations

Goal:

```text
Discover useful relationships between captured information.
```

Possible future model:

```text
content_relation
source_id
target_id
relation_type
score
```

Prefer simple relational storage first.

Do not introduce Neo4j merely because relations exist.

---

## V1.0 — Personal AI

Goal:

```text
AI can work with the user's accumulated personal information.
```

Possible future capabilities include:

* Personal information analysis
* Personal RAG
* Conversational retrieval
* Long-term topic summaries
* Project discovery
* Personal Agent capabilities

Agent functionality should come after useful personal data and retrieval capabilities exist.

Do not build Agent infrastructure prematurely.

---

# Architecture

Frontend:

* Vue 3
* Vite

Backend:

* Java 21
* Spring Boot 4.1
* MyBatis-Plus
* MySQL

AI Engine:

* Python
* FastAPI

Current architecture:

```text
Vue
 ↓
Spring Boot
 ↓
├── MySQL
├── Local File Storage
└── FastAPI
      ↓
   Parser / OCR / AI
```

Future infrastructure may include:

```text
Redis
Vector Store
MQ
```

but only when a current problem requires it.

Do not introduce infrastructure merely because it appears in the long-term architecture.

---

# Java and Python Responsibilities

This boundary is one of the most important architectural rules in LifeInbox.

## Java / Spring Boot

Java owns:

```text
The Product
```

Typical responsibilities:

* InboxItem
* Business data
* MySQL persistence
* File metadata
* Favorite
* Archive
* Delete
* Product APIs
* Search APIs
* Business validation
* AI processing state
* AI Attempt ownership
* AI Result persistence
* Future Todo / Deadline business state
* Future permissions and authentication
* Business-level error handling

Java is the:

```text
Business Source of Truth
```

Do not move core business ownership into Python.

---

## Python / FastAPI

Python owns:

```text
Understanding the Information
```

Typical responsibilities:

* Web content extraction
* Document parsing
* OCR
* Future image understanding
* Summarization
* Classification
* Tagging
* Keyword extraction
* Entity extraction
* Embedding
* Semantic retrieval support
* Reranking
* Future relation discovery
* Future Action extraction
* Future Deadline extraction

Python should return structured results to Java.

Python must not become an independent owner of InboxItem business data.

Do not duplicate InboxItem CRUD in Python.

---

# Core Data Model

The most important business model is:

```text
InboxItem
```

All captured information should first enter the unified Inbox.

Examples:

```text
TEXT
URL
FILE
IMAGE
```

Future types may exist when there is a real requirement.

Do not create independent core models such as:

```text
TextItem
UrlItem
FileItem
ImageItem
```

without a proven need.

Different content types may use different parsing or processing strategies,

but they should still belong to the unified Inbox model.

---

# Existing Repository Is the Current Source of Truth

The original roadmap contains conceptual field names such as:

```text
raw_content
file_id
created_at
```

The implemented repository may use different names.

Do not rename working database fields, APIs, entities, or services merely to match old planning documents.

When implementation and old planning terminology differ:

1. Understand the existing code.
2. Preserve working behavior.
3. Follow current repository conventions.
4. Change the model only when a current requirement justifies it.

Architecture principles matter more than matching old placeholder names exactly.

---

# Capture Principle

Capture is the foundation of LifeInbox.

The system should prioritize:

```text
See something useful
        ↓
Capture it quickly
        ↓
Organize later
```

AI must never become a prerequisite for Capture.

If:

* FastAPI is down
* LLM is unavailable
* OCR fails
* URL parsing fails
* Semantic Search is unavailable

the user should still be able to preserve original information whenever possible.

---

# AI Failure Principle

AI is an enhancement.

It is not the source of truth.

If AI processing fails:

* Original InboxItem must remain.
* Existing successful AI results should not be destroyed unnecessarily.
* Capture should remain available.
* Favorite should remain available.
* Archive should remain available.
* Delete should remain available.
* Basic Inbox browsing should remain available.

Prefer graceful degradation.

---

# AI Processing Principle

AI processing should not create long database transactions around:

* HTTP requests
* OCR
* Document extraction
* LLM calls

Continue using short business transactions and explicit AI processing ownership.

Preserve existing mechanisms such as:

```text
AI Status
Attempt ID
stale Recovery
Late Result Protection
```

when they already exist.

Do not rewrite these systems as part of unrelated Search work.

---

# Search Architecture

Search is one of the core product capabilities of V0.3.

The target direction is:

```text
Keyword Search
      +
Semantic / Vector Search
      ↓
Hybrid Retrieval
      ↓
Rerank
      ↓
Final Results
```

Implement this gradually.

A likely progression is:

```text
Basic Keyword Search
        ↓
AI-derived Field Search
        ↓
Filtering / Ranking
        ↓
Searchable Content
        ↓
Embedding
        ↓
Vector Search
        ↓
Semantic Search
        ↓
Hybrid Search
        ↓
Rerank
```

Each task should implement only its own step.

---

# Search Responsibilities

## Java

Java should normally own:

* Product-facing Search API
* Query validation
* Business filters
* Pagination
* Keyword Search coordination
* Result composition
* Resolving search candidates back to InboxItem
* Search degradation behavior

Frontend should normally use:

```text
Vue
 ↓
Spring Boot Search API
```

not:

```text
Vue
 ↓
FastAPI
```

---

## Python

Python may support:

* Embedding generation
* Query embedding
* Semantic retrieval
* Reranking
* Future query understanding

Python should return retrieval information to Java.

Java should remain responsible for final product-level results.

---

# Search Data Ownership

MySQL remains:

```text
Business Source of Truth
```

A future Vector Store is:

```text
Derived Retrieval Index
```

A vector store may contain:

* InboxItem ID
* Embedding vector
* Minimal retrieval metadata

It should not become the authoritative owner of:

* InboxItem
* Favorite
* Archive
* Business status
* Original content ownership
* AI processing state

Conceptually:

```text
MySQL
=
Authoritative Business Data

Vector Store
=
Rebuildable Search Index
```

If the vector index is lost,

the original LifeInbox data must remain intact.

---

# Search Quality Principle

Do not consider Search complete merely because:

```text
WHERE title LIKE '%keyword%'
```

works.

Basic Keyword Search is useful and should be built first,

but V0.3 ultimately aims to solve:

```text
I remember what the information meant,
but I do not remember its exact title or words.
```

When semantic retrieval is introduced:

evaluate whether conceptually related content can actually be found.

Do not use an LLM to scan the entire Inbox for every Search request.

Use appropriate retrieval techniques.

---

# Search Failure Principle

Semantic Search is an enhancement to retrieval.

If future:

* Embedding service
* Vector Store
* Semantic retrieval
* Reranker

fails,

Keyword Search should continue working when possible.

Search failures must not break:

* Capture
* Inbox
* Favorite
* Archive
* Delete
* Existing AI results

---

# Search Is Not Automatically RAG

V0.3 focuses on:

```text
Retrieve
```

Search asks:

```text
Which saved items are relevant?
```

RAG asks:

```text
What answer should an LLM generate using retrieved items?
```

These are different capabilities.

Do not automatically add:

* RAG
* Chat with your data
* Agent
* Memory
* MCP

because Embedding or Vector Search is introduced.

Those capabilities belong to later product stages unless a task explicitly requires them.

---

# Infrastructure Principle

Do not build the project around middleware.

Start with the simplest infrastructure that solves the current problem.

Current core infrastructure:

```text
MySQL
+
Spring Boot
+
FastAPI
```

Future infrastructure may include:

```text
Redis
Vector Store
MQ
```

but only when justified.

Do not automatically add:

* Redis
* Kafka
* RabbitMQ
* RocketMQ
* Elasticsearch
* Milvus
* Qdrant
* Neo4j

just because they appear in the long-term roadmap.

Prefer:

```text
Requirement first
Infrastructure second
```

not:

```text
Technology first
Problem later
```

---

# Authentication and Multi-user Features

The long-term architecture may include:

* User
* Authentication
* Permissions

However, LifeInbox is currently primarily developed as a personal product.

Do not introduce a large authentication or RBAC system unless the current task requires it.

Existing `userId` or related fields may be preserved for future expansion.

Do not allow future multi-user requirements to unnecessarily complicate current personal workflows.

---

# Browser Extension

A browser extension remains a useful future Capture enhancement.

Its purpose would be:

```text
See webpage
 ↓
Click Save
 ↓
LifeInbox
```

However, it is not required for current V0.3 Search development.

Do not create Chrome extension functionality during Search tasks unless explicitly requested.

---

# Development Principles

* Read existing code before making changes.
* Understand existing behavior before redesigning it.
* Work in small increments.
* Prefer the simplest implementation that solves the current task.
* Do not refactor unrelated code.
* Preserve existing working behavior.
* Reuse existing services and patterns where appropriate.
* Avoid speculative abstractions.
* Keep dependencies minimal.
* Do not introduce infrastructure without a real requirement.
* Run relevant tests or builds.
* Never claim verification that was not actually performed.
* Do not hardcode secrets.
* Do not commit passwords, API keys, tokens, or private credentials.
* When the task is complete, stop.

---

# Codex Working Model

Codex should behave like a developer implementing one GitHub Issue at a time.

Preferred workflow:

```text
Read Repository
      ↓
Understand Current Task
      ↓
Report Short Plan
      ↓
Implement
      ↓
Test / Build
      ↓
Report Result
      ↓
Stop
```

Codex should not automatically continue to the next roadmap feature.

---

# Responsibility Split

The user owns:

* Product direction
* Architecture
* Core data model
* Java / Python boundaries
* Search Pipeline
* AI task state design
* Action Extractor schema
* Permission strategy
* Failure strategy
* Major infrastructure decisions
* Final review

Codex may implement:

* Controller
* Service
* Mapper
* DTO
* SQL
* Tests
* Vue UI
* FastAPI endpoints
* Bug fixes
* Small refactors

Codex should accelerate implementation.

It should not independently redefine LifeInbox.

---

# Code Comments

Important code should contain concise Chinese comments.

Use comments especially for:

* Important class responsibilities
* Important Controller / Service methods
* Non-obvious business logic
* AI processing state changes
* Attempt Guard
* Failure degradation
* Security checks
* Search ranking logic
* Search fallback logic
* Java / Python integration
* Vue state and request logic

Prefer comments that explain:

```text
Why
```

rather than simply translating:

```text
What the code says
```

Do not comment every line.

Do not add meaningless comments to:

* imports
* getters/setters
* trivial assignments
* obvious CRUD code

Keep comments synchronized with implementation.

---

# Testing and Verification

After changes:

## Java

Run relevant Maven tests or compilation.

## Python

Run relevant Python tests or import/startup validation when Python is modified.

Avoid real paid LLM requests in automated tests when mocking is practical.

## Frontend

Run the frontend build after relevant changes.

For Search features,

verify behavior such as:

* Normal query
* Empty query
* No results
* Multiple results
* Pagination
* Relevant ordering when ranking is introduced
* Failure behavior

Never report a test as passed unless it was actually executed.

---

# Security

Pay attention to:

* SQL injection
* Unsafe dynamic SQL
* XSS
* Search highlight rendering
* SSRF
* URL redirects
* File upload validation
* Path traversal
* Unsafe file access
* Hardcoded secrets
* Untrusted AI output

Use parameterized database queries.

Do not concatenate raw user Search input directly into SQL.

Security should be proportional to a personal project,

but obvious vulnerabilities must not be ignored.

---

# Documentation

Keep documentation aligned with actual implementation.

Important files include:

```text
README.md
docs/architecture.md
docs/database.md
docs/api.md
docs/roadmap.md
```

Documentation should clearly distinguish:

```text
Completed
In Progress
Planned
```

Do not describe planned functionality as completed.

Historical prompts under:

```text
docs/history/
```

are historical records.

They must not override:

1. Current user task
2. Current root AGENTS.md
3. Current repository implementation

---

# Repository Structure

Keep the monorepo structure conceptually similar to:

```text
life-inbox/
├── web/
├── server/
├── ai-engine/
├── extension/
├── docs/
├── deploy/
├── AGENTS.md
├── README.md
└── .gitignore
```

Do not create future modules or empty directories merely because they appear in the roadmap.

Create them when a real task requires them.

---

# Scope Control

Only implement functionality explicitly requested by the current task.

Examples:

A Keyword Search task does not automatically authorize:

* Embedding
* Vector Store
* Semantic Search
* Rerank
* RAG

An Embedding task does not automatically authorize:

* Qdrant
* Hybrid Search
* RAG
* Agent

A Semantic Search task does not automatically authorize:

* Personal Chat
* Todo
* Deadline
* Relations
* Knowledge Graph
* Agent

An Action Extractor task does not automatically authorize:

* Calendar integration
* Reminder system
* Agent workflow

Follow:

```text
One Task
One Clear Goal
Small Increment
Verify
Stop
```

---

# Long-Term Principle

LifeInbox should grow from real user problems,

not from a list of AI technologies.

The project should continue following:

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

Prefer:

```text
A smaller product that works well
```

over:

```text
RAG
Agent
MCP
GraphRAG
Multi-Agent
Knowledge Graph
```

all implemented only partially.

The role of Codex is to accelerate engineering implementation.

The architecture, product direction, data ownership, and roadmap should remain intentional decisions made by the project owner.

# Inspiration

LifeInbox is inspired by ideas from projects such as:

- DropMind — frictionless universal capture
- NoteGen — Capture First, Organize Later
- Karakeep — bookmark capture and retrieval
- 4DPocket — AI enrichment and search
- My-Brain-System — AI-assisted organization
- Eclaire — unified personal data
- Khoj — semantic personal retrieval
- Personal OS + Personal Wiki — knowledge to action

LifeInbox does not copy any single project's architecture.
It combines these ideas around its own:

Capture → Understand → Organize → Retrieve → Action
