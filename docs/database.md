# LifeInbox 数据库设计

## 1. 数据库定位

LifeInbox 当前业务数据库使用：

```text
MySQL
```

数据库名：

```text
life_inbox
```

核心原则：

```text
Spring Boot / Java
=
Business Data Owner

MySQL
=
Business Source of Truth
```

Java 是 LifeInbox 业务数据的唯一 Owner。

Python / FastAPI 负责 AI 理解、内容处理、Embedding、Semantic Retrieval、Rerank、Action Extraction 等能力，但不拥有核心业务数据。

Qdrant 负责向量检索，但同样不是业务数据库。

整体数据关系：

```text
                        LifeInbox

                           MySQL
                             │
                     Business Source
                        of Truth
                             │
           ┌─────────────────┼─────────────────┐
           ▼                 ▼                 ▼
       InboxItem         AI Result      Action Candidate
           │
           ▼
    Searchable Content
           │
           ▼
       Embedding
           │
           ▼
        Qdrant

  Derived / Rebuildable Retrieval Index
```

即使 Qdrant 中的全部数据丢失：

```text
LifeInbox Business Data
```

仍然必须完整保存在 MySQL 中。

---

# 2. 当前数据库阶段

当前版本状态：

```text
V0.1 — Universal Inbox        ✅ Completed
V0.2 — AI Organizer           ✅ Completed
V0.3 — Smart Search           ✅ Completed
V0.4 — Action Extractor       🚧 Current
```

V0.4 Task 35 后，MySQL 当前仍使用 7 张业务表：

```text
inbox_item
tag
inbox_tag
inbox_keyword
inbox_entity
action_candidate
todo
```

因此当前必须明确区分：

```text
Current Schema
=
V0.4 Current Schema
=
V0.3 Final Schema + action_candidate + todo
```

Task 35 的 Candidate Decision 使用现有两张表完成，没有新增 Schema。仍未实现的是：

```text
Independent Deadline / Reminder Schema
=
Planned / Not Yet Implemented
```

---

# 3. 当前表关系

当前主要关系：

```text
                        inbox_item
                            │
             ┌──────────────┼──────────────┬─────────────────┬──────────────┐
             │              │              │                 │              │
             ▼              ▼              ▼                 ▼              ▼
         inbox_tag     inbox_keyword   inbox_entity   action_candidate     todo
             │
             ▼
            tag
```

`todo` 的两个 Source 外键均可为空；它可以追溯 InboxItem / ActionCandidate，但来源删除时只清空引用，
不会删除已经形成的业务任务。

其中：

```text
inbox_item
```

始终是整个 LifeInbox 最重要的核心业务实体。

所有 Capture 内容统一从：

```text
InboxItem
```

开始。

---

# 4. `inbox_item`

`inbox_item` 统一保存：

```text
TEXT
URL
FILE
IMAGE
```

不按照不同 Capture 类型拆分核心业务表。

当前主要字段：

| 字段组      | 主要字段                                             | 作用                               |
| -------- | ------------------------------------------------ | -------------------------------- |
| 身份与类型    | `id`, `user_id`, `type`                          | InboxItem 唯一身份与 Capture 类型       |
| 原始内容     | `title`, `content`, `source_url`, `file_url`     | TEXT 正文、URL、受管文件地址等原始业务信息        |
| 派生检索正文   | `searchable_content`                             | URL / FILE / IMAGE 提取后的统一条目级检索正文 |
| AI 主结果   | `summary`, `category`                            | 最近一次成功 Analyze 的主 AI 结果          |
| AI 状态    | `ai_status`, `ai_attempt_id`, `ai_error_message` | AI 状态机、Attempt Guard 和安全错误信息     |
| AI 时间    | `ai_started_time`, `ai_finished_time`            | 当前有效 Attempt 的开始和结束时间            |
| Action 状态 | `action_status`, `action_attempt_id`, `action_error_message` | 独立 Action 状态机、Attempt Guard 与安全错误摘要 |
| Action 时间 | `action_started_time`, `action_finished_time`    | 当前有效 Action Attempt 的开始和结束时间      |
| Inbox 状态 | `status`, `favorite`                             | ACTIVE / ARCHIVED 与收藏状态          |
| 审计时间     | `created_time`, `updated_time`                   | 创建与更新时间                          |

