# LifeInbox

> **Capture first. Organize later. Retrieve when needed. Turn information into action. Discover what connects.**

LifeInbox is an AI-driven personal information inbox for capturing, understanding, organizing, retrieving, acting on, and discovering relationships across useful personal information.

LifeInbox 解决的是一个很实际的问题：

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
      ↓
逐渐发现信息之间的关系
```

LifeInbox 的长期产品演进主线是：

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

核心原则始终是：

```text
Capture First, Organize Later.
```

LifeInbox 并不首先把自己定位成传统“知识库”。

它更关心的是：

```text
“I saw something useful.”
           ↓
“I saved it without organizing it.”
           ↓
“I can find it again.”
           ↓
“I can act on it.”
           ↓
“I can understand how it connects
 to everything else I have saved.”
```

---

# Current Status

LifeInbox is under active development.

Current roadmap:

```text
V0.1 — Universal Inbox        ✅ Completed
V0.2 — AI Organizer           ✅ Completed
V0.3 — Smart Search           ✅ Completed
V0.4 — Action Extractor       ✅ Completed
V0.5 — Relations              🚧 Current
V1.0 — Personal AI            📋 Planned
```

Latest completed release baseline:

```text
V0.4 — Action Extractor
```

Current development stage:

```text
V0.5 — Relations
```

V0.4 established the complete path from captured information to user-confirmed Todo state.

V0.5 now asks the next question:

```text
“Which pieces of information
I have saved are meaningfully related?”
```

Current V0.5 work begins incrementally.

The fact that V0.5 is now the active stage does **not** mean every Relations capability already exists.

Each Relation capability is implemented task by task.

The first six V0.5 implementation tasks are complete:

```text
V0.5 Task 1
=
Overall Task 41

Relation Core Model & Persistence Foundation

V0.5 Task 2
=
Overall Task 42

Bounded Relation Candidate Discovery

V0.5 Task 3
=
Overall Task 43

AI Relation Discovery Foundation

V0.5 Task 4
=
Overall Task 44

Relation Persistence Integration

V0.5 Task 5
=
Overall Task 45

Related Items Product API

V0.5 Task 6
=
Overall Task 46

Frontend Related Items UI
```

Task 42 retrieves bounded semantic neighbors for an existing indexed ACTIVE InboxItem and filters them through Java/MySQL. Task 43 sends
only the bounded Source/Candidate text to one LLM call and returns strictly validated runtime `RELATED_TO` suggestions. Task 44 converts
valid suggestions into canonical, additive, idempotent Relations after one short final-validation transaction. Task 45 exposes persisted
Relations through a bounded, read-only MySQL Product API. Task 46 adds a lazy, read-only Related Items section to existing Inbox cards so the
user can rediscover and focus related saved information. Automatic Relation processing is not implemented yet.

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
* Project ideas
* Learning materials

The difficult part usually is not seeing the information for the first time.

The real problems happen later:

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

LifeInbox grows around these real problems instead of adding AI technologies simply because they are fashionable.

---

# Core Principle

## Capture First, Organize Later

Capture should remain simple.

```text
See something useful
        ↓
Save it
        ↓
Organize later
```

AI is an enhancement.

It must not become a prerequisite for preserving information.

Even if:

```text
FastAPI unavailable
LLM unavailable
OCR failure
URL extraction failure
Embedding failure
Qdrant unavailable
Semantic Search unavailable
Reranker unavailable
Action Extraction unavailable
Relation Discovery unavailable
```

the original information should still be preserved whenever possible.

The invariant is:

```text
AI Failure
≠
Capture Failure
```

The same principle extends to later capabilities:

```text
Search Enhancement Failure
≠
Inbox Failure

Action Extraction Failure
≠
Inbox Failure

Relation Discovery Failure
≠
Existing Product Failure
```

---

# What LifeInbox Can Do Today

LifeInbox has completed four major capability layers:

```text
Capture
   ↓
Understand / Organize
   ↓
Retrieve
   ↓
Action
```

V0.5 is now incrementally adding:

```text
Relations
```

on top of those existing layers.

---

# Universal Capture

LifeInbox currently supports a unified Inbox for:

```text
TEXT
URL
FILE
IMAGE
```

All captured information first enters the core model:

```text
InboxItem
```

rather than creating separate business models such as:

```text
TextItem
UrlItem
FileItem
ImageItem
```

Current Inbox capabilities include:

* Capture text
* Capture URL
* Upload files
* Upload images
* Inbox listing
* Favorite
* Archive
* Delete
* AI analysis
* Smart Search
* Action detection
* Todo workflow

Different content types may use different processing strategies,

but their business state remains centered around:

```text
InboxItem
```

The same principle continues into V0.5.

Relations should initially connect:

```text
InboxItem
    ↕
InboxItem
```

rather than introducing a second generic knowledge-node model.

---

# AI Organizer

LifeInbox can automatically understand content after Capture.

Different content types first go through content preparation.

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

The resulting usable text can then enter:

```text
Prepared Content
      ↓
AI Analyze
      ↓
Structured Result
```

Current structured AI information includes:

```text
Summary
Category
Tags
Keywords
Entities
```

The intended experience is:

```text
User:
Save

AI:
Understand
+
Organize
```

---

# Reliable AI Processing

AI processing is designed as an enhancement rather than a blocking business dependency.

Existing reliability mechanisms include:

* AI processing status
* Manual Analyze
* Automatic Analyze after Capture
* Retry
* stale PROCESSING recovery
* Attempt Guard
* Late-result protection
* Background processing
* Failure degradation

Conceptually:

```text
Capture
   ↓
Database Commit
   ↓
Background AI Processing
```

Long-running external AI calls should not keep the core business transaction open.

If a later Analyze attempt fails:

```text
Existing InboxItem
+
Previous Successful Result
```

should not be unnecessarily destroyed.

---

# AI Attempt Guard

AI requests can finish out of order.

For example:

```text
Attempt A
   ↓
