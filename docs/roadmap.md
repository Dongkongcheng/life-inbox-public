# LifeInbox Roadmap

Roadmap 只描述方向；未标记完成的内容不是当前产品能力。

## V0.1 — Universal Inbox ✅

- TEXT、URL、FILE、IMAGE Capture
- 统一 InboxItem
- 本地文件存储
- Inbox 列表、收藏、归档、删除

## V0.2 — AI Organizer ✅

- Java ↔ FastAPI 明确边界
- TEXT Analyze
- URL 静态网页正文提取
- TXT/Markdown/PDF 文本提取
- IMAGE 本地 OCR
- 一次 LLM 生成 Summary、Category、Tags、Keywords、Entities
- AI 状态机、Retry、stale Recovery、Attempt Guard
- 可选 AFTER_COMMIT 后台自动 Analyze
- AI Failure 不影响 Capture

## V0.3 — Smart Search ✅

- ✅ Task 1 — Basic Keyword Search（title/content/summary/category，ACTIVE only）
- ✅ Task 2 — AI-derived Field Search（tags/keywords/entities，EXISTS 去重）
- ✅ Task 3 — Search Filter + Ranking + Highlight（type/category/favorite，确定性字段排序，安全文本高亮）
- ✅ Task 4 — Searchable Content Preparation（条目级统一正文，复用安全提取器，Attempt Guard）
- ✅ Task 5 — Embedding Pipeline（独立模型配置，严格向量校验，内部按需调用）
- ✅ Task 6 — Vector Storage / Indexing Lifecycle（Qdrant 派生索引、模型/维度隔离、异步 Upsert/Delete）
- ✅ Task 7 — Semantic Search（Query Embedding、Qdrant Top K、MySQL 权威解析、独立 Search Mode）
- ✅ Task 8 / Overall Task 28 — Hybrid Search（有界双路候选、RRF、ID 去重、单分支故障降级）
- ✅ Task 9 / Overall Task 29 — Rerank（有界 Item-level 精排、独立模型配置、失败回退 RRF）
- ✅ Task 10 / Overall Task 30 — V0.3 Final Acceptance（自动化测试、真实 Provider、故障降级、Capture 与 Search UI 回归）

V0.3 已完成基础字段与 AI 派生字段检索、过滤、基础排序、安全高亮、统一可搜正文、Embedding、条目级 Vector Index 生命周期、独立 Semantic Search、RRF Hybrid Search、可选 Rerank 与最终验收。RAG、Agent 和 Action Extractor 不属于本版本完成范围。

## V0.4 — Action Extractor ✅

- ✅ Task 1 / Overall Task 31 — FastAPI Action Extraction Foundation（准备文本 → 有界、已验证的 Action 建议；不持久化）
- ✅ Task 2 / Overall Task 32 — Action Candidate Persistence & Java Integration（手动提取、Java 校验、MySQL PENDING Candidate 原子替换与查询）
- ✅ Task 3 / Overall Task 33 — Deadline / Date Normalization（`created_time` 稳定参考日期、确定性相对日期归一化、保守歧义处理）
- ✅ Task 4 / Overall Task 34 — Todo Core Business Model（独立业务表、来源追溯、Candidate 唯一约束与最小 Java 持久化）
- ✅ Task 5 / Overall Task 35 — Candidate Confirm / Dismiss（终态状态机、原子 Candidate → Todo、幂等与并发保护）
- ✅ Task 6 / Overall Task 36 — Frontend Action Candidate UI（按需加载、手动检测、创建 Todo/忽略与局部状态反馈）
- ✅ Task 7 / Overall Task 37 — Automatic Action Extraction（可用正文 AFTER_COMMIT 自动触发、独立状态、Attempt Guard、失败降级与终态精确去重）
- ✅ Task 8 / Overall Task 38 — Todo List & Todo Lifecycle（OPEN/COMPLETED 列表、幂等完成/重开与前端 Todo 体验）
- ✅ Task 9 / Overall Task 39 — Source Traceability & Integration（按需只读来源上下文、有界预览、缺失降级与 Todo UI）
- ✅ Task 10 / Overall Task 40 — V0.4 Final Acceptance（全量测试、Fresh/增量 Schema 等价性、真实 Provider E2E、故障降级与文档对齐）
- Todo 编辑、删除、手动创建与 Reminder 等后续能力尚未实现
- Reminder / Action Item 后续能力仍为 Planned

V0.4 已完成 Action Extraction、确定性日期归一化、Candidate 决策、Todo 生命周期与来源追溯，并通过最终回归验收。

## V0.5 — Relations 🚧

- ✅ Task 1 / Overall Task 41 — Relation Core Model & Persistence Foundation（`InboxItem ↔ InboxItem`、仅 `RELATED_TO`、对称 Canonical Pair、ACTIVE-only 创建、MySQL 唯一约束与 Delete Cascade）
- ✅ Task 2 / Overall Task 42 — Bounded Relation Candidate Discovery（复用已有 Qdrant Point Vector、有界近邻、Java/MySQL 权威过滤、运行时 Score、不落库）
- ✅ Task 3 / Overall Task 43 — AI Relation Discovery Foundation（复用 Task 42 候选、一次有界 LLM 判断、严格 ID 校验、运行时 `RELATED_TO` 建议、不落库）
- ✅ Task 4 / Overall Task 44 — Relation Persistence Integration（AI 调用在事务外、最终 ACTIVE 校验、批量锁定/查询、Canonical 幂等新增、非破坏性持久化）
- ✅ Task 5 / Overall Task 45 — Related Items Product API（只读 MySQL 双向 Relation 查询、ACTIVE 产品过滤、有界结果、稳定排序、最小 DTO、读取不调用 AI）
- ✅ Task 6 / Overall Task 46 — Frontend Related Items UI（单条 Inbox 卡片懒加载、Loading/Empty/局部 Error、可重试读取、复用现有 Inbox 卡片导航、无列表 N+1）
- ✅ Task 7 / Overall Task 47 — Automatic Relation Discovery & Processing Lifecycle（Vector 成功后自动首次发现、独立状态与 Attempt Guard、同步手动重试、短事务原子持久化、安全失败降级）
- ✅ Task 8 / Overall Task 48 — Relation Rediscovery & Historical Backfill Foundation（SUCCESS 显式重新发现、新 Attempt 与既有 Guard、ACTIVE + NOT_PROCESSED 有界显式历史调度、仅消费已就绪 Vector、非破坏性增量持久化）
- 定时/自动 SUCCESS 重新发现、启动或无限历史回填、自动 Vector 修复、FAILED 自动重试与最终 V0.5 hardening 仍未实现；当前 UI 仍只读取已经持久化的 Relation

## V1.0 — Personal AI（Long-term）

长期目标是让 LifeInbox 沿着 `Capture → Understand → Organize → Retrieve → Action → Relations → Personal AI` 形成个人信息系统。Agent、RAG、知识图谱等只有在前序产品能力和真实需求验证后再决定，不作为当前承诺。