具体字段定义和类型以当前真实 SQL Schema 为准。

早期总体规划中可能出现：

```text
raw_content
file_id
created_at
```

等概念字段名。

不要为了匹配旧规划而重新命名当前已经正常工作的数据库字段。

遵循：

```text
Current Repository
>
Old Placeholder Naming
```

---

# 5. Unified Inbox 原则

所有 Capture 内容统一进入：

```text
TEXT ───┐
URL ────┤
FILE ───┤
IMAGE ──┤
        ▼
    InboxItem
```

不要建立：

```text
text_item
url_item
file_item
image_item
```

四套互相独立的核心业务模型。

不同类型可以拥有不同：

```text
Extractor
Parser
OCR
Processing Strategy
```

但业务状态统一围绕：

```text
InboxItem
```

组织。

---

# 6. AI Processing State

当前 `ai_status` 只有：

```text
NOT_PROCESSED
PROCESSING
SUCCESS
FAILED
```

## `NOT_PROCESSED`

尚未开始 AI Analyze。

## `PROCESSING`

当前存在有效 Analyze Attempt。

## `SUCCESS`

最近一次有效 Attempt 成功。

## `FAILED`

最近一次有效 Attempt 失败，可以 Retry / Re-analyze。

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

# 7. stale Processing

stale 不作为独立数据库状态保存。

数据库中不存在：

```text
STALE
```

Java 根据：

```text
ai_status = PROCESSING
+
ai_started_time
+
processing-stale-after
```

动态判断：

```text
aiProcessingStale
```

这样可以避免把：

```text
Runtime Timeout Judgment
```

错误建模成永久数据库状态。

---

# 8. AI Attempt Guard

每次 Analyze 都拥有：

```text
ai_attempt_id
```

例如：

```text
Attempt A
id = AAA
```

如果 Attempt A 超时，

新的：

```text
Attempt B
id = BBB
```

可以接管处理权。

如果旧 Attempt A 后来返回：

```text
AAA != 当前 BBB
```

旧结果不能：

```text
覆盖新的 AnalyzeResult
改变新的 AI Status
覆盖新的 Searchable Content
覆盖较新的处理结果
```

核心原则：

```text
Only Current Attempt
May Commit Generated Result
```

---

# 9. Analyze Result Replacement

Re-analyze 成功时，Java 在一个短事务中：

1. 检查当前 `ai_attempt_id`；
2. 更新 `summary`、`category`；
3. 完整替换 Tags；
4. 完整替换 Keywords；
5. 完整替换 Entities；
6. 最后把同一 Attempt 标记为 `SUCCESS`。

如果事务中的任一步出现异常：

```text
ROLLBACK
```

失败 Attempt：

```text
只更新 AI 状态和安全错误信息
```

不会主动删除上一次成功的：

```text
Summary
Category
Tags
Keywords
Entities
```

因此：

```text
New Analyze Failure
≠
Old Successful Result Lost
```

---

# 10. Searchable Content

`searchable_content` 当前为可空：

```text
MEDIUMTEXT
```

它属于：

```text
Derived / Rebuildable Retrieval Data
```

而不是：

```text
Original Business Source Data
```

---

## TEXT

TEXT 直接使用：

```text
content
```

参与检索。

不额外复制原文到：

```text
searchable_content
```

避免保存两份相同原始文本。

---

## URL

URL 复用当前安全网页正文提取能力：

```text
URL
 ↓
Web Content Extraction
 ↓
Normalized Text
 ↓
searchable_content
```

---

## FILE

FILE 复用当前已有文档文本提取能力。

当前支持范围以真实代码为准，例如：

```text
TXT
Markdown
Text-layer PDF
```

提取成功后写入：

```text
searchable_content
```

---

## IMAGE

IMAGE 当前主要通过：

```text
OCR
```

得到文本：