slow / timeout

Attempt B
   ↓
takes current ownership
```

If Attempt A later returns:

```text
A != Current Attempt
```

it cannot overwrite:

```text
New AI Result
New Searchable Content
New Processing State
```

This protects LifeInbox from:

```text
old slow result
      ↓
overwriting
      ↓
newer valid result
```

The same ownership principle is reused where later AI processing requires protection against stale results.

V0.4 already applies this principle to Action processing.

If V0.5 later introduces automatic Relation rediscovery or retries, the corresponding task should decide whether an equivalent Relation attempt lifecycle is actually required.

---

# Smart Search

V0.3 completed the Retrieve stage.

LifeInbox no longer relies only on queries such as:

```sql
WHERE title LIKE '%keyword%'
```

Current conceptual Search Pipeline:

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
                             ↓
                     Hybrid Candidates
                             ↓
                         Reranker
                             ↓
                       Final Results
```

V0.3 completed:

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

V0.5 Relations must preserve this retrieval pipeline unless a concrete task explicitly requires a limited change.

---

# Keyword Search

Keyword Search is handled by Spring Boot and MySQL.

Current searchable information can include:

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

Business filters may include fields such as:

```text
Type
Category
Favorite
```

Keyword Search remains important when the user remembers exact terminology.

```text
Exact / Strong Text Match
```

still has high value even after Semantic Search exists.

---

# Searchable Content

Different Capture types are normalized into reusable text.

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

For URL / FILE / IMAGE this may be persisted as:

```text
searchable_content
```

Searchable Content is:

```text
Derived / Rebuildable Data
```

not:

```text
Original Business Source of Truth
```

It can currently support:

```text
Keyword Search
Embedding
Semantic Retrieval
Action Extraction
```

and may also support bounded Relation Discovery when a concrete V0.5 task requires it.

V0.5 should reuse existing prepared content instead of creating another:

```text
URL Fetcher
Document Parser
OCR Pipeline
```

specifically for Relations.

---

# Semantic Search

Semantic Search solves the problem:

> The user remembers the meaning, but no longer remembers the exact words.

For example:

```text
Query:

那个讲 Redis 防止重复请求的文章
```

while the saved information may contain:

```text
接口幂等
Redisson
Redis Lua
分布式锁
```

Current flow:

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

Qdrant discovers retrieval candidates.

MySQL still decides the final valid business result.

---

# Hybrid Search

Keyword and Semantic retrieval solve different problems.

```text
Keyword Search
→ exact wording

Semantic Search
→ approximate meaning
```

LifeInbox combines both:

```text
Keyword Results
       +
Semantic Results
       ↓
Reciprocal Rank Fusion
       ↓
Hybrid Candidates
```

Current fusion uses:

```text
RRF
=
Reciprocal Rank Fusion
```

A single InboxItem remains a single final candidate even when it appears in both retrieval branches.

---

# Reranking

After Hybrid Retrieval produces a bounded candidate set:

```text
Hybrid Candidates
       ↓
Reranker
       ↓
Final Ranking
```

The Reranker:

```text
does not search the whole database
does not expand the candidate set
does not become business Source of Truth
```

It only makes a more precise relevance judgment between:

```text
Query
↔
Candidate
```

Rerank scores belong to the current request.

They are not permanent business attributes.

---

# Search Failure Degradation

Search follows graceful degradation.

If the Reranker is unavailable:

```text
Reranker unavailable
        ↓
Hybrid / RRF Results
```

If Semantic Retrieval is unavailable:

```text
Embedding / Qdrant unavailable
        ↓
Keyword Search
```

If the AI Engine is unavailable:

```text
Capture
Inbox
Favorite
Archive
Delete
Keyword Search
```

should continue working whenever possible.

Relations must not weaken these existing degradation guarantees.

---

# Qdrant

LifeInbox uses Qdrant for vector retrieval.

The important ownership boundary is:

```text
MySQL
=
Business Source of Truth


Qdrant
=
Derived / Rebuildable Retrieval Index
```

Qdrant may contain retrieval data such as:

```text
InboxItem ID
Embedding Vector
Embedding Model
Content Hash
Minimal Retrieval Metadata
```

but it does not own:

```text
InboxItem
Favorite
Archive
Original Content
AI Status
Action Candidate
Todo Business State
Relation Business State
```

Conceptually:

```text
MySQL
 ↓
Searchable Content
 ↓
Embedding
 ↓
Rebuild Qdrant
```

The loss of the vector index must not mean the loss of LifeInbox business data.

During V0.5, Qdrant may potentially help discover a bounded set of semantically related InboxItem candidates.

However:

```text
Semantic Similarity
≠
Authoritative Relation
```

and:

```text
Qdrant Candidate
≠
Relation Business State
```

---

# V0.4 — Action Extractor

V0.4 is complete.

Its goal was:

```text
Useful information
can become actionable.
```

It solves:

```text
“Does this saved information
contain something I actually need to do?”
```

For example:

```text
软件工程课程设计
8月25日前交报告
```

Existing content preparation can first produce usable text.

Action Extraction then converts semantic meaning into structured suggestions.

The exact current Action Extraction contract is defined by the current repository and technical documentation.

README intentionally does not redefine the API schema independently.

---

# Action Candidate

One of the most important V0.4 boundaries is:

```text
AI Suggestion
≠
Confirmed Business Action
```

The completed conceptual flow is:

```text
InboxItem
    ↓
Usable Content
    ↓
Action Extraction
    ↓
Action Candidate
    ↓
User Decision
   ↙         ↘
Accept     Dismiss
   ↓
 Todo
```

AI discovering:

```text
“This information may contain an action.”
```

does not mean the system may immediately:

```text
INSERT Todo
```

without the defined product decision boundary.

