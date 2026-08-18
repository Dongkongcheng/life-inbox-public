# LifeInbox

> Capture First, Organize Later.

LifeInbox 是一个面向个人使用的信息收件箱，用于统一收集日常生活和学习过程中遇到的文字、网页链接、文件、图片等信息。

它希望解决一个很常见的问题：

> 我记得自己以前看到过、收藏过或者保存过某个东西，但需要的时候却找不到了。

LifeInbox 的长期目标不是做一个普通收藏夹，而是逐步形成一个能够帮助用户：

**收集 → 理解 → 整理 → 检索 → 行动**

的个人信息系统。

---

## ✨ Product Philosophy

LifeInbox 的核心原则是：

**Capture First, Organize Later.**

保存信息时，用户不应该被迫立即：

* 选择文件夹
* 选择分类
* 添加标签
* 写摘要
* 整理知识结构

第一件事应该只是：

```text
看到有价值的信息
        ↓
       Save
        ↓
      Inbox
```

整理工作可以以后再完成，也可以逐步交给 AI。

---

## 🧭 Product Flow

LifeInbox 的长期产品流程：

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

对应含义：

### Capture

快速收集：

* Text
* URL
* File
* Image

### Understand

未来通过 AI 理解信息：

* 内容摘要
* 自动分类
* 标签生成
* 关键词提取
* 实体提取

### Organize

自动帮助用户整理已经保存的信息。

### Retrieve

帮助用户重新找到过去保存过的内容：

* 关键词搜索
* 语义搜索
* RAG
* 个人知识检索

### Action

进一步从信息中识别：

* Todo
* Deadline
* Reminder
* Action Item

---

# 📌 Current Status

## V0.1 — Universal Inbox ✅

V0.1 主要解决：

> **如何把不同类型的信息快速保存进统一 Inbox？**

目前已经实现的主要功能：

* ✅ TEXT 文本收集
* ✅ URL 链接收集
* ✅ URL 自动获取网页标题
* ✅ FILE 文件上传
* ✅ IMAGE 图片上传
* ✅ 图片预览
* ✅ Inbox 列表
* ✅ 收藏 / 取消收藏
* ✅ 归档
* ✅ 删除
* ✅ 本地文件存储
* ✅ 基础文件安全检查
* ✅ URL 基础安全检查
* ✅ Vue 与 Spring Boot 前后端通信
* ✅ MySQL 数据持久化

V0.1 不依赖 AI。

即使未来 AI 服务不可用，LifeInbox 的基本 Capture 和 Inbox 管理能力仍然可以正常工作。

---

## V0.2 — AI Understanding 🚧

当前正在进入 V0.2 开发阶段。

V0.2 的目标是：

> **让 LifeInbox 开始理解用户保存进去的信息。**

计划逐步实现：

* ✅ Python AI Engine 基础服务
* ✅ FastAPI `/health`
* ✅ Java ↔ Python Health Integration
* ✅ AI 服务不可用时返回结构化 503
* ⏳ AI Summary
* ⏳ AI Tags
* ⏳ AI Classification
* ⏳ Keyword Extraction
* ⏳ Entity Extraction
* ⏳ AI Processing Status
* ⏳ AI Failure Handling

> 注意：以上带有 `⏳` 的功能属于开发计划，目前尚未完成。

---

# 🏗 Architecture

LifeInbox 当前采用：

```text
                    LifeInbox
                        │
                        ▼
                    Vue 3
                     Vite
                        │
                   REST API
                        │
                        ▼
               Spring Boot 4.1
                  Java 21
                        │
             ┌──────────┴──────────┐
             ▼                     ▼
           MySQL              Local Files
         Business Data          Uploads
```

从 V0.2 开始，将逐步增加：

```text
                    LifeInbox
                        │
                        ▼
                     Vue 3
                        │
                        ▼
                  Spring Boot
                        │
             ┌──────────┴──────────┐
             │                     │
             ▼                     ▼
           MySQL               FastAPI
                                  │
                                  ▼
                             AI Processing
```

---

# 🧩 Java + Python Responsibilities

LifeInbox 采用：

> **Java 负责业务，Python 负责 AI。**