```text
IMAGE
 ↓
OCR
 ↓
Normalized Text
 ↓
searchable_content
```

当前 OCR 能力不等同于完整 General Vision。

---

# 11. Searchable Content Normalization

Java 当前统一进行：

```text
NFKC
换行归一
空白归一
```

当前最大保存长度：

```text
20,000 Java Characters
```

应用层应该继续限制派生正文长度。

不要因为数据库字段是 `MEDIUMTEXT` 就允许无限保存提取文本。

---

# 12. 为什么使用 `MEDIUMTEXT`

`searchable_content` 使用：

```text
MEDIUMTEXT
```

主要是为了在：

```text
utf8mb4
+
最多约 20,000 Java Characters
```

条件下拥有足够明确的容量余量。

数据库字段容量：

```text
≠
应用允许的最大输入长度
```

应用层长度限制仍然必须生效。

---

# 13. Searchable Content 更新流程

成功提取正文后：

```text
Content Preparation
       ↓
Attempt Guard
       ↓
Short Database Update
       ↓
searchable_content
       ↓
LLM Analyze
```

因此：

```text
Content Extraction Success
+
LLM Failure
```

不会导致已经成功准备的 Searchable Content 丢失。

---

# 14. Searchable Content Failure

如果发生：

```text
URL Extraction Failure
FILE Extraction Failure
OCR Failure
Empty Extraction Result
```

不会自动清空：

```text
旧 searchable_content
```

同样：

```text
Expired Attempt
```

不能覆盖较新的 Searchable Content。

---

# 15. Searchable Content Reprocessing

重新 Analyze 时：

成功提取的新正文：

```text
Replace Old Searchable Content
```

而不是：

```text
Append Forever
```

当前仍然采用：

```text
Item-level Searchable Content
```

目前没有：

```text
document_chunk
chunk table
chunk embedding
chunk retrieval
```

---

# 16. `tag`

`tag` 是全局展示字典。

主要字段之一：

```text
normalized_name
```

用于保存经过：

```text
NFKC
Whitespace Normalization
Lowercase
```

处理后的唯一值。

这样可以减少：

```text
Redis
redis
 Redis
```

等仅格式不同的重复 Tag。

---

# 17. `inbox_tag`

`inbox_tag` 建立：

```text
InboxItem
↔
Tag
```

多对多关系。

当前使用复合主键：

```text
(inbox_item_id, tag_id)
```

同一个 InboxItem 不会重复关联同一个 Tag。

删除 InboxItem 时：

相关 `inbox_tag` 根据当前外键设计级联清理。

没有任何 InboxItem 引用的全局 Tag：

当前不会自动删除。

---

# 18. `inbox_keyword`

`inbox_keyword` 直接隶属于单条：

```text
InboxItem
```

当前 Keyword 最大长度：

```text
64 characters
```

唯一约束：

```text
(inbox_item_id, keyword)
```

同一 InboxItem 不重复保存完全相同 Keyword。

删除 InboxItem 时：

相关 Keyword 根据当前 Foreign Key 规则清理。

---

# 19. `inbox_entity`

`inbox_entity` 同样直接属于 InboxItem。

主要信息：

```text
name
type
```

当前唯一约束：

```text
(inbox_item_id, name, type)
```

当前 Entity 类型：

```text
PERSON
ORGANIZATION
LOCATION
TECHNOLOGY
PRODUCT
EVENT
OTHER
```

Entity Type 属于有限枚举语义。

不要让 LLM 任意创造无限的新 Entity 类型。

---

# 20. Keyword Search 与 MySQL

Keyword Search 当前继续主要依赖：

```text
MySQL
```

可检索字段以实际代码为准，当前包括：

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

关联数据查询使用：

```text
EXISTS
```

或当前等价数据库实现，

避免：

```text
同一个 InboxItem
因为多个 Tag / Keyword 命中
出现多条重复结果
```

---

# 21. Qdrant 不属于业务 Schema

V0.3 已经引入：

```text
Qdrant
```

用于：

```text
Semantic Retrieval
```

但 Qdrant 不是 MySQL Schema 的一部分。

数据所有权：