---

# Automatic Action Processing

V0.4 supports best-effort Action processing.

Conceptually:

```text
TEXT Capture
    ↓
Commit
    ↓
Action Extraction
```

For content types requiring preparation:

```text
URL / FILE / IMAGE
        ↓
Usable Searchable Content Ready
        ↓
Action Extraction
```

Manual detection / re-detection remains available as a recovery and retry path.

Action processing has its own lifecycle and ownership rules.

Action failure must not roll back Capture or silently damage Search or existing AI organization data.

---

# Todo

V0.4 established an independent Todo business model.

Conceptually:

```text
Todo
 ├── title
 ├── description
 ├── status
 ├── optional due_date
 └── optional source references
```

Todo belongs to:

```text
Java
+
MySQL
```

as authoritative business state.

A confirmed Todo is no longer merely an AI suggestion.

---

# Deadline Direction

The earliest LifeInbox planning treated:

```text
Todo
+
Deadline
```

as potentially separate models.

The implemented V0.4 adopted a simpler first design:

```text
Todo
└── optional due_date
```

For example:

```text
8月25日前交报告
```

can become:

```text
Todo:
  title = 提交报告
  due_date = 2026-08-25
```

This avoids maintaining two highly overlapping lifecycles.

An independent Deadline entity should only be reconsidered if future product requirements create a genuinely independent deadline lifecycle.

Examples might include:

```text
Shared deadline lifecycle

Independent reminder state

Calendar-specific lifecycle

One deadline shared across multiple Todos
```

Until such requirements exist:

```text
Todo
+
optional due_date
```

remains the preferred model.

---

# Todo Lifecycle

Current Todo lifecycle is intentionally simple:

```text
OPEN
  ↕
COMPLETED
```

Completing a Todo records the completion time.

Reopening it clears that completion state.

Todo lifecycle operations do not call the AI Engine and do not rewrite the originating ActionCandidate.

---

# Action Source Traceability

Action Candidate and Todo remain traceable to their source whenever available.

```text
InboxItem
    ↓
Action Candidate
    ↓
Todo
```

This allows LifeInbox to answer:

```text
Why did this Todo appear?

Where did this deadline come from?
```

The original information remains owned by:

```text
InboxItem
```

Todo source context is loaded on demand rather than joining full source data into every Todo list response.

Source traceability is:

```text
Read-only Context
```

not:

```text
Synchronization
```

Deleting or losing a source does not automatically destroy the Todo business state.

---

# User Decisions Are Authoritative

V0.4 established an important priority:

```text
User-confirmed Business State
            >
Current Business Data
            >
AI-generated Suggestion
```

AI processing must not silently undo an explicit user decision.

Examples include:

* Accept
* Dismiss
* Complete
* Reopen
* Future user edits
* Future user rescheduling

The AI may suggest.

The product owns business state.

The user owns confirmed decisions.

This principle also applies to future Relation features if a V0.5 task introduces user-confirmed Relation state.

---

# Deadline Normalization

Date extraction distinguishes:

```text
Original Expression
```

from:

```text
Normalized Deadline
```

For supported relative expressions, stable source context can be used to produce deterministic normalization.

The implementation preserves uncertainty when source information is insufficient.

The system should not fabricate unsupported:

```text
year
timezone
exact time
```

simply to make a value look precise.

Prefer:

```text
Keep uncertainty
```

over:

```text
Fabricate precision
```

The exact normalization contract is defined by the current implementation and technical documentation.

---

# V0.5 — Relations

V0.5 is the current development stage.

Goal:

```text
Discover useful relationships
between captured information.
```

For example, a LifeInbox may contain:

```text
Redis缓存穿透
Redis分布式锁
Redisson
Lua脚本
高并发抢购
```

These items may have useful connections that are difficult to notice manually.

Conceptually:

```text
InboxItem A
     ↕
  Relation
     ↕
InboxItem B
```

The implemented first-version relation type is:

```text
RELATED_TO
```

It is symmetric, so `A RELATED_TO B` and `B RELATED_TO A` are one persisted Canonical Pair.

---

# Why Relations?

V0.3 answers:

```text
“What information is relevant
to this search query?”
```

V0.5 addresses a different question:

```text
“What information in my Inbox
is related to other information
I have already saved?”
```

Search is:

```text
Query-driven
```

Relations are closer to:

```text
Item-to-Item Connection
```

Relations can make useful connections visible even when the user does not remember to search for them.

Therefore:

```text
Semantic Search
≠
Persisted Relation
```

and:

```text
Similar Items
≠
Automatically Confirmed Relations
```

---

# Implemented V0.5 Foundation, Bounded Candidates, and AI Judgment

Task 41 established this concrete first-version contract:

```text
Endpoint          = InboxItem ↔ InboxItem only
Relation Type     = RELATED_TO only
Directionality    = Symmetric
Canonical Pair    = leftInboxItemId < rightInboxItemId
Duplicate Guard   = UNIQUE(left, right, relationType)
Self Relation     = Rejected
New Creation      = both endpoints currently ACTIVE
Archive           = existing relation remains
Delete            = either endpoint cascades relation row
Persistence Owner = Java + MySQL
```

The implementation provides a Java internal service for idempotent `ensureRelatedTo(A, B)` and a minimal query that finds relations when
the requested InboxItem is on either canonical side. Endpoint rows are locked in canonical ID order during creation so Archive/Delete and
concurrent reverse-pair writes have deterministic database ordering.

Task 41 intentionally contains no persisted `RelationCandidate`, score, evidence/reason, provider metadata, or Relation processing state.

Task 42 adds a separate runtime-only candidate flow:

```text
ACTIVE Source InboxItem
        ↓
Existing Qdrant Point Vector
        ↓
Bounded nearest-neighbor search
        ↓
Candidate IDs + transient semanticScore
        ↓
Java/MySQL ACTIVE resolution
        ↓
Filter self / stale / archived / already RELATED_TO
        ↓
Final candidates (limit <= 20)
```