## Java / Spring Boot

Java 是业务数据的 **Source of Truth**。

主要负责：

* InboxItem
* 业务状态
* 数据持久化
* 文件元数据
* 收藏
* 归档
* 删除
* 产品 API
* AI 任务状态
* AI 结果持久化
* 业务异常处理

---

## Python / FastAPI

Python AI Service 主要负责未来的 AI 处理能力，例如：

* 内容理解
* 摘要生成
* 自动标签
* 自动分类
* 关键词提取
* 实体提取
* 文档解析
* OCR
* Embedding
* Rerank

Python 不负责重复实现 InboxItem CRUD。

Python 不应该成为核心业务数据的 Source of Truth。

---

# 🧱 Core Model

LifeInbox 当前最重要的业务模型是：

```text
InboxItem
```

所有捕获的信息首先进入统一 Inbox。

当前主要类型：

```text
TEXT
URL
FILE
IMAGE
```

例如：

```text
                InboxItem
                    │
       ┌────────────┼────────────┐
       ▼            ▼            ▼
      TEXT          URL         FILE
                                  │
                                IMAGE
```

当前不会为不同 Capture 类型分别创建：

```text
TextItem
UrlItem
FileItem
ImageItem
```

等独立核心业务模型。

除非未来出现明确需求，否则优先保持统一 InboxItem 模型。

---

# 🛠 Tech Stack

## Frontend

* Vue 3
* Vite
* JavaScript

## Backend

* Java 21
* Spring Boot 4.1
* Spring MVC
* MyBatis-Plus
* MySQL

## AI Service

V0.2 开始逐步引入：

* Python
* FastAPI

## Storage

当前：

* MySQL
* Local File Storage

未来根据真实需求可能增加：

* Vector Database
* Redis
* Object Storage

但不会为了技术栈而提前引入。

---

# 📂 Project Structure

```text
life-inbox/
│
├── web/
│   └── Vue 3 frontend
│
├── server/
│   └── Spring Boot backend
│
├── ai-engine/
│   └── Python AI service
│
├── extension/
│   └── Future browser extension
│
├── docs/
│   └── Project documents
│
├── deploy/
│   └── Deployment configuration
│
├── AGENTS.md
│   └── Repository development instructions
│
├── .gitignore
│
└── README.md
```

> 部分目录目前可能仍处于预留或开发阶段，以仓库实际代码为准。

---

# 🗄 Database

数据库名称：

```text
life_inbox
```

核心表：

```text
inbox_item
```

主要字段：

```text
id
user_id
type
title
content
source_url
file_url
status
favorite
created_time
updated_time
```

其中：

```text
type
```

当前主要用于区分：

```text
TEXT
URL
FILE
IMAGE
```

`status` 用于表示 InboxItem 当前状态，例如：

```text
ACTIVE
ARCHIVED
```

`favorite` 用于表示收藏状态。

---

# 🚀 Local Development

## Requirements

建议准备以下环境：

```text
Java 21
Node.js
npm
MySQL
Git
```

V0.2 AI Engine 开始后还需要：

```text
Python
uv
```

---

# 1. Clone Repository

```bash
git clone <your-repository-url>

cd life-inbox
```

---

# 2. Prepare MySQL

创建数据库：

```sql
CREATE DATABASE life_inbox
DEFAULT CHARACTER SET utf8mb4
COLLATE utf8mb4_unicode_ci;
```

然后选择数据库：

```sql
USE life_inbox;
```

根据项目当前数据库结构创建：

```text
inbox_item
```

表。

---

# 3. Backend Configuration

后端配置位于：

```text
server/src/main/resources/application.yaml
```

数据库密码等敏感信息不要直接提交到 Git。

推荐通过环境变量提供，例如：

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/life_inbox?useUnicode=true&characterEncoding=utf8
    username: root
    password: ${MYSQL_PASSWORD}
```

然后在本地提供：

```text
MYSQL_PASSWORD
```

环境变量。

---

# 4. Start Backend

进入：

```bash
cd server
```

Windows：

```bash
.\mvnw.cmd spring-boot:run
```

后端默认运行：

```text
http://localhost:8080
```

---

# 5. Start Frontend

打开新的终端：

```bash
cd web
```

安装依赖：

```bash
npm install
```

启动：

```bash
npm run dev
```

前端默认运行：

```text
http://localhost:5173
```

---

# 🧪 Build

## Backend

Windows：

```bash
cd server