```text
MySQL
=
Authoritative Business Data

Qdrant
=
Derived / Rebuildable Retrieval Index
```

Qdrant 当前可以保存类似：

```text
InboxItem ID
Embedding Vector
Embedding Model
Content Hash
Indexed Time
Minimal Retrieval Metadata
```

但不能成为：

```text
InboxItem Source of Truth
Favorite Source of Truth
Archive Source of Truth
AI Status Source of Truth
Todo Source of Truth
Future Deadline Source of Truth
```

---

# 22. Embedding 不进入 MySQL

当前不要在：

```text
inbox_item
```

中增加：

```text
embedding
vector
embedding_json
```

等字段。

当前架构：

```text
Searchable Content
       ↓
Embedding Provider
       ↓
Vector
       ↓
Qdrant
```

Embedding 是：

```text
Rebuildable Retrieval Data
```

而不是业务字段。

---

# 23. Search Runtime Score 不持久化

以下 Score 属于单次查询产生的运行时数据：

```text
Semantic Score
RRF Score
Rerank Score
```

不要保存进：

```text
inbox_item
```

也不要创建：

```text
semantic_score
rrf_score
rerank_score
```

数据库字段。

原因：

```text
Query A
→ Score A

Query B
→ Score B
```

这些 Score 不属于 InboxItem 的永久业务属性。

---

# 24. V0.4 数据库方向

当前已经进入：

```text
V0.4 — Action Extractor
```

当前数据库已经是：

```text
V0.4 Current Schema
=
V0.3 Final Schema + action_candidate + todo + InboxItem Action Processing State
```

V0.4 首先需要解决的重要数据边界：

```text
AI 检测出的 Action
≠
用户真正确认的 Todo
```

因此推荐：

```text
AI Suggestion
      ↓
Action Candidate
      ↓
User Confirmation
      ↓
Todo
```

`action_candidate` 与 `todo` 已实际建表；Task 35 已使用现有 Schema 实现用户 Accept / Dismiss，
没有新增 Decision 或 History 表。

---

# 25. Action Candidate 与 Todo

推荐概念：

```text
InboxItem
    │
    ▼
Action Extraction
    │
    ▼
action_candidate
    │
    ├── ACCEPT
    │      ↓
    │     todo
    │
    └── DISMISS
```

其中：

```text
action_candidate
```

属于：

```text
AI-generated Derived Suggestion
```

而：

```text
todo
```

属于：

```text
User-confirmed Business Data
```

两者不应该混为同一种状态。

---

# 26. 为什么需要 Action Candidate

例如原始 InboxItem：

```text
软件工程课程设计
8月25日前交报告
```

AI 可以识别：

```text
提交软件工程课程设计报告

Deadline:
2026-08-25
```

但此时：

```text
Todo
```

不应该自动创建。

正确流程：

```text
AI Detect
    ↓
Action Candidate
    ↓
User Confirm
    ↓
Todo
```

因此不要：

```text
LLM Response
↓
直接 INSERT todo
```

---

# 27. `action_candidate` 当前结构

`action_candidate` 已在 V0.4 Task 32 实现，其职责是：

> 保存 AI 从 InboxItem 中识别出的 Action 建议。

当前字段为：

```text
id

inbox_item_id

action_type

title

deadline_text
deadline_date

evidence

status

created_time
updated_time
```

关键约束：

```text
action_type: TODO / DEADLINE（由 Java 受控 Enum 校验）
status: PENDING / ACCEPTED / DISMISSED（自动与手动提取都只创建 PENDING）
index: (inbox_item_id, status)
foreign key: inbox_item_id → inbox_item.id ON DELETE CASCADE
```

Task 33 没有修改 Schema。`deadline_date` 仍是可空 `DATE`：它既可保存完整绝对日期，也可保存 Python 根据
`InboxItem.created_time` 派生的稳定 `referenceDate` 所解析出的相对日期；无法安全确定时保留
`deadline_text`，并让 `deadline_date = NULL`。

---

# 28. Action Candidate Status

当前已实现的状态保持非常简单：

```text
PENDING
ACCEPTED
DISMISSED
```

