# LifeInbox

LifeInbox 是一个个人信息收件箱，用来快速收集暂时来不及整理的文字、链接、文件和图片。

核心理念：**Capture First, Organize Later.**

长期产品流程：

```text
Capture → Understand → Organize → Retrieve → Action
```

当前仓库处于 **V0.1 Capture 阶段**，重点是稳定地保存信息。Understand、Retrieve、Action 等能力仍属于未来规划。

## V0.1 已实现功能

- TEXT：保存文字内容
- URL：保存网页链接；未填写标题时尝试读取 HTML `<title>`，失败时使用域名或 URL 降级
- FILE：上传 PDF、TXT、MD、Office 文档和 ZIP 文件，并支持查看或下载
- IMAGE：上传常见图片，支持本地提交前预览、列表缩略图和原图查看
- Inbox：查询 `ACTIVE` 状态的信息
- 收藏 / 取消收藏
- 归档（保留数据库记录，但不再出现在主 Inbox）
- 删除 InboxItem
- 对本地上传文件进行受限访问

## 技术栈

### Frontend

- Vue 3
- Vite

### Backend

- Java 21
- Spring Boot 4.1
- MyBatis-Plus 3.5.17
- MySQL
- Jsoup（只用于读取网页标题）

### Future（尚未实现）

- Python
- FastAPI

## 项目目录

```text
life-inbox/
├─ server/                 Spring Boot 后端与 Maven Wrapper
│  ├─ src/main/            Controller、Service、Mapper、Entity 与配置
│  ├─ src/test/            后端测试
│  └─ uploads/             运行时上传目录，不提交到 Git
├─ web/                    Vue 3 + Vite 前端
│  └─ src/                 Capture 表单与 Inbox 列表页面
├─ docs/history/           各阶段任务记录
├─ AGENTS.md               仓库开发约定
└─ README.md
```

## 本地运行

### 1. 环境要求

- JDK 21
- MySQL 8.x
- Node.js 与 npm
- Maven 3.9.x（可选；仓库也提供 Maven Wrapper）

### 2. 准备数据库

先创建数据库和当前 V0.1 使用的数据表：

```sql
CREATE DATABASE IF NOT EXISTS life_inbox
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

USE life_inbox;

CREATE TABLE inbox_item (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    user_id      BIGINT NULL COMMENT '用户ID',
    type         VARCHAR(20) NOT NULL COMMENT '内容类型',
    title        VARCHAR(255) NULL COMMENT '标题',
    content      TEXT NULL COMMENT '内容正文',
    source_url   VARCHAR(1000) NULL COMMENT '来源链接',
    file_url     VARCHAR(1000) NULL COMMENT '文件访问地址',
    status       VARCHAR(20) DEFAULT 'ACTIVE' NOT NULL COMMENT '状态',
    favorite     TINYINT DEFAULT 0 NOT NULL COMMENT '是否收藏',
    created_time DATETIME DEFAULT CURRENT_TIMESTAMP NOT NULL COMMENT '创建时间',
    updated_time DATETIME DEFAULT CURRENT_TIMESTAMP NOT NULL
        ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间'
) COMMENT '信息收件箱条目表';
```

后端默认连接 `localhost:3306/life_inbox`，用户名为 `root`。数据库密码不写入仓库，请在启动后端的同一个 PowerShell 窗口设置：

```powershell
$env:MYSQL_PASSWORD = "你的数据库密码"
```

如需调整数据库地址或用户名，可修改 `server/src/main/resources/application.yaml`，但不要提交真实密码。

### 3. 启动后端

```powershell
cd server
.\mvnw.cmd spring-boot:run
```

如果本机 Maven Wrapper 无法工作，也可以使用已安装的 Maven：

```powershell
mvn spring-boot:run
```

后端默认运行在 `http://localhost:8080`。上传文件保存在后端启动目录下的 `uploads/`；普通文件最大 20MB，图片最大 10MB。

### 4. 启动前端

新开一个终端：

```powershell
cd web
npm install
npm run dev
```

访问 `http://localhost:5173`。开发服务器会把 `/api` 请求代理到 `http://localhost:8080`。

## 主要接口

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| `POST` | `/api/inbox` | 新增 TEXT 或 URL |
| `POST` | `/api/inbox/file` | 上传 FILE |
| `POST` | `/api/inbox/image` | 上传 IMAGE |
| `GET` | `/api/inbox` | 查询 ACTIVE InboxItem |
| `PUT` | `/api/inbox/{id}/favorite` | 收藏 |
| `PUT` | `/api/inbox/{id}/unfavorite` | 取消收藏 |
| `PUT` | `/api/inbox/{id}/archive` | 归档 |
| `DELETE` | `/api/inbox/{id}` | 删除记录 |
| `GET` | `/api/files/{storedName}` | 安全读取上传文件 |

## 当前限制

- 文件保存在单机本地目录，不适用于多实例部署。
- 文件类型校验采用扩展名、Content-Type 和图片基础文件头检查，不是完整的恶意文件检测。
- URL 标题抓取包含超时、响应大小、重定向和私有地址基础限制，但不是企业级 SSRF 防护。
- 删除 InboxItem 当前只删除数据库记录，不会同步删除此前上传的物理文件。
- 图片列表直接加载原图作为缩略图，尚未生成独立缩略图文件。

## Future Roadmap（未来规划，尚未实现）

- 文本、网页和文件内容理解
- AI 摘要与自动标签
- OCR 与图片理解
- Embedding、语义搜索与 RAG
- Todo 与 Deadline 提取
- 用户系统与多端 Capture

Roadmap 只表示产品方向，不代表当前仓库已经具备这些功能。
