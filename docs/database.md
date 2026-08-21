# LifeInbox V0.2 数据库

数据库名为 `life_inbox`，Java 是唯一业务数据 Owner。当前 V0.2 使用 5 张表；新环境可执行 [`sql/v0.2-schema.sql`](sql/v0.2-schema.sql)，已有环境继续使用 `docs/sql/` 下保留的历史增量 SQL。

## `inbox_item`

统一保存 TEXT、URL、FILE、IMAGE，不按类型拆分核心业务表。

| 字段组 | 主要字段 | 作用 |
| --- | --- | --- |
| 身份与类型 | `id`, `user_id`, `type` | 统一 InboxItem 身份与 Capture 类型 |
| 原始内容 | `title`, `content`, `source_url`, `file_url` | TEXT 正文、URL、受管文件地址 |
| AI 结果 | `summary`, `category` | 当前最近一次成功的主结果 |
| AI 状态 | `ai_status`, `ai_attempt_id`, `ai_error_message` | 状态机、并发保护和安全错误 |
| AI 时间 | `ai_started_time`, `ai_finished_time` | 当前 Attempt 的开始/结束时间 |
| Inbox 状态 | `status`, `favorite` | ACTIVE/ARCHIVED 与收藏 |
| 审计时间 | `created_time`, `updated_time` | 创建与更新时间 |

`ai_status` 只有：

- `NOT_PROCESSED`：尚未开始；
- `PROCESSING`：当前 Attempt 正在处理；
- `SUCCESS`：最近一次 Attempt 成功；
- `FAILED`：最近一次 Attempt 失败，可重试。

stale 不写入数据库。Java 使用 `PROCESSING + ai_started_time + processing-stale-after` 动态计算 `aiProcessingStale`。

## Tags

`tag` 是全局展示字典，`normalized_name` 保存 NFKC/空白归一和小写后的唯一值。`inbox_tag` 使用 `(inbox_item_id, tag_id)` 复合主键建立多对多关系。删除 InboxItem 时关系由外键级联清理；没有引用的全局 tag 当前不会自动删除。

## Keywords 与 Entities

`inbox_keyword` 和 `inbox_entity` 都直接隶属于单条 InboxItem：

- Keyword 最长 64 字符，`(inbox_item_id, keyword)` 唯一；
- Entity 包含 `name` 和有限 `type`，`(inbox_item_id, name, type)` 唯一；
- 两表删除 InboxItem 时都由外键级联清理。

Entity 类型为 `PERSON`、`ORGANIZATION`、`LOCATION`、`TECHNOLOGY`、`PRODUCT`、`EVENT`、`OTHER`。

## 分析结果替换

重新 Analyze 成功时，Java 在一个短事务中：

1. 用当前 attemptId 更新 `summary`、`category` 并锁定主行；
2. 完整替换 tags、keywords、entities；
3. 最后把同一 Attempt 标记为 SUCCESS。

任一步异常会回滚。失败 Attempt 只更新状态和安全错误，不删除上一次成功结果。

## SQL 使用方式

- 全新安装：执行 `docs/sql/v0.2-schema.sql`；
- 已有 V0.1/V0.2 数据库：只按版本顺序执行尚未执行的增量 SQL；
- 不要在已有数据的数据库上重复执行 Fresh Schema；
- 本项目当前没有 Flyway/Liquibase，迁移必须由使用者手工执行并核对。