## `PENDING`

AI 已识别出 Candidate，

但用户尚未决定。

## `ACCEPTED`

用户已经接受该 Candidate。

## `DISMISSED`

用户明确忽略该 Candidate。

Task 35 当前状态机：

```text
PENDING ── Accept  ──> ACCEPTED
PENDING ── Dismiss ──> DISMISSED
```

`ACCEPTED` 与 `DISMISSED` 都是终态。Accept 使用 `SELECT ... FOR UPDATE` 锁定 Candidate，在同一短事务中创建
Todo 并更新状态；`todo.source_action_candidate_id` 的唯一约束保证一个 Candidate 最多对应一个 Todo。
重复 Accept 返回已有 Todo，重复 Dismiss 返回当前 Candidate，普通 Re-extraction 仍只删除 `PENDING`。

暂时不要提前加入：

```text
SNOOZED
EXPIRED
AUTO_ACCEPTED
SYNCED
FAILED_SYNC
REMINDER_SENT
```

等复杂状态。

---

# 28.1 Action Processing State 与 Attempt Guard

`inbox_item` 现在同时拥有两套互相独立的处理元数据：

```text
Analyze:
ai_status / ai_attempt_id / ai_error_message / ai_*_time

Action Extraction:
action_status / action_attempt_id / action_error_message / action_*_time
```

这不是重复状态。Analyze 与 Action 可以独立成功、失败和重试；Action 失败不能修改 `ai_status`，Analyze 失败也
不能阻止已经拥有可用正文的 Action Attempt。`action_status` 使用：

```text
NOT_PROCESSED → PROCESSING → SUCCESS / FAILED
```

`SUCCESS + 0 Candidates` 是合法结果。stale 仍由 `PROCESSING + action_started_time + processing-stale-after`
运行时判断，不新增数据库 `STALE` 状态；Action 与 Analyze 复用同一阈值配置以避免无意义配置分裂，但状态与
Attempt ID 完全独立。

每次 Action 开始先在短事务中写入新的 UUID Attempt；FastAPI 调用不在事务内。成功完成事务会先锁定当前
Attempt Owner，再锁定同一 InboxItem 的 Candidate，删除旧 `PENDING`、插入新 `PENDING`，最后写入 `SUCCESS`。
任一步失败都会回滚整个完成事务，并由独立短事务按当前 Attempt 记录 `FAILED`。迟到成功或失败因为 Attempt ID
不匹配而不产生任何 Candidate 或状态修改。

自动重新提取永远保留 `ACCEPTED` / `DISMISSED`。若新结果与终态 Candidate 的 `action_type + 规范化 title +
规范化 deadline_text + deadline_date` 完全相同，则不重新插入 PENDING；当前不做语义相似度去重。

---

# 29. `todo` 当前结构

`todo` 已在 V0.4 Task 34 实现，其职责是：

> 保存独立的用户业务任务状态。

它属于：

```text
Business Data
```

而不是 AI 派生数据。

当前字段为：

```text
id

source_inbox_item_id
source_action_candidate_id

title
description

status

due_date

completed_time

created_time
updated_time
```

关键约束：

```text
status: OPEN / COMPLETED（Task 34 新建时只允许 OPEN）
source_inbox_item_id: nullable，ON DELETE SET NULL
source_action_candidate_id: nullable + UNIQUE，ON DELETE SET NULL
due_date: nullable DATE
completed_time: 新建 Todo 时为 NULL
```

Task 34 建立 Entity、Mapper 与内部 Service 持久化基础；Task 35 已复用该 Service 实现 Candidate Accept / Dismiss
和 Candidate → Todo Conversion。Task 38 在不修改 Schema 的前提下增加独立 Todo List、Complete 与 Reopen：
列表只查询 `todo`，不依赖 Source JOIN；生命周期在短事务中锁定 Todo 行并保持 `OPEN → completed_time NULL`、
`COMPLETED → completed_time NOT NULL`。当前仍没有 Todo Edit、Delete 或 Manual Create API。

---

# 30. Deadline 第一版不建议单独建表

