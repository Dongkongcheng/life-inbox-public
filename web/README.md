# LifeInbox Web

LifeInbox 的 Vue 3 + Vite 前端。浏览器只调用 Spring Boot 产品 API，不直接调用 FastAPI。

当前界面支持：

- TEXT、URL、FILE、IMAGE Capture；
- Inbox 列表、收藏、归档、删除；
- AI 状态、Summary、Category、Tags、Keywords、Entities；
- 手工 Analyze、FAILED Retry、stale PROCESSING Recovery；
- 自动 Analyze 后的有限状态发现和 fresh PROCESSING 轮询。

## 本地开发

先启动 Java 后端，再运行：

```powershell
npm install
npm run dev
```

开发地址默认是 `http://localhost:5173`。Vite 配置会把 `/api` 请求代理到 Java 的 `http://localhost:8080`。

## 构建

```powershell
npm run build
```

当前没有前端自动化测试框架。修改请求、状态或轮询逻辑后，除构建外还应按根目录的 [`docs/manual-acceptance.md`](../docs/manual-acceptance.md) 做浏览器验证。
