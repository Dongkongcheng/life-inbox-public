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

## V0.3 — Smart Search（In Progress）

- ✅ Task 1 — Basic Keyword Search（title/content/summary/category，ACTIVE only）
- ✅ Task 2 — AI-derived Field Search（tags/keywords/entities，EXISTS 去重）
- ✅ Task 3 — Search Filter + Ranking + Highlight（type/category/favorite，确定性字段排序，安全文本高亮）
- Searchable Content
- Embedding / Semantic Search 的可行性验证
- Hybrid Search 与 Rerank 只在真实需求出现后评估

V0.3 已完成基础字段与 AI 派生字段检索，以及过滤、基础排序和安全高亮；整个版本仍在进行中。

## V0.4 — Action Extractor（Planned）

- Todo
- Deadline
- Reminder / Action Item

## V0.5 — Relations（Planned）

- 内容关系
- 个人知识组织

## V1.0 — Personal AI（Long-term）

长期目标是让 LifeInbox 沿着 `Capture → Understand → Organize → Retrieve → Action` 形成个人信息系统。Agent、RAG、知识图谱等只有在前序产品能力和真实需求验证后再决定，不作为当前承诺。