最初总体规划中曾经将：

```text
todo
deadline
```

分别列为概念模型。

进入实际实现阶段后，

V0.4 第一版更推荐：

```text
Todo
 ├── title
 ├── status
 └── due_date nullable
```

也就是：

```text
Deadline
=
Optional Property of Todo
```

例如：

```text
8月25日前交软件工程报告
```

本质上可以表示为：

```text
Todo:
  title = 提交软件工程报告
  due_date = 2026-08-25
```

没有必要一开始创建：

```text
todo
+
deadline
```

两套生命周期高度重叠的业务实体。

---

# 31. 什么时候考虑独立 `deadline`

只有未来出现真正独立的业务需求，例如：

```text
Deadline 有独立生命周期

一个 Deadline 关联多个 Todo

Deadline 有独立确认流程

Deadline 有独立 Reminder

Deadline 独立参与 Calendar / Scheduling
```

再考虑把：

```text
deadline
```

升级为独立业务实体。

遵循：

```text
Requirement First
Schema Second
```

---

# 32. Action Source Traceability

未来 Action Candidate 和 Todo 应尽可能追溯到：

```text
InboxItem
```

概念：

```text
InboxItem
    ↓
Action Candidate
    ↓
Todo
```

这样系统可以回答：

```text
这个 Todo 为什么出现？

这个截止日期来自哪条保存的信息？
```

---

# 33. 不复制完整 Source Content

不要为了 Action Traceability，

把整份：

```text
searchable_content
PDF text
Web body
OCR result
```

复制到：

```text
action_candidate
todo
```

表中。

优先保存：

```text
source_inbox_item_id
```

引用 Source of Truth。

如果确实需要保留证据，

只保存最小必要内容，例如：

```text
original_deadline_text
small evidence text
```

---

# 34. Deadline 原始表达与标准化值

Deadline Extraction 应区分：

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
“下周五之前”
```

可能最终得到：

```text
Normalized:
2026-08-28
```

它们不是同一种数据。

原始表达有利于：

```text
Traceability
User Confirmation
Debugging
```

---

# 35. 不确定日期

如果 AI 只看到：

```text
周五之前交
```

但无法可靠判断：

```text
哪一周
哪一年
timezone
具体时间
```

系统不应该要求：

```text
必须生成精确 datetime
```

可以允许：

```text
normalized deadline = NULL
```

或者在未来 Schema 中表达：

```text
requires confirmation
```

不要为了满足数据库非空字段而伪造时间。

---

# 36. 用户确认优先级

V0.4 以后应该保持：

```text
User-confirmed Business State
            >
Current Business State
            >
AI-generated Candidate
```

例如：

AI 第一次识别：

```text
deadline = 2026-08-25
```

用户手动修改：

```text
deadline = 2026-08-28
```

后来重新 Analyze 后 AI 又产生：

```text
deadline = 2026-08-25
```

系统不能：

```text
Silent Overwrite