The Source Vector is reused without another Embedding call. Qdrant over-fetch is bounded by `min(limit * 3, 100)`; Java applies the final
limit after business filtering. Source Point missing is a normal `sourceIndexed=false` result, while Qdrant availability, Collection, model,
dimension, and timeout failures remain controlled infrastructure errors. Candidates and `semanticScore` are not persisted, and no formal
Relation is created by this flow.

Task 43 consumes that bounded list without sending `semanticScore` to the LLM. Java rechecks ACTIVE state and existing relations, builds
text only from title, summary, and already available content, and applies limits of 4,000 characters for the Source, 1,000 per Candidate,
and 20 Candidates. Python evaluates the complete batch in one call with a precision-first Prompt and returns only
`relatedTargetInboxItemIds`. Unknown, duplicate, Source, or out-of-set IDs invalidate the whole output. The validated result remains a
runtime `RELATED_TO` suggestion: Task 43 does not call `ensureRelatedTo` or write `content_relation`.

Task 44 adds a separate internal `discoverAndPersistRelations` orchestration. Task 43 and all AI/network work finish before the persistence
transaction begins. The short transaction batch-locks the Source and suggested Targets in canonical ID order, aborts if the Source is missing
or archived, skips individually invalid Targets, queries existing Relations once, and inserts only missing canonical pairs. Existing pairs are
idempotent success. Empty discovery, AI failure, and absence from a later discovery result never delete an existing Relation.

Task 45 adds `GET /api/inbox/{id}/related?limit=10`. It validates an ACTIVE Source, reads both Canonical Pair directions, filters ACTIVE Targets,
orders by persisted Relation recency, and returns a minimal bounded Product DTO. This normal read path uses only Java/MySQL: it never calls
FastAPI, LLM, Embedding, Qdrant, Rerank, Relation Discovery, or Relation Persistence.

Task 46 consumes that Product API only after the user expands “相关内容” on one Inbox card. It renders compact bounded previews with isolated
loading, empty, and retryable error states. Clicking a related row focuses the existing full Inbox card and replaces the active Related panel,
so chained navigation does not build nested detail surfaces. Opening or viewing an item never triggers Relation Discovery.

---

# MySQL First for Relations

The first version of Relations does **not** require a graph database.

The implemented table is:

```text
content_relation
────────────────
id
left_inbox_item_id
right_inbox_item_id
relation_type
created_time
updated_time
```

The old planning example included:

```text
score
```

Task 41 deliberately does not persist it because the product has not defined what it means.

For example:

```text
Similarity?
Confidence?
Relevance?
Provider-specific Score?
```

These are different concepts.

The important rule is:

```text
Relations
≠
Automatically Neo4j
```

MySQL is sufficient for initial Relation storage unless real requirements appear for:

* complex multi-hop graph traversal
* graph-native querying
* graph algorithms
* graph-specific scale or performance

---

# Relations Are Not Automatically a Knowledge Graph

Useful InboxItem relationships do not automatically mean LifeInbox needs:

```text
Knowledge Graph
RDF
Ontology
SPARQL
GraphRAG
Graph Database
```

The V0.5 goal is:

```text
Useful Personal Information Relationships
```

not graph technology for its own sake.

---

# Avoid Unbounded Pairwise Comparison

Relation discovery must not naively do:

```text
Every InboxItem
    ×
Every other InboxItem
```

over an unbounded collection.

That grows approximately as:

```text
O(n²)
```

Instead, Relation discovery should operate on:

```text
Bounded Candidate Set
```

Existing signals may eventually help generate candidates, such as:

```text
Semantic Nearest Neighbors
Shared Metadata
Search Signals
Recent Relevant Items
```

Task 42 now implements the first candidate strategy: semantic nearest neighbors from the Source InboxItem's existing Qdrant Point Vector.
The requested limit is `1..20`; Python performs bounded over-fetch, and Java batch-resolves MySQL before final filtering. Other candidate
signals remain future task scope.

Task 43 implements the next bounded step: one LLM call judges the Source against those candidates. It does not perform all-pairs analysis,
does not receive the Qdrant score, and does not itself persist its runtime suggestions. Task 44 then provides an explicit internal conversion
step whose persistence is additive and non-destructive. Task 45 provides a separate bounded read-only Product API; it is not an automatic
discovery trigger.

Existing retrieval infrastructure such as Qdrant may be reused when justified.

Qdrant may assist in:

```text
Candidate Discovery
```

but it does not become the authoritative Relation database.

---

# Relation Data Ownership

The same Java / Python boundary continues into V0.5.

## Spring Boot — The Product

Java owns:

```text
InboxItem
Business Data
MySQL Persistence

Capture
Favorite
Archive
Delete

AI Processing State
Attempt Ownership
AI Result Persistence

Search Product API
Search Orchestration
Final Business Results

Action Candidate State
Todo Business State

Relation Business Validation
Relation Persistence
Product-facing Relation APIs
```

Java remains:

```text
Business Source of Truth
```

for final product state.

---

## FastAPI — Understanding Information

Python owns capabilities such as:

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
Deadline Understanding

Relation Discovery
Relation Semantic Analysis
```

Conceptually:

```text
Java:
“There are authoritative InboxItems
and current business states.”

Python:
“I can analyze those items
and return structured suggestions.”
```

Python does not independently own authoritative:

```text
InboxItem
Todo
Favorite
Archive
Relation Business State
```

Python must not directly mutate MySQL Relation business state.

---

# Relation AI Output Is Not Business Truth

V0.5 continues a lesson already established in V0.4:

```text
AI Output
≠
Authoritative Business State
```

Task 43 validates every AI Relation output as untrusted data.

Where applicable this may include:

```text
valid source ID