.\mvnw.cmd clean compile
```

---

## Frontend

```bash
cd web

npm run build
```

---

# 🤖 AI Service

LifeInbox 从 V0.2 开始逐步加入独立的：

```text
ai-engine
```

Python 服务。

V0.2 Task 1 已完成 FastAPI 基础服务和 Java/Python 健康检查链路，当前还没有接入大模型或真实 AI 处理能力。

进入 `ai-engine` 后安装依赖并启动：

```powershell
uv sync
uv run uvicorn app.main:app --host 127.0.0.1 --port 8000
```

Python 健康检查：

```text
GET http://localhost:8000/health
```

Spring Boot 集成检查：

```text
GET http://localhost:8080/api/ai/health
```

Java 默认访问 `http://localhost:8000`，可通过 `AI_SERVICE_BASE_URL` 覆盖。连接和读取超时统一配置在 `server/src/main/resources/application.yaml`；Python 不可用时，该 AI 接口返回 503，原有 Capture 功能不受影响。

目标架构：

```text
Spring Boot
     │
     │ JSON API
     ▼
   FastAPI
     │
     ▼
AI Processing
```

AI 服务失败时：

```text
TEXT
URL
FILE
IMAGE
```

等基础 Capture 功能仍然应该正常工作。

AI 是增强能力，而不是基础功能的前置条件。

---

# 🗺 Roadmap

## V0.1 — Capture ✅

```text
TEXT
URL
FILE
IMAGE

+
Favorite
Archive
Delete
```

---

## V0.2 — Understand 🚧

```text
Python AI Engine
        ↓
AI Summary
        ↓
AI Tags
        ↓
Classification
        ↓
Keyword / Entity Extraction
```

---

## V0.3 — Retrieve

计划：

```text
Keyword Search
       +
Semantic Search
       +
Embedding
       +
Rerank
```

目标：

> 不需要记住标题，也能重新找到以前保存过的信息。

---

## V0.4 — Action

计划：

```text
Information
     ↓
AI Understanding
     ↓
Action Extraction
     ↓
Todo / Deadline / Reminder
```

例如：

```text
“软件工程实验报告 8 月 25 日前提交”
```

未来 LifeInbox 可以识别：

```text
Todo:
提交软件工程实验报告

Deadline:
8 月 25 日
```

---

## V0.5 — Relations

未来尝试自动发现不同 InboxItem 之间的关系。

例如：

```text
Redis
├── Redis Lua
├── Redisson
├── Distributed Lock
└── Cache Consistency
```

---

## V1.0 — Personal AI

长期目标是让 AI 能够基于用户自己的 LifeInbox 数据：

* 搜索
* 回顾
* 总结
* 问答
* 推荐
* 发现关系
* 提取行动

最终形成一个真正属于用户自己的个人信息系统。

---

# 🔒 Security Principles

当前开发过程中重点关注：

* 文件路径穿越
* 不安全文件访问
* 文件上传校验
* SSRF
* 不可信 URL
* 不可信文件名
* 密码和 API Key 泄露
* AI 输出未经校验直接进入业务系统

LifeInbox 是个人项目，不追求过度工程化，但不会忽略明显的安全风险。

---

# 📖 Development Principles

LifeInbox 的开发遵循：

```text
Small Steps
Simple First
Real Requirement First
```

即：

* 小步迭代
* 优先简单实现
* 不为不存在的需求提前设计复杂系统
* 不为了技术栈而引入技术
* 每次只解决一个清晰的问题
* 完成功能后进行构建和测试
* 保持代码能够被自己重新看懂

更详细的开发约束请查看：

```text
AGENTS.md
```

---

# 🎯 Long-Term Goal

LifeInbox 最终希望实现：

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

用户只需要负责：

> **把值得留下的东西放进来。**

剩下的整理、理解、关联和重新发现，可以逐步交给 LifeInbox。
