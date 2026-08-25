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

## V0.4 — Action Extractor（In Progress）

- ✅ Task 1 / Overall Task 31 — FastAPI Action Extraction Foundation（准备文本 → 有界、已验证的 Action 建议；不持久化）
- ✅ Task 2 / Overall Task 32 — Action Candidate Persistence & Java Integration（手动提取、Java 校验、MySQL PENDING Candidate 原子替换与查询）
- ✅ Task 3 / Overall Task 33 — Deadline / Date Normalization（`created_time` 稳定参考日期、确定性相对日期归一化、保守歧义处理）
- ✅ Task 4 / Overall Task 34 — Todo Core Business Model（独立业务表、来源追溯、Candidate 唯一约束与最小 Java 持久化）
- 用户确认/忽略、Candidate → Todo Conversion 与 Public Todo API 尚未实现
- Reminder / Action Item 后续能力仍为 Planned

## V0.5 — Relations（Planned）

- 内容关系
- 个人知识组织

## V1.0 — Personal AI（Long-term）

长期目标是让 LifeInbox 沿着 `Capture → Understand → Organize → Retrieve → Action` 形成个人信息系统。Agent、RAG、知识图谱等只有在前序产品能力和真实需求验证后再决定，不作为当前承诺。