valid target ID

source != target

supported Relation Type

bounded result count

duplicate handling

reverse duplicate handling

current InboxItem existence

deleted / archived state
```

The current output contains only `relatedTargetInboxItemIds`; any unknown, duplicate, Source, non-positive, or out-of-bounds ID invalidates
the whole response. It contains no Relation score, confidence, reason, or evidence.

The important principle is:

```text
Valid JSON
≠
Valid Business Relation
```

---

# Relation Failure Degradation

Relations are an enhancement.

Relation processing failure must not break:

```text
Capture
Inbox
Favorite
Archive
Delete

AI Organizer

Keyword Search
Semantic Search
Hybrid Search
Rerank

Action Extraction
Action Candidate decisions

Todo
Todo Lifecycle
Todo Source Traceability
```

The existing product should remain useful even when Relation Discovery is unavailable.

---

# Relation Reprocessing

If V0.5 later supports:

```text
Retry
Re-discovery
Automatic Relation Discovery
```

new processing must not blindly:

```text
DELETE existing relations
        ↓
Call AI
        ↓
AI fails
        ↓
all previous relation data lost
```

Replacement behavior must be defined explicitly by the corresponding task.

If future Relations contain user-confirmed state:

```text
User-confirmed Relation
>
AI-generated Relation Suggestion
```

must be preserved.

---

# Relations Must Not Rewrite Action State

V0.5 Relation work does not authorize modifying:

```text
ActionCandidate.status

Todo.title
Todo.description
Todo.status
Todo.due_date
Todo.completed_time
```

Relations are a new information-connection layer.

They are not an Action business-state rewrite mechanism.

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

V0.4 added the Action path:

```text
InboxItem
    ↓
Usable Content
    ↓
Action Extraction
    ↓
Action Candidate
    ↓
User Decision
    ↓
Todo
└── optional due_date
```

V0.5 now begins adding the Relations layer:

```text
Accumulated InboxItems
        ↓
Bounded Relation Candidates
        ↓
Relation Discovery
        ↓
Structured Relation Suggestion
        ↓
Java Validation
        ↓
MySQL Relation State
        ↓
Useful Related Information
```

The V0.5 diagram is a development direction.

Only capabilities implemented by current tasks should be treated as completed.

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

Qdrant is derived retrieval infrastructure.

Core Capture / Inbox functionality does not require Qdrant to preserve information.

---

# Infrastructure Principle

Infrastructure follows real requirements.

Current core infrastructure:

```text
MySQL
+
Spring Boot
+
FastAPI
+
Qdrant
```

Qdrant exists because V0.3 created a real requirement for semantic retrieval.

The project should not automatically add:

```text
Redis
Kafka
RabbitMQ
RocketMQ
Elasticsearch
Milvus
Neo4j
```

simply because these technologies appear in possible long-term architectures or external reference projects.

Use:

```text
Requirement First
Technology Second
```

not:

```text
Technology First
Problem Later
```

Therefore:

```text
Relations
≠
Neo4j required

Background AI
≠
MQ required

Semantic Search
≠
RAG required

Personal information
≠
Agent required
```

---

# Repository Structure

LifeInbox uses a monorepo.

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

Directories should grow with real requirements.

Do not create empty future modules merely because words such as:

```text
relation
agent
rag
memory
graph
```

appear in the roadmap.

Create new modules only when a concrete implementation task needs them.

---

# Running LifeInbox Locally

LifeInbox currently consists primarily of:

```text
MySQL
Spring Boot
FastAPI
Vue
```

Semantic Search additionally requires:

```text
Embedding Provider
Qdrant
```

Reranking additionally requires:

```text
Rerank Provider
```

Relation-specific runtime requirements should only be added here after they are actually introduced by V0.5 implementation.

---

## 1. Start MySQL

Create the database:

```text
life_inbox
```

Schema and migration documentation:

```text
docs/database.md
docs/sql/
```

Always use the current repository SQL as the source of truth.

Before the first real V0.5 Schema migration exists, the current implemented database baseline remains the completed V0.4 Schema.

---

## 2. Start Spring Boot

Enter:

```text
server/
```

On Windows:

```powershell
.\mvnw.cmd spring-boot:run
```

Use the repository-equivalent Maven command if the wrapper or project layout changes.

---

## 3. Start FastAPI

Enter:

```text
ai-engine/
```

Run:

```powershell
uv run uvicorn app.main:app --host 127.0.0.1 --port 8000
```

AI Engine provider configuration is documented in:

```text
ai-engine/README.md
.env.example
```

Never commit real API keys.

---

## 4. Start Qdrant

Semantic Search and the Semantic branch of Hybrid Search require Qdrant.

A common local endpoint is:

```text
http://127.0.0.1:6333
```

Use the actual configuration documented in:

```text
ai-engine/README.md
```

If Qdrant is unavailable,

Keyword Search should still work whenever possible.

If V0.5 later reuses Qdrant for Relation candidate discovery,

that does not change Qdrant's ownership role:

```text
Qdrant
=
Derived Candidate / Retrieval Infrastructure
```

---

## 5. Start Frontend

Enter:

```text
web/
```

Run:

```bash
npm install
npm run dev
```

Use the development URL printed by Vite.

---

# AI Configuration

External AI providers are configured through environment variables.

Current configuration areas include:

```text
LLM
Embedding
Qdrant / Vector Store
Rerank
```

The exact variable names are defined by:

```text
.env.example
ai-engine/README.md
```

Do not commit:

```text
API Key
Token
Private Credential
Secret
Workspace Credential
```

V0.5 should reuse existing provider infrastructure where appropriate rather than creating unnecessary duplicate credentials.

A Relation task should introduce new configuration only when the implementation actually requires it.

---

# Alibaba Cloud Model Studio Compatibility

If Alibaba Cloud Model Studio is used,

Chat / Embedding and Rerank may use different compatible API bases.

Conceptually:

```text
Chat
↓
compatible-mode/v1/chat/completions

