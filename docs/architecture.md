# LifeInbox V0.2 架构

## 总览

```text
Vue 3 / Vite
      │ 产品 REST API
      ▼
Spring Boot / Java 21 ───────────────┐
      │                              │
      ├── MySQL（业务数据）           ├── 本地 uploads（FILE / IMAGE）
      │                              │
      └── FastAPI 内部 HTTP API ─────┘
                 │
        ┌────────┼─────────┐
        │        │         │
     URL 抓取  文档提取   图片 OCR
        └────────┼─────────┘
                 ▼
          AnalyzeService
                 │ 一次结构化 LLM 请求
                 ▼
       summary/category/tags/
          keywords/entities
```

Java 是业务数据的 Source of Truth。它负责 Capture、InboxItem、文件元数据、MySQL、AI 状态和 Attempt、分析结果持久化，以及收藏、归档和删除。Python 只负责内容提取、OCR、LLM 调用和结构化结果校验，不连接业务数据库，也不实现 InboxItem CRUD。

## 统一 Analyze Pipeline

```text
TEXT ───────────────────────┐
URL   → 安全网页正文提取 ────┤
FILE  → TXT/MD/PDF 提取 ─────┤→ AnalyzeService → AnalyzeResult
IMAGE → OCR 文字提取 ─────────┘
```

四种类型只在准备正文时分流。Python 最终都调用同一个 AnalyzeService，并用一次 LLM 请求生成五类结果。Java 收到结果后再次校验有限分类、数量、长度和实体类型，再进入同一持久化流程。

## Capture 与自动分析

Capture 的数据库事务只保存 InboxItem。事务提交后，`@TransactionalEventListener(AFTER_COMMIT)` 才把 id 交给 Spring 管理的有界线程池。默认关闭自动分析；开启后 Capture 响应仍不等待网页、文档、OCR 或 LLM。

线程池保持小规模（core 1、max 2、queue 20），线程名以 `life-inbox-ai-` 开头。队列拒绝不会回滚 Capture；系统会尽量把尚未处理的条目标成可手工重试的 FAILED。

## 状态、事务与 Attempt Guard

```text
短事务：条件 UPDATE 领取任务，写 PROCESSING + attemptId
                         ↓
事务外：准备正文 + 调用 FastAPI / LLM
                         ↓
短事务：替换五类结果并写 SUCCESS
        或匹配 attemptId 写 FAILED
```

外部调用不占用数据库长事务。新的 Attempt 可接管超过阈值的 PROCESSING；旧 Attempt 的成功和失败写入都必须匹配当前 `ai_attempt_id`，所以迟到结果不能覆盖后来接管的请求。stale 是根据状态和开始时间计算的派生值，不是数据库第五种状态。

## 失败降级

AI 是增强能力，不是 Capture 的依赖：

- FastAPI 或 LLM 不可用时，原始 InboxItem 仍已保存；
- URL 抓取、PDF 提取或 OCR 失败时，原 URL/FILE/IMAGE 仍存在；
- 分析开始不会清空旧结果，只有完整的新结果成功后才原子替换；
- 用户级响应只返回受控错误，内部异常和密钥不会直接交给前端。

## 当前边界

V0.2 的 IMAGE 是 OCR-based Analyze，不是通用图片理解。URL 不执行 JavaScript；PDF 只读取文本层；当前没有 MQ、调度器、跨进程持久队列、自动重试或启动扫描。进程中断后的 PROCESSING 由 stale Recovery 手工接管。