2026-08-28
→
2026-08-25
```

用户确认后的 Todo：

```text
=
Business Fact
```

AI Candidate：

```text
=
Suggestion
```

---

# 37. Action Reprocessing

重新执行：

```text
Analyze
Action Extraction
Retry
```

可以根据后续正式设计更新：

```text
未确认的 Candidate
```

但不能自动覆盖：

```text
User Accepted Todo
User Edited Todo
Completed Todo
Dismissed User Decision
```

用户业务状态不能因为 LLM 再跑一次而被还原。

---

# 38. Source InboxItem 删除行为

未来需要区分：

```text
Action Candidate
```

和：

```text
Confirmed Todo
```

推荐原则：

## 未确认 Candidate

属于 InboxItem 的 AI 派生结果。

如果 Source InboxItem 被真正删除：

可以考虑一起清理。

---

## 已确认 Todo

一旦用户确认：

Todo 已经成为独立业务状态。

删除原 InboxItem：

不应该默认删除用户已经确认的 Todo。

因此未来 Foreign Key 设计必须慎重选择：

```text
CASCADE
SET NULL
RESTRICT
```

不能机械地全部使用 Cascade。

---

# 39. Archive 与 Todo 生命周期

Archive InboxItem：

```text
≠
Complete Todo
```

也：

```text
≠
Delete Todo
```

Inbox 生命周期和 Todo 生命周期属于不同业务概念。

不能因为：

```text
InboxItem
→ ARCHIVED
```

自动：

```text
Todo
→ COMPLETED
```

---

# 40. Delete 与 Todo 生命周期

同样：

```text
Delete InboxItem
```

不应该无条件等于：

```text
Delete User-confirmed Todo
```

Todo 一旦被用户确认，

应该拥有自己的生命周期。

---

# 41. Todo Status

V0.4 第一版 Todo 状态已保持为两个值：

最小方向：

```text
OPEN
COMPLETED
```

Task 38 实现的转换为：

```text
OPEN --Complete--> COMPLETED
COMPLETED --Reopen--> OPEN
```

Complete 只在第一次有效转换时写入 Java 业务时间，重复请求不刷新 `completed_time`；Reopen 必须清空它。
两个动作都不修改来源 Candidate。是否需要：

```text
CANCELLED
```

应该根据实际产品需求决定。

不要第一版就增加：

```text
IN_PROGRESS
BLOCKED
WAITING
DEFERRED
SNOOZED
ARCHIVED
```

等复杂状态机。

---

# 42. Reminder 暂不建模

V0.4 Action Extractor：

```text
≠
Reminder System
```

因此当前不要提前创建：

```text
reminder
reminder_job
notification
schedule
```

等表。

未来真正开发 Reminder 时再设计。

---

# 43. Calendar 暂不建模

识别出：

```text
Deadline
```

不意味着已经需要：

```text
Google Calendar Integration
```

V0.4 第一阶段不要为了可能存在的未来 Calendar 功能提前增加：

```text
calendar_event_id
calendar_provider
external_event_id
sync_status
```

等字段。

---

# 44. Relations 暂不进入当前 Schema

V0.5 计划中的：

```text
content_relation
```

目前仍属于：

```text
PLANNED
```

不是当前 Schema。

未来可以优先从简单 MySQL 模型开始，例如概念：

```text
content_relation
────────────────
source_id
target_id
relation_type
score
```

但不要为了 Roadmap 提前建表。

---

# 45. Personal AI 暂不产生 Schema

当前不要为了未来：

```text
RAG
Agent
Conversation
Memory
MCP
Workflow
```

提前创建：

```text
conversation
message
agent_memory
workflow
tool_call
```

等数据库表。

等真正进入对应版本并出现真实需求后再设计。

---

# 46. 数据库索引原则

索引只服务于真实查询。

遵循：

```text
Current Query Pattern
       ↓