Embedding
↓
compatible-mode/v1/embeddings
```

Reranking may use:

```text
Rerank
↓
compatible-api/v1/reranks
```

The actual configuration belongs in environment/configuration files,

not hard-coded product logic.

Use:

```text
.env.example
ai-engine/README.md
```

as the configuration reference.

---

# Testing

## Java

Enter:

```text
server/
```

Run:

```powershell
.\mvnw.cmd clean test
```

or the current repository-equivalent command.

For larger integration or final acceptance tasks,

run the complete relevant Java test suite.

---

## Python

Enter:

```text
ai-engine/
```

Run:

```powershell
uv run pytest -p no:cacheprovider
```

Automated tests should mock paid external providers whenever practical:

```text
LLM Provider
Embedding Provider
Rerank Provider
Relation AI Provider
Other External AI Service
```

Tests should not unexpectedly consume paid API quota.

Do not claim real Provider behavior was verified if only mocks were executed.

---

## Frontend

Enter:

```text
web/
```

Run:

```bash
npm run build
```

If a frontend test suite exists,

run the relevant tests for modified functionality.

Do not report:

```text
PASS
```

for verification that was never actually executed.

---

# V0.5 Testing Direction

Task 41 automated tests cover:

```text
Create RELATED_TO
Canonical ordering
Same-pair idempotency
Reverse-pair deduplication
Self-relation rejection
Missing endpoint rejection
Archived endpoint creation rejection
Archive-preserved query behavior
Delete CASCADE schema
Querying either canonical side
Controlled RelationType enum
Database uniqueness
Transaction proxy behavior
V0.4 + incremental = V0.5 fresh schema
```

As later Relation capabilities are implemented,

relevant test cases should eventually cover situations such as:

```text
Clearly related items

Clearly unrelated items

No relation

One relation

Multiple relations

Self relation

Duplicate relation

Reverse duplicate

Symmetric relation

Directional relation

Missing source item

Missing target item

Deleted item

Archived item

Unsupported relation type

Invalid score if score exists

Malformed AI output

Empty AI output

Provider timeout

Provider failure

Repeated discovery

Bounded candidate selection

No unbounded all-pairs comparison
```

Relations tests must also protect completed behavior from:

```text
V0.1
V0.2
V0.3
V0.4
```

---

# Roadmap

## V0.1 — Universal Inbox ✅

Goal:

```text
Anything useful can be captured quickly.
```

Completed capabilities:

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

Primary stage:

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

Completed capabilities:

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

Primary stages:

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

Completed capabilities:

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

Primary stage:

```text
Retrieve
```

---

## V0.4 — Action Extractor ✅

Goal:

```text
Information can become actionable.
```

Completed capabilities include:

```text
Structured Action Extraction
Action Candidate Persistence
Automatic / Manual Detection
Reliable Action Processing
Attempt Guard
Deadline Normalization

User Accept / Dismiss
Candidate → Todo

Todo OPEN / COMPLETED Lifecycle
Complete / Reopen

Source Traceability
Failure Degradation
```

Completed conceptual flow:

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
└── optional due_date
```

V0.4 intentionally does not imply:

```text
Google Calendar
Push Notifications
Recurring Tasks
Autonomous Todo Creation
Workflow Engine
Agent Execution
```

---

## V0.5 — Relations 🚧

Goal:

```text
Discover useful relationships
between captured information.
```

Implemented Task 1 foundation:

```text
InboxItem A
      ↕
  RELATED_TO
      ↕
InboxItem B
```

Current contract:

```text
InboxItem-centered

MySQL first

Symmetric Canonical Pair

ACTIVE-only new creation

Archive preserves Relation

Delete cascades Relation

No persisted RelationCandidate / Relation score / evidence
```

V0.5 does not automatically imply:

```text
Neo4j
Knowledge Graph
GraphRAG
Multi-hop Graph Reasoning
Agent
MCP
Workflow Engine
```

These require independent product justification.

The exact V0.5 task decomposition is intentionally not fully hard-coded in advance.

Completed:

```text
✅ V0.5 Task 1 / Overall Task 41
— Relation Core Model & Persistence Foundation

✅ V0.5 Task 2 / Overall Task 42
— Bounded Relation Candidate Discovery

✅ V0.5 Task 3 / Overall Task 43
— AI Relation Discovery Foundation

✅ V0.5 Task 4 / Overall Task 44
— Relation Persistence Integration

✅ V0.5 Task 5 / Overall Task 45
— Related Items Product API

✅ V0.5 Task 6 / Overall Task 46
— Frontend Related Items UI
```

Automatic discovery, rediscovery/hardening, and final V0.5 acceptance remain unimplemented future work.

---

## V1.0 — Personal AI 📋

Goal:

```text
AI can work with accumulated
personal information.
```

Possible future capabilities include:

```text
Personal Information Analysis

Personal RAG

Conversational Retrieval

Long-term Topic Summaries

Project Discovery

Memory Resurfacing

Personal AI Assistant

Personal Agent
```

Personal AI should be built only after stable:

```text
Capture
+
Understanding
+
Retrieval
+
Action
+
Relations
+
Useful Personal Data
```

exist.

---

# Browser Extension

Browser Extension remains a potentially valuable Capture enhancement.

Target experience:

```text
See useful webpage
       ↓
Click Save
       ↓
LifeInbox
```

Its postponement is:

```text
Priority Adjustment
```

not:

```text
Abandoned Direction
```

The project prioritized completing the core product layers before expanding Capture entry points.

Browser Extension should not be implemented during unrelated V0.5 Relations tasks unless explicitly requested.

---

# Inspiration

LifeInbox is inspired by ideas from several personal-information, knowledge-management, retrieval, action-oriented, and relation-oriented projects.

Different projects are useful references for different stages.

Reference projects help answer:

```text
“What ideas are worth learning from?”
```

They do **not** answer:

```text
“What architecture must LifeInbox copy?”
```

---

## Capture / Organize

* **DropMind** — frictionless universal capture and Inbox-first product thinking.
* **NoteGen** — `Capture First, Organize Later` and AI-assisted organization.
* **My-Brain-System** — AI-assisted organization of accumulated personal knowledge.
* **Eclaire** — unified personal-data concepts across notes, files, bookmarks, and tasks.

---

## Content / Retrieval

* **Karakeep** — practical bookmark capture, content preservation, search, OCR, and AI-assisted organization.
* **4DPocket** — content enrichment and retrieval-pipeline ideas.
* **Khoj** — semantic retrieval over personal information.

---

## Action

V0.4 selectively referenced:

* **Personal OS + Personal Wiki (`lawyer112/personal-os-wiki`)** — moving messy captured information toward explicit, reviewable work while keeping source knowledge and action state conceptually separate.
* **PersonalOS (`amanaiproduct/personal-os`)** — turning unstructured backlog information into structured tasks with simple task context and optional deadline information.
* **work-os (`guo-yichen/work-os`)** — distinguishing actions from other extracted information such as decisions and ideas.

The main lessons applied to LifeInbox were:

```text
AI-extracted information
≠
Authoritative Work State
```

and:

```text
Human-confirmed Work
should have its own lifecycle.
```

---

## Relations

V0.5 selectively references:

### `Timeverse/My-Brain-System`

Useful inspiration for:

```text
Connecting newly processed information
to existing personal knowledge

Surfacing related concepts

Reducing isolated information

Discovering useful cross-topic connections

Making relationships understandable
to the user
```

LifeInbox does **not** automatically copy its:

```text
Obsidian-based storage model

WikiLink persistence model

Claude Code / Agent architecture

Make.com workflows

LINE integration

Folder taxonomy

Knowledge-garden file structure
```

The useful lesson is the product idea:

```text
Accumulated information
should become increasingly connected
instead of remaining isolated.
```

---

### `onllm-dev/4DPocket`

Useful inspiration for:

```text
AI-enriched personal content

Related-content discovery

Semantic retrieval signals

Bounded candidate discovery

Combining structured metadata
with semantic similarity

Connecting previously saved information
without requiring exact user queries
```

LifeInbox does **not** automatically copy its:

```text
SQLite / PostgreSQL architecture

Meilisearch

ChromaDB

MCP Server

Entity Graph

Chunk-level retrieval architecture

Background-worker architecture

Graph ranking implementation
```

LifeInbox already has its own V0.3 retrieval stack:

```text
Keyword Retrieval
        +
Semantic Retrieval
        ↓
       RRF
        ↓
      Rerank
```

V0.5 should reuse existing LifeInbox retrieval capabilities where appropriate,

rather than redesigning V0.3 Search merely to imitate another project.

---

## V0.5 Reference Boundary

For V0.5, the two primary reference projects are:

```text
Primary:
Timeverse/My-Brain-System

Secondary:
onllm-dev/4DPocket
```

They are primarily useful for:

```text
Product ideas
Relation discovery ideas
Candidate discovery ideas
Related-information experience
```

They are not:

```text
LifeInbox Architecture Source of Truth
```

LifeInbox keeps its own V0.5 boundary:

```text
InboxItem
    ↓
Bounded Candidate Discovery
    ↓
Relation Understanding
    ↓
Structured Relation Suggestion
    ↓
Java Validation
    ↓
MySQL Relation State
```

Qdrant may assist:

```text
Candidate Discovery
```

but:

```text
MySQL
=
Business Source of Truth
```

continues to hold.

---

# Reference Project Restrictions

LifeInbox does not automatically copy any reference project's:

```text
Technology Stack

Database Model

Agent Framework

Task Worker

Knowledge Graph

Graph Database

MCP Integration

Reminder System

Workflow Engine

Message Queue

Vector Database Choice

Frontend Architecture
```

A reference project may inspire one narrow idea without authorizing its surrounding architecture.

For example:

```text
Reference project uses Graph
≠
LifeInbox needs Graph Database
```

```text
Reference project uses MCP
≠
LifeInbox needs MCP
```

```text
Reference project uses Agent
≠
LifeInbox needs Agent
```

```text
Reference project uses another Vector DB
≠
LifeInbox should replace Qdrant
```

---

# Authority Order

When references, planning documents, and current implementation disagree,

the authority order is:

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
        ↓