Evaluate Index
```

不要因为：

```text
以后可能需要
```

就提前建立大量索引。

尤其不要未经验证就在：

```text
searchable_content
AI generated text
long text fields
```

上建立复杂索引体系。

当前向量检索已经由：

```text
Qdrant
```

负责。

---

# 47. Foreign Key 原则

对于完全依赖 InboxItem 生命周期的数据，例如当前：

```text
inbox_tag
inbox_keyword
inbox_entity
```

可以继续按照现有合理 Foreign Key 规则清理。

但是未来：

```text
User-confirmed Todo
```

和：

```text
Derived AI Child Data
```

生命周期不同。

因此不要机械复制：

```text
ON DELETE CASCADE
```

到所有未来表。

---

# 48. 数据库迁移原则

当前项目没有：

```text
Flyway
Liquibase
```

Schema Migration 继续使用：

```text
Manual SQL Migration
```

历史 Migration：

```text
必须保留
```

不要修改旧 Migration，

让历史看起来像数据库一直就是当前状态。

---

# 49. V0.3 Fresh Schema

V0.3 Final Schema 文件以仓库当前真实路径为准。

当前文档和 SQL 目录应保持一致，例如仓库如果实际使用：

```text
docs/sql/v0.3-schema.sql
```

则该文件代表：

```text
Fresh Install
→
V0.3 Final Database State
```

不要为了 V0.4 修改或覆盖该历史版本文件。

如果仓库当前实际路径不是 `docs/sql/v0.3-schema.sql`，

应以仓库真实文件位置为准，并同步修改本文档。

---

# 50. V0.3 Incremental Migration

当前已存在的 Searchable Content 增量 Migration，

以仓库真实文件为准，例如：

```text
docs/sql/v0.3-task4-add-searchable-content.sql
```

用于已有旧数据库升级。

不要在已经存在业务数据的数据库上重复执行：

```text
Fresh Schema
```

---

# 51. V0.4 Fresh Schema

V0.4 当前 Fresh Schema 已建立：

```text
docs/sql/v0.4-schema.sql
```

用于：

```text
Fresh Install
→
Current V0.4 Database State
```

它等价于 `v0.3-schema.sql` 依次应用 Task 32、Task 34 与 Task 37 增量 Migration，且没有覆盖历史文件：

```text
v0.3-schema.sql
```

---

# 52. V0.4 Incremental Migration

已有 V0.3 数据库升级到 V0.4：

应该使用增量 Migration。

当前依次提供：

```text
docs/sql/v0.4-task2-add-action-candidate.sql
docs/sql/v0.4-task4-add-todo.sql
docs/sql/v0.4-task7-add-action-processing-state.sql
```

它们分别新增 `action_candidate`、`todo`，以及 InboxItem 的独立 Action Processing Metadata。
Deadline 第一版继续使用 `todo.due_date`，Reminder 仍未建表。

---

# 53. SQL 使用原则

## 全新安装

执行当前版本对应的：

```text
Fresh Schema
```

当前 V0.4 全新环境：

```text
docs/sql/v0.4-schema.sql
```

具体路径以仓库真实结构为准。

---

## 已有数据库升级

按照：

```text
docs/sql/
```

中真实存在的历史增量 Migration 顺序执行。

不要跳过中间必要 Migration。

---

## 从 V0.3 进入当前 V0.4

执行：

```text
docs/sql/v0.4-task2-add-action-candidate.sql
docs/sql/v0.4-task4-add-todo.sql
docs/sql/v0.4-task7-add-action-processing-state.sql
```

进入一个版本：

```text
≠
必须修改数据库
```

---

# 54. Current 与 Planned 必须分开

`database.md` 必须始终明确区分：

```text
CURRENT
```

与：

```text
PLANNED
```

当前存在的表：

必须和真实 MySQL Schema 一致。

未来模型：

必须明确标注：

```text
Planned
Recommended Direction
Not Yet Implemented
```

禁止把计划功能写成已经完成。

---

# 55. 当前数据库总结

截至 V0.4 Task 35：

```text
MySQL
│
├── inbox_item
├── tag
├── inbox_tag
├── inbox_keyword
├── inbox_entity
├── action_candidate
└── todo
```

当前没有：

```text
deadline
reminder
content_relation
conversation
agent_memory
```

这些表。

当前正在进入：

```text
V0.4 — Action Extractor
```

推荐逐步演进方向：

```text
                     InboxItem
                         │
                         ▼
                  Action Candidate
                         │
                   User Accept / Dismiss
                         │
                         ▼
                        Todo
                         │
                        └── optional due_date
```

而不是一次性建立：

```text
Todo
Deadline
Reminder
Calendar
Workflow
Agent
```

全部业务模型。

---

# 56. 长期数据库原则

LifeInbox 数据库模型继续遵循：

```text
Capture
   ↓
InboxItem
   ↓
Derived AI Understanding
   ↓
Retrieval
   ↓
Action Candidate
   ↓
User-confirmed Business Action
```

核心数据所有权：

```text
MySQL
=
Business Source of Truth
```

AI 结果：

```text
=
Derived / Suggested Information
```

Qdrant：

```text
=
Derived / Rebuildable Retrieval Index
```

当前 Action Candidate：

```text
=
AI Suggestion
```

当前 Todo Core Model：

```text
=
Independent Business State Foundation
```

始终坚持：

```text
Requirement First
Schema Second
```

以及：

```text
AI Suggestion
≠
User-confirmed Business State
```