Historical Planning / docs/history
```

Reference Projects are:

```text
Inspiration
```

not:

```text
Executable Specification
```

Historical prompts are development records.

They must not override the current repository.

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

V0.3 already includes:

```text
Embedding
Vector Retrieval
Hybrid Search
Rerank
```

but that does not automatically mean LifeInbox needs:

```text
RAG
Chat with your data
Agent
MCP
```

V0.5 adds useful Relations,

but Relations do not automatically mean:

```text
Knowledge Graph
Graph Database
GraphRAG
Ontology
Multi-hop Graph Reasoning
```

Those are separate product and architecture decisions.

---

# Design Principles

## Capture must survive AI failure

```text
AI Down
≠
Capture Down
```

---

## Java owns business state

```text
Spring Boot / MySQL
=
Business Source of Truth
```

---

## Python understands information

```text
FastAPI
=
AI Processing Capability
```

---

## Vector data is derived

```text
Qdrant
=
Rebuildable Retrieval Index
```

---

## AI suggestions are not user decisions

```text
AI Candidate
≠
Confirmed Business State
```

---

## User decisions have higher authority

```text
User-confirmed State
>
AI-generated Suggestion
```

---

## Relations do not automatically require graphs

```text
Useful Relationships
≠
Graph Database
```

---

## Relation similarity is not Relation truth

```text
Semantic Similarity
≠
Authoritative Relation
```

---

## Infrastructure follows requirements

```text
Requirement First
Technology Second
```

---

## Current implementation beats old placeholder naming

```text
Current Repository
>
Old Conceptual Schema
```

---

## Development stays incremental

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

Current intentional scope limitations include:

* Some older InboxItems may require reprocessing before a vector index exists.
* Retrieval is primarily item-level rather than document-chunk-level.
* Semantic Search requires an Embedding Provider and Qdrant.
* Reranking requires an explicitly configured Rerank Provider.
* Candidate terminal-state deduplication is deterministic rather than semantic.
* Action processing does not provide realtime push, reminders, or calendar synchronization.
* Todo editing is not currently part of the completed V0.4 scope.
* Todo deletion is not currently part of the completed V0.4 scope.
* Manual Todo creation is not currently part of the completed V0.4 scope.
* Todo reminders and recurrence are not currently implemented.
* Calendar integration is not currently implemented.
* Todo source traceability depends on available source data; deleted source content is not reconstructed from a snapshot.
* Browser Extension Capture remains postponed.
* V0.5 Relations is now the active development stage; Task 1 Relation persistence, Task 2 bounded runtime candidates, Task 3 bounded AI Relation judgment, Task 4 additive persistence integration, Task 5 bounded Related Items Product API, and Task 6 lazy frontend Related Items are implemented, while automatic processing and rediscovery are not.
* `content_relation` exists in the V0.5 Task 1 migration and fresh schema; existing V0.4 databases must apply the incremental migration.
* The first version intentionally has no `relation_candidate` model.
* The first version intentionally persists no Relation score; Task 42's `semanticScore` is a runtime-only Qdrant ranking signal, not Relation truth.
* Personal RAG is not implemented.
* Personal Agent functionality is not implemented.
* GraphRAG and Knowledge Graph infrastructure are not implemented.

These are intentional scope boundaries rather than accidental missing features.

---

# Documentation

Detailed technical documentation lives under:

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
Current User Task

Root AGENTS.md

Current Repository

Current Documentation
```

The documentation responsibilities are:

```text
AGENTS.md
→ Persistent repository / Codex rules

README.md
→ Product overview, current status and inspiration

docs/architecture.md
→ Architecture and boundaries

docs/database.md
→ Current schema and clearly-labelled planned direction

docs/api.md
→ Actually implemented API contracts

docs/roadmap.md
→ Version evolution and product direction

docs/history/
→ Historical development records
```

---

# Development Philosophy

LifeInbox should grow from real user problems,

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

# Development Model

LifeInbox development should continue to follow:

```text
Read Repository
      ↓
Check git status
      ↓
Understand Current Task
      ↓
Inspect Existing Implementation
      ↓
Plan Small Change
      ↓
Implement
      ↓
Test / Build
      ↓
Inspect git diff / git status
      ↓
Report
      ↓
Stop
```

Each task should have:

```text
One clear goal

Explicit scope

Explicit non-goals

Real verification

Acceptance criteria

Stop condition
```

Codex accelerates implementation.

It does not independently redefine:

```text
Product Direction

Architecture

Core Data Model

Java / Python Boundary

Search Architecture

Action Semantics

Todo Semantics

Relation Semantics

Infrastructure Strategy
```

---

# Current Focus

```text
V0.5 — Relations
```

The question is no longer:

```text
“Can I save useful information?”
```

V0.1 addressed that.

It is no longer:

```text
“Can AI understand what I saved?”
```

V0.2 addressed that.

It is no longer:

```text
“Can I find what I saved?”
```

V0.3 addressed that.

And it is no longer:

```text
“Can LifeInbox recognize
when saved information requires action?”
```

V0.4 addressed that.

The next question is:

```text
“Can LifeInbox discover
useful relationships between
the information I have accumulated?”
```

That is the focus of V0.5.

The completed first implementation step is:

```text
InboxItem A
      ↕
  RELATED_TO
      ↕
InboxItem B
      ↓
Java / MySQL Persistence
```

while continuing to protect every completed capability from V0.1 through V0.4.

Completed task:

```text
V0.5 Task 1
=
Overall Task 41

V0.5 Task 2
=
Overall Task 42

V0.5 Task 3
=
Overall Task 43

V0.5 Task 4
=
Overall Task 44

V0.5 Task 5
=
Overall Task 45

V0.5 Task 6
=
Overall Task 46
```

Task 41 established the first concrete Relation Contract and persistence foundation. Task 42 added bounded semantic neighbor candidates
without converting them into business Relation state. Task 43 added one bounded, strict AI judgment step. Task 44 added the explicit,
non-destructive conversion of validated suggestions into business Relation state. Task 45 added the read-only MySQL Product API for bounded
ACTIVE Related Items. Task 46 added lazy frontend display and navigation through existing Inbox cards. Automatic processing and hardening
remain separate tasks.

---

# Long-Term Direction

LifeInbox is intentionally evolving in this order:

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

Each layer should become useful before the project moves too far into the next one.

The long-term destination may eventually include:

```text
Personal Search
+
Relations
+
Personal RAG
+
Personal AI
```

but only when the earlier layers provide reliable, useful personal data.

The project continues to follow:

```text
Capture First,
Organize Later.
```

and:

```text
Requirement First,
Technology Second.
```

Java / MySQL remain responsible for:

```text
Authoritative Product State
```

FastAPI remains responsible for:

```text
AI Understanding Capability
```

Qdrant remains:

```text
Derived / Rebuildable Retrieval Infrastructure
```

Reference projects remain:

```text
Inspiration
```

not:

```text
Architecture Source of Truth
```

Codex can accelerate implementation.

The architecture, product direction, core data model, business ownership, failure behavior, and roadmap must remain deliberate LifeInbox decisions.
